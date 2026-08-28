package io.github.malexmuracoder.trainbeat

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Hand-written RIFF/WAVE reader.
 *
 * Why: generic players hand WAV over to the system codecs, which accept little
 * more than 16-bit PCM. Here the samples are converted in-house, so 8/24/32-bit
 * integer, 32/64-bit float, A-law and mu-law, RF64, and files carrying extra
 * chunks (LIST, bext, iXML, cue) that trip up sloppy parsers all get through.
 */
object WavReader {

    class UnsupportedWav(val tag: Int, message: String) : Exception(message)

    private const val FMT_PCM = 1
    private const val FMT_FLOAT = 3
    private const val FMT_ALAW = 6
    private const val FMT_MULAW = 7
    private const val FMT_EXTENSIBLE = 0xFFFE

    /** true if the first bytes are RIFF....WAVE or RF64....WAVE */
    fun sniff(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val id = String(head, 0, 4, Charsets.US_ASCII)
        val form = String(head, 8, 4, Charsets.US_ASCII)
        return (id == "RIFF" || id == "RF64" || id == "BW64") && form == "WAVE"
    }

    fun read(input: InputStream): AudioClip {
        val s = BufferedInputStream(input, 1 shl 16)
        val head = ByteArray(12)
        readFully(s, head, 12)
        if (!sniff(head)) throw Exception("Not a RIFF/WAVE file.")
        val container = String(head, 0, 4, Charsets.US_ASCII) + "/WAVE"

        var tag = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var blockAlign = 0
        val notes = ArrayList<String>()
        var dataSize64 = -1L
        var software: String? = null

        while (true) {
            val idBytes = ByteArray(4)
            val got = readUpTo(s, idBytes, 4)
            if (got < 4) throw Exception("No 'data' chunk found in the file.")
            val id = String(idBytes, Charsets.US_ASCII)
            var size = readU32(s)
            if (size < 0) size = Long.MAX_VALUE / 2   // size 0xFFFFFFFF = streaming

            when (id) {
                "ds64" -> {
                    // RF64: the real sizes live here
                    val payload = ByteArray(size.toInt().coerceAtMost(1 shl 16))
                    readFully(s, payload, payload.size)
                    if (payload.size >= 16) dataSize64 = leLong(payload, 8)
                    notes += "RF64: data size taken from the ds64 chunk"
                }
                "fmt " -> {
                    val n = size.toInt().coerceAtMost(1 shl 12)
                    val f = ByteArray(n)
                    readFully(s, f, n)
                    if (n < 16) throw Exception("'fmt ' chunk too short ($n bytes).")
                    tag = leU16(f, 0)
                    channels = leU16(f, 2)
                    sampleRate = leI32(f, 4)
                    blockAlign = leU16(f, 12)
                    bits = leU16(f, 14)
                    if (tag == FMT_EXTENSIBLE) {
                        if (n >= 26) {
                            val validBits = leU16(f, 18)
                            tag = leU16(f, 24)   // first 2 bytes of the subformat GUID
                            notes += "WAVE_FORMAT_EXTENSIBLE, subformat $tag, valid bits $validBits"
                        } else {
                            throw Exception("WAVE_FORMAT_EXTENSIBLE with no readable subformat.")
                        }
                    }
                    if (size % 2 == 1L) skipFully(s, 1)
                }
                "data" -> {
                    if (tag < 0) throw Exception("'data' chunk before 'fmt ': malformed file.")
                    if (channels <= 0 || sampleRate <= 0) {
                        throw Exception("Invalid header: $channels channels, $sampleRate Hz.")
                    }
                    val declared = if (dataSize64 > 0) dataSize64 else size
                    val clip = decodeData(s, tag, channels, sampleRate, bits, blockAlign, declared, container, notes)
                    return if (software == null) clip else AudioClip(
                        clip.samples, clip.sampleRate, clip.channels,
                        clip.info.copy(software = software), clip.totalMs, clip.partial
                    )
                }
                "LIST" -> {
                    // The one chunk worth reading rather than skipping: LIST/INFO
                    // holds the name of the software that wrote the file, which is
                    // how the trimmer later knows a recording made on this phone
                    // and starts it with the beat grid switched off.
                    val body = ByteArray(minOf(size, 8192L).toInt())
                    readFully(s, body, body.size)
                    val rest = size - body.size
                    if (rest > 0) skipFully(s, rest)
                    if (size % 2 == 1L) skipFully(s, 1)
                    software = infoSoftware(body)
                    if (software != null) notes += "written by $software"
                }
                else -> {
                    skipFully(s, size + (size % 2))
                    if (id.isNotBlank()) notes += "chunk '$id' skipped"
                }
            }
        }
    }

