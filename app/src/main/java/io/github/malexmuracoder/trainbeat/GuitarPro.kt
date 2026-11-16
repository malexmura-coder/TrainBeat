package io.github.malexmuracoder.trainbeat

/** The file is not a Guitar Pro 3, 4 or 5 file this reader can use. The message says why. */
class NotGuitarPro(message: String) : Exception(message)

/**
 * Guitar Pro 3, 4 and 5 files (.gp3, .gp4, .gp5) read into the same Score a MIDI file
 * gives, plus a [TabScore]: unlike a MIDI file, a Guitar Pro file says on which string
 * and fret every note is played, which is the whole reason to read it and not just
 * play it.
 *
 * As with MusicXml, everything the player does works on a Score, so reading one
 * here is all it takes for speed, key, the mixer, the metronome, the loop and the
 * staff or tablature to work on these files.
 *
 * The format is the one Guitar Pro itself writes, documented by the people who
 * read it for free programs (TuxGuitar, among others). This reader was written from
 * that description, field by field, and checked note for note against files made by
 * Guitar Pro. Versions 3.00, 4.00, 4.06, 5.00 and 5.10 are read; Guitar Pro 6 and 7
 * (.gpx, .gp) are a different, packed format and are refused with a message.
 *
 * REPEATS ARE PLAYED, in the sound and in the tablature alike, exactly as for
 * MusicXML (see [RepeatPlan]): the score is unrolled, so a section that repeats
 * appears twice in the bars and under the cursor, and each ending of a volta is played
 * on its own pass. D.C., D.S., coda and fine are not followed.
 *
 * Read: tempo and tempo changes, time and key signatures, every track with its
 * tuning, capo, instrument, volume and pan, rests, chords of notes, dotted values and
 * tuplets, ties, both voices of a Guitar Pro 5 measure, drum tracks. Not read, and
 * therefore not played or shown: dead (muted) notes, grace notes, bends, slides,
 * hammer-ons, harmonics, trills, tremolo picking, strums, dynamics changes inside
 * the piece, and everything that is only about how the page looks.
 */
object GuitarPro {

    /** Guitar Pro counts a quarter note as 960. */
    const val PPQ = 960

    private const val MAX_MEASURES = 20_000
    private const val MAX_TRACKS = 64
    private const val MAX_BEATS_PER_VOICE = 5_000
    private const val MAX_NOTES = 1_000_000
    private const val MAX_TICKS = 400_000_000L
    private const val DEFAULT_VELOCITY = 95
    private const val DEFAULT_TEMPO = 120

