package io.github.malexmuracoder.trainbeat

/**
 * Everything the player has to prepare BEFORE the sound starts.
 *
 * This is the change of v4. Until now speed and transposition were applied while
 * playing, by AudioTrack's own Sonic, and that is why they sounded poor on a band
 * with drums. Now the loop is prepared once - stretched, transposed, or synthesised
 * from a score - and then played untouched at its native rate. The cost moves from
 * every buffer to one wait per setting, which is what the "preparing rendering..."
 * message is for.
 *
 * At the default setting nothing is prepared at all: 100 per cent and no
 * transposition means the original samples, which is also the best quality
 * obtainable.
 *
 * Free of Android dependencies, so the whole of it is testable on a desktop JVM.
 */
object Renderer {

    /** How many prepared repetitions are kept: enough for a stepper tapped back and forth. */
    private const val CACHE_MAX = 4

    /**
     * What a prepared buffer is prepared FOR.
     *
     * [voices] is the mixer's channel mask, and it has to be in here. A buffer is
     * identified by everything that went into making it, and once an instrument can
     * be switched off, the same speed and the same key no longer describe the same
     * sound. Leave it out and turning the bass off returns the cached buffer with
     * the bass still in it - which looks exactly like a switch that does nothing.
     * Audio has no channels to switch, and passes ALL.
     */
    class Key(val speed: Int, val semitones: Int, val stamp: Long, val voices: Int) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.speed == speed && other.semitones == semitones &&
                other.stamp == stamp && other.voices == voices
        override fun hashCode(): Int = speed * 31 + semitones * 7 + voices * 131 + stamp.hashCode()
    }

    private val cache = LinkedHashMap<Key, ShortArray>()

    fun clearCache() = synchronized(cache) { cache.clear() }

    fun cached(speed: Float, semitones: Int, stamp: Long, voices: Int = Instruments.ALL): ShortArray? =
        synchronized(cache) { cache[Key(Math.round(speed * 1000), semitones, stamp, voices)] }

    private fun put(
        speed: Float, semitones: Int, stamp: Long, voices: Int, data: ShortArray
    ) {
        synchronized(cache) {
            cache[Key(Math.round(speed * 1000), semitones, stamp, voices)] = data
            while (cache.size > CACHE_MAX) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    /** True when the setting needs no work: the original samples are already right. */
    fun isPlain(speed: Float, semitones: Int): Boolean =
        Math.abs(speed - 1f) < 0.001f && semitones == 0

    /**
     * The samples to play for an AUDIO source at this setting. [stamp] identifies
     * the source (so the cache cannot serve one file's audio for another).
     */
    fun forAudio(
        base: AudioClip,
        speed: Float,
        semitones: Int,
        stamp: Long,
        cancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {}
    ): ShortArray {
        if (isPlain(speed, semitones)) return base.samples
        cached(speed, semitones, stamp)?.let { return it }
        val out = Stretch.render(base.samples, base.channels, speed.toDouble(), semitones, cancelled, progress)
        // audio has no channels to switch off: every buffer here is the whole thing
        put(speed, semitones, stamp, Instruments.ALL, out)
        return out
    }

    /**
     * The samples to play for a MIDI source at this setting. Speed and semitones
     * are applied to the SCORE, so there is no stretching and no resampling of
     * audio at all: the result is as exact as the soundfont allows.
     */
    fun forMidi(
        score: Score,
        sf: SoundFont,
        sampleRate: Int,
        speed: Float,
        semitones: Int,
        stamp: Long,
        /** The mixer's channel mask: everything, unless something was switched off. */
        voices: Int = Instruments.ALL,
        cancelled: () -> Boolean = { false },
        progress: (Double) -> Unit = {}
    ): Synth.Result {
        // Muted first, transposed second: the notes that will not be heard should
        // not be moved, and dropping them first is also the cheaper order.
        val heard = score.onlyChannels(voices)
        val used = if (semitones == 0) heard else heard.transpose(semitones)
        return Synth.render(used, sf, sampleRate, speed.toDouble(), cancelled, progress)
    }

    /** Cached MIDI samples, or null: the caller renders and stores them. */
    fun midiCached(speed: Float, semitones: Int, stamp: Long, voices: Int = Instruments.ALL): ShortArray? =
        cached(speed, semitones, stamp, voices)

    fun storeMidi(
        speed: Float, semitones: Int, stamp: Long, data: ShortArray, voices: Int = Instruments.ALL
    ) = put(speed, semitones, stamp, voices, data)

    /**
     * The speed of repetition [loop] under a train ramp, which is what the next
     * repetition has to be rendered at while the current one is playing.
     */
    fun rampSpeed(base: Float, step: Float, loop: Int): Float = Ramp.speedAt(base, step, loop)
}

/**
 * The train ramp, now that speed is baked into the samples.
 *
 * One repetition ahead is rendered on a worker thread while the current one plays,
 * and handed over at the seam. The loop lasts seconds and rendering it takes a
 * fraction of that, so the work is invisible - the "preparing" message appears only
 * for the FIRST repetition, and never again while the ramp climbs.
 *
 * If a phone ever cannot keep up, nothing breaks and nothing stutters: the
 * repetition simply plays again at the speed it already had, and [misses] counts
 * how often that happened so the screen can admit it instead of lying.
 */
class TrainRenderer(
    private val baseSpeed: Float,
    private val step: Float,
    private val render: (Float) -> ShortArray
) {
    @Volatile private var alive = true
    @Volatile private var wanted = 1
    @Volatile private var readyLoop = -1
    @Volatile private var readyBuf: ShortArray? = null
    @Volatile var misses = 0
        private set

    private val worker: Thread? = if (step <= 0f) null else Thread {
        while (alive) {
            val want = wanted
            // Only ever working on the repetition that is actually still needed. An
            // earlier version moved the target on every unsatisfied request, so under
            // repeated asking it restarted for ever and delivered nothing - which is
            // exactly what its test caught.
            if (readyBuf == null) {
                val speed = Ramp.speedAt(baseSpeed, step, want)
                val buf = try { render(speed) } catch (_: Throwable) { null }
                if (buf != null && alive && readyBuf == null) {
                    readyBuf = buf
                    readyLoop = want
                }
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
    }.also { it.name = "TrainRenderer"; it.isDaemon = true; it.start() }

    /**
     * Called at the seam, on the audio writer thread: never blocks, never waits.
     * A buffer prepared for an earlier repetition is still accepted - being one
     * step behind on the ramp is nothing, a stutter would be everything.
     */
    fun nextFor(loop: Int): ShortArray? {
        if (step <= 0f) return null
        val ready = readyLoop
        val buf = readyBuf
        if (buf != null && ready in 0..loop) {
            readyBuf = null
            readyLoop = -1
            wanted = loop + 1
            return buf
        }
        misses++
        return null
    }

    fun close() {
        alive = false
        worker?.interrupt()
        readyBuf = null
    }
}
