#!/usr/bin/env python3
"""
Banco di prova per rilevamento di tempo e tonalita', su file veri.

Uso:
    python3 keytempo_bench.py "/percorso/della/cartella"
    python3 keytempo_bench.py                # legge scan-folder.txt accanto allo script

Legge i WAV della cartella, ricava la verita' di riferimento dal NOME del file
(solo per il collaudo: nell'app i nomi non si usano), e confronta piu' varianti
di algoritmo affiancate. Serve a scegliere la variante da portare in Kotlin.

Richiede solo numpy.
"""
import os
import re
import sys
import struct
import numpy as np

# ----------------------------------------------------------------------------
# lettura WAV: PCM 8/16/24/32, float 32/64, WAVE_FORMAT_EXTENSIBLE
# ----------------------------------------------------------------------------

def read_wav(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] not in (b"RIFF", b"RF64", b"BW64") or data[8:12] != b"WAVE":
        raise ValueError("non e' un RIFF/WAVE")
    pos = 12
    fmt = None
    while pos + 8 <= len(data):
        cid = data[pos:pos + 4]
        csz = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = pos + 8
        if cid == b"fmt ":
            tag, ch, sr, _, _, bits = struct.unpack("<HHIIHH", data[body:body + 16])
            if tag == 0xFFFE and csz >= 26:
                tag = struct.unpack("<H", data[body + 24:body + 26])[0]
            fmt = (tag, ch, sr, bits)
        elif cid == b"data":
            if fmt is None:
                raise ValueError("data prima di fmt")
            tag, ch, sr, bits = fmt
            end = len(data) if csz in (0, 0xFFFFFFFF) else min(len(data), body + csz)
            raw = data[body:end]
            x = decode_pcm(raw, tag, bits)
            n = (len(x) // ch) * ch
            x = x[:n].reshape(-1, ch).mean(axis=1)
            return x.astype(np.float64), sr, ch, bits, tag
        pos = body + csz + (csz & 1)
    raise ValueError("nessun chunk data")


def decode_pcm(raw, tag, bits):
    if tag == 1 and bits == 16:
        return np.frombuffer(raw[:len(raw) // 2 * 2], dtype="<i2").astype(np.float64) / 32768.0
    if tag == 1 and bits == 8:
        return (np.frombuffer(raw, dtype=np.uint8).astype(np.float64) - 128.0) / 128.0
    if tag == 1 and bits == 24:
        n = len(raw) // 3
        b = np.frombuffer(raw[:n * 3], dtype=np.uint8).reshape(n, 3).astype(np.int32)
        v = (b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16))
        v = np.where(v & 0x800000, v - 0x1000000, v)
        return v.astype(np.float64) / 8388608.0
    if tag == 1 and bits == 32:
        return np.frombuffer(raw[:len(raw) // 4 * 4], dtype="<i4").astype(np.float64) / 2147483648.0
    if tag == 3 and bits == 32:
        return np.frombuffer(raw[:len(raw) // 4 * 4], dtype="<f4").astype(np.float64)
    if tag == 3 and bits == 64:
        return np.frombuffer(raw[:len(raw) // 8 * 8], dtype="<f8").astype(np.float64)
    raise ValueError("formato non gestito: tag %d, %d bit" % (tag, bits))


# ----------------------------------------------------------------------------
# verita' di riferimento dal nome del file
# ----------------------------------------------------------------------------

KEY_TOKEN = re.compile(r"^([A-G])(#|b)?(m|min|minor)?$")

def truth_from_name(name):
    stem = os.path.splitext(name)[0]
    tokens = re.split(r"[_\-\s]+", stem)
    key = None
    bpm = None
    for t in tokens:
        m = KEY_TOKEN.match(t)
        if m and key is None:
            root = m.group(1) + (m.group(2) or "")
            key = norm_key(root + ("m" if m.group(3) else ""))
        mb = re.match(r"^(\d{2,3})\s*bpm$", t, re.I)
        if mb:
            bpm = float(mb.group(1))
        elif re.match(r"^\d{2,3}$", t) and bpm is None:
            bpm = float(t)
    return key, bpm


FLAT_TO_SHARP = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#"}

def norm_key(k):
    minor = k.endswith("m")
    root = k[:-1] if minor else k
    root = FLAT_TO_SHARP.get(root, root)
    return root + ("m" if minor else "")


NOTES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
KEY_NAMES = [NOTES[i // 2] + ("m" if i % 2 else "") for i in range(24)]

# ----------------------------------------------------------------------------
# tempo
# ----------------------------------------------------------------------------

FLUX_N, FLUX_HOP = 1024, 512

def onset_env(x, sr):
    win = np.hanning(FLUX_N)
    frames = max(0, (len(x) - FLUX_N) // FLUX_HOP)
    env = np.zeros(frames)
    prev = None
    for i in range(frames):
        seg = x[i * FLUX_HOP:i * FLUX_HOP + FLUX_N] * win
        mag = np.abs(np.fft.rfft(seg))
        if prev is not None:
            d = mag - prev
            env[i] = d[d > 0].sum()
        prev = mag
    return env


def acf_fn(env, envrate, min_bpm=55.0):
    x = env - env.mean()
    maxlag = min(int(60 * envrate / min_bpm) * 3 + 2, max(4, len(x) - 2))
    ac = np.zeros(maxlag + 1)
    for lag in range(1, maxlag + 1):
        n = len(x) - lag
        if n <= 0:
            break
        ac[lag] = np.dot(x[:n], x[lag:lag + n]) / n

    def at(lag):
        if lag < 1 or lag > maxlag - 1:
            return 0.0
        i = int(lag)
        f = lag - i
        return ac[i] * (1 - f) + ac[i + 1] * f
    return at


def comb_score(at, envrate, bpm, prior=True):
    lag = 60 * envrate / bpm
    w = np.exp(-0.5 * (np.log2(bpm / 120.0) / 0.55) ** 2) if prior else 1.0
    return (at(lag) + 0.5 * at(2 * lag) + 0.25 * at(3 * lag)) * w


def sweep(at, envrate, prior=True, lo=60.0, hi=200.0):
    best, bs = lo, -1e18
    b = lo
    while b <= hi:
        s = comb_score(at, envrate, b, prior)
        if s > bs:
            bs, best = s, b
        b += 0.25
    return best


def v1_current(at, envrate, dur):
    """quello che c'e' oggi nell'app: prior+pettine, poi ottava per acf pura"""
    base = sweep(at, envrate, prior=True)
    win, wv = base, at(60 * envrate / base)
    for r in (1 / 3, 0.5, 2.0, 3.0):
        c = base * r
        if 60 <= c <= 200:
            v = at(60 * envrate / c)
            if v > wv:
                wv, win = v, c
    return win


def v2_no_octave(at, envrate, dur):
    """solo prior + pettine, senza ridecidere l'ottava"""
    return sweep(at, envrate, prior=True)


def v3_comb_no_prior(at, envrate, dur):
    """pettine senza prior: il pettine dovrebbe da solo preferire il battito"""
    return sweep(at, envrate, prior=False)


def v4_fastest_close(at, envrate, dur, ratio=0.9):
    """fra le ottave, la piu' veloce il cui punteggio a pettine resta vicino al migliore"""
    base = sweep(at, envrate, prior=True)
    cands = [base * r for r in (0.5, 1.0, 2.0) if 60 <= base * r <= 200]
    sc = {c: comb_score(at, envrate, c, prior=False) for c in cands}
    best = max(sc.values()) if sc else 0.0
    ok = [c for c in cands if sc[c] >= ratio * best]
    return max(ok) if ok else base


def v5_bar_grid(at, envrate, dur, mult=4, bonus=0.15):
    """
    sfrutta il vincolo del loop: i battiti sono interi e (quasi sempre) multipli
    di 4. Enumero i conteggi possibili, li valuto col pettine, e premio i numeri
    di battute "buoni" (multipli di 4).
    """
    best, bs = None, -1e18
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
            s *= (1 + bonus)
        if s > bs:
            bs, best = s, bpm
    return best if best else sweep(at, envrate)


TEMPO_VARIANTS = [
    ("V1 attuale", v1_current),
    ("V2 no-ottava", v2_no_octave),
    ("V3 no-prior", v3_comb_no_prior),
    ("V4 piu-veloce", v4_fastest_close),
    ("V5 griglia-battute", v5_bar_grid),
]

# ----------------------------------------------------------------------------
# tonalita'
# ----------------------------------------------------------------------------

MAJOR = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
MINOR = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])


def chroma(x, sr, n=4096, fmin=55.0, fmax=2000.0, peaks=False, per_frame=False, logmag=False):
    hop = n // 2
    win = np.hanning(n)
    frames = max(0, (len(x) - n) // hop)
    binhz = sr / n
    kmin = max(1, int(fmin / binhz))
    kmax = min(n // 2 - 1, int(fmax / binhz))
    ks = np.arange(kmin, kmax + 1)
    midi = 69 + 12 * np.log2((ks * binhz) / 440.0)
    pc = np.mod(np.rint(midi).astype(int), 12)
    out = np.zeros(12)
    for i in range(frames):
        seg = x[i * hop:i * hop + n] * win
        mag = np.abs(np.fft.rfft(seg))[kmin:kmax + 1]
        if logmag:
            mag = np.log1p(mag * 100.0)
        if peaks:
            keep = np.zeros_like(mag, dtype=bool)
            keep[1:-1] = (mag[1:-1] > mag[:-2]) & (mag[1:-1] > mag[2:])
            thr = mag.mean() + mag.std()
            keep &= mag > thr
            mag = np.where(keep, mag, 0.0)
        frame = np.zeros(12)
        np.add.at(frame, pc, mag)
        s = frame.sum()
        if s <= 0:
            continue
        out += frame / s if per_frame else frame
    t = out.sum()
    return out / t if t > 0 else out


def key_probs(ch):
    if ch.sum() <= 0:
        return np.zeros(24)
    sc = np.zeros(24)
    for root in range(12):
        for mode in range(2):
            prof = np.roll(MAJOR if mode == 0 else MINOR, root)
            c = np.corrcoef(ch, prof)[0, 1]
            sc[root * 2 + mode] = max(0.0, 0.0 if np.isnan(c) else c)
    t = sc.sum()
    return sc / t if t > 0 else sc




# ----------------------------------------------------------------------------
# variante "alla KeyFinder": sottocampionamento a 4410 Hz, FFT lunga, Q costante
# su 6 ottave, cromagramma a 72 bin ripiegato a 12, similarita' cosinusoidale.
# Parametri dalla tesi di Sha'ath, implementazione mia (KeyFinder e' GPL v3:
# nessuna riga del suo codice qui dentro).
# ----------------------------------------------------------------------------

SHAATH_MAJOR = np.array([7.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 5.5, 2.0, 3.5, 1.5, 4.0])
SHAATH_MINOR = np.array([7.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 5.5, 3.5, 2.0, 4.5, 4.0])

_KERNEL_CACHE = {}

def lowpass_decimate(x, sr, target=4410.0):
    factor = int(round(sr / target))
    if factor < 2:
        return x, sr
    cutoff = 0.40 / factor              # normalizzata al rate originale
    taps = 161
    n = np.arange(taps) - (taps - 1) / 2.0
    h = np.sinc(2 * cutoff * n) * np.blackman(taps)
    h /= h.sum()
    y = np.fft.irfft(np.fft.rfft(x, len(x) + taps) * np.fft.rfft(h, len(x) + taps))
    y = y[(taps - 1) // 2:(taps - 1) // 2 + len(x)]
    return y[::factor], sr / factor


def cq_kernels(sr, n, p=0.8, bins=72, fmin=32.703):
    key = (round(sr, 3), n, p, bins, fmin)
    if key in _KERNEL_CACHE:
        return _KERNEL_CACHE[key]
    binhz = sr / n
    ks, ws = [], []
    for b in range(bins):
        f = fmin * 2 ** (b / 12.0)
        bw = p * (2 ** (1 / 12.0) - 1) * f          # larghezza di banda del semitono
        lo, hi = f - bw / 2, f + bw / 2
        i0 = max(1, int(np.floor(lo / binhz)))
        i1 = min(n // 2 - 1, int(np.ceil(hi / binhz)))
        if i1 <= i0:
            i0 = max(1, min(n // 2 - 2, int(round(f / binhz))))
            i1 = i0 + 1
        idx = np.arange(i0, i1 + 1)
        # finestra a coseno nel dominio della frequenza
        w = 0.5 - 0.5 * np.cos(2 * np.pi * (idx * binhz - lo) / max(1e-9, hi - lo))
        w = np.clip(w, 0, None)
        s = w.sum()
        if s > 0:
            w = w / s
        ks.append(idx)
        ws.append(w)
    _KERNEL_CACHE[key] = (ks, ws)
    return ks, ws


def chroma_keyfinder(x, sr, n=16384):
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
    ch = c72.reshape(6, 12).sum(axis=0)
    t = ch.sum()
    return ch / t if t > 0 else ch


def key_probs_cosine(ch, major, minor):
    if ch.sum() <= 0:
        return np.zeros(24)
    sc = np.zeros(24)
    nch = np.linalg.norm(ch)
    for root in range(12):
        for mode in range(2):
            prof = np.roll(major if mode == 0 else minor, root)
            d = nch * np.linalg.norm(prof)
            sc[root * 2 + mode] = (float(np.dot(ch, prof)) / d) if d > 0 else 0.0
    sc = np.clip(sc - sc.min(), 0, None)          # solo per leggere le percentuali
    t = sc.sum()
    return sc / t if t > 0 else sc


def kf_krumhansl(x, sr):
    return key_probs_cosine(chroma_keyfinder(x, sr), MAJOR, MINOR)


def kf_shaath(x, sr):
    return key_probs_cosine(chroma_keyfinder(x, sr), SHAATH_MAJOR, SHAATH_MINOR)


KEY_VARIANTS = [
    ("K1 attuale", dict(n=4096, fmin=55, fmax=2000)),
    ("K2 picchi", dict(n=4096, fmin=100, fmax=2000, peaks=True)),
    ("K3 picchi+8192", dict(n=8192, fmin=130, fmax=2600, peaks=True)),
    ("K4 K3+per-frame", dict(n=8192, fmin=130, fmax=2600, peaks=True, per_frame=True)),
    ("K5 K4+log", dict(n=8192, fmin=130, fmax=2600, peaks=True, per_frame=True, logmag=True)),
    ("K6 CQ-krumhansl", kf_krumhansl),
    ("K7 CQ-shaath", kf_shaath),
]

# ----------------------------------------------------------------------------

def tempo_verdict(got, truth):
    if truth is None or got is None:
        return "?"
    r = got / truth
    for name, target in (("OK", 1.0), ("x2", 2.0), ("/2", 0.5), ("x1.5", 1.5), ("/1.5", 2 / 3.0)):
        if abs(r - target) < 0.03:
            return name
    return "NO"


def key_verdict(got, truth):
    if truth is None:
        return "?"
    if got == truth:
        return "OK"
    # errori tipici: relativa, parallela, quinta
    ri = KEY_NAMES.index(got)
    ti = KEY_NAMES.index(truth)
    rr, rm = ri // 2, ri % 2
    tr, tm = ti // 2, ti % 2
    if rm != tm and ((rm == 1 and (rr - tr) % 12 == 9) or (tm == 1 and (tr - rr) % 12 == 9)):
        return "rel"
    if rr == tr and rm != tm:
        return "par"
    if (rr - tr) % 12 in (5, 7):
        return "5th"
    return "NO"


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
        print("uso: python3 keytempo_bench.py \"/percorso/cartella\"")
        sys.exit(2)

    files = sorted(
        [os.path.join(r, f) for r, _, fs in os.walk(folder) for f in fs
         if f.lower().endswith((".wav", ".wave", ".bwf"))],
        key=lambda p: os.path.basename(p).lower()
    )
    if not files:
        print("nessun WAV in", folder)
        sys.exit(1)

    print("cartella:", folder)
    print("file WAV :", len(files))
    print()

    tempo_hits = {n: 0 for n, _ in TEMPO_VARIANTS}
    key_hits = {n: 0 for n, _ in KEY_VARIANTS}
    key_close = {n: 0 for n, _ in KEY_VARIANTS}
    n_tempo_truth = 0
    n_key_truth = 0

    # ---------------- TEMPO ----------------
    print("=" * 118)
    print("TEMPO   (OK = giusto, /2 = meta', x2 = doppio, x1.5 = terzina, NO = altro)")
    print("=" * 118)
    hdr = "%-34s %6s" % ("file", "nome")
    for n, _ in TEMPO_VARIANTS:
        hdr += " %18s" % n
    print(hdr)
    print("-" * 118)

    cache = {}
    for p in files:
        name = os.path.basename(p)
        try:
            x, sr, ch, bits, tag = read_wav(p)
        except Exception as e:
            print("%-34s  ERRORE %s" % (name[:34], e))
            continue
        tkey, tbpm = truth_from_name(name)
        dur = len(x) / sr
        env = onset_env(x, sr)
        envrate = sr / FLUX_HOP
        at = acf_fn(env, envrate)
        cache[p] = (x, sr, dur, tkey, tbpm)

        row = "%-34s %6s" % (name[:34], ("%.0f" % tbpm) if tbpm else "-")
        if tbpm:
            n_tempo_truth += 1
        for n, fn in TEMPO_VARIANTS:
            try:
                got = fn(at, envrate, dur)
            except Exception:
                got = None
            # aggancio al vincolo del loop, come fa l'app
            if got:
                beats = max(1, int(round(got * dur / 60.0)))
                got = beats * 60.0 / dur
            v = tempo_verdict(got, tbpm)
            if v == "OK":
                tempo_hits[n] += 1
            row += " %12.1f %5s" % (got or 0.0, v)
        print(row)

    print("-" * 118)
    line = "%-41s" % ("giusti su %d con tempo nel nome:" % n_tempo_truth)
    for n, _ in TEMPO_VARIANTS:
        line += " %18s" % ("%d" % tempo_hits[n])
    print(line)
    print()

    # ---------------- TONALITA' ----------------
    print("=" * 118)
    print("TONALITA'   (OK = giusta, rel = relativa, par = parallela, 5th = quinta, NO = altro)")
    print("=" * 118)
    hdr = "%-34s %6s" % ("file", "nome")
    for n, _ in KEY_VARIANTS:
        hdr += " %16s" % n
    print(hdr)
    print("-" * 118)

    for p in files:
        if p not in cache:
            continue
        x, sr, dur, tkey, tbpm = cache[p]
        name = os.path.basename(p)
        row = "%-34s %6s" % (name[:34], tkey or "-")
        if tkey:
            n_key_truth += 1
        for n, kw in KEY_VARIANTS:
            if callable(kw):
                pr = kw(x, sr)
            else:
                pr = key_probs(chroma(x, sr, **kw))
            i = int(np.argmax(pr))
            got = KEY_NAMES[i]
            v = key_verdict(got, tkey) if tkey else "?"
            if v == "OK":
                key_hits[n] += 1
            if v in ("OK", "rel", "par"):
                key_close[n] += 1
            row += " %8s %2d%% %3s" % (got, round(pr[i] * 100), v)
        print(row)

    print("-" * 118)
    line = "%-41s" % ("giusti su %d con tonalita' nel nome:" % n_key_truth)
    for n, _ in KEY_VARIANTS:
        line += " %16s" % ("%d" % key_hits[n])
    print(line)
    line = "%-41s" % "giusti o quasi (rel/par inclusi):"
    for n, _ in KEY_VARIANTS:
        line += " %16s" % ("%d" % key_close[n])
    print(line)


if __name__ == "__main__":
    main()
