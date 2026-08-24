#!/usr/bin/env python3
"""
Secondo banco: ricerca a griglia sulle combinazioni, invece di poche varianti.

Uso:
    python3 bench2.py "/percorso/cartella"
    python3 bench2.py                # legge scan-folder.txt

TONALITA': incrocia catene di analisi (spettro con picchi, Q costante alla
KeyFinder, con e senza normalizzazione per finestra) x profili (Krumhansl,
Temperley, Shaath) x misure di similarita' (Pearson, coseno). Stampa la
classifica e il dettaglio per file delle tre migliori.

TEMPO: aggiunge alla griglia delle battute una decisione dell'ottava basata sul
CONTEGGIO DEGLI ATTACCHI, indizio indipendente dall'autocorrelazione.

Richiede solo numpy. Riusa il lettore WAV e le funzioni del primo banco.
"""
import os
import sys
import itertools
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from keytempo_bench import (  # noqa: E402
    read_wav, truth_from_name, KEY_NAMES, MAJOR, MINOR,
    SHAATH_MAJOR, SHAATH_MINOR, chroma, chroma_keyfinder,
    onset_env, acf_fn, comb_score, sweep, v5_bar_grid, tempo_verdict, key_verdict,
    FLUX_HOP,
)

# profili di Temperley (Kostka-Payne), per confronto
TEMPERLEY_MAJOR = np.array([5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0])
TEMPERLEY_MINOR = np.array([5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0])

PROFILES = {
    "krum": (MAJOR, MINOR),
    "temp": (TEMPERLEY_MAJOR, TEMPERLEY_MINOR),
    "shaath": (SHAATH_MAJOR, SHAATH_MINOR),
}


def front_ends(x, sr):
    """Cromagrammi a 12 bin, uno per catena di analisi."""
    out = {}
    out["peaks"] = chroma(x, sr, n=4096, fmin=100, fmax=2000, peaks=True)
    out["peaks_pf"] = chroma(x, sr, n=4096, fmin=100, fmax=2000, peaks=True, per_frame=True)
    out["cq"] = chroma_keyfinder(x, sr)
    out["cq_bass"] = chroma_keyfinder_range(x, sr, lo_oct=1, n_oct=4)
    return out


def chroma_keyfinder_range(x, sr, lo_oct=0, n_oct=6, n=16384):
    """Come la catena KeyFinder, ma su un sottoinsieme di ottave."""
    from keytempo_bench import lowpass_decimate, cq_kernels
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
    block = c72.reshape(6, 12)[lo_oct:lo_oct + n_oct]
    ch = block.sum(axis=0)
    t = ch.sum()
    return ch / t if t > 0 else ch


def score_keys(ch, major, minor, measure):
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


def probs_from_scores(sc, sharpness=12.0):
    """
    Punteggi -> percentuali con contrasto: softmax sui punteggi centrati.
    La normalizzazione lineare del primo banco schiacciava tutto intorno al 10%.
    """
    if not np.any(sc):
        return np.zeros(24)
    z = (sc - sc.max()) * sharpness
    e = np.exp(z)
    return e / e.sum()


def onset_times(env, envrate, sr_hop=FLUX_HOP):
    """Attacchi netti: picchi locali sopra media + deviazione standard."""
    if len(env) < 5:
        return np.array([])
    e = env - env.mean()
    e[e < 0] = 0
    thr = e.mean() + e.std()
    peaks = []
    for i in range(1, len(e) - 1):
        if e[i] >= e[i - 1] and e[i] > e[i + 1] and e[i] > thr:
            if peaks and (i - peaks[-1]) < 3:      # niente doppi entro ~35 ms
                if e[i] > e[peaks[-1]]:
                    peaks[-1] = i
                continue
            peaks.append(i)
    return np.array(peaks) / envrate


