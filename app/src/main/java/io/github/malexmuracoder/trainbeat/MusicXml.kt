package io.github.malexmuracoder.trainbeat

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** The file is not a MusicXML score this reader can use. The message says why. */
class NotMusicXml(message: String) : Exception(message)

/**
 * MusicXML (.musicxml, .xml, and the zipped .mxl) read into the same Score a MIDI
 * file gives, plus, where the file says on which string and fret each note is
 * played, a [TabScore].
 *
 * WHY A SCORE AND NOT A SECOND KIND OF TRACK. Everything the player does - speed,
 * transposition, the mixer, the loop, the metronome, the library - is done on a
 * Score. Giving it one means all of it works on a MusicXML file with nothing
 * added, and the staff of a part that has no tablature is simply the one the MIDI
 * engine already draws.
 *
 * REPEATS ARE PLAYED, in the sound and in the tablature alike (see [RepeatPlan]): the
 * score is unrolled, repeat signs and voltas followed, so a section that repeats
 * appears twice in the bars, in the staff and under the cursor, and the second
 * ending is played after the second pass. Everything is built from the one list of
 * measures in playing order, which is what keeps the sound, the cursor and the pages of
 * the staff agreeing. D.C., D.S., coda and fine are not followed.
 *
 * THE FIRST MEASURE, when the file says it is a pick-up (implicit), is padded with
 * silence in front so that the bars fall where a MIDI file would put them. Any
 * other measure is as long as the time signature says, unless the file marks it
 * implicit or it holds more than the signature allows.
 *
 * Read: partwise scores; notes, rests, chords, ties, tuplets (through the durations
 * the file already states), voices, tempo, dynamics, key, time signature, the
 * instrument and its volume and pan, repeats and endings, and transposing parts. Not read,
 * and therefore not played: D.C., D.S., coda, grace notes,
 * cue notes, ornaments, bends, slides, glissandi. Timewise scores are refused.
 */
object MusicXml {

    /** Zip bombs: more than this once unpacked is refused. */
    const val MAX_UNZIPPED = 64L * 1024 * 1024