    fun isGuitarProName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".gp3") || n.endsWith(".gp4") || n.endsWith(".gp5")
    }

    /** Cheap, before parsing anything: does it start the way a Guitar Pro 3-5 file does. */
    fun looksLikeGuitarPro(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        val len = bytes[0].toInt() and 0xFF
        if (len < PREFIX.length || len > 30) return false
        return String(bytes, 1, len, Charsets.ISO_8859_1).startsWith(PREFIX)
    }

    private const val PREFIX = "FICHIER GUITAR PRO"

    fun parse(bytes: ByteArray): Score {
        if (bytes.size >= 4) {
            val head = String(bytes, 0, 4, Charsets.ISO_8859_1)
            if (head == "BCFZ" || head == "BCFS" || bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                throw NotGuitarPro(
                    "this is a Guitar Pro 6 or 7 file, which cannot be read; " +
                        "save it as .gp5 or export it as MusicXML"
                )
            }
        }
        if (!looksLikeGuitarPro(bytes)) throw NotGuitarPro("this file is not a Guitar Pro 3, 4 or 5 file")
        return Song(Reader(bytes)).read()
    }

    // ------------------------------------------------------------------ reading bytes

    /** Little-endian reading with every access checked: a truncated file is an error, not a crash. */
    private class Reader(private val b: ByteArray) {
        var p = 0

        private fun need(n: Int) {
            if (n < 0 || p + n > b.size) throw NotGuitarPro("the file ends too early")
        }

        val left: Int get() = b.size - p

        fun skip(n: Int) { need(n); p += n }
        fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
        fun i8(): Int { need(1); return b[p++].toInt() }
        fun bool(): Boolean = u8() != 0
        fun i16(): Int { need(2); val v = (b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8); p += 2; return v }
        fun i32(): Int {
            need(4)
            val v = (b[p].toInt() and 0xFF) or
                ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or
                (b[p + 3].toInt() shl 24)
            p += 4
            return v
        }

        /** One length byte, then a field of [fixed] bytes of which that many are the text. */
        fun byteString(fixed: Int): String {
            val len = u8()
            need(fixed)
            val s = String(b, p, minOf(len, fixed), Charsets.ISO_8859_1)
            p += fixed
            return s
        }

        /** An integer that is one more than the field size, then a [byteString]. */
        fun intByteString(): String {
            val n = i32()
            if (n < 1) throw NotGuitarPro("a text of the file has a negative size")
            return byteString(n - 1)
        }

        /** An integer length, then that many bytes of text. */
        fun intString(): String {
            val n = i32()
            if (n < 0) throw NotGuitarPro("a text of the file has a negative size")
            need(n)
            val s = String(b, p, n, Charsets.ISO_8859_1)
            p += n
            return s
        }
    }

    // ------------------------------------------------------------------ what is read

    private class Version(val major: Int, val minor: Int) {
        val v3 get() = major == 3
        val v5 get() = major == 5
        val v50 get() = major == 5 && minor == 0
        val after50 get() = major == 5 && minor > 0
    }

    private class Header(
        val numerator: Int, val denominator: Int,
        val open: Boolean, val close: Int,
        /** Extra passes the closing repeat sign asks for: 1 = the section is played twice. */
        val extra: Int,
        /** Bit n = ending n+1; 0 for no ending, or the continuation of one. */
        val ending: Int,
        val sharps: Int, val minor: Boolean
    ) {
        val ticks: Int get() = (PPQ * 4L * numerator / denominator).toInt()
    }

    private class Track(
        val name: String,
        val tuning: IntArray,
        val capo: Int,
        val channelIndex: Int,
        val percussion: Boolean
    )

    private class RNote(val string: Int, val fret: Int, val tied: Boolean, val dead: Boolean, val velocity: Int)

    /**
     * [off] and [dur] are in ticks, [off] from the start of the measure; [tempo] and
     * [instrument] are -1 when the beat does not change them.
     */
    private class RBeat(val off: Int, val dur: Int, val notes: List<RNote>, val tempo: Int, val instrument: Int)

    private class Channel(val instrument: Int, val volume: Int, val balance: Int)

    private class Song(val r: Reader) {
        lateinit var v: Version
        var title: String? = null
        var tempo = DEFAULT_TEMPO
        var songSharps = 0
        val channels = ArrayList<Channel>()
        val headers = ArrayList<Header>()
        val tracks = ArrayList<Track>()
        /** beats[measure][track] */
        lateinit var beats: Array<Array<List<RBeat>>>
        var noteCount = 0

        fun read(): Score {
            readVersion()
            readInfo()
            val measureCount: Int
            val trackCount: Int
            if (v.v5) {
                skipLyrics()
                if (v.after50) { r.skip(8); r.skip(11) }          // master volume, reserved, equaliser
                skipPageSetup()
                r.intByteString()                                  // tempo name
                tempo = r.i32()
                if (v.after50) r.skip(1)                           // hide tempo
                songSharps = r.i8(); r.skip(4)                     // key; octave
                readChannels()
                r.skip(19 * 2)                                     // directions: not played
                r.skip(4)                                          // master reverb
            } else {
                r.skip(1)                                          // triplet feel
                if (!v.v3) skipLyrics()
                tempo = r.i32()
                songSharps = r.i32()
                if (!v.v3) r.skip(1)                               // octave
                readChannels()
            }
            measureCount = r.i32()
            trackCount = r.i32()
            if (measureCount < 1 || measureCount > MAX_MEASURES) throw NotGuitarPro("the score has no usable number of measures")
            if (trackCount < 1 || trackCount > MAX_TRACKS) throw NotGuitarPro("the score has no usable number of tracks")
            readHeaders(measureCount)
            readTracks(trackCount)
            readMeasures()
            return assemble()
        }

        // ---- the top of the file

        fun readVersion() {
            val s = r.byteString(30)
            if (s.startsWith("CLIPBOARD")) throw NotGuitarPro("this is a clipboard file, not a score")
            val m = Regex("v(\\d+)\\.(\\d+)").find(s) ?: throw NotGuitarPro("the version of this Guitar Pro file is unknown")
            val major = m.groupValues[1].toInt()
            val minor = m.groupValues[2].toInt()
            if (major < 3 || major > 5) throw NotGuitarPro("only Guitar Pro 3, 4 and 5 files can be read, this is version $major")
            v = Version(major, minor)
        }

        fun readInfo() {
            val t = r.intByteString()
            title = t.trim().takeIf { it.isNotEmpty() }
            repeat(if (v.v5) 8 else 7) { r.intByteString() }       // subtitle ... instructions
            val notices = r.i32()
            if (notices < 0 || notices > 100_000) throw NotGuitarPro("the notice of the file is not valid")
            repeat(notices) { r.intByteString() }
        }

        fun skipLyrics() {
            r.skip(4)
            repeat(5) { r.skip(4); r.intString() }
        }

        fun skipPageSetup() {
            r.skip(2 * 4 + 4 * 4 + 4 + 2)
            repeat(10) { r.intByteString() }
        }

        fun readChannels() {
            for (i in 0 until 64) {
                val instrument = r.i32()
                val volume = r.i8()
                val balance = r.i8()
                r.skip(4)                 // chorus, reverb, phaser, tremolo
                r.skip(2)                 // blank
                channels.add(Channel(instrument, volume, balance))
            }
        }

        // ---- measure headers

        fun readHeaders(count: Int) {
            var num = 4
            var den = 4
            var sharps = songSharps
            var minor = false
            val masks = ArrayList<Int>()
            val opens = ArrayList<Boolean>()
            for (n in 1..count) {
                if (v.v5 && n > 1) r.skip(1)
                val flags = r.u8()
                if (flags and 0x01 != 0) num = r.i8()
                if (flags and 0x02 != 0) den = r.i8()
                val open = flags and 0x04 != 0
                var close = 0
                if (flags and 0x08 != 0) close = r.i8()
                var stored = 0
                var hasEnding = false
                if (!v.v5 && flags and 0x10 != 0) { stored = r.u8(); hasEnding = true }
                if (flags and 0x20 != 0) { r.intByteString(); r.skip(4) }          // marker, colour
                if (flags and 0x40 != 0) { sharps = r.i8(); minor = r.i8() != 0 }
                if (v.v5 && flags and 0x10 != 0) { stored = r.u8(); hasEnding = true }
                if (v.v5) {
                    if (flags and 0x03 != 0) r.skip(4)                              // beams
                    if (flags and 0x10 == 0) r.skip(1)
                    r.skip(1)                                                       // triplet feel
                }
                if (num < 1 || den < 1 || den > 64 || den and (den - 1) != 0) throw NotGuitarPro("a measure has a time signature that cannot be right")
                // Guitar Pro 5 writes the endings as bits; 3 and 4 as the number of the ending
                var mask = 0
                if (hasEnding) {
                    if (v.v5) {
                        mask = stored
                    } else {
                        var used = 0
                        for (i in masks.indices.reversed()) {
                            if (opens[i]) break
                            used = used or masks[i]
                        }
                        for (i in 0 until minOf(stored, 8)) if (used and (1 shl i) == 0) mask = mask or (1 shl i)
                    }
                }
                masks.add(mask)
                opens.add(open)
                // Guitar Pro 3 and 4 store the number of repeats (1 = played twice), 5 stores
                // the number of times it is played (2 = played twice)
                val extra = if (close <= 0) 0 else (if (v.v5) close - 1 else close).coerceAtMost(RepeatPlan.MAX_EXTRA)
                headers.add(Header(num, den, open, close, extra, mask, sharps.coerceIn(-7, 7), minor))
            }
        }

        // ---- tracks

        fun readTracks(count: Int) {
            for (i in 0 until count) {
                if (v.v5 && (i == 0 || v.v50)) r.skip(1)
                val flags = r.u8()
                val name = r.byteString(40).trim()
                val strings = r.i32()
                if (strings < 0 || strings > 7) throw NotGuitarPro("a track has $strings strings")
                val tuning = IntArray(strings)
                for (s in 0 until 7) {
                    val t = r.i32()
                    if (s < strings) tuning[s] = t
                }
                r.skip(4)                                   // port
                val channel = r.i32() - 1
                r.skip(4)                                   // effects channel
                r.skip(4)                                   // number of frets
                val capo = r.i32()
                r.skip(4)                                   // colour
                if (v.v5) {
                    r.skip(2 + 3)                           // flags, accentuation, bank, humanize
                    r.skip(4 + 4 + 4 + 12)                  // clef, unknown
                    r.skip(if (v.v50) 12 + 3 else 12 + 4)   // RSE instrument
                    if (v.after50) {
                        r.skip(4)                           // equaliser
                        r.intByteString(); r.intByteString()
                    }
                }
                val percussion = flags and 0x01 != 0 || channel >= 0 && channel % 16 == 9
                tracks.add(Track(name, tuning, capo.coerceIn(0, 30), channel, percussion))
            }
            if (v.v5) r.skip(if (v.v50) 2 else 1)
        }

        // ---- measures, beats, notes

        fun readMeasures() {
            val beatsOf = Array(headers.size) { Array(tracks.size) { emptyList<RBeat>() } }
            for (m in headers.indices) {
                for (t in tracks.indices) {
                    val list = ArrayList<RBeat>()
                    val voices = if (v.v5) 2 else 1
                    for (voice in 0 until voices) readVoice(tracks[t], list)
                    list.sortBy { it.off }                  // both voices, in the order of time
                    if (v.v5 && r.left > 0) r.skip(1)       // line break; the last one may be missing
                    beatsOf[m][t] = list
                }
            }
            beats = beatsOf
        }

        fun readVoice(track: Track, into: MutableList<RBeat>) {
            val n = r.i32()
            if (n < 0 || n > MAX_BEATS_PER_VOICE) throw NotGuitarPro("a measure has $n beats")
            var offExact = 0.0
            for (i in 0 until n) {
                val flags = r.u8()
                var status = 1
                if (flags and 0x40 != 0) status = r.u8()
                val code = r.i8()
                if (code < -2 || code > 4) throw NotGuitarPro("a note has a length that cannot be right")
                var exact = PPQ * 4.0 / (1 shl (code + 2))
                if (flags and 0x01 != 0) exact *= 1.5
                if (flags and 0x20 != 0) {
                    val tuplet = r.i32()
                    val times = when (tuplet) {
                        3 -> 2
                        5, 6, 7 -> 4
                        9, 10, 11, 12, 13 -> 8
                        else -> 0
                    }
                    if (times > 0) exact = exact * times / tuplet
                }
                if (flags and 0x02 != 0) skipChord(track.tuning.size)
                if (flags and 0x04 != 0) r.intByteString()
                if (flags and 0x08 != 0) skipBeatEffects()
                var tempo = -1
                var instrument = -1
                if (flags and 0x10 != 0) {
                    val change = readMixTable()
                    tempo = change.first
                    instrument = change.second
                }
                val notes = readNotes(track)
                if (v.v5) {
                    val flags2 = r.i16()
                    if (flags2 and 0x0800 != 0) r.skip(1)
                }
                if (status == 0) continue                  // an empty beat takes no time
                val off = Math.round(offExact).toInt()
                val ticks = (Math.round(offExact + exact).toInt() - off).coerceAtLeast(1)
                if (notes.isNotEmpty() || tempo > 0 || instrument >= 0) into.add(RBeat(off, ticks, notes, tempo, instrument))
                offExact += exact
            }
        }

        fun skipChord(strings: Int) {
            if (!r.bool()) {
                r.intByteString()                           // name
                val firstFret = r.i32()
                if (firstFret != 0) r.skip(6 * 4)
            } else {
                r.skip(if (v.v3) 124 else 106)
            }
        }

        fun skipBend() {
            r.skip(1 + 4)
            val points = r.i32()
            if (points < 0 || points > 1000) throw NotGuitarPro("a bend has $points points")
            r.skip(points * 9)
        }

        fun skipBeatEffects() {
            if (v.major == 3) {
                val flags = r.u8()
                if (flags and 0x20 != 0) { r.skip(1); r.skip(4) }
                if (flags and 0x40 != 0) r.skip(2)
            } else {
                val flags1 = r.u8()
                val flags2 = r.u8()
                if (flags1 and 0x20 != 0) r.skip(1)
                if (flags2 and 0x04 != 0) skipBend()
                if (flags1 and 0x40 != 0) r.skip(2)
                if (flags2 and 0x02 != 0) r.skip(1)
            }
        }

        /** Returns (tempo, instrument), -1 where not changed. */
        fun readMixTable(): Pair<Int, Int> {
            val instrument = r.i8()
            if (v.v5) {
                r.skip(if (v.v50) 12 + 3 else 12 + 4)       // RSE instrument
                if (v.v50) r.skip(1)
            }
            val volume = r.i8()
            val balance = r.i8()
            val chorus = r.i8()
            val reverb = r.i8()
            val phaser = r.i8()
            val tremolo = r.i8()
            if (v.v5) r.intByteString()                      // tempo name
            val tempo = r.i32()
            for (x in intArrayOf(volume, balance, chorus, reverb, phaser, tremolo)) if (x >= 0) r.skip(1)
            if (tempo >= 0) {
                r.skip(1)
                if (v.after50) r.skip(1)                     // hide tempo
            }
            if (!v.v3) r.skip(1)                             // which changes apply to all tracks
            if (v.v5) {
                r.skip(1)                                    // wah
                if (v.after50) { r.intByteString(); r.intByteString() }
            }
            return Pair(if (tempo > 0) tempo else -1, instrument)
        }

        fun readNotes(track: Track): List<RNote> {
            val flags = r.u8()
            val out = ArrayList<RNote>(2)
            for (s in 1..track.tuning.size) {
                if (flags and (1 shl (7 - s)) == 0) continue
                readNote(s, out)
            }
            return out
        }

        fun readNote(string: Int, out: MutableList<RNote>) {
            val flags = r.u8()
            var type = 1
            var velocity = DEFAULT_VELOCITY
            var fret = -1
            if (flags and 0x20 != 0) type = r.u8()
            if (!v.v5 && flags and 0x01 != 0) r.skip(2)
            if (flags and 0x10 != 0) velocity = (16 * r.i8() - 1).coerceIn(1, 127)
            if (flags and 0x20 != 0) fret = r.i8()
            if (flags and 0x80 != 0) r.skip(2)
            if (v.v5) {
                if (flags and 0x01 != 0) r.skip(8)
                r.skip(1)
            }
            if (flags and 0x08 != 0) skipNoteEffects()
            if (fret < 0 && type != 2) return
            if (++noteCount > MAX_NOTES) throw NotGuitarPro("the score has too many notes")
            out.add(RNote(string, fret, type == 2, type == 3, velocity))
        }

        fun skipNoteEffects() {
            if (v.major == 3) {
                val flags = r.u8()
                if (flags and 0x01 != 0) skipBend()
                if (flags and 0x10 != 0) r.skip(4)
            } else {
                val flags1 = r.u8()
                val flags2 = r.u8()
                if (flags1 and 0x01 != 0) skipBend()
                if (flags1 and 0x10 != 0) r.skip(if (v.v5) 5 else 4)
                if (flags2 and 0x04 != 0) r.skip(1)
                if (flags2 and 0x08 != 0) r.skip(1)
                if (flags2 and 0x10 != 0) {
                    val kind = r.i8()
                    if (v.v5) {
                        if (kind == 2) r.skip(3)
                        if (kind == 3) r.skip(1)
                    }
                }
                if (flags2 and 0x20 != 0) r.skip(2)
            }
        }

        // ---- from what was read to a Score

        /**
         * The measures in the order they are played: repeats and endings unrolled.
         *
         * An ending lasts until the closing repeat sign, or until the next ending or
         * repeat, and the endings after the first last as long as the first one did.
         */
        fun playOrder(): IntArray {
            val n = headers.size
            val flags = List(n) { RepeatPlan.Flags() }
            var seenFirst = false
            var firstLen = 0
            for (m in 0 until n) {
                val h = headers[m]
                val f = flags[m]
                if (h.open) { f.repeatStart = true; seenFirst = false; firstLen = 0 }
                f.repeatEnd = h.extra
                if (h.ending == 0) continue

                // how far this ending reaches by itself
                var j = m
                while (headers[j].close <= 0 && j < n - 1 && headers[j + 1].ending == 0 && !headers[j + 1].open) j++
                val passes = ArrayList<Int>()
                for (bit in 0 until 8) if (h.ending and (1 shl bit) != 0) passes.add(bit + 1)
                if (h.ending and 1 != 0) {
                    seenFirst = true
                    firstLen = j - m + 1
                    f.endingStart = passes
                    flags[j].endingStop = true
                } else if (seenFirst) {
                    f.endingStart = passes
                    flags[minOf(j, m + maxOf(firstLen, 1) - 1)].endingStop = true
                } else {
                    f.endingStart = emptyList()            // an ending with no first one before it is played
                }
            }
            return RepeatPlan.order(flags).toIntArray()
        }

        fun assemble(): Score {
            val order = playOrder()
            val outStart = IntArray(order.size)
            var total = 0
            var longTotal = 0L
            for ((k, m) in order.withIndex()) {
                outStart[k] = total
                longTotal += headers[m].ticks
                if (longTotal > MAX_TICKS) throw NotGuitarPro("the piece is far too long")
                total = longTotal.toInt()
            }

            val timeSigs = ArrayList<TimeSignature>()
            val keySigs = ArrayList<KeySignature>()
            val bars = ArrayList<TabBar>()
            var prevMeter: Pair<Int, Int>? = null
            var prevKey: Pair<Int, Boolean>? = null
            for ((k, m) in order.withIndex()) {
                val h = headers[m]
                val meter = Pair(h.numerator, h.denominator)
                if (meter != prevMeter) timeSigs.add(TimeSignature(outStart[k], h.numerator, h.denominator))
                prevMeter = meter
                val key = Pair(h.sharps, h.minor)
                if (key != prevKey) keySigs.add(KeySignature(outStart[k], h.sharps, h.minor))
                prevKey = key
                bars.add(TabBar(outStart[k], h.ticks, h.numerator, h.denominator))
            }

            // tempo: the one the file starts with, then every change in the order played
            val tempos = ArrayList<TempoPoint>()
            var cur = if (tempo in 1..2000) tempo else DEFAULT_TEMPO
            tempos.add(TempoPoint(0, 60_000_000 / cur))
            val events = ArrayList<Pair<Int, Int>>()
            for ((k, m) in order.withIndex()) {
                for (t in tracks.indices) for (b in beats[m][t]) {
                    if (b.tempo > 0) events.add(Pair(outStart[k] + b.off, b.tempo))
                }
            }
            events.sortBy { it.first }
            for ((tick, bpm) in events) {
                if (bpm == cur || bpm > 2000) continue
                cur = bpm
                if (tick == 0) tempos.clear()
                tempos.add(TempoPoint(tick, 60_000_000 / bpm))
            }

            val notes = ArrayList<MidiNote>()
            val programs = ArrayList<ProgramChange>()
            val controllers = ArrayList<Controller>()
            val tabParts = ArrayList<TabPart>()
            var nextChannel = 0
            for ((t, track) in tracks.withIndex()) {
                val chInfo = channels.getOrNull(track.channelIndex)
                val out = ArrayList<MidiNote>()
                val tab = ArrayList<TabNote>()
                // for ties: the last note on each string
                val lastIdx = IntArray(8) { -1 }
                val lastFret = IntArray(8) { -1 }
                val lastEnd = IntArray(8)
                var channel = Score.DRUM_CHANNEL
                var channelGiven = track.percussion
                for ((k, m) in order.withIndex()) {
                    val list = beats[m][t]
                    for (b in list) {
                        if (b.instrument >= 0 && !track.percussion && channelGiven) {
                            programs.add(ProgramChange(outStart[k] + b.off, channel, b.instrument.coerceIn(0, 127), 0))
                        }
                        val dur = b.dur.coerceAtLeast(1)
                        for (n in b.notes) {
                            if (n.dead) { lastIdx[n.string] = -1; continue }
                            val tick = outStart[k] + b.off
                            if (!track.percussion && !channelGiven) {
                                if (nextChannel == Score.DRUM_CHANNEL) nextChannel++
                                if (nextChannel > 15) continue
                                channel = nextChannel++
                                channelGiven = true
                            }
                            if (!channelGiven) continue
                            if (track.percussion) {
                                if (n.fret in 0..127) out.add(MidiNote(tick, dur, Score.DRUM_CHANNEL, n.fret, n.velocity))
                                continue
                            }
                            var fret = n.fret
                            if (n.tied) {
                                fret = lastFret[n.string]
                                if (fret < 0) continue
                                val idx = lastIdx[n.string]
                                // a tie follows its note; after a skipped ending it does not, and is struck
                                if (idx >= 0 && tick - lastEnd[n.string] in 0..PPQ) {
                                    val old = out[idx]
                                    val end = tick + dur
                                    out[idx] = MidiNote(old.tick, end - old.tick, old.channel, old.key, old.velocity)
                                    lastEnd[n.string] = end
                                    tab.add(TabNote(tick, dur, n.string, fret, true))
                                    continue
                                }
                            }
                            val key = track.tuning[n.string - 1] + track.capo + fret
                            if (key !in 0..127) { lastIdx[n.string] = -1; continue }
                            out.add(MidiNote(tick, dur, channel, key, n.velocity))
                            lastIdx[n.string] = out.size - 1
                            lastFret[n.string] = fret
                            lastEnd[n.string] = tick + dur
                            tab.add(TabNote(tick, dur, n.string, fret, false))
                        }
                    }
                }
                if (out.isEmpty()) continue
                if (!track.percussion) {
                    programs.add(ProgramChange(0, channel, (chInfo?.instrument ?: 0).coerceIn(0, 127), 0))
                    chInfo?.let {
                        controllers.add(Controller(0, channel, 7, ((it.volume shl 3) - 1).coerceIn(0, 127)))
                        controllers.add(Controller(0, channel, 10, ((it.balance shl 3) - 1).coerceIn(0, 127)))
                    }
                }
                // the same note written in both voices is one note
                val seen = HashMap<Long, Int>()
                for (n in out) {
                    val id = (n.tick.toLong() shl 16) or (n.channel * 128L + n.key)
                    val prev = seen[id]
                    if (prev == null) { seen[id] = notes.size; notes.add(n) }
                    else if (n.durTicks > notes[prev].durTicks) notes[prev] = n
                }
                if (!track.percussion && track.tuning.isNotEmpty() && tab.isNotEmpty()) {
                    val dup = HashSet<Long>()
                    val unique = ArrayList<TabNote>()
                    for (n in tab) {
                        val id = (n.tick.toLong() shl 24) or (n.string.toLong() shl 8) or n.fret.toLong()
                        if (dup.add(id)) unique.add(n)
                    }
                    unique.sortBy { it.tick }
                    val open = IntArray(track.tuning.size) { track.tuning[it] + track.capo }
                    tabParts.add(TabPart(channel, track.name.ifEmpty { "Track ${t + 1}" }, open, unique))
                }
            }
            if (notes.isEmpty()) throw NotGuitarPro("the file contains no notes")
            notes.sortBy { it.tick }

            return Score(
                ppq = PPQ,
                notes = notes,
                tempos = tempos,
                timeSignatures = timeSigs,
                keySignatures = keySigs,
                programs = programs.sortedBy { it.tick },
                controllers = controllers.sortedBy { it.tick },
                endTick = maxOf(total, notes.maxOf { it.endTick }),
                title = title,
                tab = if (tabParts.isEmpty()) null else TabScore(PPQ, bars, tabParts)
            )
        }
    }
}
