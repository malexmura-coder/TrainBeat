package io.github.malexmuracoder.trainbeat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Time stretching by phase vocoder, and the pitch shift built on top of it.
 *
 * Why this exists: AudioTrack's own tempo change uses Sonic, which cuts and splices
 * waveform segments. On a solo instrument that is fine; on a band with drums it
 * warbles. A phase vocoder works in the frequency domain instead, and with the two
 * corrections below it holds up on real material:
 *
 *  - PHASE LOCKING. Left alone, each bin advances its phase independently and the
 *    partials of one instrument drift apart from each other, which is exactly what
 *    "phasey" means. So the peaks of the spectrum are found and every bin around a
 *    peak keeps its original phase RELATIVE to that peak.
 *
 *  - TRANSIENT RESET. A drum hit is a moment when all the bins agree; smearing it
 *    across the window is what makes stretched drums sound like cardboard. When the
 *    spectral flux jumps, the phases are reset to the analysis phases, so the attack
 *    passes through untouched. The onset detector is the same idea already used to
 *    estimate tempo, which is a pleasant economy.
 *
 * Everything is offline. Cost is a few seconds of one core for a loop, and the
 * player pays it once per setting, not once per buffer.
 */
object Stretch {

    private const val N = 2048                               // window
    private const val SYNTH_HOP = N / 4                      // 75 per cent overlap
    private const val FLUX_JUMP = 0.45f                      // new energy over old: a hit, not a chord

    /**
     * Stretches to [factor] times the original length, pitch untouched.
     * factor > 1 makes it longer (slower), factor < 1 shorter (faster).
     */
    fun timeStretch(
        samples: ShortArray,
        channels: Int,
        factor: Double,
        cancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {}
    ): ShortArray {
        if (abs(factor - 1.0) < 1e-6 || samples.isEmpty()) return samples
        val frames = samples.size / channels
        if (frames < N * 2) return Resample.resample(samples, channels, 1.0 / factor)

        val analysisHop = (SYNTH_HOP / factor).toInt().coerceAtLeast(1)
        val nFrames = Math.ceil(frames.toDouble() / analysisHop).toInt()
        if (nFrames < 2) return Resample.resample(samples, channels, 1.0 / factor)
        // The material is a LOOP, so it is treated as circular: the analysis reads
        // past the end by wrapping to the beginning, and the overlap-add wraps too.
        // That is what keeps the stretched loop seamless - a linear stretch would
        // fade in at the start and out at the end, and the seam would click.
        val outFrames = Math.round(frames * factor).toInt().coerceAtLeast(N)
        val window = Fft.hann(N)

        // per channel spectra are rotated by ONE common phase advance, computed from
        // the mid signal: that keeps the stereo image exactly as it was, where
        // treating the channels separately makes it wander
        val acc = Array(channels) { FloatArray(outFrames) }
        val norm = FloatArray(outFrames)

        val re = Array(channels) { FloatArray(N) }
        val im = Array(channels) { FloatArray(N) }
        val midRe = FloatArray(N)
        val midIm = FloatArray(N)

        val bins = N / 2
        val lastPhase = FloatArray(bins)
        val sumPhase = FloatArray(bins)
        val prevMag = FloatArray(bins)
        val mag = FloatArray(bins)
        val phase = FloatArray(bins)
        val expected = 2.0 * PI * analysisHop / N

        var first = true
        for (f in 0 until nFrames) {
            if (cancelled()) break
            val start = f * analysisHop

            // ---- analyse ----
            for (c in 0 until channels) {
                val r = re[c]
                val i2 = im[c]
                for (k in 0 until N) {
                    val idx = (start + k) % frames
                    r[k] = samples[idx * channels + c] * window[k]
                    i2[k] = 0f
                }
                Fft.transform(r, i2)
            }
            for (k in 0 until N) {
                var s = 0f
                for (c in 0 until channels) s += re[c][k]
                midRe[k] = s / channels
            }
            for (k in 0 until N) {
                var s = 0f
                for (c in 0 until channels) s += im[c][k]
                midIm[k] = s / channels
            }
            for (k in 0 until bins) {
                mag[k] = hypot(midRe[k], midIm[k])
                phase[k] = atan2(midIm[k], midRe[k])
            }

            // ---- is this a transient ----
            var flux = 0f
            var base = 0f
            for (k in 0 until bins) {
                val d = mag[k] - prevMag[k]
                if (d > 0) flux += d
                base += prevMag[k]
            }
            // relative flux: how much of the frame's energy is NEW. A drum hit is a
            // large fraction, a sustained chord almost none.
            val onset = !first && base > 1e-3f && flux / base > FLUX_JUMP

            // ---- advance the phases ----
            if (first || onset) {
                // a fresh start, or a drum hit: take the phases as they are, which is
                // what keeps an attack an attack
                for (k in 0 until bins) sumPhase[k] = phase[k]
            } else {
                for (k in 0 until bins) {
                    var d = phase[k] - lastPhase[k] - (expected * k).toFloat()
                    d = wrap(d)
                    val trueFreq = (expected * k).toFloat() + d
                    sumPhase[k] = wrap(sumPhase[k] + (trueFreq * factor).toFloat())
                }
                lockToPeaks(mag, phase, sumPhase, bins)
            }
            for (k in 0 until bins) {
                lastPhase[k] = phase[k]
                prevMag[k] = mag[k]
            }
            first = false

            // ---- synthesise: keep each channel's own magnitude and its phase
            //      difference from the mid, rotate by the new mid phase ----
            val outStart = f * SYNTH_HOP
            for (c in 0 until channels) {
                val r = re[c]
                val i2 = im[c]
                for (k in 0 until bins) {
                    val m = hypot(r[k], i2[k])
                    val old = atan2(i2[k], r[k])
                    val rot = sumPhase[k] - phase[k] + old
                    r[k] = (m * cos(rot))
                    i2[k] = (m * sin(rot))
                    if (k > 0) {                              // hermitian half
                        r[N - k] = r[k]
                        i2[N - k] = -i2[k]
                    }
                }
                i2[0] = 0f
                i2[bins] = 0f                                // Nyquist bin has no partner
                // inverse transform by conjugation
                for (k in 0 until N) i2[k] = -i2[k]
                Fft.transform(r, i2)
                val scale = 1f / N
                val target = acc[c]
                for (k in 0 until N) {
                    target[(outStart + k) % outFrames] += r[k] * scale * window[k]
                }
            }
            for (k in 0 until N) norm[(outStart + k) % outFrames] += window[k] * window[k]
            if (f % 32 == 0) progress(f.toDouble() / nFrames)
        }

        // overlap-add normalisation: the windows do not sum to one on their own
        val outLen = outFrames
        val out = ShortArray(outLen * channels)
        for (c in 0 until channels) {
            val a = acc[c]
            for (i in 0 until outLen) {
                val w = norm[i]
                val v = if (w > 1e-4f) a[i] / w else a[i]
                out[i * channels + c] = clip(v.toDouble())
            }
        }
        return out
    }

