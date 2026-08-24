package io.github.malexmuracoder.trainbeat

/**
 * Writes a score back out as a format 0 MIDI file.
 *
 * This is what makes the MIDI trimmer worth having: cutting a score on a bar line
 * and writing it out is exact by construction - no zero crossing to find, no seam
 * to search for, no quality lost - and the result is a few kilobytes that stay
 * adjustable in tempo and key forever.
 */
object SmfWriter {

    fun write(score: Score): ByteArray {
        val track = ArrayList<Byte>(score.notes.size * 8 + 64)
        var last = 0

        // events as (tick, order, bytes); order keeps setup before notes on one tick
        val events = ArrayList<Triple<Int, Int, ByteArray>>()

        score.title?.let { events.add(Triple(0, 0, meta(0x03, it.toByteArray(Charsets.ISO_8859_1)))) }
        for (t in score.tempos) {
            val us = t.usPerQuarter
            events.add(Triple(t.tick, 1, meta(0x51, byteArrayOf(
                ((us shr 16) and 0xFF).toByte(), ((us shr 8) and 0xFF).toByte(), (us and 0xFF).toByte()))))
        }
        for (s in score.timeSignatures) {
            var d = 0
            var v = s.denominator
            while (v > 1) { v = v shr 1; d++ }
            events.add(Triple(s.tick, 1, meta(0x58, byteArrayOf(
                s.numerator.toByte(), d.toByte(), 24, 8))))
        }
        for (k in score.keySignatures) {
            events.add(Triple(k.tick, 1, meta(0x59, byteArrayOf(
                k.sharps.toByte(), (if (k.minor) 1 else 0).toByte()))))
        }
        for (p in score.programs) {
            if (p.bank != 0) {
                events.add(Triple(p.tick, 2, byteArrayOf(
                    (0xB0 or p.channel).toByte(), 0, ((p.bank shr 7) and 0x7F).toByte())))
                events.add(Triple(p.tick, 2, byteArrayOf(
                    (0xB0 or p.channel).toByte(), 32, (p.bank and 0x7F).toByte())))
            }
            events.add(Triple(p.tick, 3, byteArrayOf(
                (0xC0 or p.channel).toByte(), p.program.toByte())))
        }
        for (c in score.controllers) {
            events.add(Triple(c.tick, 3, byteArrayOf(
                (0xB0 or c.channel).toByte(), c.number.toByte(), c.value.toByte())))
        }
        for (n in score.notes) {
            events.add(Triple(n.tick, 4, byteArrayOf(
                (0x90 or n.channel).toByte(), n.key.toByte(), n.velocity.coerceIn(1, 127).toByte())))
            events.add(Triple(n.endTick, 4, byteArrayOf(
                (0x80 or n.channel).toByte(), n.key.toByte(), 64)))
        }

        events.sortWith(compareBy({ it.first }, { it.second }))
        for ((tick, _, data) in events) {
            writeVar(track, tick - last)
            last = tick
            for (b in data) track.add(b)
        }
        writeVar(track, (score.endTick - last).coerceAtLeast(0))
        track.add(0xFF.toByte()); track.add(0x2F.toByte()); track.add(0)

        val out = ArrayList<Byte>(track.size + 22)
        ascii(out, "MThd"); int32(out, 6); int16(out, 0); int16(out, 1); int16(out, score.ppq)
        ascii(out, "MTrk"); int32(out, track.size)
        out.addAll(track)
        return out.toByteArray()
    }

    private fun meta(type: Int, data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size + 4)
        out.add(0xFF.toByte()); out.add(type.toByte())
        writeVar(out, data.size)
        for (b in data) out.add(b)
        return out.toByteArray()
    }

    private fun writeVar(out: MutableList<Byte>, value: Int) {
        val v = value.coerceAtLeast(0)
        var buffer = v.toLong() and 0x7F
        var x = v shr 7
        while (x > 0) {
            buffer = (buffer shl 8) or 0x80 or (x and 0x7F).toLong()
            x = x shr 7
        }
        while (true) {
            out.add((buffer and 0xFF).toByte())
            if (buffer and 0x80L != 0L) buffer = buffer shr 8 else break
        }
    }

    private fun ascii(out: MutableList<Byte>, s: String) { for (c in s) out.add(c.code.toByte()) }
    private fun int16(out: MutableList<Byte>, v: Int) {
        out.add(((v shr 8) and 0xFF).toByte()); out.add((v and 0xFF).toByte())
    }
    private fun int32(out: MutableList<Byte>, v: Int) {
        out.add(((v shr 24) and 0xFF).toByte()); out.add(((v shr 16) and 0xFF).toByte())
        out.add(((v shr 8) and 0xFF).toByte()); out.add((v and 0xFF).toByte())
    }
}
