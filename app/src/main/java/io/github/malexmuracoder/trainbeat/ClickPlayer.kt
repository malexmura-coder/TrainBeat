package io.github.malexmuracoder.trainbeat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays a [Click.countIn] buffer once, blocking the calling thread until it is
 * either finished or cancelled.
 *
 * Built exactly the way LoopPlayer builds its own AudioTrack - same
 * attributes, same streamed write - rather than a different, untried
 * construction (MODE_STATIC, USAGE_MEDIA/SONIFICATION). The loop itself is
 * proven to be heard on every device this app runs on; the count-in has
 * nothing to gain by risking a path that isn't, and the first version of this
 * class used exactly that untried path and produced no audible click.
 */
object ClickPlayer {

    private const val TAG = "ClickPlayer"
    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2

    /** How often cancellation is checked, so Stop feels as instant here as everywhere else. */
    private const val POLL_MS = 30L

    /**
     * @param stillWanted polled every [POLL_MS] while the count-in plays; the
     *   moment it returns false the track is stopped immediately.
     * @return true if the whole count-in played, or if there was nothing to
     *   play (an unusable tempo, or a device that refused the AudioTrack) -
     *   the caller then starts the loop right away rather than not starting it
     *   at all. False only means it was cancelled.
     */
    fun play(bpm: Float, stillWanted: () -> Boolean): Boolean {
        val buf = Click.countIn(SAMPLE_RATE, CHANNELS, bpm)
        if (buf == null) {
            Log.w(TAG, "no count-in for bpm=$bpm - skipping")
            return true
        }

        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            Log.w(TAG, "device rejects $SAMPLE_RATE Hz stereo - skipping count-in")
            return true
        }
        // as generous as LoopPlayer's own margin, so the whole click sits in
        // the buffer at once and nothing waits on a drain mid-tick
        val bufBytes = maxOf(minBytes * 2, buf.size * 2)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack rejected - skipping count-in", e)
            return true
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack not initialised (state=${t.state}) - skipping count-in")
            try { t.release() } catch (_: Exception) {}
            return true
        }

        return try {
            t.play()
            var off = 0
            var ok = true
            while (off < buf.size) {
                val w = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                if (w <= 0) {
                    Log.w(TAG, "AudioTrack.write returned $w at offset $off of ${buf.size}")
                    ok = false
                    break
                }
                off += w
                if (!stillWanted()) { ok = false; break }
            }
            if (ok) {
                // the write loop only feeds the buffer; wait for the click to
                // actually finish coming out of the speaker before it is torn
                // down, or the very thing this exists to be heard is cut off
                // by its own cleanup
                val totalMs = buf.size.toLong() / CHANNELS * 1000L / SAMPLE_RATE
                var waited = 0L
                while (waited < totalMs) {
                    if (!stillWanted()) { ok = false; break }
                    Thread.sleep(POLL_MS)
                    waited += POLL_MS
                }
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "count-in playback failed", e)
            true
        } finally {
            try { t.pause(); t.flush(); t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }
}