    private fun decodeData(
        s: InputStream,
        tag: Int,
        channels: Int,
        sampleRate: Int,
        bits: Int,
        blockAlign: Int,
        declaredSize: Long,
        container: String,
        notes: MutableList<String>
    ): AudioClip {
        val encoding = when (tag) {
            FMT_PCM -> "PCM $bits bit integer"
            FMT_FLOAT -> "IEEE float $bits bit"
            FMT_ALAW -> "A-law 8 bit"
            FMT_MULAW -> "mu-law 8 bit"
            else -> throw UnsupportedWav(tag, "WAV format tag $tag not handled natively.")
        }
        val bytesPerSample = when (tag) {
            FMT_ALAW, FMT_MULAW -> 1
            else -> {
                if (bits <= 0 || bits % 8 != 0) throw Exception("$bits bits per sample: not supported.")
                bits / 8
            }
        }
        if (tag == FMT_PCM && bits !in intArrayOf(8, 16, 24, 32, 64)) {
            throw Exception("PCM at $bits bits not supported.")
        }
        if (tag == FMT_FLOAT && bits != 32 && bits != 64) {
            throw Exception("Float at $bits bits not supported.")
        }
        if (blockAlign > 0 && blockAlign != bytesPerSample * channels) {
            notes += "blockAlign $blockAlign inconsistent with $channels x $bytesPerSample bytes"
        }

        val out = ShortBuf()
        val chunk = ByteArray(bytesPerSample * channels * 4096)
        var remaining = declaredSize
        val conv = ShortArray(chunk.size / bytesPerSample)

        while (remaining > 0) {
            val want = minOf(remaining, chunk.size.toLong()).toInt()
            val aligned = want - (want % (bytesPerSample * channels))
            val toRead = if (aligned > 0) aligned else want
            val n = readUpTo(s, chunk, toRead)
            if (n <= 0) break
            val usable = n - (n % bytesPerSample)
            var produced = 0
            var i = 0
            while (i + bytesPerSample <= usable) {
                conv[produced++] = when (tag) {
                    FMT_PCM -> when (bits) {
                        8 -> (((chunk[i].toInt() and 0xFF) - 128) shl 8).toShort()
                        16 -> ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()
                        24 -> ((chunk[i + 1].toInt() and 0xFF) or (chunk[i + 2].toInt() shl 8)).toShort()
                        32 -> ((chunk[i + 2].toInt() and 0xFF) or (chunk[i + 3].toInt() shl 8)).toShort()
                        else -> ((chunk[i + 6].toInt() and 0xFF) or (chunk[i + 7].toInt() shl 8)).toShort()
                    }
                    FMT_FLOAT -> {
                        val v = if (bits == 32) {
                            Float.fromBits(leI32(chunk, i)).toDouble()
                        } else {
                            Double.fromBits(leLong(chunk, i))
                        }
                        floatToPcm(v)
                    }
                    FMT_ALAW -> alawToPcm(chunk[i].toInt() and 0xFF)
                    else -> mulawToPcm(chunk[i].toInt() and 0xFF)
                }
                i += bytesPerSample
            }
            out.add(conv, produced)
            remaining -= n
            if (declaredSize == Long.MAX_VALUE / 2) remaining = Long.MAX_VALUE / 2
        }

        if (out.size == 0) throw Exception("The 'data' chunk is empty.")
        var samples = out.toArray()
        if (samples.size % channels != 0) samples = samples.copyOf(samples.size - samples.size % channels)

        return AudioClip(
            samples = samples,
            sampleRate = sampleRate,
            channels = channels,
            info = FormatInfo("native WAV", container, encoding, sampleRate, channels, notes)
        )
    }

    private fun floatToPcm(v: Double): Short {
        val c = if (v > 1.0) 1.0 else if (v < -1.0) -1.0 else v
        return (c * 32767.0).toInt().toShort()
    }

    private fun mulawToPcm(u: Int): Short {
        val x = u.inv() and 0xFF
        val sign = x and 0x80
        val exp = (x shr 4) and 0x07
        val mant = x and 0x0F
        var v = ((mant shl 3) + 0x84) shl exp
        v -= 0x84
        return (if (sign != 0) -v else v).coerceIn(-32768, 32767).toShort()
    }

    private fun alawToPcm(a: Int): Short {
        val x = a xor 0x55
        val sign = x and 0x80
        val exp = (x shr 4) and 0x07
        val mant = x and 0x0F
        // G.711: t = mant<<4 + bias, then shift per segment; sign bit set = positive
        var v = (mant shl 4) + if (exp == 0) 8 else 0x108
        if (exp > 1) v = v shl (exp - 1)
        return (if (sign != 0) v else -v).coerceIn(-32768, 32767).toShort()
    }

    // --- little endian helpers ---
    private fun leU16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun leI32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun leLong(b: ByteArray, o: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
        return v
    }

    /** ISFT inside a LIST/INFO chunk: the name of the program that wrote the file. */
    private fun infoSoftware(body: ByteArray): String? {
        if (body.size < 12) return null
        if (String(body, 0, 4, Charsets.US_ASCII) != "INFO") return null
        var p = 4
        while (p + 8 <= body.size) {
            val id = String(body, p, 4, Charsets.US_ASCII)
            val len = ((body[p + 4].toInt() and 0xFF)) or ((body[p + 5].toInt() and 0xFF) shl 8) or
                ((body[p + 6].toInt() and 0xFF) shl 16) or ((body[p + 7].toInt() and 0xFF) shl 24)
            if (len < 0 || p + 8 + len > body.size) return null
            if (id == "ISFT") {
                val sb = StringBuilder()
                for (i in 0 until len) {
                    val c = body[p + 8 + i].toInt() and 0xFF
                    if (c == 0) break
                    if (c >= 32) sb.append(c.toChar())
                }
                return sb.toString().trim().ifEmpty { null }
            }
            p += 8 + len + (len % 2)
        }
        return null
    }

    private fun readU32(s: InputStream): Long {
        val b = ByteArray(4)
        readFully(s, b, 4)
        val v = leI32(b, 0).toLong() and 0xFFFFFFFFL
        return if (v == 0xFFFFFFFFL) -1L else v
    }

    private fun readFully(s: InputStream, b: ByteArray, n: Int) {
        if (readUpTo(s, b, n) < n) throw EOFException("Truncated file.")
    }

    private fun readUpTo(s: InputStream, b: ByteArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = s.read(b, off, n - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        val junk = ByteArray(8192)
        while (left > 0) {
            val sk = s.skip(left)
            if (sk > 0) { left -= sk; continue }
            val r = s.read(junk, 0, minOf(left, junk.size.toLong()).toInt())
            if (r <= 0) return
            left -= r
        }
    }
}
