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

    /**
     * How long the whole thing is going to be, in interleaved samples.
     *
     * The container's figure, so an estimate, and that is exactly what the cursor
     * needs: a bar that measured itself against how much has been DECODED would
     * shrink and stretch under the finger as the decoder worked, and would put the
     * half way mark at a quarter of the way in.
     */
    val expectedSamples: Int get() = expected.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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
            // The container's duration is an estimate, so growing has to be possible
            // - but NOT by doubling. At this size doubling is a catastrophe: a ten
            // minute track is ninety six megabytes, and doubling asks for another
            // hundred and ninety while the old one is still alive. An eighth is
            // plenty, because the estimate is wrong by seconds, not by minutes.
            val bigger = ShortArray(maxOf(filled + n, arr.size + arr.size / 8))
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
        // Trim the slack HERE, once, so that toClip() can hand out this very array
        // instead of copying it. Copying was costing a second ninety six megabyte
        // array that then stayed alive beside this one for as long as the screen
        // was open - which is what filled the heap and killed the next track.
        if (f in 1 until samples.size) samples = samples.copyOf(f)
        complete = true
    }

    fun fail(message: String) {
        error = message
        complete = true
    }

    /**
     * The finished thing, once decoding is over.
     *
     * SHARES the array rather than copying it. Both objects are read-only from here
     * on - the decoder has finished, and nothing ever writes into a finished clip -
     * so two owners of one array is safe, and the alternative is carrying the whole
     * track twice. finish() has already trimmed it to the exact length.
     */
    fun toClip(): AudioClip {
        val exact = if (samples.size == filled) samples else samples.copyOf(filled)
        return AudioClip(exact, sampleRate, channels, info)
    }
}

/** What LoopPlayer writes from: a finished array, or one still filling up. */
interface Feeder {
    fun fill(out: ShortArray, count: Int): Int
    val loopsCompleted: Int

    /**
     * How far into the current pass the WRITING has got, in interleaved samples.
     *
     * Written, not heard: a buffer of it is still waiting to come out of the
     * device. Cursor.heard is what turns one into the other, and it is stated
     * there rather than here because the size of that buffer is the player's
     * business and not the feeder's.
     */
    val position: Int

    /** How long one pass is, in interleaved samples. */
    val loopSamples: Int

    /**
     * Stop waiting and come back, because the player is being torn down.
     *
     * Only the streaming feeder ever waits - for a decoder that is behind - and a
     * wait that outlives the reason for it is how a stop takes seconds instead of
     * being instant.
     */
    fun abort() {}
}

/**
 * Feeds from a StreamClip. While the decoder is still ahead this behaves exactly
 * like the ordinary feeder; if it were ever caught up with, it waits rather than
 * inventing silence - a short wait is a glitch, silence in the middle of a bar is
 * a wrong note.
 */
class StreamFeeder(
    private val clip: StreamClip,
    /**
     * Where the first pass comes in, in interleaved samples. A seek forward can
     * land past what has been decoded, and that is allowed: see the wait below.
     */
    startAt: Int = 0,
    /** Called when the loop wraps, so the train ramp can move on. */
    private val atSeam: ((Int) -> Unit)? = null
) : Feeder {

    @Volatile private var pos =
        startAt.coerceIn(0, (clip.samples.size - clip.channels.coerceAtLeast(1)).coerceAtLeast(0))

    @Volatile private var aborted = false

    @Volatile override var loopsCompleted: Int = 0
        private set

    override val position: Int get() = pos

    /**
     * One pass, in interleaved samples.
     *
     * While the decoder works this is what the file is EXPECTED to come to, not
     * what has arrived - see StreamClip.expectedSamples for why. Once decoding is
     * over the estimate is thrown away for the fact.
     */
    override val loopSamples: Int
        get() = if (clip.complete) clip.filled else maxOf(clip.expectedSamples, clip.filled)

    override fun abort() { aborted = true }

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
                // The decoder is behind. On any real phone at the start that means
                // it is busy starting up; after a seek forward it means we asked for
                // a part of the file it has not reached yet. Either way waiting is
                // the right answer, because the decoder runs many times faster than
                // the music and the wait ends by itself - and because inventing
                // silence in the middle of a bar is a wrong note.
                //
                // What must NOT happen is waiting for something that is never
                // coming, so every reason for no more samples ends it at once.
                if (aborted || clip.cancelled || clip.error != null) return done
                if (waited > MAX_WAIT_MS) return done
                try { Thread.sleep(5) } catch (_: InterruptedException) { return done }
                waited += 5
                continue
            }
            // src.size as well as available, and not only for tidiness. finish()
            // is the one moment when the array gets SMALLER - it trims the slack,
            // and trims a half frame off the end - so a reader that took the count
            // an instant before the trim and the array an instant after it would
            // index past the end. Everywhere else the two agree and this costs a
            // comparison.
            val n = minOf(want - done, minOf(available, src.size) - pos)
            if (n <= 0) return done
            System.arraycopy(src, pos, out, done, n)
            pos += n
            done += n
        }
        return done
    }

    companion object {
        /**
         * Long enough for a decoder to cross a whole track after a seek to the end,
         * short enough that a decoder which has genuinely died lets go of the track
         * rather than holding it open for ever.
         */
        private const val MAX_WAIT_MS = 60_000
    }
}
