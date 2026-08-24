package io.github.malexmuracoder.trainbeat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Band-limited resampling, windowed sinc with a Kaiser window.
 *
 * This replaces the linear interpolation that AudioTrack's own path uses when it
 * transposes, and it is the reason the top end stops sounding gritty: reading the
 * samples faster folds everything above the new Nyquist frequency back down into
 * the audible range, so the material is lowpass filtered FIRST and only then read.
 *
 * Offline, so the filter can be long enough to be honest: 32 taps per side.
 */
object Resample {

    private const val HALF_TAPS = 32
    private const val TABLE_STEPS = 512                      // sub-sample positions
    private const val BETA = 8.6                             // Kaiser beta, about 90 dB of stopband

    /**
     * Reads the input at [step] samples per output sample. step > 1 raises the
     * pitch and shortens the material, step < 1 lowers and lengthens it.
     */
    fun resample(samples: ShortArray, channels: Int, step: Double): ShortArray {
        if (abs(step - 1.0) < 1e-9 || samples.isEmpty()) return samples
        val frames = samples.size / channels
        // Reading faster means the old top octave would alias: filter it away first.
        val src = if (step > 1.0) lowpass(samples, channels, 1.0 / step) else samples
        val outFrames = ceil(frames / step).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * channels)
        val table = table(if (step > 1.0) 1.0 / step else 1.0)

        for (c in 0 until channels) {
            var pos = 0.0
            for (i in 0 until outFrames) {
                val centre = pos.toInt()
                val frac = pos - centre
                val sub = (frac * TABLE_STEPS).toInt().coerceIn(0, TABLE_STEPS - 1)
                var acc = 0.0
                var w = 0.0
                for (t in -HALF_TAPS until HALF_TAPS) {
                    val idx = centre + t
                    if (idx < 0 || idx >= frames) continue
                    val coeff = table[(t + HALF_TAPS) * TABLE_STEPS + sub]
                    acc += src[idx * channels + c] * coeff
                    w += coeff
                }
                val v = if (w > 1e-9) acc / w else 0.0
                out[i * channels + c] = clip(v)
                pos += step
            }
        }
        return out
    }

    /** Simple linear-phase FIR lowpass at [cutoff] of Nyquist, applied in place of nothing. */
    fun lowpass(samples: ShortArray, channels: Int, cutoff: Double): ShortArray {
        val fc = cutoff.coerceIn(0.02, 0.99)
        val n = 63                                            // odd, so the delay is an integer
        val half = n / 2
        val h = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val x = i - half
            val s = if (x == 0) fc else sin(PI * fc * x) / (PI * x)
            val win = kaiser(i.toDouble() / (n - 1))
            h[i] = s * win
            sum += h[i]
        }
        for (i in 0 until n) h[i] /= sum

        val frames = samples.size / channels
        val out = ShortArray(samples.size)
        for (c in 0 until channels) {
            for (f in 0 until frames) {
                var acc = 0.0
                for (i in 0 until n) {
                    val k = f + i - half
                    if (k < 0 || k >= frames) continue
                    acc += samples[k * channels + c] * h[i]
                }
                out[f * channels + c] = clip(acc)
            }
        }
        return out
    }

    private val tables = HashMap<Int, DoubleArray>()

    /** Sinc table for a given band limit, cached: the cost is paid once. */
    private fun table(cutoff: Double): DoubleArray {
        val key = (cutoff * 1000).toInt()
        synchronized(tables) {
            tables[key]?.let { return it }
            val fc = cutoff.coerceIn(0.05, 1.0)
            val t = DoubleArray(2 * HALF_TAPS * TABLE_STEPS)
            for (tap in -HALF_TAPS until HALF_TAPS) {
                for (s in 0 until TABLE_STEPS) {
                    val x = tap - s.toDouble() / TABLE_STEPS
                    val sinc = if (abs(x) < 1e-9) fc else sin(PI * fc * x) / (PI * x)
                    val win = kaiser((x / (2.0 * HALF_TAPS)) + 0.5)
                    t[(tap + HALF_TAPS) * TABLE_STEPS + s] = sinc * win
                }
            }
            tables[key] = t
            return t
        }
    }

    /** Kaiser window over 0..1, computed with the series form of I0. */
    private fun kaiser(u: Double): Double {
        if (u <= 0.0 || u >= 1.0) return 0.0
        val r = 2.0 * u - 1.0
        val arg = BETA * Math.sqrt(1.0 - r * r)
        return i0(arg) / i0(BETA)
    }

    private fun i0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2.0
        for (k in 1..24) {
            term *= (half / k) * (half / k)
            sum += term
            if (term < 1e-12 * sum) break
        }
        return sum
    }

    private fun clip(v: Double): Short = when {
        v > 32767.0 -> 32767
        v < -32768.0 -> -32768
        else -> v.toInt().toShort()
    }
}
