package io.github.malexmuracoder.trainbeat

/**
 * Standard MIDI File reader, and the score it produces.
 *
 * A MIDI file is a score, not a sound: that is the whole reason it is worth
 * supporting. Changing the tempo is a multiplication, transposing is an addition,
 * and both are exact - none of the time stretching that costs quality on audio.
 * So the score is kept in TICKS with a tempo map beside it, and seconds are
 * computed only when something has to be synthesised.
 *
 * Scope, chosen deliberately: format 0 and 1, note on/off, program change, bank
 * select, channel volume, expression, pan, the sustain pedal, and the tempo, time
 * signature and key signature meta events. No pitch bend and no SysEx beyond
 * skipping them: a backing track does not need them, and every feature that is
 * parsed but not rendered is a lie about what the app can do.
 */

/** One sounding note, in ticks. Channel 9 is the drum channel (MIDI channel 10). */
class MidiNote(
    val tick: Int,
    val durTicks: Int,
    val channel: Int,
    val key: Int,
    val velocity: Int
) {
    val endTick: Int get() = tick + durTicks
}

class TempoPoint(val tick: Int, val usPerQuarter: Int) {
    val bpm: Double get() = 60_000_000.0 / usPerQuarter
}

class TimeSignature(val tick: Int, val numerator: Int, val denominator: Int)

/** sharps: -7..+7 as written in the file. */
class KeySignature(val tick: Int, val sharps: Int, val minor: Boolean)

class ProgramChange(val tick: Int, val channel: Int, val program: Int, val bank: Int)

class Controller(val tick: Int, val channel: Int, val number: Int, val value: Int)

class MalformedMidi(message: String) : Exception(message)

/**
 * A parsed MIDI file. Immutable: every operation returns a new score, so the
 * original stays available when the user moves a stepper back.
 */
