package io.github.malexmuracoder.trainbeat

import java.io.ByteArrayOutputStream
import java.io.File

/** Builds synthetic WAV files in memory, to test the parser without external files. */
object WavTestUtil {

    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    fun le64(v: Long): ByteArray {
        val b = ByteArray(8)
        for (k in 0 until 8) b[k] = ((v shr (8 * k)) and 0xFF).toByte()
        return b
    }

    fun build(
        formatTag: Int,
        channels: Int,
        sampleRate: Int,
        bits: Int,
        data: ByteArray,
        extensible: Boolean = false,
        chunksBeforeFmt: List<Pair<String, ByteArray>> = emptyList(),
        chunksAfterFmt: List<Pair<String, ByteArray>> = emptyList(),
        dataSizeField: Long? = null,
        riffId: String = "RIFF",
        ds64DataSize: Long? = null
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))

        if (ds64DataSize != null) {
            val payload = ByteArrayOutputStream()
            payload.write(le64(0))                 // riffSize
            payload.write(le64(ds64DataSize))      // dataSize
            payload.write(le64(0))                 // sampleCount
            payload.write(le32(0))                 // tableLength
            writeChunk(body, "ds64", payload.toByteArray())
        }

        chunksBeforeFmt.forEach { writeChunk(body, it.first, it.second) }

        val blockAlign = channels * bits / 8
        val fmt = ByteArrayOutputStream()
        fmt.write(le16(if (extensible) 0xFFFE else formatTag))
        fmt.write(le16(channels))
        fmt.write(le32(sampleRate))
        fmt.write(le32(sampleRate * blockAlign))
        fmt.write(le16(blockAlign))
        fmt.write(le16(bits))
        if (extensible) {
            fmt.write(le16(22))          // cbSize
            fmt.write(le16(bits))        // valid bits
            fmt.write(le32(3))           // channel mask
            fmt.write(le16(formatTag))   // first 2 bytes of the GUID = real format
            fmt.write(le16(0))
            fmt.write(ByteArray(12))
        }
        writeChunk(body, "fmt ", fmt.toByteArray())

        chunksAfterFmt.forEach { writeChunk(body, it.first, it.second) }

        // data chunk, possibly with a false or streaming declared size
        body.write("data".toByteArray(Charsets.US_ASCII))
        val declared = dataSizeField ?: data.size.toLong()
        body.write(le32((declared and 0xFFFFFFFFL).toInt()))
        body.write(data)

        val out = ByteArrayOutputStream()
        out.write(riffId.toByteArray(Charsets.US_ASCII))
        out.write(le32(body.size()))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(le32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)   // padding RIFF
    }

    // --- sample data generators ---

    fun bytesPcm16(v: ShortArray): ByteArray {
        val b = ByteArray(v.size * 2)
        v.forEachIndexed { i, s ->
            b[i * 2] = (s.toInt() and 0xFF).toByte()
            b[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return b
    }

    fun bytesPcm8(v: IntArray): ByteArray = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    fun bytesPcm24(v: IntArray): ByteArray {
        val b = ByteArray(v.size * 3)
        v.forEachIndexed { i, x ->
            b[i * 3] = (x and 0xFF).toByte()
            b[i * 3 + 1] = ((x shr 8) and 0xFF).toByte()
            b[i * 3 + 2] = ((x shr 16) and 0xFF).toByte()
        }
        return b
    }

    fun bytesPcm32(v: IntArray): ByteArray {
        val b = ByteArray(v.size * 4)
        v.forEachIndexed { i, x -> le32(x).copyInto(b, i * 4) }
        return b
    }

    fun bytesFloat32(v: FloatArray): ByteArray {
        val b = ByteArray(v.size * 4)
        v.forEachIndexed { i, f -> le32(f.toRawBits()).copyInto(b, i * 4) }
        return b
    }

    fun bytesFloat64(v: DoubleArray): ByteArray {
        val b = ByteArray(v.size * 8)
        v.forEachIndexed { i, d -> le64(d.toRawBits()).copyInto(b, i * 8) }
        return b
    }

    /** Writes a 16-bit PCM WAV to disk: used by the loop render you listen to on the Mac. */
    fun writeWav16(file: File, samples: ShortArray, sampleRate: Int, channels: Int) {
        val data = bytesPcm16(samples)
        file.outputStream().buffered().use { o ->
            o.write("RIFF".toByteArray(Charsets.US_ASCII))
            o.write(le32(36 + data.size))
            o.write("WAVE".toByteArray(Charsets.US_ASCII))
            o.write("fmt ".toByteArray(Charsets.US_ASCII))
            o.write(le32(16))
            o.write(le16(1))
            o.write(le16(channels))
            o.write(le32(sampleRate))
            o.write(le32(sampleRate * channels * 2))
            o.write(le16(channels * 2))
            o.write(le16(16))
            o.write("data".toByteArray(Charsets.US_ASCII))
            o.write(le32(data.size))
            o.write(data)
        }
    }
}
