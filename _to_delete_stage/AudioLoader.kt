package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Entry point of decoding.
 *
 * Strategy: if the file is RIFF/WAVE it is read by WavReader (no codec in the
 * middle, hence no rejected formats). Everything else - mp3, m4a, flac, ogg,
 * opus - goes through MediaExtractor + MediaCodec, always present on the phone.
 * Either way the result is the same: 16-bit PCM in RAM.
 */
object AudioLoader {

    /** Above a minute, a file is a track: only a central window of it is analysed. */
    const val ANALYSIS_WINDOW_SEC = 40.0

    /**
     * Decodes only what the analysis needs: the whole thing for a loop, a central
     * window for anything longer. On compressed files the extractor seeks straight
     * to the middle, so the part that is thrown away is never decoded - which is
     * where most of the saving comes from, in time and in memory both.
     *
     * The centre is also the better sample musically: intros without harmony,
     * fades and applause pollute a key estimate rather than enrich it.
     */
    fun loadForAnalysis(
        context: Context,
        uri: Uri,
        windowSec: Double = ANALYSIS_WINDOW_SEC
    ): AudioClip {
        val probe = readHead(context, uri, 64)
        val head = probe.bytes
        val isWav = head.size >= 12 && WavReader.sniff(head)
        try {
            if (isWav) {
                // uncompressed: reading is cheap, so read and keep the middle
                val full = context.contentResolver.openInputStream(uri).use { st ->
                    if (st == null) throw Exception("Cannot open the file.")
                    WavReader.read(st)
                }
                return centreWindow(full, windowSec)
            }
            val durUs = codecDurationUs(context, uri)
            val windowUs = (windowSec * 1_000_000).toLong()
            if (durUs <= 0 || durUs <= windowUs) return decodeWithCodec(context, uri, null)
            val startUs = durUs / 2 - windowUs / 2
            val part = decodeWithCodec(
                context, uri,
                "analysed a central window of ${windowSec.toInt()} s",
                startUs, startUs + windowUs
            )
            return AudioClip(part.samples, part.sampleRate, part.channels, part.info, durUs / 1000, true)
        } catch (e: Throwable) {
            throw Exception((e.message ?: e.javaClass.simpleName) + "\n\n" + probeText(context, uri, head, isWav, probe.error))
        }
    }

    /** Keeps the central window of an already decoded clip. */
    private fun centreWindow(clip: AudioClip, windowSec: Double): AudioClip {
        val windowFrames = (windowSec * clip.sampleRate).toInt()
        if (clip.frames <= windowFrames) return clip
        val start = (clip.frames - windowFrames) / 2
        val from = start * clip.channels
        val to = (start + windowFrames) * clip.channels
        return AudioClip(
            clip.samples.copyOfRange(from, to),
            clip.sampleRate,
            clip.channels,
            clip.info.copy(notes = clip.info.notes + "analysed a central window of ${windowSec.toInt()} s"),
            clip.durationMs,
            true
        )
    }

    fun load(context: Context, uri: Uri, trimSilence: Boolean): AudioClip {
        val probe = readHead(context, uri, 64)
        val head = probe.bytes
        val isWav = head.size >= 12 && WavReader.sniff(head)

        if (head.isEmpty()) {
            // No readable byte: this is an access problem, not a format problem.
            throw Exception(
                "The file is not readable: zero bytes received.\n" +
                    "Usual causes: a Drive/Dropbox file not downloaded to the phone, " +
                    "an expired permission, or a file that was moved or renamed.\n\n" +
                    probeText(context, uri, head, isWav, probe.error)
            )
        }

        try {
            return decode(context, uri, trimSilence, head, isWav)
        } catch (e: Throwable) {
            // If it will not open, the reason is in the first bytes: attach the raw
            // header to the message so the real format can be recognised at a glance.
            throw Exception((e.message ?: e.javaClass.simpleName) + "\n\n" + probeText(context, uri, head, isWav, probe.error))
        }
    }