class Score(
    val ppq: Int,
    val notes: List<MidiNote>,
    val tempos: List<TempoPoint>,
    val timeSignatures: List<TimeSignature>,
    val keySignatures: List<KeySignature>,
    val programs: List<ProgramChange>,
    val controllers: List<Controller>,
    val endTick: Int,
    val title: String?
) {
    /** Channels that actually carry notes, in order. */
    val usedChannels: List<Int> by lazy { notes.map { it.channel }.distinct().sorted() }

    val hasDrums: Boolean get() = usedChannels.contains(DRUM_CHANNEL)

    /** Seconds from the start of the file to a tick, following the tempo map. */
    fun secondsAt(tick: Int): Double {
        var t = 0.0
        var prevTick = 0
        var us = DEFAULT_US_PER_QUARTER
        for (p in tempos) {
            if (p.tick >= tick) break
            t += (p.tick - prevTick).toDouble() * us / (ppq * 1_000_000.0)
            prevTick = p.tick
            us = p.usPerQuarter
        }
        t += (tick - prevTick).toDouble() * us / (ppq * 1_000_000.0)
        return t
    }

    val durationSec: Double get() = secondsAt(endTick)

    /** The tempo in force at a tick. */
    fun bpmAt(tick: Int): Double {
        var us = DEFAULT_US_PER_QUARTER
        for (p in tempos) {
            if (p.tick > tick) break
            us = p.usPerQuarter
        }
        return 60_000_000.0 / us
    }

    /** Ticks in one bar at a tick, from the time signature in force. */
    fun ticksPerBar(tick: Int = 0): Int {
        var num = 4
        var den = 4
        for (s in timeSignatures) {
            if (s.tick > tick) break
            num = s.numerator
            den = s.denominator
        }
        return (ppq * 4.0 * num / den).toInt().coerceAtLeast(1)
    }

    /**
     * Transposes by semitones. The drum channel is left alone on purpose: there
     * the note number is the name of an instrument, not a pitch, and transposing
     * it turns the snare into a cymbal.
     */
    fun transpose(semitones: Int): Score {
        if (semitones == 0) return this
        val moved = notes.mapNotNull { n ->
            if (n.channel == DRUM_CHANNEL) n
            else {
                val k = n.key + semitones
                if (k in 0..127) MidiNote(n.tick, n.durTicks, n.channel, k, n.velocity) else null
            }
        }
        return copy(notes = moved)
    }

    /**
     * Cuts the score between two ticks. Notes that straddle the start are dropped
     * (a note that began before the loop did would sound like a mistake at every
     * repeat); notes that overrun the end are clipped.
     */
    fun slice(fromTick: Int, toTick: Int): Score {
        val a = fromTick.coerceAtLeast(0)
        val b = toTick.coerceAtLeast(a + 1)
        val kept = notes.filter { it.tick >= a && it.tick < b }.map { n ->
            MidiNote(n.tick - a, minOf(n.endTick, b) - n.tick, n.channel, n.key, n.velocity)
        }
        // the state at the cut has to be carried over, or the loop plays with the
        // wrong instruments: the last tempo, signature, program and controller
        // before the cut are moved to tick 0
        return Score(
            ppq = ppq,
            notes = kept,
            tempos = carryTempos(a, b),
            timeSignatures = carry(timeSignatures, a, b, { it.tick }) { TimeSignature(0, it.numerator, it.denominator) } +
                timeSignatures.filter { it.tick in (a + 1) until b }.map { TimeSignature(it.tick - a, it.numerator, it.denominator) },
            keySignatures = carry(keySignatures, a, b, { it.tick }) { KeySignature(0, it.sharps, it.minor) } +
                keySignatures.filter { it.tick in (a + 1) until b }.map { KeySignature(it.tick - a, it.sharps, it.minor) },
            programs = lastPerChannel(programs, a).map { ProgramChange(0, it.channel, it.program, it.bank) } +
                programs.filter { it.tick in a until b }.map { ProgramChange(it.tick - a, it.channel, it.program, it.bank) },
            controllers = lastControllers(a).map { Controller(0, it.channel, it.number, it.value) } +
                controllers.filter { it.tick in a until b }.map { Controller(it.tick - a, it.channel, it.number, it.value) },
            endTick = b - a,
            title = title
        )
    }

    private fun carryTempos(a: Int, b: Int): List<TempoPoint> {
        val before = tempos.lastOrNull { it.tick <= a }
        val inside = tempos.filter { it.tick in (a + 1) until b }.map { TempoPoint(it.tick - a, it.usPerQuarter) }
        val head = TempoPoint(0, before?.usPerQuarter ?: DEFAULT_US_PER_QUARTER)
        return listOf(head) + inside
    }

    private fun <T> carry(list: List<T>, a: Int, b: Int, tickOf: (T) -> Int, move: (T) -> T): List<T> {
        val before = list.lastOrNull { tickOf(it) <= a } ?: return emptyList()
        return listOf(move(before))
    }

    private fun lastPerChannel(list: List<ProgramChange>, a: Int): List<ProgramChange> =
        list.filter { it.tick <= a }.groupBy { it.channel }.values.map { it.last() }

    private fun lastControllers(a: Int): List<Controller> =
        controllers.filter { it.tick <= a }
            .groupBy { it.channel * 256 + it.number }.values.map { it.last() }

    fun copy(
        notes: List<MidiNote> = this.notes,
        tempos: List<TempoPoint> = this.tempos,
        endTick: Int = this.endTick
    ) = Score(ppq, notes, tempos, timeSignatures, keySignatures, programs, controllers, endTick, title)

    companion object {
        const val DRUM_CHANNEL = 9
        const val DEFAULT_US_PER_QUARTER = 500_000          // 120 BPM, as the standard says
    }
}

object Smf {

