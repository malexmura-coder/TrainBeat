package io.github.malexmuracoder.trainbeat

import java.io.OutputStream

/**
 * Writes a 16-bit PCM WAV. Thirty lines, and no dependency on anything.
 *
 * With [software] set, a LIST/INFO chunk carrying that name goes in before the
 * audio. It is the standard place for it, every other program ignores it, and it
 * is how a file recorded here is still recognisable after being renamed or moved.
 */
object WavWriter {

    fun write(
        out: OutputStream,
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        software: String? = null
    ) {
        val dataBytes = samples.size * 2
        val info = if (software == null) ByteArray(0) else infoChunk(software)
        val header = ByteArray(44)
        var i = 0
        fun ascii(s: String) { for (c in s) header[i++] = c.code.toByte() }
        fun le32(v: Int) {
            header[i++] = (v and 0xFF).toByte()
            header[i++] = ((v shr 8) and 0xFF).toByte()
            header[i++] = ((v shr 16) and 0xFF).toByte()
            header[i++] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(v: Int) {
            header[i++] = (v and 0xFF).toByte()
            header[i++] = ((v shr 8) and 0xFF).toByte()
        }

        ascii("RIFF"); le32(36 + info.size + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(sampleRate * channels * 2); le16(channels * 2); le16(16)
        ascii("data"); le32(dataBytes)
        // fmt, then the optional INFO, then data: the reader walks the chunks in
        // order and 'data' has to come last for the size above to be right
        out.write(header, 0, 36)
        if (info.isNotEmpty()) out.write(info)
        out.write(header, 36, 8)

        val chunk = ByteArray(1 shl 16)
        var pos = 0
        while (pos < samples.size) {
            var k = 0
            while (k + 1 < chunk.size && pos < samples.size) {
                val v = samples[pos++].toInt()
                chunk[k++] = (v and 0xFF).toByte()
                chunk[k++] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(chunk, 0, k)
        }
        out.flush()
    }

    /** LIST/INFO with a single ISFT field, padded to an even length. */
    private fun infoChunk(software: String): ByteArray {
        val text = (software + "\u0000").toByteArray(Charsets.US_ASCII)
        val padded = if (text.size % 2 == 1) text + byteArrayOf(0) else text
        val body = ByteArray(4 + 8 + padded.size)
        var i = 0
        fun put(s: String) { for (c in s) body[i++] = c.code.toByte() }
        fun le32(v: Int) {
            body[i++] = (v and 0xFF).toByte()
            body[i++] = ((v shr 8) and 0xFF).toByte()
            body[i++] = ((v shr 16) and 0xFF).toByte()
            body[i++] = ((v shr 24) and 0xFF).toByte()
        }
        put("INFO")
        put("ISFT")
        le32(text.size)
        System.arraycopy(padded, 0, body, i, padded.size)

        val out = ByteArray(8 + body.size)
        var j = 0
        for (c in "LIST") out[j++] = c.code.toByte()
        out[j++] = (body.size and 0xFF).toByte()
        out[j++] = ((body.size shr 8) and 0xFF).toByte()
        out[j++] = ((body.size shr 16) and 0xFF).toByte()
        out[j++] = ((body.size shr 24) and 0xFF).toByte()
        System.arraycopy(body, 0, out, j, body.size)
        return out
    }
}