    /** First bytes of the file in hex and ASCII: identifies the real container. */
    private fun probeText(context: Context, uri: Uri, head: ByteArray, isWav: Boolean, readError: String?): String {
        val sb = StringBuilder()
        sb.append("DIAGNOSTICS\n")
        sb.append("declared mime : ").append(context.contentResolver.getType(uri) ?: "none").append('\n')
        sb.append("readable bytes: ").append(head.size).append('\n')
        if (readError != null) sb.append("read error    : ").append(readError).append('\n')
        sb.append("size          : ").append(sizeOf(context, uri)).append('\n')
        sb.append("RIFF/WAVE     : ").append(if (isWav) "yes" else "no").append('\n')
        if (head.size >= 4) {
            sb.append("signature     : ").append(ascii(head, 0, 4))
            sb.append("  (").append(hex(head, 0, 4)).append(")\n")
        }
        if (head.size >= 12) sb.append("form type     : ").append(ascii(head, 8, 4)).append('\n')
        sb.append("header bytes  :\n")
        var off = 0
        while (off < head.size) {
            val n = minOf(16, head.size - off)
            sb.append(hex(head, off, n)).append("  ").append(ascii(head, off, n)).append('\n')
            off += n
        }
        return sb.toString()
    }

    /** Size as declared by the provider: if 0 or unknown, the file is not really there. */
    private fun sizeOf(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) {
                    if (c.isNull(idx)) return "not declared by the provider"
                    return c.getLong(idx).toString() + " byte"
                }
            }
        } catch (e: Exception) {
            return "unreadable (${e.javaClass.simpleName})"
        }
        return "unknown"
    }

    private fun hex(b: ByteArray, off: Int, n: Int): String {
        val sb = StringBuilder()
        for (i in off until off + n) {
            sb.append(String.format("%02X", b[i].toInt() and 0xFF))
            if (i < off + n - 1) sb.append(' ')
        }
        return sb.toString()
    }

    private fun ascii(b: ByteArray, off: Int, n: Int): String {
        val sb = StringBuilder()
        for (i in off until off + n) {
            val c = b[i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        return sb.toString()
    }

    /** Leading bytes of the file, plus why they could not be read, if they could not. */
    private class Head(val bytes: ByteArray, val error: String?)

    private fun readHead(context: Context, uri: Uri, n: Int): Head {
        val buf = ByteArray(n)
        var off = 0
        var err: String? = null
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                err = "the provider returned no stream (openInputStream = null)"
            } else {
                stream.use { s ->
                    while (off < n) {
                        val r = s.read(buf, off, n - off)
                        if (r <= 0) break
                        off += r
                    }
                }
            }
        } catch (e: SecurityException) {
            err = "permission denied on the file: ${e.message}"
        } catch (e: Exception) {
            err = "${e.javaClass.simpleName}: ${e.message}"
        }
        return Head(if (off == n) buf else buf.copyOf(off), err)
    }

    private fun decode(context: Context, uri: Uri, trimSilence: Boolean, head: ByteArray, isWav: Boolean): AudioClip {
        var clip = if (isWav) {
            try {
                context.contentResolver.openInputStream(uri).use { s ->
                    if (s == null) throw Exception("Cannot open the file.")
                    WavReader.read(s)
                }
            } catch (u: WavReader.UnsupportedWav) {
                // ADPCM and friends: the system codec handles those
                decodeWithCodec(context, uri, "WAV tag ${u.tag}: handed to MediaCodec")
            }
        } else {
            decodeWithCodec(context, uri, null)
        }

        if (clip.channels > 2) clip = downmixToStereo(clip)
        if (trimSilence) clip = trimEdges(clip)
        return clip
    }

    private fun decodeWithCodec(
        context: Context,
        uri: Uri,
        note: String?,
        startUs: Long = -1,
        endUs: Long = -1
    ): AudioClip {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw Exception("Format not recognised by Android (${e.message ?: "open error"}).")
        }

        var trackIndex = -1
        var mime = ""
        var container = "unknown"
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) {
                trackIndex = i
                mime = m
                break
            }
        }
        if (trackIndex < 0) {
            extractor.release()
            throw Exception("No audio track in the file.")
        }
        extractor.selectTrack(trackIndex)
        if (startUs >= 0) {
            try {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            } catch (_: Exception) {
            }
        }
        val inFormat = extractor.getTrackFormat(trackIndex)
        container = mime

        var sampleRate = inFormat.getIntOrElse(MediaFormat.KEY_SAMPLE_RATE, 0)
        var channels = inFormat.getIntOrElse(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val notes = ArrayList<String>()
        if (note != null) notes += note

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        val out = ShortBuf()
        val bufInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var pcmFloat = false
        var guard = 0

        try {
            while (!sawOutputEos) {
                if (guard++ > 2_000_000) throw Exception("Decoding stalled.")
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        val past = endUs > 0 && extractor.sampleTime > endUs
                        if (n < 0 || past) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sampleRate = of.getIntOrElse(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = of.getIntOrElse(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    val enc = of.getIntOrElse(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    pcmFloat = enc == AudioFormat.ENCODING_PCM_FLOAT
                    if (pcmFloat) notes += "decoder output is 32-bit float, converted"
                } else if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(bufInfo.offset)
                    buf.limit(bufInfo.offset + bufInfo.size)
                    buf.order(ByteOrder.nativeOrder())
                    if (pcmFloat) {
                        val fb = buf.asFloatBuffer()
                        val tmp = ShortArray(fb.remaining())
                        var k = 0
                        while (fb.hasRemaining()) {
                            val v = fb.get()
                            val c = if (v > 1f) 1f else if (v < -1f) -1f else v
                            tmp[k++] = (c * 32767f).toInt().toShort()
                        }
                        out.add(tmp, k)
                    } else {
                        val sb = buf.asShortBuffer()
                        val tmp = ShortArray(sb.remaining())
                        sb.get(tmp)
                        out.add(tmp, tmp.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }

        if (channels <= 0 || sampleRate <= 0) throw Exception("The decoder did not report a valid format.")
        if (out.size == 0) throw Exception("The decoder produced no samples.")

        var samples = out.toArray()
        if (samples.size % channels != 0) samples = samples.copyOf(samples.size - samples.size % channels)

        return AudioClip(
            samples, sampleRate, channels,
            FormatInfo("MediaCodec", container, if (pcmFloat) "decoder -> float 32" else "decoder -> PCM 16", sampleRate, channels, notes)
        )
    }

    /** Duration in microseconds from the container, without decoding anything. */
    private fun codecDurationUs(context: Context, uri: Uri): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var best = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("audio/")) continue
                if (f.containsKey(MediaFormat.KEY_DURATION)) best = f.getLong(MediaFormat.KEY_DURATION)
                break
            }
            best
        } catch (_: Exception) {
            0L
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun MediaFormat.getIntOrElse(key: String, fallback: Int): Int =
        try { if (containsKey(key)) getInteger(key) else fallback } catch (_: Exception) { fallback }

    private fun downmixToStereo(c: AudioClip): AudioClip {
        val frames = c.frames
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val base = f * c.channels
            out[f * 2] = c.samples[base]
            out[f * 2 + 1] = c.samples[base + 1]
        }
        return AudioClip(
            out, c.sampleRate, 2,
            c.info.copy(channels = 2, notes = c.info.notes + "${c.channels} channels reduced to the first 2")
        )
    }

    /**
     * Trims digital silence at the start and end. Mostly for MP3s: the encoder adds
     * padding that no Android decoder removes, and it is exactly that padding which
     * produces the "gap" when the track starts over.
     */
    private fun trimEdges(c: AudioClip): AudioClip {
        val threshold = 16
        val ch = c.channels
        var first = 0
        var last = c.frames - 1
        outer@ while (first <= last) {
            for (k in 0 until ch) {
                if (kotlin.math.abs(c.samples[first * ch + k].toInt()) > threshold) break@outer
            }
            first++
        }
        outer2@ while (last > first) {
            for (k in 0 until ch) {
                if (kotlin.math.abs(c.samples[last * ch + k].toInt()) > threshold) break@outer2
            }
            last--
        }
        if (first >= last) return c
        if (first == 0 && last == c.frames - 1) return c
        val cut = c.samples.copyOfRange(first * ch, (last + 1) * ch)
        val msHead = first * 1000L / c.sampleRate
        val msTail = (c.frames - 1 - last) * 1000L / c.sampleRate
        return AudioClip(
            cut, c.sampleRate, ch,
            c.info.copy(notes = c.info.notes + "silence trimmed: ${msHead} ms at the head, ${msTail} ms at the tail")
        )
    }
}
