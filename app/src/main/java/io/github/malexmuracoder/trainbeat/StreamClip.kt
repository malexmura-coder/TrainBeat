package io.github.malexmuracoder.trainbeat

/**
 * Audio that is still being decoded while it is already being played.
 *
 * A ten minute backing track is twenty six million samples, and decoding it takes
 * about twenty seconds - a wait with nothing to look at. But the decoder runs some
 * thirty times faster than the music plays, so there is no reason to wait for the
 * end: after a second of audio exists, playback can start, and the decoder stays
 * far ahead for ever after.
 *
 * The loop is not at risk. By the time the first pass reaches the end of the file,
 * the file has been complete for minutes, and from there on this is an ordinary
 * array with an ordinary seam.
 *
 * Thread discipline: the decoder thread appends, everyone else reads. A reader
 * takes [filled] FIRST and the array SECOND, because the array can be replaced by
 * a larger one in between and the larger one always contains what the smaller had.
 */
class StreamClip(
    val sampleRate: Int,
    val channels: Int,
    val info: FormatInfo,
    expectedFrames: Int
) {
    @Volatile var samples: ShortArray = ShortArray(
        (expectedFrames.coerceAtLeast(sampleRate) * channels.coerceAtLeast(1)))
        private set

    /** Samples written so far. */
    @Volatile var filled: Int = 0
        private set

    @Volatile var complete: Boolean = false
        private set

    @Volatile var error: String? = null
        private set

    /** Set from outside to stop the decoder: leaving the screen, or a new file. */
    @Volatile var cancelled: Boolean = false

    private val expected = (expectedFrames.toLong() * channels.coerceAtLeast(1)).coerceAtLeast(1)

    val frames: Int get() = if (channels > 0) filled / channels else 0

    val durationMs: Long get() = if (sampleRate > 0) frames * 1000L / sampleRate else 0L

    /** What the container says the whole thing lasts, known before decoding it. */
    val expectedMs: Long =
        if (sampleRate > 0 && channels > 0) expectedFrames * 1000L / sampleRate else 0L

    /** 0 to 1, for a line that says how far along the decoding is. */
    val progress: Float
        get() = if (complete) 1f else (filled.toFloat() / expected).coerceIn(0f, 0.999f)

    fun append(src: ShortArray, n: Int) {
        if (n <= 0) return
        var arr = samples
        if (filled + n > arr.size) {
            // the container's duration is an estimate: grow rather than truncate
            val bigger = ShortArray(maxOf(arr.size * 2, filled + n))
            System.arraycopy(arr, 0, bigger, 0, filled)
            samples = bigger
            arr = bigger
        }
        System.arraycopy(src, 0, arr, filled, n)
        filled += n                                   // last: this is what readers trust
    }

    fun finish() {
        // trim to a whole number of frames, so the loop cannot end mid-frame
        val f = filled - (filled % channels.coerceAtLeast(1))
        if (f != filled) filled = f
        complete = true
    }

    fun fail(message: String) {
        error = message
        complete = true
    }

    /** The finished thing, once decoding is over. */
    fun toClip(): AudioClip {
        val exact = if (samples.size == filled) samples else samples.copyOf(filled)
        return AudioClip(exact, sampleRate, channels, info)
    }
}

/** What LoopPlayer writes from: a finished array, or one still filling up. */
interface Feeder {
    fun fill(out: ShortArray, count: Int): Int
    val loopsCompleted: Int
}

/**
 * Feeds from a StreamClip. While the decoder is still ahead this behaves exactly
 * like the ordinary feeder; if it were ever caught up with, it waits rather than
 * inventing silence - a short wait is a glitch, silence in the middle of a bar is
 * a wrong note.
 */
class StreamFeeder(
    private val clip: StreamClip,
    /** Called when the loop wraps, so the train ramp can move on. */
    private val atSeam: ((Int) -> Unit)? = null
) : Feeder {

    private var pos = 0

    @Volatile override var loopsCompleted: Int = 0
        private set

    override fun fill(out: ShortArray, count: Int): Int {
        val want = minOf(count, out.size)
        var done = 0
        var waited = 0
        while (done < want) {
            val available = clip.filled                 // read the count first,
            val src = clip.samples                      // then the array
            if (clip.channels <= 0) return done
            if (pos >= available) {
                if (clip.complete) {
                    if (available <= 0) return done
                    pos = 0                             // <- the seam
                    loopsCompleted++
                    atSeam?.invoke(loopsCompleted)
                    continue
                }
                // the decoder is behind, which on any real phone means it is busy
                // starting up: wait briefly rather than break the stream
                if (waited > 2000) return done
                try { Thread.sleep(5) } catch (_: InterruptedException) { return done }
                waited += 5
                continue
            }
            val n = minOf(want - done, available - pos)
            System.arraycopy(src, pos, out, done, n)
            pos += n
            done += n
        }
        return done
    }
}
