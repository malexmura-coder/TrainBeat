package io.github.malexmuracoder.trainbeat

/**
 * Tempo and key estimation. Plain Kotlin, no Android dependency, so every step
 * is verifiable on a desktop JVM.
 *
 * Every choice in here was decided by measurement on real files, not by theory
 * (see tools/bench*.py). The constants below are the winning configuration.
 *
 * TEMPO. Spectral-flux onset envelope, comb-filtered autocorrelation, then the
 * loop constraint: a loop lasts a whole number of beats, so only integer beat
 * counts are considered and whole bars are preferred. The metric octave - the
 * classic half/double mistake - is settled by COUNTING ONSETS, an indicator
 * independent of autocorrelation: real drum patterns correlate most strongly at
 * the bar, so autocorrelation alone systematically picks a tempo too slow.
 *
 * KEY. Three different chromagram chains, each with its own harmonic
 * suppression, all classified against the Sha'ath profiles, and their scores
 * fused. On the test set the fusion beat every single chain on its own.
 */
object Analyzer {

    data class KeyGuess(val name: String, val p: Float)

    data class Result(
        val bpm: Float,          // snapped to the loop constraint
        val bpmRaw: Float,       // raw autocorrelation estimate
        val beats: Int,
        val bars: Int,
        val barsRemainder: Int,
        val onsets: Int,
        val keyProbs: FloatArray // 24 values aligned with KEY_NAMES, summing to 1
    ) {
        val keys: List<KeyGuess> get() = topKeys(keyProbs)
    }

    // ---------------- tone profiles ----------------

