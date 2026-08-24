package io.github.malexmuracoder.trainbeat

import java.io.OutputStream

/** Writes a 16-bit PCM WAV. Thirty lines, and no dependency on anything. */
object WavWriter {

    fun write(out: OutputStream, samples: ShortArray, sampleRate: Int, channels: Int) {
        val dataBytes = samples.size * 2
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

        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(sampleRate * channels * 2); le16(channels * 2); le16(16)
        ascii("data"); le32(dataBytes)
        out.write(header)

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
}
