package io.github.malexmuracoder.trainbeat

/**
 * Where the loop is, in time.
 *
 * Free of Android, like the feeder it reads from, and for the same reason: this is
 * the arithmetic behind the moving cursor and behind coming in at a chosen point,
 * and both are things that can be got wrong by one frame in a way nobody hears but
 * everybody sees. So it is proved here rather than watched on a screen.
 *
 * TWO TIMELINES. What the track is fed is not always what the file contains. With
 * Hi-Fi on, a loop played at half speed has been rendered to twice the length, so a
 * position in the material is not a position in the piece. Everything here is
 * therefore stated as a ratio between the material actually being fed - loopFrames -
 * and the length of the original - durationMs. When nothing was rendered the two
 * agree and the ratio is one, which is the ordinary case and costs nothing.
 *
 * THE LEAD. The feeder reports what has been WRITTEN, and about a quarter of a
 * second of that is still in the buffer, not yet heard. A cursor that showed the
 * written position would sit permanently ahead of the music, which is precisely the
 * error a cursor exists to prevent, so the buffer is subtracted.
 */
object Cursor {

    /** How many frames of material correspond to a moment in the original piece. */
    fun frameAt(millis: Long, loopFrames: Int, durationMs: Long): Int {
        if (loopFrames <= 0 || durationMs <= 0L) return 0
        val ms = millis.coerceIn(0L, durationMs)
        val f = ms * loopFrames / durationMs
        return f.coerceIn(0L, (loopFrames - 1).toLong()).toInt()
    }

    /** The same relation read the other way: material back to the original piece. */
    fun millisAt(frames: Int, loopFrames: Int, durationMs: Long): Long {
        if (loopFrames <= 0 || durationMs <= 0L) return 0L
        val f = frames.coerceIn(0, loopFrames)
        return (f.toLong() * durationMs / loopFrames).coerceIn(0L, durationMs)
    }

    /**
     * An index into the interleaved array, ALWAYS on a frame boundary.
     *
     * The clamp is why this is not a multiplication written in place. An index one
     * short of the end is odd on a stereo file, and entering the loop on an odd
     * index swaps the two channels for the whole repetition - inaudible on a drum
     * loop, unmistakable on anything with a stereo image, and impossible to
     * diagnose from the symptom.
     */
    fun sampleAt(frames: Int, channels: Int, totalSamples: Int): Int {
        if (channels <= 0 || totalSamples < channels) return 0
        val last = totalSamples - (totalSamples % channels) - channels
        val idx = frames.toLong() * channels
        return idx.coerceIn(0L, last.toLong()).toInt()
    }

    /**
     * The written position moved back by the lead, wrapped into the loop.
     *
     * The lead is one buffer of MATERIAL, and deliberately not scaled by the speed.
     * What the app writes into the track is the material at its own rate; the
     * device's tempo change happens after the buffer, not before it, so what is
     * waiting in there is a buffer's worth of material however fast it is about to
     * be played. Speed changes how long that quarter second lasts to the ear, not
     * how much of the file it holds.
     *
     * The first pass is the exception, and it has to be. Before one buffer has been
     * written there is nothing to move back through, and wrapping would put the
     * cursor at the END of a loop that has only just started - the one place it
     * certainly is not.
     */
    fun heard(written: Int, lead: Int, loopFrames: Int, loopsDone: Int): Int {
        if (loopFrames <= 0) return 0
        val back = written - lead
        if (back >= 0) return back % loopFrames
        if (loopsDone <= 0) return 0
        return ((back % loopFrames) + loopFrames) % loopFrames
    }

    /**
     * m:ss, and mm:ss once it needs the room.
     *
     * Truncated rather than rounded, so the figure shown never names a moment that
     * has not arrived - a cursor reading 0:04 while the fourth second is still
     * playing is the kind of small lie that makes people distrust the whole reading.
     */
    fun label(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0L)
        val m = total / 60
        val s = total % 60
        return "$m:${twoDigits(s)}"
    }

    private fun twoDigits(n: Long): String = if (n < 10) "0$n" else "$n"
}
