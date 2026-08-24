#!/usr/bin/env python3
"""
Terzo banco: soppressione armonica, regola del terzo grado, fusione di stimatori.

Uso:
    python3 bench3.py "/percorso/cartella"
    python3 bench3.py                     # legge scan-folder.txt

VERITA' DI RIFERIMENTO
Al primo avvio scrive keys-truth.txt accanto allo script, precompilato con le
tonalita' dedotte dai nomi dei file. Correggilo a mano (accordatore alla mano) e
rilancia: da quel momento il banco usa quello. Formato: "nomefile = Am", una
riga per file, # per i commenti.

COSA PROVA
- soppressione armonica sul cromagramma: la terza armonica di una nota cade una
  quinta sopra, la quinta armonica una terza maggiore sopra. Sottraendo quelle
  code si attaccano gli errori di quinta, che erano metà degli errori residui.
- regola del terzo grado: se nessuna delle due terze ha energia (riff di power
  chord, dove la terza non esiste) il modo e' indeterminato, e si pende al minore.
- fusione: invece di scegliere una catena, si sommano i punteggi normalizzati di
  piu' stimatori. Serve anche a produrre i candidati ordinati che vuole l'app.

Richiede solo numpy.
"""
import os
import sys
import itertools
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from keytempo_bench import (  # noqa: E402
    read_wav, truth_from_name, KEY_NAMES, NOTES, MAJOR, MINOR,
    SHAATH_MAJOR, SHAATH_MINOR, chroma, chroma_keyfinder,
    lowpass_decimate, cq_kernels, key_verdict,
)

TRUTH_FILE = "keys-truth.txt"

PROFILES = {
    "krum": (MAJOR, MINOR),
    "shaath": (SHAATH_MAJOR, SHAATH_MINOR),
}


# ---------------------------------------------------------------- verita'

def load_truth(script_dir, files):
    path = os.path.join(script_dir, TRUTH_FILE)
    if not os.path.isfile(path):
        with open(path, "w") as f:
            f.write("# Tonalita' di riferimento, una riga per file.\n")
            f.write("# Precompilate dai nomi: CORREGGI quelle sbagliate e rilancia.\n")
            f.write("# Formato:  nomefile = Am        (usa i diesis: C# non Db)\n")
            f.write("# Lascia vuoto dopo l'uguale per escludere un file dal conteggio.\n\n")
            for p in files:
                name = os.path.basename(p)
                k, _ = truth_from_name(name)
                f.write("%s = %s\n" % (name, k or ""))
        print("scritto %s: correggilo e rilancia per usare le tue verifiche" % path)
        print()
        return None

    truth = {}
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, val = line.split("=", 1)
        val = val.strip()
        truth[name.strip()] = val if val else None
    return truth


# ------------------------------------------------------- catene di analisi

