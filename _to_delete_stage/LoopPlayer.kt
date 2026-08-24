package io.github.malexmuracoder.trainbeat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

/**
 * Continuous loop playback, with variable speed, transposition and ramp.
 *
 * The loop: a single AudioTrack fed by LoopFeeder, which on reaching the end of
 * the array restarts from zero inside the same write cycle. For the audio track
 * the stream never breaks, so the seam is sample-exact.
 *
 * Speed and key: PlaybackParams (Sonic underneath). setSpeed changes tempo,
 * setPitch transposes. They are independent, so they combine.
 *
 * The ramp: a "conductor" thread estimates which loop is being HEARD - not the
 * one being written - and applies the new speed on every loop change. The
 * estimate starts from AudioTrack's playback counter and is clamped between
 * frames written and frames written minus the buffer, so a wrong reading of
 * that counter cannot drift further than the buffer length.
 */
class LoopPlayer {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var conductor: Thread? = null
    @Volatile private var running = false

    @Volatile private var framesWritten = 0L
    @Volatile private var bufferFrames = 0

    @Volatile var currentSpeed = 1.0f
        private set
    @Volatile var currentLoop = 0
        private set

    val isPlaying: Boolean get() = running

    fun start(clip: AudioClip, baseSpeed: Float, pitchSemitones: Int, trainStep: Float) {
        stop()
        if (clip.frames <= 0) return

        val channelMask =
            if (clip.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes =
            AudioTrack.getMinBufferSize(clip.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
            throw Exception("The device rejects ${clip.sampleRate} Hz with ${clip.channels} channels.")
        }
        val frameBytes = clip.channels * 2
        // ~250 ms: long enough to avoid underruns, short enough that the ramp
        // increment lands close to the seam
        val bufBytes = maxOf(minBytes * 2, clip.sampleRate * frameBytes / 4)

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
                    .setSampleRate(clip.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        bufferFrames = bufBytes / frameBytes
        framesWritten = 0L
        currentLoop = 0
        currentSpeed = Ramp.speedAt(baseSpeed, trainStep, 0)

        if (!applyParams(t, currentSpeed, pitchSemitones)) {
            t.release()
            throw Exception("Speed ${Ramp.speedLabel(currentSpeed)} with ${pitchSemitones} semitones was rejected by the device.")
        }

        track = t
        running = true
        t.play()

        val step = 8192
        val feeder = LoopFeeder(clip.samples)
        val th = Thread {
            val buf = ShortArray(step)
            try {
                while (running) {
                    val n = feeder.fill(buf, step)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && running) {
                        val w = t.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) { off = n; break }
                        off += w
                        framesWritten += w / clip.channels
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

        val cond = Thread {
            val frames = clip.frames.toLong()
            var heard = 0L
            var lastHead = 0
            while (running) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                val tt = track ?: break
                val head = try { tt.playbackHeadPosition } catch (_: Exception) { break }
                var d = head - lastHead
                if (d < 0) d = 0                      // the counter is 32-bit: handle the wrap
                lastHead = head
                heard += (d * currentSpeed).toLong()   // output frames -> source frames

                // sanity bounds: no more than written, no less than written - buffer
                val w = framesWritten
                if (heard > w) heard = w
                if (heard < w - bufferFrames) heard = w - bufferFrames
                if (heard < 0) heard = 0

                val loop = (heard / frames).toInt()
                if (loop != currentLoop) {
                    currentLoop = loop
                    if (trainStep > 0f) {
                        val s = Ramp.speedAt(baseSpeed, trainStep, loop)
                        if (s != currentSpeed) {
                            if (applyParams(tt, s, pitchSemitones)) currentSpeed = s
                        }
                    }
                }
            }
        }
        cond.name = "LoopConductor"
        conductor = cond
        cond.start()
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
        conductor?.let { c -> try { c.join(400) } catch (_: InterruptedException) {} }
        conductor = null
        worker?.let { w -> try { w.join(1500) } catch (_: InterruptedException) {} }
        worker = null
        currentLoop = 0
    }
}
