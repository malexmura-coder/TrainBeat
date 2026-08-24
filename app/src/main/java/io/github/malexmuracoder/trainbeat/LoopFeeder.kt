package io.github.malexmuracoder.trainbeat

/**
 * Produces the endless stream of loop samples.
 *
 * Deliberately free of Android dependencies: this is the very class that feeds
 * AudioTrack on the phone and that desktop JVM tests can verify, so loop
 * continuity is proved on paper instead of trusted to the ear.
 *
 * Contract: consecutive fill() calls yield src repeated forever, without a
 * single sample inserted, lost or duplicated at the seam.
 *
 * Since v4 the loop can also CHANGE at the seam, and only at the seam: the train
 * mode renders the next repetition at the next speed while the current one plays,
 * and hands it over here. The swap happens exactly where the wrap happens, so the
 * guarantee above is untouched - what follows the last sample of one repetition is
 * the first sample of another, with nothing in between.
 */
class LoopFeeder(
    src: ShortArray,
    /** Asked for the buffer to use from repetition n on. Null means keep the current one. */
    private val next: ((Int) -> ShortArray?)? = null
) : Feeder {

    @Volatile private var cur: ShortArray = src
    private var pos = 0

    /** Repetitions completed, counted as they are WRITTEN, not as they are heard. */
    @Volatile override var loopsCompleted = 0
        private set

    val position: Int get() = pos

    val loopSamples: Int get() = cur.size

    fun reset() {
        pos = 0
        loopsCompleted = 0
    }

    /** Fills out[0 until count] continuing the loop. Returns samples written. */
    override fun fill(out: ShortArray, count: Int): Int {
        if (cur.isEmpty() || count <= 0) return 0
        val want = minOf(count, out.size)
        var done = 0
        while (done < want) {
            val src = cur
            if (pos >= src.size) pos = 0
            val n = minOf(want - done, src.size - pos)
            System.arraycopy(src, pos, out, done, n)
            pos += n
            done += n
            if (pos >= src.size) {                          // <- the seam
                pos = 0
                loopsCompleted++
                val replacement = next?.invoke(loopsCompleted)
                if (replacement != null && replacement.isNotEmpty()) cur = replacement
            }
        }
        return done
    }
}