def v6_onset_grid(at, envrate, dur, env=None, mult=4):
    """
    Griglia delle battute, ma con l'ottava decisa dal conteggio degli attacchi.
    Il numero di attacchi netti in un loop sta di solito fra una e due volte il
    numero di battiti: un candidato che ne implica quattro volte tanti, o la
    meta', e' sbagliato.
    """
    cands = []
    for beats in range(4, 4000):
        if beats % mult != 0:
            continue
        bpm = beats * 60.0 / dur
        if bpm < 60:
            continue
        if bpm > 200:
            break
        s = comb_score(at, envrate, bpm, prior=True)
        bars = beats / 4.0
        if bars == int(bars) and int(bars) % 4 == 0:
            s *= 1.15
        cands.append((bpm, beats, s))
    if not cands:
        return sweep(at, envrate)
    cands.sort(key=lambda c: -c[2])
    top = cands[:6]
    n_onsets = len(onset_times(env, envrate)) if env is not None else 0
    if n_onsets < 4:
        return top[0][0]

    def penalty(beats):
        # attacchi per battito: in un pattern reale sta vicino a 1 (solo battiti)
        # o a 2 (ottavi). La distanza in ottave dal piu' vicino dei due e' la penalita'.
        r = n_onsets / float(beats)
        if r <= 0:
            return 4.0
        return min(abs(np.log2(r / 1.0)), abs(np.log2(r / 2.0)))

    best = max(top, key=lambda c: c[2] / (1.0 + 1.5 * penalty(c[1])))
    return best[0]


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else None
    if not folder:
        here = os.path.dirname(os.path.abspath(__file__))
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
        print('uso: python3 bench2.py "/percorso/cartella"')
        sys.exit(2)

    files = sorted(
        [os.path.join(r, f) for r, _, fs in os.walk(folder) for f in fs
         if f.lower().endswith((".wav", ".wave", ".bwf"))],
        key=lambda p: os.path.basename(p).lower()
    )
    print("cartella:", folder, "   file:", len(files))
    print("(un numero nudo nel nome NON viene piu' preso per un BPM)")
    print()

    data = []
    for p in files:
        name = os.path.basename(p)
        try:
            x, sr, ch, bits, tag = read_wav(p)
        except Exception as e:
            print("ERRORE su", name, e)
            continue
        tkey, tbpm = truth_from_name(name)
        # un numero nudo non e' un tempo: solo "###bpm" conta
        if tbpm is not None and ("bpm" not in name.lower()):
            tbpm = None
        dur = len(x) / sr
        env = onset_env(x, sr)
        envrate = sr / FLUX_HOP
        at = acf_fn(env, envrate)
        data.append(dict(name=name, x=x, sr=sr, dur=dur, tkey=tkey, tbpm=tbpm,
                         env=env, envrate=envrate, at=at, fe=front_ends(x, sr)))

    # ----------------------------- TEMPO -----------------------------
    print("=" * 104)
    print("TEMPO")
    print("=" * 104)
    print("%-34s %6s %14s %14s %10s" % ("file", "nome", "V5 griglia", "V6 attacchi", "attacchi"))
    print("-" * 104)
    hits = {"V5": 0, "V6": 0}
    n_truth = 0
    for d in data:
        got5 = v5_bar_grid(d["at"], d["envrate"], d["dur"])
        got6 = v6_onset_grid(d["at"], d["envrate"], d["dur"], env=d["env"])
        n_on = len(onset_times(d["env"], d["envrate"]))
        v5 = tempo_verdict(got5, d["tbpm"])
        v6 = tempo_verdict(got6, d["tbpm"])
        if d["tbpm"]:
            n_truth += 1
            if v5 == "OK":
                hits["V5"] += 1
            if v6 == "OK":
                hits["V6"] += 1
        print("%-34s %6s %9.1f %4s %9.1f %4s %10d" % (
            d["name"][:34], ("%.0f" % d["tbpm"]) if d["tbpm"] else "-",
            got5, v5, got6, v6, n_on))
    print("-" * 104)
    print("giusti su %d con bpm nel nome:   V5 = %d    V6 = %d" % (n_truth, hits["V5"], hits["V6"]))
    print()

    # --------------------------- TONALITA' ---------------------------
    combos = list(itertools.product(["peaks", "peaks_pf", "cq", "cq_bass"],
                                    ["krum", "temp", "shaath"],
                                    ["pear", "cos"]))
    results = {}
    for fe, pr, ms in combos:
        maj, minr = PROFILES[pr]
        exact = close = 0
        got_all = {}
        for d in data:
            if not d["tkey"]:
                continue
            sc = score_keys(d["fe"][fe], maj, minr, ms)
            i = int(np.argmax(sc))
            got = KEY_NAMES[i]
            got_all[d["name"]] = (got, probs_from_scores(sc)[i])
            v = key_verdict(got, d["tkey"])
            if v == "OK":
                exact += 1
            if v in ("OK", "rel", "par"):
                close += 1
        results[(fe, pr, ms)] = (exact, close, got_all)

    n_key = sum(1 for d in data if d["tkey"])
    print("=" * 104)
    print("TONALITA': classifica delle combinazioni (su %d file con tonalita' nel nome)" % n_key)
    print("=" * 104)
    print("%-28s %8s %8s" % ("combinazione", "esatte", "o quasi"))
    print("-" * 104)
    order = sorted(results.items(), key=lambda kv: (-kv[1][0], -kv[1][1]))
    for (fe, pr, ms), (exact, close, _) in order:
        print("%-28s %8d %8d" % ("%s + %s + %s" % (fe, pr, ms), exact, close))

    print()
    print("=" * 104)
    print("DETTAGLIO delle tre migliori")
    print("=" * 104)
    best3 = order[:3]
    hdr = "%-34s %6s" % ("file", "nome")
    for (fe, pr, ms), _ in best3:
        hdr += " %20s" % ("%s/%s/%s" % (fe[:6], pr[:4], ms))
    print(hdr)
    print("-" * 104)
    for d in data:
        if not d["tkey"]:
            continue
        row = "%-34s %6s" % (d["name"][:34], d["tkey"])
        for key, (exact, close, got_all) in best3:
            got, p = got_all.get(d["name"], ("?", 0))
            row += " %10s %3d%% %4s" % (got, round(p * 100), key_verdict(got, d["tkey"]))
        print(row)


if __name__ == "__main__":
    main()
