package io.github.malexmuracoder.trainbeat

/**
 * Where a recording actually begins and ends.
 *
 * A phone recording always starts and finishes with the operator reaching for the
 * screen, so there is dead air at both ends - but never true silence: there is
 * always room noise, the amplifier hum, the fan. So a fixed threshold is useless.
 * The noise floor is MEASURED instead, as a low percentile of the frame energy: in
 * a take with dead air at both ends, that percentile IS the noise, by definition.
 *
 * Three decisions worth stating.
 *
 * The end is the LAST window above the threshold, not the first drop below it. A
 * plucked note decays smoothly into the noise, so "the first time it goes quiet"
 * lands in the middle of the note. The cost of this choice is that a thump while
 * putting the phone down keeps the tail; the cost of the other choice is a
 * truncated note. Keeping too much is the recoverable mistake.
 *
 * Generous margins are then left on both sides - a note's attack is quieter than
 * its body, and cutting into an attack is audible immediately.
 *
 * And nothing is ever cut here. This returns two offsets; the caller decides when
 * to apply them, which is what makes the operation reversible with no undo stack
 * and no second copy of the samples.
 */
object Silence {

    /** Energy is measured over windows this long: short enough to place an onset. */
    const val WINDOW_MS = 20

    /** The noise floor is this percentile of window energy. */
    const val FLOOR_PERCENTILE = 10

    /** A window counts as sound this far above the floor. */
    const val THRESHOLD_DB = 12.0

    /**
     * And the loudest window must be at least this far above the floor, or the take
     * is declared to be all noise. Without it, on a take of nothing but hiss the
     * random peaks of the noise itself would be mistaken for a performance.
     */
    const val PRESENCE_DB = 18.0

    /** Kept before the first sound: an attack starts quieter than the note. */
    const val HEAD_MARGIN_MS = 150

    /** Kept after the last sound: a decay ends quieter than the note. */
    const val TAIL_MARGIN_MS = 250

    /** How far a cut may be moved to land on a zero crossing, avoiding a click. */
    const val SNAP_MS = 5

    /** A trim that would leave less than this is refused. */
    const val MIN_KEEP_MS = 200

    /**
     * @param start first frame to keep
     * @param end   one past the last frame to keep
     * @param usable false when there is nothing to trim, or nothing but noise: in
     *               that case start/end span the whole take and the caller should
     *               leave the switch off.
     */
    class Bounds(val start: Int, val end: Int, val usable: Boolean) {
        val frames: Int get() = end - start
    }

    fun detect(samples: ShortArray, channels: Int, sampleRate: Int): Bounds {
        val frames = if (channels > 0) samples.size / channels else 0
        val whole = Bounds(0, frames, false)
        if (frames <= 0 || sampleRate <= 0) return whole

        val win = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        val count = frames / win
        if (count < 4) return whole                     // too short to say anything

        val energy = DoubleArray(count)
        for (w in 0 until count) {
            var sum = 0.0
            val from = w * win
            for (f in from until from + win) {
                var m = 0.0
                val base = f * channels
                for (ch in 0 until channels) m += samples[base + ch].toDouble()
                m /= channels
                sum += m * m
            }
            energy[w] = Math.sqrt(sum / win)
        }

        val sorted = energy.clone()
        sorted.sort()
        val floor = Math.max(sorted[count * FLOOR_PERCENTILE / 100], 1e-9)
        val loudest = sorted[count - 1]

        // is anything actually played here?
        if (loudest < floor * Math.pow(10.0, PRESENCE_DB / 20.0)) return whole

        val threshold = floor * Math.pow(10.0, THRESHOLD_DB / 20.0)
        var first = -1
        var last = -1
        for (w in 0 until count) {
            if (energy[w] >= threshold) {
                if (first < 0) first = w
                last = w
            }
        }
        if (first < 0) return whole

        val head = sampleRate * HEAD_MARGIN_MS / 1000
        val tail = sampleRate * TAIL_MARGIN_MS / 1000
        var start = (first * win - head).coerceAtLeast(0)
        var end = ((last + 1) * win + tail).coerceAtMost(frames)

        // the snap may only move OUTWARD - earlier for the start, later for the end.
        // Allowed to move either way it will happily walk into the first note looking
        // for a tidier zero crossing, which is exactly the damage the margins above
        // are there to prevent. Moving outward can only add a few milliseconds of
        // room noise, and it leaves a boundary that is already at the very edge of
        // the take exactly where it is.
        start = snap(samples, channels, start, sampleRate, -1, 0, end)
        end = snap(samples, channels, end, sampleRate, +1, start, frames)

        if (end - start < sampleRate * MIN_KEEP_MS / 1000) return whole
        if (start <= 0 && end >= frames) return whole   // nothing to cut

        return Bounds(start, end, true)
    }

    /**
     * Moves a cut to a frame where the waveform crosses zero, so the saved file
     * neither begins nor ends on a step. Searches only in [direction] - see the
     * caller for why that restriction is the whole point.
     */
    private fun snap(
        samples: ShortArray, channels: Int, frame: Int, sampleRate: Int,
        direction: Int, lo: Int, hi: Int
    ): Int {
        val reach = (sampleRate * SNAP_MS / 1000).coerceAtLeast(1)
        var best = frame
        var bestAbs = Int.MAX_VALUE
        for (d in 0..reach) {
            val f = frame + direction * d
            if (f < lo || f > hi) break
            val v = Math.abs(samples[(if (f >= hi) hi - 1 else f) * channels].toInt())
            if (v < bestAbs) { bestAbs = v; best = f }
            if (v == 0) return f
        }
        return best
    }

    /** What the switch shows: how much goes at each end. */
    fun describe(b: Bounds, frames: Int, sampleRate: Int): String {
        if (sampleRate <= 0) return ""
        val a = b.start.toDouble() / sampleRate
        val z = (frames - b.end).toDouble() / sampleRate
        return String.format(java.util.Locale.US, "-%.1f s / -%.1f s", a, z)
    }
}
