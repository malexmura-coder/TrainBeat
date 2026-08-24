package io.github.malexmuracoder.trainbeat

import java.io.ByteArrayOutputStream

/**
 * Builds a minimal but VALID SoundFont in memory, so the tests need no external
 * file: one preset, one instrument, one zone, one sample.
 *
 * A file built here is by definition easy, and easy files prove little - the same
 * mistake once let a tempo rule pass on click trains and fail on real drums. So it
 * is used for what it is good for, which is checking that the reader walks the
 * chunks and resolves the generators correctly; the real SoundFonts are what the
 * bench measures.
 */
object Sf2Gen {

    /** A one-preset SoundFont whose only sample is a sine at [freq] with a loop. */
    fun sine(freq: Double = 440.0, rate: Int = 44100, seconds: Double = 1.0,
             rootKey: Int = 69, loop: Boolean = true): ByteArray {
        val n = (rate * seconds).toInt()
        val data = ShortArray(n)
        for (i in 0 until n) {
            data[i] = (Math.sin(2.0 * Math.PI * freq * i / rate) * 12000).toInt().toShort()
        }
        // loop over a whole number of periods, so the loop itself is clean
        val period = rate / freq
        val periods = Math.floor((n * 0.6) / period).toInt().coerceAtLeast(2)
        val loopStart = (period * 2).toInt()
        val loopEnd = loopStart + (period * (periods - 2)).toInt()
        return build(data, rate, rootKey, loopStart, loopEnd, loop)
    }

    fun build(
        samples: ShortArray, rate: Int, rootKey: Int,
        loopStart: Int, loopEnd: Int, loop: Boolean
    ): ByteArray {
        val info = ByteArrayOutputStream()
        info.write(ascii("INFO"))
        info.write(chunk("ifil", byteArrayOf(2, 0, 1, 0)))
        info.write(chunk("isng", zStr("EMU8000", 8)))
        info.write(chunk("INAM", zStr("TrainBeat test", 16)))

        val smpl = ByteArrayOutputStream()
        for (s in samples) {
            smpl.write(s.toInt() and 0xFF)
            smpl.write((s.toInt() shr 8) and 0xFF)
        }
        repeat(46 * 2) { smpl.write(0) }              // the spec's 46 zero frames
        val sdta = ByteArrayOutputStream()
        sdta.write(ascii("sdta"))
        sdta.write(chunk("smpl", smpl.toByteArray()))

        val pdta = ByteArrayOutputStream()
        pdta.write(ascii("pdta"))
        pdta.write(chunk("phdr", phdr()))
        pdta.write(chunk("pbag", bag(intArrayOf(0, 1, 2))))
        pdta.write(chunk("pmod", ByteArray(10)))
        pdta.write(chunk("pgen", gen(listOf(41 to 0, 41 to 0, 0 to 0))))
        pdta.write(chunk("inst", inst()))
        pdta.write(chunk("ibag", bag(intArrayOf(0, 4))))
        pdta.write(chunk("imod", ByteArray(10)))
        pdta.write(chunk("igen", gen(listOf(
            43 to (127 shl 8),                        // key range 0..127
            54 to (if (loop) 1 else 0),               // sample modes
            58 to rootKey,                            // overriding root key
            53 to 0,                                  // sample id: always last
            0 to 0                                    // terminal
        ))))
        pdta.write(chunk("shdr", shdr(samples.size, rate, rootKey, loopStart, loopEnd)))

        val body = ByteArrayOutputStream()
        body.write(ascii("sfbk"))
        body.write(chunk("LIST", info.toByteArray()))
        body.write(chunk("LIST", sdta.toByteArray()))
        body.write(chunk("LIST", pdta.toByteArray()))
        return chunk("RIFF", body.toByteArray())
    }

    /**
     * Two presets: one melodic in bank 0, one drum kit in bank 128. Both are
     * needed, because the player refuses to substitute one bank for the other -
     * that substitution is what once turned a whole band into sound effects.
     */
    private fun phdr(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(zStr("Test", 20)); out.write(le16(0)); out.write(le16(0))
        out.write(le16(0)); out.write(le32(0)); out.write(le32(0)); out.write(le32(0))
        out.write(zStr("Kit", 20)); out.write(le16(0)); out.write(le16(128))
        out.write(le16(1)); out.write(le32(0)); out.write(le32(0)); out.write(le32(0))
        out.write(zStr("EOP", 20)); out.write(le16(0)); out.write(le16(0))
        out.write(le16(2)); out.write(le32(0)); out.write(le32(0)); out.write(le32(0))
        return out.toByteArray()
    }

    private fun inst(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(zStr("Inst", 20)); out.write(le16(0))
        out.write(zStr("EOI", 20)); out.write(le16(1))
        return out.toByteArray()
    }

    private fun bag(genIndices: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (g in genIndices) { out.write(le16(g)); out.write(le16(0)) }
        return out.toByteArray()
    }

    private fun gen(gens: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((op, amount) in gens) { out.write(le16(op)); out.write(le16(amount)) }
        return out.toByteArray()
    }

    private fun shdr(n: Int, rate: Int, root: Int, loopStart: Int, loopEnd: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(zStr("Sine", 20))
        out.write(le32(0)); out.write(le32(n)); out.write(le32(loopStart)); out.write(le32(loopEnd))
        out.write(le32(rate)); out.write(root); out.write(0); out.write(le16(0)); out.write(le16(1))
        out.write(zStr("EOS", 20))
        out.write(le32(0)); out.write(le32(0)); out.write(le32(0)); out.write(le32(0))
        out.write(le32(0)); out.write(0); out.write(0); out.write(le16(0)); out.write(le16(0))
        return out.toByteArray()
    }

    private fun chunk(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ascii(id))
        out.write(le32(body.size))
        out.write(body)
        if (body.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun zStr(s: String, len: Int): ByteArray {
        val b = ByteArray(len)
        val src = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(src, 0, b, 0, minOf(src.size, len - 1))
        return b
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
}

/** A ByteSource over a byte array, for tests. */
class ArrayByteSource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        val from = pos.toInt().coerceIn(0, data.size)
        val n = minOf(len, data.size - from)
        if (n > 0) System.arraycopy(data, from, dst, off, n)
    }
}
