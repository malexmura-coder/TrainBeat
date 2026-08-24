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
 */
class LoopFeeder(private val src: ShortArray) {

    private var pos = 0

    val position: Int get() = pos

    fun reset() { pos = 0 }

    /** Fills out[0 until count] continuing the loop. Returns samples written. */
    fun fill(out: ShortArray, count: Int): Int {
        if (src.isEmpty() || count <= 0) return 0
        val want = minOf(count, out.size)
        var done = 0
        while (done < want) {
            val n = minOf(want - done, src.size - pos)
            System.arraycopy(src, pos, out, done, n)
            pos += n
            done += n
            if (pos >= src.size) pos = 0     // <- the seam
        }
        return done
    }
}