    fun isMusicXmlName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".musicxml") || n.endsWith(".mxl") || n.endsWith(".xml")
    }

    fun parse(bytes: ByteArray): Score {
        val xml = if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            unzip(bytes) else bytes
        sniff(xml)
        return read(dom(xml))
    }

    // ---- getting at the XML

    private fun unzip(bytes: ByteArray): ByteArray {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = z.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_UNZIPPED) throw NotMusicXml("this compressed file is too large to be a score")
                    out.write(buf, 0, n)
                }
                files[e.name] = out.toByteArray()
            }
        }
        // the container says which file is the score; failing that, the first one
        val container = files["META-INF/container.xml"]
        if (container != null) {
            val m = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(String(container, Charsets.UTF_8))
            val named = m?.groupValues?.get(1)?.let { files[it] }
            if (named != null) return named
        }
        for ((name, data) in files) {
            val n = name.lowercase()
            if (!n.startsWith("meta-inf") && (n.endsWith(".xml") || n.endsWith(".musicxml"))) return data
        }
        throw NotMusicXml("no score found inside this compressed file")
    }

    /** Cheap, before parsing anything: is this a score at all, and a safe one. */
    private fun sniff(xml: ByteArray) {
        val head = String(xml, 0, minOf(xml.size, 8192), Charsets.ISO_8859_1)
        // entities are how a small file becomes a very large one; MusicXML has no use for them
        if (head.contains("<!ENTITY")) throw NotMusicXml("this XML defines entities, which a score never does")
        when {
            head.contains("<score-partwise") -> {}
            head.contains("<score-timewise") ->
                throw NotMusicXml("this is a timewise MusicXML score; export it as partwise")
            else -> throw NotMusicXml("this XML file is not a MusicXML score")
        }
    }

    private fun dom(xml: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
        try {
            val b = f.newDocumentBuilder()
            // The header of a MusicXML file points at its DTD on a web server. Reading
            // it would mean a network request to open a file, and nothing needs it.
            b.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            return b.parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw NotMusicXml("the file is not well-formed XML: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.tagName == name) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.all(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()
    private fun Element.text(name: String): String? = kid(name)?.textContent?.trim()
    private fun Element.int(name: String): Int? = text(name)?.toDoubleOrNull()?.let { Math.round(it).toInt() }

    // ---- what is read from the file, before it is laid on a timeline

    private class PartInfo(
        val id: String,
        val name: String,
        val program: Int?,
        val volume: Int?,
        val pan: Int?,
        /** midi-unpitched of each instrument id, for percussion */
        val unpitched: Map<String, Int>
    )

    /** A note as the file gives it: in ticks from the start of ITS measure. */
    private class RawNote(
        val off: Int,
        val dur: Int,
        val key: Int,
        val drum: Boolean,
        val string: Int,
        val fret: Int,
        val tieStart: Boolean,
        val tieStop: Boolean,
        val vel: Int
    )

    private class TempoEv(val off: Int, val us: Int)

    private class PartScan(val info: PartInfo, measures: Int) {
        val notes: Array<MutableList<RawNote>> = Array(measures) { ArrayList<RawNote>() }
        var hintLines = 0
        val hint = HashMap<Int, Int>()          // staff-tuning line (1 = lowest) -> pitch
    }

    private val STEPS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private val DYNAMICS = mapOf(
        "ppp" to 20, "pp" to 33, "p" to 49, "mp" to 64, "mf" to 80, "f" to 96,
        "ff" to 112, "fff" to 126, "fp" to 96, "sf" to 112, "sfz" to 112, "sffz" to 120,
        "rfz" to 112, "fz" to 112, "sfp" to 96, "sfpp" to 96
    )

    private const val DEFAULT_VELOCITY = 80

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /** The resolution: every divisions value in the file divides it, and it is not coarse. */
    private fun resolutionOf(root: Element): Int {
        var l = 1L
        val list = root.getElementsByTagName("divisions")
        for (i in 0 until list.length) {
            val d = list.item(i).textContent.trim().toDoubleOrNull()?.toInt() ?: continue
            if (d <= 0) continue
            l = l / gcd(l, d.toLong()) * d
            if (l > 100_000L) return 960
        }
        var ppq = l
        while (ppq < 96L) ppq += l
        return ppq.toInt()
    }

    private fun meterOf(el: Element, beats: Int, type: Int): Pair<Int, Int> {
        val b = el.text("beats")
            ?.split('+')?.mapNotNull { it.trim().toIntOrNull() }?.sum()?.takeIf { it > 0 } ?: beats
        val t = el.int("beat-type")?.takeIf { it > 0 } ?: type
        return Pair(b, t)
    }

    /** The meter that has exactly this length, in the plainest terms. */
    fun meterFor(length: Int, ppq: Int): Pair<Int, Int> {
        for (den in intArrayOf(4, 8, 16, 32, 64)) {
            val q = length.toLong() * den
            if (q % (4L * ppq) == 0L) return Pair((q / (4L * ppq)).toInt().coerceAtLeast(1), den)
        }
        return Pair(Math.round(length * 64.0 / (4.0 * ppq)).toInt().coerceAtLeast(1), 64)
    }

    // ---- the whole file

    private fun read(doc: Document): Score {
        val root = doc.documentElement
        if (root.tagName != "score-partwise") throw NotMusicXml("this XML file is not a MusicXML score")
        val ppq = resolutionOf(root)

        val infos = HashMap<String, PartInfo>()
        root.kid("part-list")?.kids("score-part")?.forEach { sp ->
            val id = sp.getAttribute("id")
            val mi = sp.kids("midi-instrument")
            val unp = HashMap<String, Int>()
            for (m in mi) m.int("midi-unpitched")?.let { unp[m.getAttribute("id")] = it - 1 }
            val first = mi.firstOrNull { it.int("midi-program") != null } ?: mi.firstOrNull()
            infos[id] = PartInfo(
                id,
                sp.text("part-name")?.takeIf { it.isNotEmpty() }
                    ?: sp.kid("score-instrument")?.text("instrument-name") ?: id,
                first?.int("midi-program")?.let { (it - 1).coerceIn(0, 127) },
                first?.text("volume")?.toDoubleOrNull()?.let { Math.round(it * 127.0 / 100.0).toInt().coerceIn(0, 127) },
                first?.text("pan")?.toDoubleOrNull()?.let {
                    Math.round((it.coerceIn(-90.0, 90.0) + 90.0) / 180.0 * 127.0).toInt().coerceIn(0, 127)
                },
                unp
            )
        }

        val partEls = root.kids("part")
        if (partEls.isEmpty()) throw NotMusicXml("the score has no parts")
        val count = partEls.maxOf { it.kids("measure").size }
        if (count == 0) throw NotMusicXml("the score has no measures")

        // what is common to the whole piece, read from the first part
        val lenContent = IntArray(count)
        val implicit = BooleanArray(count)
        val beatsAt = IntArray(count) { 4 }
        val typeAt = IntArray(count) { 4 }
        val fifthsAt = IntArray(count)
        val minorAt = BooleanArray(count)
        val flags = Array(count) { RepeatPlan.Flags() }
        val tempoEv = Array(count) { ArrayList<TempoEv>() }

        val scans = ArrayList<PartScan>()
        for ((pi, partEl) in partEls.withIndex()) {
            val id = partEl.getAttribute("id")
            val scan = PartScan(infos[id] ?: PartInfo(id, id, null, null, null, emptyMap()), count)
            scans.add(scan)
            scanPart(partEl, pi == 0, scan, ppq, count, lenContent, implicit,
                beatsAt, typeAt, fifthsAt, minorAt, flags, tempoEv)
        }

        // ---- the length of every measure, and where it starts once repeats are unrolled
        val nominal = IntArray(count) { (ppq * 4L * beatsAt[it] / typeAt[it]).toInt().coerceAtLeast(1) }
        val length = IntArray(count)
        val pad = IntArray(count)
        for (m in 0 until count) {
            val c = lenContent[m]
            length[m] = when {
                c > nominal[m] -> c
                m > 0 && implicit[m] && c > 0 -> c
                m == 0 && implicit[0] && c in 1 until nominal[0] -> nominal[0]
                else -> nominal[m]
            }
            if (m == 0 && implicit[0] && c in 1 until nominal[0]) pad[0] = nominal[0] - c
        }
        val order = RepeatPlan.order(flags.toList())
        val outStart = IntArray(order.size)
        var t = 0
        for ((k, m) in order.withIndex()) {
            outStart[k] = t
            t += length[m]
        }
        val total = t

        val timeSigs = ArrayList<TimeSignature>()
        val keySigs = ArrayList<KeySignature>()
        val bars = ArrayList<TabBar>()
        var prevMeter: Pair<Int, Int>? = null
        var prevKey: Pair<Int, Boolean>? = null
        for ((k, m) in order.withIndex()) {
            val meter = if (length[m] == nominal[m]) Pair(beatsAt[m], typeAt[m]) else meterFor(length[m], ppq)
            if (meter != prevMeter) timeSigs.add(TimeSignature(outStart[k], meter.first, meter.second))
            prevMeter = meter
            val key = Pair(fifthsAt[m], minorAt[m])
            if (key != prevKey) keySigs.add(KeySignature(outStart[k], key.first, key.second))
            prevKey = key
            bars.add(TabBar(outStart[k], length[m], meter.first, meter.second))
        }

        // tempo: what is in force at the start of each measure, in the order of the
        // file, so that a jump back to a measure restores the tempo it was played at
        val startTempo = arrayOfNulls<Int>(count)
        var cur: Int? = null
        for (m in 0 until count) {
            startTempo[m] = cur
            for (e in tempoEv[m].sortedBy { it.off }) cur = e.us
        }
        val tempos = ArrayList<TempoPoint>()
        var emitted: Int? = null
        for ((k, m) in order.withIndex()) {
            val st = startTempo[m]
            if (st != null && st != emitted) {
                tempos.add(TempoPoint(if (k == 0) 0 else outStart[k], st))
                emitted = st
            }
            for (e in tempoEv[m].sortedBy { it.off }) {
                val tick = if (m == 0 && e.off == 0) outStart[k] else outStart[k] + pad[m] + e.off
                if (e.us != emitted) {
                    tempos.add(TempoPoint(tick, e.us))
                    emitted = e.us
                }
            }
        }

        // ---- the parts: channels, notes, programs, controllers, tablature
        val notes = ArrayList<MidiNote>()
        val programs = ArrayList<ProgramChange>()
        val controllers = ArrayList<Controller>()
        val tabParts = ArrayList<TabPart>()
        var nextChannel = 0
        for (scan in scans) {
            val expanded = ArrayList<Pair<Int, RawNote>>()       // absolute tick, note
            for ((k, m) in order.withIndex()) {
                for (n in scan.notes[m]) expanded.add(Pair(outStart[k] + pad[m] + n.off, n))
            }
            if (expanded.isEmpty()) continue
            expanded.sortBy { it.first }

            val pitchedPart = expanded.any { !it.second.drum }
            var channel = -1
            if (pitchedPart) {
                if (nextChannel == Score.DRUM_CHANNEL) nextChannel++
                if (nextChannel > 15) continue                       // no sixteenth instrument to give
                channel = nextChannel++
            }

            // ties are merged on the unrolled timeline: a note held across a barline
            // or a repeat is one note to the synthesiser
            val open = HashMap<Int, IntArray>()                      // key -> [index in out, end tick]
            val out = ArrayList<MidiNote>()
            for ((tick, n) in expanded) {
                val ch = if (n.drum) Score.DRUM_CHANNEL else channel
                val slot = ch * 128 + n.key
                val held = open[slot]
                if (n.tieStop && held != null && held[1] == tick) {
                    val old = out[held[0]]
                    val end = tick + n.dur
                    out[held[0]] = MidiNote(old.tick, end - old.tick, old.channel, old.key, old.velocity)
                    if (n.tieStart) held[1] = end else open.remove(slot)
                } else {
                    out.add(MidiNote(tick, n.dur.coerceAtLeast(1), ch, n.key, n.vel))
                    if (n.tieStart) open[slot] = intArrayOf(out.size - 1, tick + n.dur) else open.remove(slot)
                }
            }
            // the same note written on two staves of one part is one note
            val seen = HashMap<Long, Int>()
            for (n in out) {
                val id = (n.tick.toLong() shl 16) or (n.channel * 128L + n.key)
                val prev = seen[id]
                if (prev == null) { seen[id] = notes.size; notes.add(n) }
                else if (n.durTicks > notes[prev].durTicks) notes[prev] = n
            }

            val info = scan.info
            val hasTab = pitchedPart && expanded.any { !it.second.drum && it.second.string > 0 }
            if (pitchedPart) {
                val program = info.program ?: if (hasTab) defaultProgram(scan, expanded) else 0
                programs.add(ProgramChange(0, channel, program, 0))
                info.volume?.let { controllers.add(Controller(0, channel, 7, it)) }
                info.pan?.let { controllers.add(Controller(0, channel, 10, it)) }
            }
            if (hasTab) buildTab(scan, channel, expanded, tabParts)
        }
        if (notes.isEmpty()) throw NotMusicXml("the file contains no notes")
        notes.sortBy { it.tick }

        val title = root.kid("work")?.text("work-title")?.takeIf { it.isNotEmpty() }
            ?: root.text("movement-title")?.takeIf { it.isNotEmpty() }

        return Score(
            ppq = ppq,
            notes = notes,
            tempos = tempos,
            timeSignatures = timeSigs,
            keySignatures = keySigs,
            programs = programs,
            controllers = controllers.sortedBy { it.tick },
            endTick = maxOf(total, notes.maxOf { it.endTick }),
            title = title,
            tab = if (tabParts.isEmpty()) null else TabScore(ppq, bars, tabParts)
        )
    }

    /** A bass has few strings and a low one: electric bass, otherwise a steel guitar. */
    private fun defaultProgram(scan: PartScan, notes: List<Pair<Int, RawNote>>): Int {
        val strings = notes.maxOf { it.second.string }
        val lowest = notes.filter { it.second.string > 0 }.minOf { it.second.key }
        return if (strings <= 5 && lowest < 40) 33 else 25
    }

    private val GUITAR = intArrayOf(64, 59, 55, 50, 45, 40, 35, 30)
    private val BASS = intArrayOf(43, 38, 33, 28, 23)

    private fun buildTab(scan: PartScan, channel: Int, expanded: List<Pair<Int, RawNote>>, into: MutableList<TabPart>) {
        val tabbed = expanded.filter { !it.second.drum && it.second.string > 0 && it.second.fret >= 0 }
        if (tabbed.isEmpty()) return
        val strings = maxOf(tabbed.maxOf { it.second.string }, scan.hintLines).coerceIn(1, 12)

        // the open strings are worked out from the notes themselves: pitch minus fret
        // is the same for every note on a string, whatever the file says about capos
        // or transposition, so this cannot disagree with what is played
        val open = IntArray(strings)
        for (s in 1..strings) {
            val votes = HashMap<Int, Int>()
            for ((_, n) in tabbed) if (n.string == s) votes.merge(n.key - n.fret, 1, Int::plus)
            val best = votes.maxByOrNull { it.value }?.key
            open[s - 1] = best
                ?: scan.hint[strings + 1 - s]
                ?: (if (strings <= 5 && tabbed.minOf { it.second.key } < 40) BASS else GUITAR)
                    .getOrElse(s - 1) { 40 - 5 * (s - 1) }
        }

        val seen = HashSet<Long>()
        val list = ArrayList<TabNote>()
        for ((tick, n) in tabbed) {
            val id = (tick.toLong() shl 24) or (n.string.toLong() shl 8) or n.fret.toLong()
            if (!seen.add(id)) continue
            list.add(TabNote(tick, n.dur.coerceAtLeast(1), n.string, n.fret, n.tieStop))
        }
        list.sortBy { it.tick }
        into.add(TabPart(channel, scan.info.name, open, list))
    }

    // ---- one part, measure by measure

    private fun scanPart(
        part: Element, first: Boolean, scan: PartScan, ppq: Int, count: Int,
        lenContent: IntArray, implicit: BooleanArray,
        beatsAt: IntArray, typeAt: IntArray, fifthsAt: IntArray, minorAt: BooleanArray,
        flags: Array<RepeatPlan.Flags>, tempoEv: Array<ArrayList<TempoEv>>
    ) {
        var div = 1
        var transpose = 0
        var vel = DEFAULT_VELOCITY
        var beats = 4
        var type = 4
        var fifths = 0
        var minor = false
        fun ticks(x: Int): Int = Math.round(x.toLong() * ppq.toDouble() / div).toInt()

        for ((m, meas) in part.kids("measure").withIndex()) {
            if (m >= count) break
            if (first && meas.getAttribute("implicit") == "yes") implicit[m] = true
            var cursor = 0
            var maxCursor = 0
            var lastOnset = 0

            fun sound(s: Element) {
                s.getAttribute("tempo").toDoubleOrNull()?.takeIf { it > 0 }?.let { bpm ->
                    tempoEv[m].add(TempoEv(ticks(cursor), Math.round(60_000_000.0 / bpm).toInt().coerceAtLeast(1)))
                }
                s.getAttribute("dynamics").toDoubleOrNull()?.let {
                    vel = Math.round(it * 0.9).toInt().coerceIn(1, 127)
                }
            }

            for (el in meas.all()) {
                when (el.tagName) {
                    "attributes" -> {
                        el.int("divisions")?.takeIf { it > 0 }?.let { div = it }
                        el.kid("key")?.let { k ->
                            fifths = k.int("fifths") ?: fifths
                            minor = k.text("mode") == "minor"
                        }
                        el.kid("time")?.let { tm -> val r = meterOf(tm, beats, type); beats = r.first; type = r.second }
                        el.kid("transpose")?.let { tr ->
                            transpose = (tr.int("chromatic") ?: 0) + 12 * (tr.int("octave-change") ?: 0)
                        }
                        for (sd in el.kids("staff-details")) {
                            val tunings = sd.kids("staff-tuning")
                            if (tunings.isNotEmpty() && scan.hint.isEmpty()) {
                                for (tn in tunings) {
                                    val line = tn.getAttribute("line").toIntOrNull() ?: continue
                                    val step = tn.text("tuning-step")?.firstOrNull() ?: continue
                                    val oct = tn.int("tuning-octave") ?: continue
                                    val alter = tn.text("tuning-alter")?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
                                    scan.hint[line] = (oct + 1) * 12 + (STEPS[step] ?: continue) + alter
                                }
                                scan.hintLines = maxOf(tunings.size, sd.int("staff-lines") ?: 0)
                            }
                        }
                    }
                    "sound" -> sound(el)
                    "direction" -> {
                        for (dt in el.kids("direction-type")) {
                            for (d in dt.kids("dynamics")) {
                                for (c in d.all()) DYNAMICS[c.tagName]?.let { vel = it }
                            }
                        }
                        el.kid("sound")?.let { sound(it) }
                    }
                    "backup" -> cursor = (cursor - (el.int("duration") ?: 0)).coerceAtLeast(0)
                    "forward" -> { cursor += el.int("duration") ?: 0; maxCursor = maxOf(maxCursor, cursor) }
                    "barline" -> if (first) barline(el, flags[m])
                    "note" -> {
                        val chord = el.kid("chord") != null
                        val dur = el.int("duration") ?: 0
                        val onset = if (chord) lastOnset else cursor
                        if (el.kid("grace") != null) continue          // no time of its own, and not played
                        if (!chord) lastOnset = cursor
                        if (el.kid("rest") == null && el.kid("cue") == null) {
                            addNote(el, scan, onset, dur, transpose, vel, ::ticks, m)
                        }
                        if (!chord) cursor += dur
                        maxCursor = maxOf(maxCursor, cursor)
                    }
                }
            }
            lenContent[m] = maxOf(lenContent[m], ticks(maxCursor))
            if (first) {
                beatsAt[m] = beats; typeAt[m] = type; fifthsAt[m] = fifths; minorAt[m] = minor
            }
        }
        // a first part shorter than the others still has a meter for the measures after it
        if (first) {
            val n = part.kids("measure").size
            for (m in n until count) { beatsAt[m] = beats; typeAt[m] = type; fifthsAt[m] = fifths; minorAt[m] = minor }
        }
    }

    private fun addNote(
        el: Element, scan: PartScan, onset: Int, dur: Int, transpose: Int, vel: Int,
        ticks: (Int) -> Int, measure: Int
    ) {
        var key: Int
        var drum = false
        val pitch = el.kid("pitch")
        if (pitch != null) {
            val step = pitch.text("step")?.firstOrNull() ?: return
            val oct = pitch.int("octave") ?: return
            val alter = pitch.text("alter")?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: 0
            key = (oct + 1) * 12 + (STEPS[step] ?: return) + alter + transpose
            if (key !in 0..127) return
        } else if (el.kid("unpitched") != null) {
            drum = true
            key = el.kid("instrument")?.getAttribute("id")?.let { scan.info.unpitched[it] } ?: 38
            key = key.coerceIn(0, 127)
        } else return

        var string = 0
        var fret = -1
        for (n in el.kids("notations")) {
            val tech = n.kid("technical") ?: continue
            tech.int("string")?.let { string = it }
            tech.int("fret")?.let { fret = it }
        }
        var tieStart = false
        var tieStop = false
        val ties = el.kids("tie")
        if (ties.isNotEmpty()) {
            for (t in ties) { if (t.getAttribute("type") == "start") tieStart = true; if (t.getAttribute("type") == "stop") tieStop = true }
        } else {
            for (n in el.kids("notations")) for (t in n.kids("tied")) {
                if (t.getAttribute("type") == "start") tieStart = true
                if (t.getAttribute("type") == "stop") tieStop = true
            }
        }
        val v = el.getAttribute("dynamics").toDoubleOrNull()?.let { Math.round(it * 0.9).toInt().coerceIn(1, 127) } ?: vel
        scan.notes[measure].add(
            RawNote(ticks(onset), ticks(dur).coerceAtLeast(1), key, drum, string, fret, tieStart, tieStop, v)
        )
    }

    private fun barline(el: Element, f: RepeatPlan.Flags) {
        el.kid("ending")?.let { e ->
            when (e.getAttribute("type")) {
                "start" -> f.endingStart = RepeatPlan.numbers(e.getAttribute("number"))
                "stop", "discontinue" -> f.endingStop = true
            }
        }
        el.kid("repeat")?.let { r ->
            when (r.getAttribute("direction")) {
                "forward" -> f.repeatStart = true
                // times is how often the section is played, two when it is not written
                "backward" -> {
                    val times = r.getAttribute("times").trim().toIntOrNull() ?: 2
                    f.repeatEnd = (times - 1).coerceIn(1, RepeatPlan.MAX_EXTRA)
                }
            }
        }
    }
}

/**
 * The order in which the measures of a score are played: repeats and voltas unrolled.
 *
 * A section between a forward repeat sign (or the start of the piece, or the end of the
 * last section played) and a backward repeat sign is played again, as many times as the
 * sign says; two by default. A volta is played on the passes it is numbered for, and
 * skipped on the others: the first ending on the first pass, the second ending on the
 * second, so the two endings never sound one after the other. With no repeat sign at
 * all a volta has only the first pass, so the first ending is kept and the others are
 * dropped, as it was before repeats were played.
 *
 * What comes out is a list of measure numbers in which a measure can appear several
 * times, and everything downstream (the sound, the bars of the staff and of the
 * tablature, the cursor, the tempo, the metre) is built from that list and nothing
 * else, so they cannot disagree about where the piece is. D.C., D.S., coda and fine
 * are not followed.
 *
 * A pure function of what each measure says about itself, so it is tested alone.
 */
object RepeatPlan {

    /** More extra passes than this is not a repeat, it is a damaged file. */
    const val MAX_EXTRA = 31

    /** The most measures a score is unrolled to, so that a damaged file cannot run away. */
    const val MAX_OUT = 20_000

    class Flags {
        /** A forward repeat sign opens a section in this measure. */
        var repeatStart = false

        /** A backward repeat sign closes a section at the end of this measure: how many EXTRA passes it asks for (1 = played twice). */
        var repeatEnd = 0

        /** The pass numbers of an ending that starts in this measure. */
        var endingStart: List<Int>? = null

        /** An ending stops at the end of this measure. */
        var endingStop = false
    }

    /** "1", "1, 2" or "1-3" as a list of pass numbers. */
    fun numbers(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (piece in text.split(',')) {
            val p = piece.trim()
            val dash = p.indexOf('-')
            if (dash > 0) {
                val a = p.substring(0, dash).trim().toIntOrNull()
                val b = p.substring(dash + 1).trim().toIntOrNull()
                if (a != null && b != null && b >= a && b - a < 50) for (i in a..b) out.add(i)
            } else p.toIntOrNull()?.let { out.add(it) }
        }
        return out
    }

    /** One open section: where it starts and which pass it is on. */
    private class Frame(val start: Int, var pass: Int = 1)

    fun order(ms: List<Flags>): List<Int> {
        val n = ms.size
        val out = ArrayList<Int>(n)
        val open = ArrayList<Frame>()          // sections opened by a forward sign, innermost last
        var base = Frame(0)                    // the section with no forward sign: from the last one closed
        // a section whose last pass has gone by while its endings were being skipped: it is
        // kept until the endings are over, because the ending that is played still has to
        // know which pass it is on
        var finished: Frame? = null
        var i = 0
        while (i < n && out.size < MAX_OUT) {
            val f = ms[i]
            if (finished != null && f.endingStart == null) {
                if (open.isNotEmpty() && open.last() === finished) open.removeAt(open.size - 1)
                base = Frame(i)
                finished = null
            }
            if (f.repeatStart && (open.isEmpty() || open.last().start != i)) open.add(Frame(i))
            val frame = finished ?: open.lastOrNull() ?: base

            val ending = f.endingStart
            if (ending != null && ending.isNotEmpty() && frame.pass !in ending) {
                // an ending for another pass: skip all of it, up to its stop mark or just
                // before the next ending or the next section
                var k = i
                var closes = f.repeatEnd > 0 && frame.pass > f.repeatEnd
                while (k < n - 1 && !ms[k].endingStop && ms[k + 1].endingStart == null && !ms[k + 1].repeatStart) {
                    k++
                    if (ms[k].repeatEnd > 0 && frame.pass > ms[k].repeatEnd) closes = true
                }
                if (closes) finished = frame
                i = k + 1
                continue
            }

            out.add(i)
            if (f.repeatEnd > 0) {
                if (frame.pass <= f.repeatEnd) {
                    frame.pass++
                    i = frame.start
                    continue
                }
                // the last pass: the section is done, and the next one starts after it
                if (open.isNotEmpty()) open.removeAt(open.size - 1)
                base = Frame(i + 1)
            }
            i++
        }
        return out
    }
}