    private val KRUM_MAJOR = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
    )
    private val KRUM_MINOR = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
    )
    // Sha'ath: Krumhansl with a reinforced tonic and reworked sevenths
    private val SHAATH_MAJOR = floatArrayOf(
        7.0f, 2.0f, 3.5f, 2.0f, 4.5f, 4.0f, 2.0f, 5.5f, 2.0f, 3.5f, 1.5f, 4.0f
    )
    private val SHAATH_MINOR = floatArrayOf(
        7.0f, 2.0f, 3.5f, 4.5f, 2.0f, 4.0f, 2.0f, 5.5f, 3.5f, 2.0f, 4.5f, 4.0f
    )

    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** The 24 keys in a fixed order: index = root * 2 + mode, mode 0 major, 1 minor. */
    val KEY_NAMES: Array<String> = Array(24) { i ->
        if (i % 2 == 0) NOTES[i / 2] else NOTES[i / 2] + "m"
    }

    fun keyIndex(name: String): Int = KEY_NAMES.indexOf(name)

    // ---------------- the fused estimators ----------------

    private const val CHAIN_PEAKS = 0
    private const val CHAIN_CQ = 1
    private const val CHAIN_HARM = 2

    /**
     * alpha suppresses the fifth above (a note's 3rd harmonic lands there),
     * beta the major third above (its 5th harmonic).
     */
    private class Estimator(
        val chain: Int,
        val alpha: Float,
        val beta: Float,
        val major: FloatArray,
        val minor: FloatArray
    )

    private val ESTIMATORS = listOf(
        Estimator(CHAIN_CQ, 0.00f, 0.10f, SHAATH_MAJOR, SHAATH_MINOR),
        Estimator(CHAIN_PEAKS, 0.20f, 0.20f, SHAATH_MAJOR, SHAATH_MINOR),
        Estimator(CHAIN_HARM, 0.00f, 0.10f, SHAATH_MAJOR, SHAATH_MINOR)
    )

    const val KEY_THRESHOLD = 0.05f

    // ---------------- analysis parameters ----------------

    private const val FLUX_N = 1024
    private const val FLUX_HOP = 512
    private const val PEAKS_N = 4096
    private const val PEAKS_HOP = 2048
    private const val PEAKS_FMIN = 100.0
    private const val PEAKS_FMAX = 2000.0
    private const val CQ_RATE = 4410.0
    private const val CQ_N = 16384
    private const val CQ_BINS = 72
    private const val CQ_FMIN = 32.703      // C1
    private const val CQ_P = 0.8
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /**
     * @param loopExact true when the material really is a whole loop, so the beat
     * count must be an integer. False for a window taken out of a longer track:
     * there the loop constraint would invent a beat count, so the tempo is left
     * as a free estimate and beats and bars are reported as unknown.
     */
    fun analyze(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        loopExact: Boolean = true
    ): Result {
        val mono = toMono(samples, channels)
        val durSec = mono.size.toDouble() / sampleRate

        // --- tempo ---
        val env = onsetEnvelope(mono)
        val envRate = sampleRate.toDouble() / FLUX_HOP
        val onsets = countOnsets(env)
        val tempo: Pair<Double, Int>
        val bpm: Float
        val beats: Int
        if (loopExact) {
            tempo = estimateTempo(env, envRate, durSec, onsets)
            beats = tempo.second
            bpm = if (durSec > 0) (beats * 60.0 / durSec).toFloat() else 0f
        } else {
            val free = estimateTempoFree(env, envRate, onsets)
            tempo = Pair(free, 0)
            beats = 0
            bpm = free.toFloat()
        }

        // --- key ---
        val c72 = constantQ(mono, sampleRate)
        val chains = arrayOf(
            chromaPeaks(mono, sampleRate),
            fold(c72),
            fold(harmonicSum(c72))
        )
        val fused = FloatArray(24)
        var used = 0
        for (e in ESTIMATORS) {
            val ch = suppress(chains[e.chain], e.alpha, e.beta)
            val sc = keyScores(ch, e.major, e.minor)
            val n = normaliseShifted(sc)
            if (n.any { it > 0f }) {
                for (i in 0..23) fused[i] += n[i]
                used++
            }
        }
        if (used > 0) for (i in 0..23) fused[i] /= used

        return Result(bpm, tempo.first.toFloat(), beats, beats / 4, beats % 4, onsets, display(fused))
    }

    /**
     * Tempo without the loop constraint: a plain sweep, with the metric octave
     * settled by the onset count exactly as in the constrained case.
     */
    fun estimateTempoFree(env: FloatArray, envRate: Double, onsets: Int): Double {
        if (env.size < 16) return 0.0
        var mean = 0.0
        for (v in env) mean += v
        mean /= env.size
        val x = FloatArray(env.size) { (env[it] - mean).toFloat() }
        val maxLag = Math.min((60.0 * envRate / MIN_BPM).toInt() * 3 + 2, x.size - 2)
        if (maxLag < 4) return 0.0
        val acf = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var s = 0.0
            var i = 0
            val n = x.size - lag
            while (i < n) { s += x[i] * x[i + lag]; i++ }
            acf[lag] = if (n > 0) s / n else 0.0
        }
        fun at(lag: Double): Double {
            if (lag < 1 || lag > maxLag - 1) return 0.0
            val i = lag.toInt()
            val f = lag - i
            return acf[i] * (1 - f) + acf[i + 1] * f
        }
        fun comb(bpm: Double): Double {
            val lag = 60.0 * envRate / bpm
            val w = Math.exp(-0.5 * Math.pow(Math.log(bpm / 120.0) / Math.log(2.0) / 0.55, 2.0))
            return (at(lag) + 0.5 * at(2 * lag) + 0.25 * at(3 * lag)) * w
        }
        var best = 0.0
        var bestScore = -Double.MAX_VALUE
        var bpm = MIN_BPM
        while (bpm <= MAX_BPM) {
            val s = comb(bpm)
            if (s > bestScore) { bestScore = s; best = bpm }
            bpm += 0.25
        }
        if (best <= 0.0 || onsets < 4) return best

        // Metric octave from the onset count. Over a window lasting W seconds a
        // candidate implies bpm*W/60 beats, so onsets per beat is measurable and
        // should sit near one or two - the same indicator used for whole loops.
        val windowSec = env.size / envRate
        var winner = best
        var winnerScore = -Double.MAX_VALUE
        for (ratio in doubleArrayOf(0.5, 1.0, 2.0)) {
            val cand = best * ratio
            if (cand < MIN_BPM || cand > MAX_BPM) continue
            val beatsHere = cand * windowSec / 60.0
            if (beatsHere <= 0) continue
            val r = onsets / beatsHere
            val penalty = if (r <= 0) 4.0 else Math.min(
                Math.abs(Math.log(r) / Math.log(2.0)),
                Math.abs(Math.log(r / 2.0) / Math.log(2.0))
            )
            val score = comb(cand) / (1.0 + 1.5 * penalty)
            if (score > winnerScore) { winnerScore = score; winner = cand }
        }
        return winner
    }

    // ---------------- signal helpers ----------------

    fun toMono(samples: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) return FloatArray(samples.size) { samples[it] / 32768f }
        val frames = samples.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            val base = f * channels
            for (c in 0 until channels) acc += samples[base + c]
            out[f] = acc / (channels * 32768f)
        }
        return out
    }

    /** Spectral flux: how much energy appears from one window to the next. */
    fun onsetEnvelope(mono: FloatArray): FloatArray {
        if (mono.size < FLUX_N * 2) return FloatArray(0)
        val win = Fft.hann(FLUX_N)
        val frames = (mono.size - FLUX_N) / FLUX_HOP
        val out = FloatArray(frames)
        var prev: FloatArray? = null
        val buf = FloatArray(FLUX_N)
        for (i in 0 until frames) {
            val off = i * FLUX_HOP
            for (k in 0 until FLUX_N) buf[k] = mono[off + k] * win[k]
            val mag = Fft.magnitudes(buf)
            val p = prev
            if (p != null) {
                var flux = 0f
                for (k in mag.indices) {
                    val d = mag[k] - p[k]
                    if (d > 0f) flux += d
                }
                out[i] = flux
            }
            prev = mag
        }
        return out
    }

    /** Clear onsets: local maxima above mean + standard deviation. */
    fun countOnsets(env: FloatArray): Int {
        if (env.size < 5) return 0
        var mean = 0.0
        for (v in env) mean += v
        mean /= env.size
        val e = FloatArray(env.size) { maxOf(0f, (env[it] - mean).toFloat()) }
        var m = 0.0
        for (v in e) m += v
        m /= e.size
        var sd = 0.0
        for (v in e) sd += (v - m) * (v - m)
        sd = Math.sqrt(sd / e.size)
        val thr = m + sd
        var count = 0
        var lastAt = -10
        for (i in 1 until e.size - 1) {
            if (e[i] >= e[i - 1] && e[i] > e[i + 1] && e[i] > thr) {
                if (i - lastAt < 3) continue        // no double hits within ~35 ms
                count++
                lastAt = i
            }
        }
        return count
    }

    /**
     * Returns raw estimate and beat count. Only integer beat counts are allowed
     * (the loop constraint); whole bars are preferred but not required, so a loop
     * of 127 beats stays reachable. The onset count settles the metric octave.
     */
    fun estimateTempo(env: FloatArray, envRate: Double, durSec: Double, onsets: Int): Pair<Double, Int> {
        if (env.size < 16 || durSec <= 0) return Pair(0.0, 1)
        var mean = 0.0
        for (v in env) mean += v
        mean /= env.size
        val x = FloatArray(env.size) { (env[it] - mean).toFloat() }

        val maxLag = Math.min((60.0 * envRate / MIN_BPM).toInt() * 3 + 2, x.size - 2)
        if (maxLag < 4) return Pair(0.0, 1)
        val acf = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var s = 0.0
            var i = 0
            val n = x.size - lag
            while (i < n) {
                s += x[i] * x[i + lag]
                i++
            }
            acf[lag] = if (n > 0) s / n else 0.0
        }

        fun at(lag: Double): Double {
            if (lag < 1 || lag > maxLag - 1) return 0.0
            val i = lag.toInt()
            val f = lag - i
            return acf[i] * (1 - f) + acf[i + 1] * f
        }

        fun comb(bpm: Double): Double {
            val lag = 60.0 * envRate / bpm
            val w = Math.exp(-0.5 * Math.pow(Math.log(bpm / 120.0) / Math.log(2.0) / 0.55, 2.0))
            return (at(lag) + 0.5 * at(2 * lag) + 0.25 * at(3 * lag)) * w
        }

        // candidates: every integer beat count whose tempo is plausible
        val cands = ArrayList<Triple<Double, Int, Double>>()
        var beats = 1
        while (beats < 20000) {
            val bpm = beats * 60.0 / durSec
            if (bpm > MAX_BPM) break
            if (bpm >= MIN_BPM) {
                var s = comb(bpm)
                if (beats % 4 == 0) s *= 1.12                       // whole bars
                if (beats % 16 == 0) s *= 1.06                      // whole groups of 4 bars
                cands.add(Triple(bpm, beats, s))
            }
            beats++
        }
        if (cands.isEmpty()) return Pair(0.0, 1)
        cands.sortByDescending { it.third }

        val raw = cands[0].first
        if (onsets < 4) return Pair(raw, cands[0].second)

        // metric octave from the onset count: in a real pattern the number of
        // clear onsets sits near one or two per beat
        var best = cands[0]
        var bestScore = -Double.MAX_VALUE
        for (c in cands.take(8)) {
            val r = onsets.toDouble() / c.second
            val penalty = if (r <= 0) 4.0 else Math.min(
                Math.abs(Math.log(r) / Math.log(2.0)),
                Math.abs(Math.log(r / 2.0) / Math.log(2.0))
            )
            val score = c.third / (1.0 + 1.5 * penalty)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return Pair(raw, best.second)
    }

    // ---------------- chroma: chain "peaks" ----------------

    /**
     * Only spectral peaks are kept: a note is a peak, a drum hit is broadband.
     * Summing every bin let percussion flatten the chromagram, which was why the
     * first version answered Am for everything.
     */
    fun chromaPeaks(mono: FloatArray, sampleRate: Int): FloatArray {
        val chroma = FloatArray(12)
        if (mono.size < PEAKS_N * 2) return chroma
        val win = Fft.hann(PEAKS_N)
        val buf = FloatArray(PEAKS_N)
        val frames = (mono.size - PEAKS_N) / PEAKS_HOP
        val binHz = sampleRate.toDouble() / PEAKS_N
        val kMin = Math.max(1, (PEAKS_FMIN / binHz).toInt())
        val kMax = Math.min(PEAKS_N / 2 - 2, (PEAKS_FMAX / binHz).toInt())
        if (kMax <= kMin + 2) return chroma
        val pc = IntArray(kMax - kMin + 1)
        for (k in kMin..kMax) {
            val f = k * binHz
            val midi = 69.0 + 12.0 * (Math.log(f / 440.0) / Math.log(2.0))
            pc[k - kMin] = ((Math.round(midi).toInt() % 12) + 12) % 12
        }
        for (i in 0 until frames) {
            val off = i * PEAKS_HOP
            for (k in 0 until PEAKS_N) buf[k] = mono[off + k] * win[k]
            val mag = Fft.magnitudes(buf)
            var m = 0.0
            for (k in kMin..kMax) m += mag[k]
            m /= (kMax - kMin + 1)
            var sd = 0.0
            for (k in kMin..kMax) sd += (mag[k] - m) * (mag[k] - m)
            sd = Math.sqrt(sd / (kMax - kMin + 1))
            val thr = m + sd
            for (k in kMin..kMax) {
                val v = mag[k]
                if (v > thr && v > mag[k - 1] && v > mag[k + 1]) chroma[pc[k - kMin]] += v.toFloat()
            }
        }
        return normaliseSum(chroma)
    }

    // ---------------- chroma: chain "cq" (KeyFinder-style) ----------------

    private class CqKernels(val idx: Array<IntArray>, val w: Array<FloatArray>)

    private var kernelCache: CqKernels? = null
    private var kernelKey: String = ""

    private fun kernels(rate: Double, n: Int): CqKernels {
        val key = "$rate/$n"
        val cached = kernelCache
        if (cached != null && kernelKey == key) return cached
        val binHz = rate / n
        val idx = ArrayList<IntArray>(CQ_BINS)
        val ws = ArrayList<FloatArray>(CQ_BINS)
        for (b in 0 until CQ_BINS) {
            val f = CQ_FMIN * Math.pow(2.0, b / 12.0)
            val bw = CQ_P * (Math.pow(2.0, 1 / 12.0) - 1) * f
            val lo = f - bw / 2
            val hi = f + bw / 2
            var i0 = Math.max(1, Math.floor(lo / binHz).toInt())
            var i1 = Math.min(n / 2 - 1, Math.ceil(hi / binHz).toInt())
            if (i1 <= i0) {
                i0 = Math.max(1, Math.min(n / 2 - 2, Math.round(f / binHz).toInt()))
                i1 = i0 + 1
            }
            val ii = IntArray(i1 - i0 + 1) { i0 + it }
            val wv = FloatArray(ii.size)
            var sum = 0f
            for (j in ii.indices) {
                val fr = (ii[j] * binHz - lo) / Math.max(1e-9, hi - lo)
                val v = (0.5 - 0.5 * Math.cos(2 * Math.PI * fr)).toFloat()
                wv[j] = if (v > 0f) v else 0f
                sum += wv[j]
            }
            if (sum > 0f) for (j in wv.indices) wv[j] /= sum
            idx.add(ii)
            ws.add(wv)
        }
        val k = CqKernels(idx.toTypedArray(), ws.toTypedArray())
        kernelCache = k
        kernelKey = key
        return k
    }

    /**
     * 72 semitone bins from C1. The signal is first decimated to ~4410 Hz, which
     * is what makes the low octaves resolvable: at that rate a 16384-point window
     * has 0.27 Hz bins, well inside a semitone even at 32 Hz.
     */
    fun constantQ(mono: FloatArray, sampleRate: Int): FloatArray {
        val out = FloatArray(CQ_BINS)
        val factor = Math.max(1, Math.round(sampleRate / CQ_RATE).toInt())
        val y = decimate(mono, factor)
        val rate = sampleRate.toDouble() / factor
        var n = CQ_N
        while (n > 1024 && y.size < n + 8) n = n shr 1
        if (y.size < n + 8) return out
        val hop = n / 4
        val win = Fft.blackman(n)
        val k = kernels(rate, n)
        val buf = FloatArray(n)
        val frames = Math.max(1, (y.size - n) / hop)
        for (i in 0 until frames) {
            val off = i * hop
            if (off + n > y.size) break
            for (j in 0 until n) buf[j] = y[off + j] * win[j]
            val mag = Fft.magnitudes(buf)
            for (b in 0 until CQ_BINS) {
                val ii = k.idx[b]
                val wv = k.w[b]
                var acc = 0f
                for (j in ii.indices) {
                    val bin = ii[j]
                    if (bin < mag.size) acc += mag[bin] * wv[j]
                }
                out[b] += acc
            }
        }
        return out
    }

    /**
     * Low-pass and decimate in one pass: the filter is only evaluated at the
     * samples that survive, so the cost is that of the output, not of the input.
     */
    fun decimate(x: FloatArray, factor: Int): FloatArray {
        if (factor <= 1) return x
        val taps = 161
        val cutoff = 0.40 / factor
        val h = FloatArray(taps)
        var sum = 0f
        for (i in 0 until taps) {
            val t = i - (taps - 1) / 2.0
            val sinc = if (t == 0.0) 2 * cutoff else Math.sin(2 * Math.PI * cutoff * t) / (Math.PI * t)
            val w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (taps - 1)) +
                0.08 * Math.cos(4 * Math.PI * i / (taps - 1))
            h[i] = (sinc * w).toFloat()
            sum += h[i]
        }
        if (sum != 0f) for (i in h.indices) h[i] /= sum
        val outLen = x.size / factor
        val out = FloatArray(outLen)
        val half = (taps - 1) / 2
        for (o in 0 until outLen) {
            val centre = o * factor
            var acc = 0f
            for (j in 0 until taps) {
                val s = centre + j - half
                if (s in x.indices) acc += x[s] * h[j]
            }
            out[o] = acc
        }
        return out
    }

    // ---------------- chroma: chain "harm" ----------------

    /**
     * Harmonic summation on the semitone scale: each note also collects the
     * energy of its own harmonics, which pulls energy back to the fundamental
     * instead of leaving it spread over the overtones.
     */
    fun harmonicSum(c72: FloatArray): FloatArray {
        val out = FloatArray(c72.size)
        val harmonics = intArrayOf(1, 2, 3, 4)
        val weights = floatArrayOf(1.0f, 0.5f, 0.33f, 0.25f)
        for (hi in harmonics.indices) {
            val shift = Math.round(12.0 * Math.log(harmonics[hi].toDouble()) / Math.log(2.0)).toInt()
            val w = weights[hi]
            for (b in c72.indices) {
                val src = b + shift
                if (src < c72.size) out[b] += w * c72[src]
            }
        }
        return out
    }

    fun fold(c72: FloatArray): FloatArray {
        val ch = FloatArray(12)
        for (b in c72.indices) ch[b % 12] += c72[b]
        return normaliseSum(ch)
    }

    // ---------------- key classification ----------------

    /** Removes the harmonic tail of the notes below: a fifth down, a major third down. */
    fun suppress(ch: FloatArray, alpha: Float, beta: Float): FloatArray {
        if (alpha == 0f && beta == 0f) return ch
        val out = FloatArray(12)
        for (p in 0..11) {
            var v = ch[p] - alpha * ch[(p - 7 + 12) % 12] - beta * ch[(p - 4 + 12) % 12]
            if (v < 0f) v = 0f
            out[p] = v
        }
        return normaliseSum(out)
    }

    fun keyScores(ch: FloatArray, major: FloatArray, minor: FloatArray): FloatArray {
        val out = FloatArray(24)
        var sum = 0f
        for (v in ch) sum += v
        if (sum <= 0f) return out
        for (root in 0..11) {
            for (mode in 0..1) {
                val profile = if (mode == 0) major else minor
                val rotated = FloatArray(12) { profile[((it - root) % 12 + 12) % 12] }
                out[root * 2 + mode] = pearson(ch, rotated).toFloat()
            }
        }
        return out
    }

    /** Shifts so the worst candidate sits at zero, then normalises. */
    private fun normaliseShifted(sc: FloatArray): FloatArray {
        if (sc.isEmpty()) return sc
        var min = Float.MAX_VALUE
        for (v in sc) if (v < min) min = v
        val out = FloatArray(sc.size)
        var sum = 0f
        for (i in sc.indices) {
            out[i] = sc[i] - min
            sum += out[i]
        }
        if (sum > 0f) for (i in out.indices) out[i] /= sum
        return out
    }

    /**
     * Turns the fused vector into figures fit to show: everything below the mean
     * goes to zero, so musically distant keys read as absent rather than as a
     * small number, and the surviving order is untouched.
     */
    private fun display(fused: FloatArray): FloatArray {
        var mean = 0f
        for (v in fused) mean += v
        mean /= fused.size
        val out = FloatArray(fused.size)
        var sum = 0f
        for (i in fused.indices) {
            val v = fused[i] - mean
            // the exponent only sharpens the contrast: a clear winner reads as
            // one, an ambiguous track shows two close numbers. Order is untouched.
            out[i] = if (v > 0f) Math.pow(v.toDouble(), 2.5).toFloat() else 0f
            sum += out[i]
        }
        if (sum > 0f) for (i in out.indices) out[i] /= sum
        return out
    }

    fun topKeys(probs: FloatArray, threshold: Float = KEY_THRESHOLD): List<KeyGuess> {
        val out = ArrayList<KeyGuess>()
        for (i in probs.indices) {
            if (probs[i] >= threshold) out.add(KeyGuess(KEY_NAMES[i], probs[i]))
        }
        out.sortByDescending { it.p }
        return out
    }

    private fun normaliseSum(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x
        if (sum > 0f) for (i in v.indices) v[i] /= sum
        return v
    }

    private fun pearson(a: FloatArray, b: FloatArray): Double {
        val n = a.size
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val den = Math.sqrt(da * db)
        return if (den <= 0.0) 0.0 else num / den
    }

    // ---------------- labels ----------------

    fun tempoLabel(r: Result): String {
        val bars = if (r.barsRemainder == 0) "${r.bars} bars"
        else "${r.bars} bars + ${r.barsRemainder}"
        return String.format("%.2f BPM   (%d beats, %s)", r.bpm, r.beats, bars)
    }

    fun keyLabel(r: Result): String {
        if (r.keys.isEmpty()) return "not detected"
        return r.keys.take(4).joinToString("  ·  ") {
            it.name + " " + Math.round(it.p * 100) + "%"
        }
    }
}