    fun parse(bytes: ByteArray): Score {
        val r = Reader(bytes)
        if (r.ascii(4) != "MThd") throw MalformedMidi("not a MIDI file: no MThd header")
        val headerLen = r.int32()
        val format = r.int16()
        val nTracks = r.int16()
        val division = r.int16()
        r.skip(headerLen - 6)
        if (format !in 0..2) throw MalformedMidi("unknown MIDI format $format")

        val ppq: Int
        if (division and 0x8000 != 0) {
            // SMPTE: negative frames per second and ticks per frame. Converted to a
            // pulses-per-quarter equivalent at 120 BPM, which is all this app needs.
            val fps = 256 - (division shr 8 and 0xFF)
            val ticksPerFrame = division and 0xFF
            ppq = (fps * ticksPerFrame / 2).coerceAtLeast(24)
        } else {
            ppq = (division and 0x7FFF).coerceAtLeast(1)
        }

        val notes = ArrayList<MidiNote>()
        val tempos = ArrayList<TempoPoint>()
        val timeSigs = ArrayList<TimeSignature>()
        val keySigs = ArrayList<KeySignature>()
        val programs = ArrayList<ProgramChange>()
        val ccs = ArrayList<Controller>()
        var title: String? = null
        var end = 0

        for (track in 0 until nTracks) {
            if (r.remaining() < 8) break
            var id = r.ascii(4)
            var len = r.int32()
            while (id != "MTrk" && r.remaining() > 8) {          // unknown chunk: skip it
                r.skip(len)
                id = r.ascii(4)
                len = r.int32()
            }
            if (id != "MTrk") break
            val trackEnd = r.pos + len
            var tick = 0
            var status = 0
            val bank = IntArray(16)
            val sustain = BooleanArray(16)
            // A QUEUE per channel and key, not one open note: real files do restart
            // the same pitch while it is still sounding (a repeated note whose
            // note-off arrives late), and a single slot silently loses one of the
            // two. Note-off closes the oldest still-open one, which is the usual
            // reading of the standard.
            val open = HashMap<Int, ArrayDeque<MidiNote>>()
            val held = ArrayList<Pair<Int, MidiNote>>()          // waiting for the pedal

            fun startNote(ch: Int, key: Int, vel: Int) {
                open.getOrPut(ch * 128 + key) { ArrayDeque() }.addLast(MidiNote(tick, 0, ch, key, vel))
            }

            fun stopNote(ch: Int, key: Int) {
                val q = open[ch * 128 + key] ?: return
                val n = q.removeFirstOrNull() ?: return
                if (q.isEmpty()) open.remove(ch * 128 + key)
                if (sustain[ch] && ch != Score.DRUM_CHANNEL) {
                    held.add(Pair(ch * 128 + key, n))
                } else {
                    val d = (tick - n.tick).coerceAtLeast(1)
                    notes.add(MidiNote(n.tick, d, ch, key, n.velocity))
                }
            }

            fun releaseHeld(ch: Int) {
                val it = held.iterator()
                while (it.hasNext()) {
                    val (k, n) = it.next()
                    if (k / 128 == ch) {
                        notes.add(MidiNote(n.tick, (tick - n.tick).coerceAtLeast(1), ch, k % 128, n.velocity))
                        it.remove()
                    }
                }
            }

            while (r.pos < trackEnd && r.remaining() > 0) {
                tick += r.varInt()
                var b = r.byte()
                if (b < 0x80) {                                   // running status
                    if (status == 0) throw MalformedMidi("running status with no previous status byte")
                    r.back()
                    b = status
                } else if (b < 0xF0) {
                    status = b
                }
                when (b and 0xF0) {
                    0x80 -> { val ch = b and 0x0F; val k = r.byte(); r.byte(); stopNote(ch, k) }
                    0x90 -> {
                        val ch = b and 0x0F
                        val k = r.byte()
                        val v = r.byte()
                        if (v == 0) stopNote(ch, k) else startNote(ch, k, v)
                    }
                    0xA0 -> { r.byte(); r.byte() }                // aftertouch: ignored
                    0xB0 -> {
                        val ch = b and 0x0F
                        val num = r.byte()
                        val v = r.byte()
                        when (num) {
                            0 -> bank[ch] = (bank[ch] and 0x7F) or (v shl 7)
                            32 -> bank[ch] = (bank[ch] and 0x3F80) or v
                            64 -> {
                                sustain[ch] = v >= 64
                                if (!sustain[ch]) releaseHeld(ch)
                            }
                            7, 10, 11 -> ccs.add(Controller(tick, ch, num, v))
                            120, 123 -> {                         // all sound / all notes off
                                sustain[ch] = false
                                releaseHeld(ch)
                                for (k in 0..127) {
                                    while (open.containsKey(ch * 128 + k)) stopNote(ch, k)
                                }
                            }
                        }
                    }
                    0xC0 -> {
                        val ch = b and 0x0F
                        val p = r.byte()
                        programs.add(ProgramChange(tick, ch, p, bank[ch]))
                    }
                    0xD0 -> r.byte()                              // channel pressure
                    0xE0 -> { r.byte(); r.byte() }                // pitch bend: ignored
                    0xF0 -> when (b) {
                        0xF0, 0xF7 -> r.skip(r.varInt())          // SysEx
                        0xFF -> {
                            val type = r.byte()
                            val len2 = r.varInt()
                            val start = r.pos
                            when (type) {
                                0x03 -> if (title == null && track == 0) title = r.ascii(len2)
                                0x51 -> if (len2 >= 3) {
                                    val us = (r.byte() shl 16) or (r.byte() shl 8) or r.byte()
                                    if (us > 0) tempos.add(TempoPoint(tick, us))
                                }
                                0x58 -> if (len2 >= 2) {
                                    val num = r.byte()
                                    val den = 1 shl r.byte()
                                    timeSigs.add(TimeSignature(tick, num.coerceAtLeast(1), den.coerceAtLeast(1)))
                                }
                                0x59 -> if (len2 >= 2) {
                                    val sf = r.signedByte()
                                    val mi = r.byte()
                                    keySigs.add(KeySignature(tick, sf, mi != 0))
                                }
                            }
                            r.pos = start + len2
                        }
                        else -> {}
                    }
                    else -> throw MalformedMidi("unknown status byte 0x%02X".format(b))
                }
            }
            // a track that ends with notes still down: close them at the track end
            for ((k, q) in open) {
                for (n in q) {
                    notes.add(MidiNote(n.tick, (tick - n.tick).coerceAtLeast(1), k / 128, k % 128, n.velocity))
                }
            }
            for ((k, n) in held) {
                notes.add(MidiNote(n.tick, (tick - n.tick).coerceAtLeast(1), k / 128, k % 128, n.velocity))
            }
            end = maxOf(end, tick)
            r.pos = trackEnd
        }

        if (notes.isEmpty()) throw MalformedMidi("the file contains no notes")
        notes.sortBy { it.tick }
        tempos.sortBy { it.tick }
        timeSigs.sortBy { it.tick }
        keySigs.sortBy { it.tick }
        programs.sortBy { it.tick }
        ccs.sortBy { it.tick }
        val lastNoteEnd = notes.maxOf { it.endTick }
        return Score(ppq, notes, tempos, timeSigs, keySigs, programs, ccs,
            maxOf(end, lastNoteEnd), title?.trim()?.ifEmpty { null })
    }

    private class Reader(val a: ByteArray) {
        var pos = 0
        fun remaining() = a.size - pos
        fun byte(): Int {
            if (pos >= a.size) throw MalformedMidi("file ends in the middle of an event")
            return a[pos++].toInt() and 0xFF
        }
        fun signedByte(): Int {
            return byte().let { if (it > 127) it - 256 else it }
        }
        fun back() { pos-- }
        fun int16() = (byte() shl 8) or byte()
        fun int32() = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun ascii(n: Int): String {
            val sb = StringBuilder()
            for (i in 0 until n) {
                val c = byte()
                if (c >= 32) sb.append(c.toChar())
            }
            return sb.toString()
        }
        fun skip(n: Int) { pos = (pos + n).coerceIn(0, a.size) }
        fun varInt(): Int {
            var v = 0
            for (i in 0 until 4) {
                val b = byte()
                v = (v shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) return v
            }
            return v
        }
    }
}