    /**
     * Identity phase locking: bins that belong to the same partial keep their
     * original phase relationship to the peak of that partial.
     */
    private fun lockToPeaks(mag: FloatArray, phase: FloatArray, sumPhase: FloatArray, bins: Int) {
        var k = 1
        while (k < bins - 1) {
            if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] && mag[k] > 1e-4f) {
                // walk out to the valleys either side: that is this peak's territory
                var lo = k - 1
                while (lo > 0 && mag[lo - 1] < mag[lo]) lo--
                var hi = k + 1
                while (hi < bins - 1 && mag[hi + 1] < mag[hi]) hi++
                val peakSynth = sumPhase[k]
                val peakAnalysis = phase[k]
                for (b in lo..hi) {
                    if (b == k) continue
                    sumPhase[b] = wrap(peakSynth + (phase[b] - peakAnalysis))
                }
                k = hi + 1
            } else {
                k++
            }
        }
    }

    /**
     * Speed and transposition together, the way the player asks for them: the
     * result has the duration the speed implies and the pitch the semitones imply.
     * Stretch first, then resample - the resampler is what finally sets the pitch,
     * and doing it last means its anti-alias filter sees the finished material.
     */
    fun render(
        samples: ShortArray,
        channels: Int,
        speed: Double,
        semitones: Int,
        cancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {}
    ): ShortArray {
        val pitch = Math.pow(2.0, semitones / 12.0)
        if (abs(speed - 1.0) < 1e-6 && semitones == 0) return samples
        val factor = pitch / speed
        val stretched = timeStretch(samples, channels, factor, cancelled, progress)
        if (semitones == 0) return stretched
        return Resample.resample(stretched, channels, pitch)
    }

    private fun wrap(x: Float): Float {
        var v = x
        while (v > PI) v -= (2.0 * PI).toFloat()
        while (v < -PI) v += (2.0 * PI).toFloat()
        return v
    }

    private fun clip(v: Double): Short = when {
        v > 32767.0 -> 32767
        v < -32768.0 -> -32768
        else -> v.toInt().toShort()
    }
}
