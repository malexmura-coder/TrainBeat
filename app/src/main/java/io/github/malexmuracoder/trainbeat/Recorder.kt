package io.github.malexmuracoder.trainbeat

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Records from the microphone into memory, then writes a WAV.
 *
 * Two decisions worth stating.
 *
 * The source is asked for UNPROCESSED first, then VOICE_RECOGNITION, and only
 * then the ordinary MIC. The reason is that the ordinary microphone path applies
 * automatic gain control, noise suppression and sometimes an aggressive high-pass
 * filter - all excellent for speech and ruinous for a guitar, because they pump
 * the level and swallow the low end. Not every phone offers the unprocessed
 * source, hence the ladder.
 *
 * And it records MONO. A phone has one usable microphone for this purpose; asking
 * for stereo would double the size to hold the same signal twice.
 */
class Recorder {

    /** Beyond this a recording is not a loop any more. Also a memory guard. */
    companion object {
        const val MAX_SECONDS = 300
        const val SAMPLE_RATE = 44100

        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
    }

    class Result(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
        val sourceName: String
    ) {
        val frames: Int get() = if (channels > 0) samples.size / channels else 0
        val seconds: Double get() = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0
    }

    @Volatile private var recording = false
    @Volatile private var worker: Thread? = null
    @Volatile private var buffer: ShortBuf? = null
    @Volatile private var sourceUsed = "mic"
    @Volatile private var failure: String? = null

    /** Peak of the last block, 0 to 1: the only proof on screen that sound is arriving. */
    @Volatile var peak: Float = 0f
        private set

    /** Frames captured so far, for the elapsed time. */
    @Volatile var frames: Int = 0
        private set

    val isRecording: Boolean get() = recording

    val error: String? get() = failure

    val seconds: Double get() = frames.toDouble() / SAMPLE_RATE

    /**
     * Opens the microphone and starts filling memory. Throws if the microphone
     * cannot be opened at all - a denied permission, or another app holding it.
     */
    fun start() {
        stop()
        failure = null
        frames = 0
        peak = 0f
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) throw Exception("This device refuses $SAMPLE_RATE Hz recording.")
        val bufBytes = maxOf(minBytes * 4, SAMPLE_RATE / 2)      // about half a second

        var rec: AudioRecord? = null
        var used = ""
        for (src in SOURCES) {
            val candidate = try {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            } catch (_: Throwable) {
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                rec = candidate
                used = when (src) {
                    MediaRecorder.AudioSource.UNPROCESSED -> "unprocessed"
                    MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice recognition"
                    else -> "mic"
                }
                break
            }
            try { candidate?.release() } catch (_: Throwable) {}
        }
        val r = rec ?: throw Exception(
            "The microphone could not be opened. Check the permission, and that no " +
                "other app is using it."
        )
        sourceUsed = used

        val buf = ShortBuf()
        buffer = buf
        recording = true
        r.startRecording()

        val th = Thread {
            val block = ShortArray(4096)
            val limit = MAX_SECONDS * SAMPLE_RATE
            try {
                while (recording) {
                    val n = r.read(block, 0, block.size)
                    if (n <= 0) {
                        if (n < 0) failure = "the microphone stopped returning audio"
                        break
                    }
                    buf.add(block, n)
                    frames += n
                    var p = 0
                    for (i in 0 until n) {
                        val v = Math.abs(block[i].toInt())
                        if (v > p) p = v
                    }
                    peak = p / 32768f
                    if (frames >= limit) {
                        failure = "stopped at the ${MAX_SECONDS / 60} minute limit"
                        break
                    }
                }
            } catch (e: Throwable) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                recording = false
                try { r.stop() } catch (_: Throwable) {}
                try { r.release() } catch (_: Throwable) {}
                peak = 0f
            }
        }
        th.priority = Thread.MAX_PRIORITY
        th.name = "Recorder"
        worker = th
        th.start()
    }

    /** Stops and returns what was captured, or null if nothing was. */
    fun stop(): Result? {
        recording = false
        worker?.let { w -> try { w.join(1500) } catch (_: InterruptedException) {} }
        worker = null
        val buf = buffer ?: return null
        buffer = null
        if (buf.size <= 0) return null
        return Result(buf.toArray(), SAMPLE_RATE, 1, sourceUsed)
    }

    /** A name that says what it is and when it was made. */
    fun fileName(millis: Long): String = AudioClip.recordingName(millis)
}
