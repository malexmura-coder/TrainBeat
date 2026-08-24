package io.github.malexmuracoder.trainbeat

/**
 * Turning a selection into a seamless loop. Pure functions, no Android, so the
 * behaviour is provable on a desktop JVM instead of judged by ear.
 *
 * Two steps, in this order and for different reasons:
 *
 * 1. the START is nudged onto a zero crossing, which removes the click that a
 *    cut through a rising waveform produces;
 * 2. the END is then chosen by SIMILARITY: among the frames around it, the one
 *    whose following samples most resemble those at the start. That is what makes
 *    the junction inaudible rather than merely quiet - the waveform continues
 *    into itself instead of jumping.
 *
 * Only the end is matched, never the start, so the beginning of the loop stays
 * where the musician put it.
 */
object LoopTrim {

    /** Nearest frame to `frame` where the channel mix crosses zero. */
    fun snapToZero(samples: ShortArray, channels: Int, frame: Int, searchFrames: Int): Int {
        val frames = samples.size / channels
        if (frames < 4) return frame
        val centre = frame.coerceIn(1, frames - 2)
        fun mix(f: Int): Int {
            var acc = 0
            val base = f * channels
            for (c in 0 until channels) acc += samples[base + c]
            return acc
        }
        for (d in 0..searchFrames) {
            for (sign in intArrayOf(1, -1)) {
                val f = centre + sign * d
                if (f < 1 || f >= frames - 1) continue
                val a = mix(f - 1)
                val b = mix(f)
                if (a == 0 || (a < 0) != (b < 0)) return f
            }
        }
        return centre
    }

    /**
     * End frame whose continuation best matches the loop start.
     *
     * Cost is the mean squared difference between the samples following the
     * candidate end and those following the start: zero would mean the file
     * repeats itself exactly there.
     */
    fun bestEnd(
        samples: ShortArray,
        channels: Int,
        start: Int,
        end: Int,
        searchFrames: Int,
        matchFrames: Int
    ): Int {
        val frames = samples.size / channels
        if (frames < 8) return end
        val minLen = 16
        var best = end
        var bestCost = Double.MAX_VALUE
        val lo = Math.max(start + minLen, end - searchFrames)
        val hi = Math.min(frames - matchFrames - 1, end + searchFrames)
        if (hi <= lo) return end
        if (start + matchFrames >= frames) return end

        // Candidates are visited from the chosen end outwards, so when several
        // match equally well - which is exactly what happens on periodic
        // material, where every period fits - the one closest to what the
        // musician picked wins. Scanning left to right moved a perfectly good
        // loop back by a whole period for no reason.
        var d = 0
        val span = Math.max(end - lo, hi - end)
        while (d <= span) {
            for (sign in intArrayOf(1, -1)) {
                val e = end + sign * d
                if (e < lo || e > hi) continue
                var cost = 0.0
                var k = 0
                while (k < matchFrames) {
                    val a = (e + k) * channels
                    val b = (start + k) * channels
                    for (c in 0 until channels) {
                        val diff = (samples[a + c] - samples[b + c]).toDouble()
                        cost += diff * diff
                    }
                    k += 2                  // every other frame: same answer, half the work
                }
                if (cost < bestCost) {
                    bestCost = cost
                    best = e
                }
                if (d == 0) break           // +0 and -0 are the same candidate
            }
            d++
        }
        return best
    }

    data class Trimmed(val start: Int, val end: Int, val movedStart: Int, val movedEnd: Int)

    /**
     * @param searchMs how far the end may move; the loop length changes by at
     * most this much, so the musical placement is preserved.
     */
    fun refine(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        start: Int,
        end: Int,
        searchMs: Double = 60.0,
        matchMs: Double = 25.0
    ): Trimmed {
        if (channels <= 0 || samples.isEmpty()) return Trimmed(start, end, 0, 0)
        val zeroWindow = Math.max(8, (sampleRate * 0.005).toInt())     // 5 ms
        val searchFrames = Math.max(4, (sampleRate * searchMs / 1000.0).toInt())
        val matchFrames = Math.max(16, (sampleRate * matchMs / 1000.0).toInt())

        val s2 = snapToZero(samples, channels, start, zeroWindow)
        val e2 = bestEnd(samples, channels, s2, end, searchFrames, matchFrames)
        return Trimmed(s2, e2, s2 - start, e2 - end)
    }

    /** Interleaved slice, on frame boundaries. */
    fun slice(samples: ShortArray, channels: Int, start: Int, end: Int): ShortArray {
        val frames = samples.size / channels
        val a = start.coerceIn(0, frames)
        val b = end.coerceIn(a, frames)
        return samples.copyOfRange(a * channels, b * channels)
    }

    /** How far the junction jumps, as a fraction of full scale. */
    fun seamStep(samples: ShortArray, channels: Int, start: Int, end: Int): Double {
        val frames = samples.size / channels
        if (frames < 2 || end <= start || end > frames) return 0.0
        var worst = 0
        for (c in 0 until channels) {
            val last = samples[(end - 1) * channels + c].toInt()
            val first = samples[start * channels + c].toInt()
            val d = Math.abs(first - last)
            if (d > worst) worst = d
        }
        return worst / 65535.0
    }
}