def chroma_harmonic_sum(x, sr, n=16384, harmonics=(1, 2, 3, 4), weights=(1.0, 0.5, 0.33, 0.25)):
    """
    Somma armonica sui semitoni: per ogni nota accumula anche l'energia dei suoi
    sottomultipli, cosi' l'energia torna verso la fondamentale invece di restare
    sparsa sulle armoniche.
    """
    y, ry = lowpass_decimate(x, sr)
    if len(y) < n + 8:
        n = 1 << int(np.floor(np.log2(max(64, len(y) - 8))))
    hop = n // 4
    win = np.blackman(n)
    ks, ws = cq_kernels(ry, n)
    frames = max(1, (len(y) - n) // hop)
    c72 = np.zeros(72)
    for i in range(frames):
        seg = y[i * hop:i * hop + n]
        if len(seg) < n:
            break
        mag = np.abs(np.fft.rfft(seg * win))
        for b in range(72):
            c72[b] += float(np.dot(mag[ks[b]], ws[b]))
    # somma armonica in scala di semitoni: h-esima armonica = +12*log2(h)
    out = np.zeros(72)
    for h, w in zip(harmonics, weights):
        shift = int(round(12 * np.log2(h)))
        if shift == 0:
            out += w * c72
        else:
            out[:-shift or None] += w * c72[shift:] if shift < 72 else 0
    ch = out.reshape(6, 12).sum(axis=0)
    t = ch.sum()
    return ch / t if t > 0 else ch


def build_front_ends(x, sr):
    return {
        "peaks": chroma(x, sr, n=4096, fmin=100, fmax=2000, peaks=True),
        "cq": chroma_keyfinder(x, sr),
        "harm": chroma_harmonic_sum(x, sr),
    }


# --------------------------------------------------- soppressione armonica

def suppress(ch, alpha, beta):
    """
    Toglie da ogni classe la coda armonica delle note sottostanti:
    -alpha * (una quinta sotto)  = terza armonica
    -beta  * (una terza maggiore sotto) = quinta armonica
    """
    if alpha == 0 and beta == 0:
        return ch
    out = ch.copy()
    for p in range(12):
        out[p] -= alpha * ch[(p - 7) % 12] + beta * ch[(p - 4) % 12]
    out = np.clip(out, 0, None)
    t = out.sum()
    return out / t if t > 0 else ch


# ------------------------------------------------------------ classificazione

def scores(ch, major, minor, measure):
    if ch.sum() <= 0:
        return np.zeros(24)
    sc = np.zeros(24)
    for root in range(12):
        for mode in range(2):
            prof = np.roll(major if mode == 0 else minor, root)
            if measure == "cos":
                d = np.linalg.norm(ch) * np.linalg.norm(prof)
                v = float(np.dot(ch, prof)) / d if d > 0 else 0.0
            else:
                c = np.corrcoef(ch, prof)[0, 1]
                v = 0.0 if np.isnan(c) else float(c)
            sc[root * 2 + mode] = v
    return sc


def third_rule(ch, sc, ratio=0.55):
    """
    Se per la fondamentale vincente nessuna delle due terze ha energia
    apprezzabile, il modo non e' deciso dal segnale: si pende al minore.
    """
    i = int(np.argmax(sc))
    root, mode = i // 2, i % 2
    if mode == 1:
        return sc
    tonic = ch[root]
    if tonic <= 0:
        return sc
    m3 = ch[(root + 3) % 12] / tonic
    M3 = ch[(root + 4) % 12] / tonic
    if max(m3, M3) < ratio:
        out = sc.copy()
        out[root * 2], out[root * 2 + 1] = out[root * 2 + 1], out[root * 2]
        return out
    return sc


def normalise(sc):
    if not np.any(sc):
        return np.zeros(24)
    z = sc - sc.min()
    t = z.sum()
    return z / t if t > 0 else z


def softmax(sc, sharp=12.0):
    if not np.any(sc):
        return np.zeros(24)
    e = np.exp((sc - sc.max()) * sharp)
    return e / e.sum()


# ---------------------------------------------------------------------- main

def main():
    here = os.path.dirname(os.path.abspath(__file__))
    folder = sys.argv[1] if len(sys.argv) > 1 else None
    if not folder:
        for cand in (os.path.join(here, "scan-folder.txt"), os.path.join(here, "..", "scan-folder.txt")):
            if os.path.isfile(cand):
                for line in open(cand):
                    line = line.strip()
                    if line and not line.startswith("#"):
                        folder = line
                        break
            if folder:
                break
    if not folder or not os.path.isdir(folder):
        print('uso: python3 bench3.py "/percorso/cartella"')
        sys.exit(2)

    files = sorted(
        [os.path.join(r, f) for r, _, fs in os.walk(folder) for f in fs
         if f.lower().endswith((".wav", ".wave", ".bwf"))],
        key=lambda p: os.path.basename(p).lower()
    )
    print("cartella:", folder, "  file:", len(files))

    truth_map = load_truth(here, files)
    from_file = truth_map is not None
    print("verita' di riferimento:", "keys-truth.txt" if from_file else "nomi dei file")
    print()

    data = []
    for p in files:
        name = os.path.basename(p)
        try:
            x, sr, ch, bits, tag = read_wav(p)
        except Exception as e:
            print("ERRORE su", name, e)
            continue
        if from_file:
            tkey = truth_map.get(name)
        else:
            tkey, _ = truth_from_name(name)
        data.append(dict(name=name, tkey=tkey, fe=build_front_ends(x, sr)))

    n_truth = sum(1 for d in data if d["tkey"])
    if n_truth == 0:
        print("nessuna verita' di riferimento: riempi keys-truth.txt")
        sys.exit(1)

    # ------------------- griglia -------------------
    configs = list(itertools.product(
        ["peaks", "cq", "harm"],
        [0.0, 0.2, 0.35, 0.5],       # alpha: quinta
        [0.0, 0.1, 0.2],             # beta: terza maggiore
        ["krum", "shaath"],
        ["pear", "cos"],
        [False, True],               # regola del terzo grado
    ))

    rows = []
    cache = {}
    for fe, a, b, pr, ms, tr in configs:
        maj, minr = PROFILES[pr]
        cnt = dict(OK=0, rel=0, par=0, fifth=0, other=0)
        picks = {}
        for d in data:
            if not d["tkey"]:
                continue
            ckey = (d["name"], fe, a, b)
            ch = cache.get(ckey)
            if ch is None:
                ch = suppress(d["fe"][fe], a, b)
                cache[ckey] = ch
            sc = scores(ch, maj, minr, ms)
            if tr:
                sc = third_rule(ch, sc)
            i = int(np.argmax(sc))
            got = KEY_NAMES[i]
            picks[d["name"]] = (got, softmax(sc)[i], normalise(sc))
            v = key_verdict(got, d["tkey"])
            cnt["fifth" if v == "5th" else (v if v in cnt else "other")] += 1
        rows.append(((fe, a, b, pr, ms, tr), cnt, picks))

    rows.sort(key=lambda r: (-r[1]["OK"], -(r[1]["rel"] + r[1]["par"])))

    print("=" * 108)
    print("CLASSIFICA (su %d file con verita') - le prime 15 configurazioni" % n_truth)
    print("=" * 108)
    print("%-44s %6s %5s %5s %5s %6s" % ("catena  alpha beta  profilo  sim  3rd", "OK", "rel", "par", "5th", "altro"))
    print("-" * 108)
    for (fe, a, b, pr, ms, tr), cnt, _ in rows[:15]:
        label = "%-6s a=%.2f b=%.2f %-7s %-5s %s" % (fe, a, b, pr, ms, "3rd" if tr else "-")
        print("%-44s %6d %5d %5d %5d %6d" % (label, cnt["OK"], cnt["rel"], cnt["par"], cnt["fifth"], cnt["other"]))

    # ------------------- fusione -------------------
    print()
    print("=" * 108)
    print("FUSIONE: somma dei punteggi normalizzati delle prime N configurazioni")
    print("=" * 108)
    # fondere varianti quasi identiche non aggiunge nulla: la prima riga fonde
    # la migliore configurazione di OGNI catena, che sono stimatori davvero diversi
    per_fe = {}
    for key, cnt, picks in rows:
        fe_name = key[0]
        if fe_name not in per_fe:
            per_fe[fe_name] = (key, cnt, picks)
    if len(per_fe) > 1:
        cnt = dict(OK=0, rel=0, par=0, fifth=0, other=0)
        for d in data:
            if not d["tkey"]:
                continue
            acc = np.zeros(24)
            for _, _, picks in per_fe.values():
                acc += picks[d["name"]][2]
            got = KEY_NAMES[int(np.argmax(acc))]
            v = key_verdict(got, d["tkey"])
            cnt["fifth" if v == "5th" else (v if v in cnt else "other")] += 1
        print("migliore di ogni catena (%s):   OK %d   rel %d   par %d   5th %d   altro %d"
              % ("+".join(per_fe.keys()), cnt["OK"], cnt["rel"], cnt["par"], cnt["fifth"], cnt["other"]))

    for topn in (2, 3, 5):
        best = rows[:topn]
        cnt = dict(OK=0, rel=0, par=0, fifth=0, other=0)
        for d in data:
            if not d["tkey"]:
                continue
            acc = np.zeros(24)
            for _, _, picks in best:
                acc += picks[d["name"]][2]
            got = KEY_NAMES[int(np.argmax(acc))]
            v = key_verdict(got, d["tkey"])
            cnt["fifth" if v == "5th" else (v if v in cnt else "other")] += 1
        print("fusione delle prime %d:   OK %d   rel %d   par %d   5th %d   altro %d"
              % (topn, cnt["OK"], cnt["rel"], cnt["par"], cnt["fifth"], cnt["other"]))

    # ------------------- dettaglio -------------------
    print()
    print("=" * 108)
    print("DETTAGLIO della configurazione migliore, piu' la fusione delle prime 3")
    print("=" * 108)
    (fe, a, b, pr, ms, tr), cnt, picks = rows[0]
    print("migliore: %s  alpha=%.2f  beta=%.2f  %s  %s  %s" % (fe, a, b, pr, ms, "con regola 3rd" if tr else "senza regola 3rd"))
    print()
    print("%-34s %6s %18s %22s" % ("file", "vero", "migliore", "fusione prime 3"))
    print("-" * 108)
    top3 = rows[:3]
    for d in data:
        if not d["tkey"]:
            continue
        got, p, _ = picks[d["name"]]
        acc = np.zeros(24)
        for _, _, pk in top3:
            acc += pk[d["name"]][2]
        order = np.argsort(-acc)
        fus = KEY_NAMES[order[0]]
        second = KEY_NAMES[order[1]]
        print("%-34s %6s %8s %3d%% %4s %10s / %-6s %4s" % (
            d["name"][:34], d["tkey"], got, round(p * 100), key_verdict(got, d["tkey"]),
            fus, second, key_verdict(fus, d["tkey"])))


if __name__ == "__main__":
    main()
