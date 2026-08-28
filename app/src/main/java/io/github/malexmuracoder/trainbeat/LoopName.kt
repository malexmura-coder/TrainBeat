package io.github.malexmuracoder.trainbeat

/**
 * What a saved loop is called.
 *
 * The name has to say WHERE the loop came from, not merely how long it is. Two four
 * bar loops out of one song - the verse and the chorus - are the normal case, and
 * naming them both "song_4bars_loop.wav" made the document picker hand back
 * "song_4bars_loop (1).wav" instead. Nothing is lost that way, but a month later a
 * folder of (1) (2) (3) tells you nothing about which is which.
 *
 * With a tempo, bars are what a musician counts, so the name counts bars: from bar
 * seventeen to bar twenty one. Without a tempo - a recording, or a track whose tempo
 * was not found - there is nothing to count in, so it falls back to the clock.
 */
object LoopName {

    /** The part that goes after the original name, extension included. */
    fun suffix(startFrame: Int, endFrame: Int, framesPerBeat: Int, sampleRate: Int): String {
        val length = (endFrame - startFrame).coerceAtLeast(0)
        return if (framesPerBeat > 0) {
            val fromBar = startFrame / framesPerBeat / 4 + 1
            val bars = Math.max(1, Math.round(length.toDouble() / framesPerBeat / 4).toInt())
            "_bars%d-%d_loop.wav".format(fromBar, fromBar + bars)
        } else {
            "_%s+%s_loop.wav".format(clock(startFrame, sampleRate), clock(length, sampleRate))
        }
    }

    /** 1m23s, or 4s when it is under a minute: legal in a file name anywhere. */
    fun clock(frames: Int, sampleRate: Int): String {
        if (sampleRate <= 0) return "0s"
        val total = frames / sampleRate
        val m = total / 60
        val s = total % 60
        return if (m > 0) "%dm%02ds".format(m, s) else "%ds".format(s)
    }
}
