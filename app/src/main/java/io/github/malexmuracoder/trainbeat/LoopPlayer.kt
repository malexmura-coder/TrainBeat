package io.github.malexmuracoder.trainbeat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

/**
 * Continuous loop playback, by either of two roads.
 *
 * The loop itself is the same in both: a single AudioTrack fed by LoopFeeder, which
 * on reaching the end of the array restarts from zero inside the same write cycle.
 * For the audio track the stream never breaks, so the seam is sample-exact.
 *
 * PREPARED (start): what arrives here is already at the right tempo and the right
 * pitch, so playback is a plain untouched stream - the one thing an audio path can
 * do without degrading anything. The train ramp then works by rendering the next
 * repetition while the current one plays and swapping it in at the seam.
 *
 * LIVE (startLive): tempo and pitch are applied while playing, by PlaybackParams
 * and the Sonic algorithm underneath it. Cheaper and instant, and honestly good
 * enough for the semitone or two most practice actually uses; it is what the app
 * does by default. The ramp here nudges the parameter at the seam instead, which is
 * simpler than the conductor thread this class used to run, and lands within one
 * buffer - about a quarter of a second - of the same place.
 */
class LoopPlayer {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var feeder: Feeder? = null

    /** The speed actually in force, which the ramp moves. */
    @Volatile var currentSpeed = 1.0f
        private set

    val isPlaying: Boolean get() = running

    /** Repetitions written so far. The one being heard is at most a buffer behind. */
    val loopsCompleted: Int get() = feeder?.loopsCompleted ?: 0

    /**
     * Plays material that is already exactly what should be heard.
     *
     * @param next asked at every seam for the buffer to continue with, or null to
     *             keep repeating the same one. This is where the train ramp lives.
     */
    fun start(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        next: ((Int) -> ShortArray?)? = null
    ) = startInternal(sampleRate, channels, samples, null, next, 1.0f, 0, 0f)

    /** Convenience for the callers that hold a clip. */
    fun start(clip: AudioClip, next: ((Int) -> ShortArray?)? = null) =
        start(clip.sampleRate, clip.channels, clip.samples, next)

    /**
     * Plays the original material and lets the device change tempo and pitch as it
     * goes. No wait, lower quality; the ramp moves the parameter at each seam.
     */
    fun startLive(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        speed: Float,
        semitones: Int,
        trainStep: Float
    ) = startInternal(sampleRate, channels, samples, null, null, speed, semitones, trainStep)

    fun startLive(clip: AudioClip, speed: Float, semitones: Int, trainStep: Float) =
        startLive(clip.sampleRate, clip.channels, clip.samples, speed, semitones, trainStep)

    /**
     * Plays audio that is still being decoded. Speed and pitch are the device's,
     * which is the only choice that makes sense here: what is not decoded yet
     * cannot be stretched.
     */
    fun startStream(clip: StreamClip, speed: Float, semitones: Int, trainStep: Float) =
        startInternal(clip.sampleRate, clip.channels, null, clip, null, speed, semitones, trainStep)

    private fun startInternal(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray?,
        stream: StreamClip?,
        next: ((Int) -> ShortArray?)?,
        speed: Float,
        semitones: Int,
        trainStep: Float
    ) {
        stop()
        if (channels <= 0) return
        if (stream == null && (samples == null || samples.isEmpty())) return

        val channelMask =
            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
            throw Exception("The device rejects $sampleRate Hz with $channels channels.")
        }
        val frameBytes = channels * 2
        // ~250 ms: long enough to avoid underruns, short enough that a change at the
        // seam is heard close to where it happened
        val bufBytes = maxOf(minBytes * 2, sampleRate * frameBytes / 4)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentSpeed = Ramp.speedAt(speed, trainStep, 0)
        if (!applyParams(t, currentSpeed, semitones)) {
            t.release()
            throw Exception(
                "Speed ${Ramp.speedLabel(currentSpeed)} with $semitones semitones was rejected by the device.")
        }

        // one hook at the seam, serving both roads: swap the buffer, or nudge the
        // playback parameter, depending on which road we are on
        val atSeam: (Int) -> ShortArray? = { loop ->
            if (trainStep > 0f) {
                val s = Ramp.speedAt(speed, trainStep, loop)
                if (s != currentSpeed && applyParams(t, s, semitones)) currentSpeed = s
            }
            next?.invoke(loop)
        }

        val f: Feeder =
            if (stream != null) StreamFeeder(stream) { loop -> atSeam(loop) }
            else LoopFeeder(samples!!, atSeam)
        feeder = f
        track = t
        running = true
        t.play()

        val step = 8192
        val th = Thread {
            val buf = ShortArray(step)
            try {
                while (running) {
                    val n = f.fill(buf, step)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && running) {
                        val w = t.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) { off = n; break }
                        off += w
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { t.pause(); t.flush(); t.stop() } catch (_: Exception) {}
                try { t.release() } catch (_: Exception) {}
                if (track === t) track = null
                running = false
            }
        }
        th.priority = Thread.MAX_PRIORITY
        th.name = "LoopWriter"
        worker = th
        th.start()
    }

    private fun applyParams(t: AudioTrack, speed: Float, semitones: Int): Boolean {
        val pitch = Ramp.pitchFactor(semitones)
        if (speed == 1.0f && pitch == 1.0f) return true
        return try {
            t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        val t = track
        try { t?.pause(); t?.flush() } catch (_: Exception) {}
        worker?.let { w -> try { w.join(1500) } catch (_: InterruptedException) {} }
        worker = null
        feeder = null
        currentSpeed = 1.0f
    }
}
