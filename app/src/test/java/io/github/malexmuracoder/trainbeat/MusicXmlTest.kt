package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** MusicXML read into a Score, checked on small scores written out by hand. */
class MusicXmlTest {

    private val HEAD = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
"""

    private fun score(partList: String, vararg parts: String): String =
        HEAD + "<score-partwise version=\"4.0\"><work><work-title>Test</work-title></work>" +
            "<part-list>$partList</part-list>" +
            parts.withIndex().joinToString("") { (i, p) -> "<part id=\"P${i + 1}\">$p</part>" } +
            "</score-partwise>"

    private fun sp(id: String, name: String, extra: String = "") =
        "<score-part id=\"$id\"><part-name>$name</part-name>$extra</score-part>"

    private fun note(step: Char, oct: Int, dur: Int, tech: String = "", extra: String = "") =
        "<note>$extra<pitch><step>$step</step><octave>$oct</octave></pitch><duration>$dur</duration>" +
            (if (tech.isEmpty()) "" else "<notations><technical>$tech</technical></notations>") + "</note>"

    private fun sf(string: Int, fret: Int) = "<string>$string</string><fret>$fret</fret>"
    private fun rest(dur: Int) = "<note><rest/><duration>$dur</duration></note>"
    private fun attrs(div: Int, extra: String = "") =
        "<attributes><divisions>$div</divisions><key><fifths>0</fifths></key>" +
            "<time><beats>4</beats><beat-type>4</beat-type></time>$extra</attributes>"
    private fun measure(n: Int, body: String, attr: String = "") =
        "<measure number=\"$n\"$attr>$body</measure>"

    private fun parse(xml: String) = MusicXml.parse(xml.toByteArray(Charsets.UTF_8))

    @Test
    fun aGuitarPartBecomesNotesAndATablature() {
        val xml = score(
            sp("P1", "Guitar", "<midi-instrument id=\"I1\"><midi-program>26</midi-program>" +
                "<volume>50</volume><pan>0</pan></midi-instrument>"),
            measure(1, attrs(2) + "<direction><sound tempo=\"100\"/></direction>" +
                note('E', 2, 2, sf(6, 0)) + note('A', 2, 2, sf(5, 0)) +
                note('D', 3, 2, sf(4, 0)) + note('G', 3, 2, sf(3, 0))) +
                measure(2, note('E', 4, 8, sf(1, 0)))
        )
        val s = parse(xml)
        assertEquals(96, s.ppq)
        assertEquals("Test", s.title)
        assertEquals(5, s.notes.size)
        assertEquals(listOf(0, 96, 192, 288, 384), s.notes.map { it.tick })
        assertEquals(listOf(40, 45, 50, 55, 64), s.notes.map { it.key })
        assertEquals(96, s.notes[1].durTicks)
        assertEquals(384, s.notes[4].durTicks)
        assertEquals(768, s.endTick)
        assertEquals(1, s.timeSignatures.size)
        assertEquals(4, s.timeSignatures[0].numerator)
        assertEquals(600_000, s.tempos[0].usPerQuarter)
        assertEquals(25, s.programs[0].program)
        assertEquals(0, s.programs[0].channel)
        assertEquals(64, s.controllers.first { it.number == 7 }.value)
        assertEquals(64, s.controllers.first { it.number == 10 }.value)

        val tab = s.tab!!
        assertEquals(2, tab.bars.size)
        assertEquals(384, tab.bars[1].startTick)
        val part = tab.partFor(0)!!
        assertEquals(listOf(64, 59, 55, 50, 45, 40), part.open.toList())
        assertEquals(5, part.notes.size)
        assertEquals(6, part.notes[0].string)
    }

    @Test
    fun theStringsAreTunedByTheNotesThemselves() {
        // a capo on the second fret: the file writes fret 0 and the pitch two semitones up
        val xml = score(sp("P1", "Guitar"),
            measure(1, attrs(1) + note('F', 2, 4, sf(6, 0))))
        val open = parse(xml).tab!!.partFor(0)!!.open
        assertEquals(41, open[5])
    }

    @Test
    fun aPartWithoutStringsHasNoTablature() {
        val s = parse(score(sp("P1", "Piano"), measure(1, attrs(1) + note('C', 4, 4))))
        assertNull(s.tab)
        assertEquals(0, s.programs[0].program)
    }

    @Test
    fun aPickUpMeasureIsPaddedInFront() {
        val s = parse(score(sp("P1", "Melody"),
            measure(1, attrs(1) + note('C', 4, 1), " implicit=\"yes\"") +
                measure(2, note('D', 4, 4))))
        assertEquals(listOf(288, 384), s.notes.map { it.tick })
        assertEquals(1, s.timeSignatures.size)
        assertEquals(768, s.endTick)
    }

    @Test
    fun aShortMeasureIsAsLongAsTheSignatureUnlessItIsImplicit() {
        val plain = parse(score(sp("P1", "M"),
            measure(1, attrs(1) + note('C', 4, 4)) +
                measure(2, note('D', 4, 2)) +
                measure(3, note('E', 4, 4))))
        assertEquals(listOf(0, 384, 768), plain.notes.map { it.tick })
        assertEquals(1, plain.timeSignatures.size)

        val irregular = parse(score(sp("P1", "M"),
            measure(1, attrs(1) + note('C', 4, 4)) +
                measure(2, note('D', 4, 2), " implicit=\"yes\"") +
                measure(3, note('E', 4, 4))))
        assertEquals(listOf(0, 384, 576), irregular.notes.map { it.tick })
        assertEquals(listOf(0, 384, 576), irregular.timeSignatures.map { it.tick })
        assertEquals(2, irregular.timeSignatures[1].numerator)
        assertEquals(4, irregular.timeSignatures[1].denominator)
    }

    @Test
    fun tiesAreOneNoteToTheSynthesiserAndAStrikeAndAContinuationInTheTablature() {
        val xml = score(sp("P1", "Guitar"),
            measure(1, attrs(1) + rest(3) +
                note('E', 4, 1, sf(1, 0), "<tie type=\"start\"/>")) +
                measure(2, note('E', 4, 4, sf(1, 0), "<tie type=\"stop\"/>")))
        val s = parse(xml)
        assertEquals(1, s.notes.size)
        assertEquals(288, s.notes[0].tick)
        assertEquals(96 + 384, s.notes[0].durTicks)
        val t = s.tab!!.partFor(0)!!.notes
        assertEquals(2, t.size)
        assertEquals(false, t[0].tiedFrom)
        assertEquals(true, t[1].tiedFrom)
    }

    @Test
    fun aRepeatedSectionIsPlayedTwiceAndAppearsTwiceInTheBars() {
        val xml = score(sp("P1", "Guitar"),
            measure(1, "<barline location=\"left\"><repeat direction=\"forward\"/></barline>" +
                attrs(1) + note('C', 4, 4, sf(2, 1))) +
                measure(2, note('D', 4, 4, sf(2, 3)) +
                    "<barline location=\"right\"><repeat direction=\"backward\"/></barline>"))
        val s = parse(xml)
        assertEquals(listOf(0, 384, 768, 1152), s.notes.map { it.tick })
        assertEquals(listOf(60, 62, 60, 62), s.notes.map { it.key })
        assertEquals(1536, s.endTick)
        val tab = s.tab!!
        assertEquals(listOf(0, 384, 768, 1152), tab.bars.map { it.startTick })
        assertEquals(4, tab.partFor(0)!!.notes.size)
    }

    @Test
    fun eachEndingOfAVoltaIsPlayedOnItsOwnPass() {
        val xml = score(sp("P1", "M"),
            measure(1, attrs(1) + note('C', 4, 4)) +
                measure(2, "<barline location=\"left\"><ending number=\"1\" type=\"start\"/></barline>" +
                    note('D', 4, 4) +
                    "<barline location=\"right\"><ending number=\"1\" type=\"stop\"/>" +
                    "<repeat direction=\"backward\"/></barline>") +
                measure(3, "<barline location=\"left\"><ending number=\"2\" type=\"start\"/></barline>" +
                    note('E', 4, 4) +
                    "<barline location=\"right\"><ending number=\"2\" type=\"stop\"/></barline>") +
                measure(4, note('F', 4, 4)))
        val s = parse(xml)
        assertEquals(listOf(60, 62, 60, 64, 65), s.notes.map { it.key })
        assertEquals(listOf(0, 384, 768, 1152, 1536), s.notes.map { it.tick })
        assertEquals(1920, s.endTick)
    }

    @Test
    fun aRepeatCanAskForMorePasses() {
        val xml = score(sp("P1", "M"),
            measure(1, "<barline location=\"left\"><repeat direction=\"forward\"/></barline>" +
                attrs(1) + note('C', 4, 4)) +
                measure(2, note('D', 4, 4) +
                    "<barline location=\"right\"><repeat direction=\"backward\" times=\"3\"/></barline>") +
                measure(3, note('E', 4, 4)))
        val s = parse(xml)
        assertEquals(listOf(60, 62, 60, 62, 60, 62, 64), s.notes.map { it.key })
        assertEquals(7 * 384, s.endTick)
    }

    @Test
    fun theTempoOfEachMeasureIsTheOneWritten() {
        val xml = score(sp("P1", "M"),
            measure(1, attrs(1) + "<direction><sound tempo=\"100\"/></direction>" + note('C', 4, 4)) +
                measure(2, "<direction><sound tempo=\"200\"/></direction>" + note('D', 4, 4)))
        val s = parse(xml)
        assertEquals(listOf(0, 384), s.tempos.map { it.tick })
        assertEquals(listOf(600_000, 300_000), s.tempos.map { it.usPerQuarter })
    }

    @Test
    fun chordsAndVoicesLandWhereTheFileSaysTheyDo() {
        val xml = score(sp("P1", "Piano"),
            measure(1, attrs(1) +
                note('C', 4, 2) + note('E', 4, 2, extra = "<chord/>") + note('G', 4, 2) +
                "<backup><duration>4</duration></backup>" +
                note('C', 3, 4)))
        val s = parse(xml)
        assertEquals(4, s.notes.size)
        val byKey = s.notes.associateBy { it.key }
        assertEquals(0, byKey[60]!!.tick)
        assertEquals(0, byKey[64]!!.tick)
        assertEquals(192, byKey[67]!!.tick)
        assertEquals(0, byKey[48]!!.tick)
        assertEquals(384, byKey[48]!!.durTicks)
        assertEquals(384, s.endTick)
    }

    @Test
    fun aTransposingPartSoundsWhereTheFileSaysItDoes() {
        val xml = score(sp("P1", "Guitar"),
            measure(1, attrs(1, "<transpose><diatonic>0</diatonic><chromatic>0</chromatic>" +
                "<octave-change>-1</octave-change></transpose>") + note('E', 4, 4)))
        assertEquals(52, parse(xml).notes[0].key)
    }

    @Test
    fun percussionGoesToTheDrumChannel() {
        val xml = score(
            sp("P1", "Drums", "<score-instrument id=\"P1-I36\"/><midi-instrument id=\"P1-I36\">" +
                "<midi-channel>10</midi-channel><midi-unpitched>36</midi-unpitched></midi-instrument>"),
            measure(1, attrs(1) + "<note><unpitched><display-step>C</display-step>" +
                "<display-octave>4</display-octave></unpitched><duration>4</duration>" +
                "<instrument id=\"P1-I36\"/></note>"))
        val s = parse(xml)
        assertEquals(Score.DRUM_CHANNEL, s.notes[0].channel)
        assertEquals(35, s.notes[0].key)
    }

    @Test
    fun theSameNoteWrittenOnTwoStavesIsOneNote() {
        val xml = score(sp("P1", "Guitar"),
            measure(1, attrs(1) + note('C', 4, 4) + "<backup><duration>4</duration></backup>" +
                note('C', 4, 4, sf(2, 1))))
        val s = parse(xml)
        assertEquals(1, s.notes.size)
        assertEquals(1, s.tab!!.partFor(0)!!.notes.size)
    }

    @Test
    fun twoPartsGetTwoChannelsAndNeverTheDrumOne() {
        val parts = Array(11) { measure(1, attrs(1) + note('C', 4, 4)) }
        val xml = score((1..11).joinToString("") { sp("P$it", "Part $it") }, *parts)
        val s = parse(xml)
        val channels = s.notes.map { it.channel }.sorted()
        assertEquals((0..8).toList() + listOf(10, 11), channels)
    }

    @Test
    fun aCompressedScoreIsReadThroughItsContainer() {
        val inner = score(sp("P1", "M"), measure(1, attrs(1) + note('C', 4, 4)))
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<container><rootfiles><rootfile full-path="score/main.xml"/></rootfiles></container>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("score/main.xml"))
            z.write(inner.toByteArray())
            z.closeEntry()
        }
        assertEquals(1, MusicXml.parse(bos.toByteArray()).notes.size)
    }

    @Test
    fun whatIsNotAScoreIsRefusedWithAReason() {
        fun refused(xml: String, part: String) {
            try {
                MusicXml.parse(xml.toByteArray())
                fail("accepted: $part")
            } catch (e: NotMusicXml) {
                assertTrue(e.message!!, e.message!!.contains(part))
            }
        }
        refused("<resources><string name=\"a\">b</string></resources>", "not a MusicXML score")
        refused("<score-timewise version=\"4.0\"/>", "timewise")
        refused("<!DOCTYPE score-partwise [<!ENTITY a \"b\">]><score-partwise><part-list/></score-partwise>", "entities")
        refused("<score-partwise><part-list/>", "well-formed")
        refused(score(sp("P1", "M"), measure(1, attrs(1) + rest(4))), "no notes")
    }

    @Test
    fun theHeaderReferringToATheDtdIsNeverFetched() {
        // the DOCTYPE of every test above points at a web address; reaching this
        // line at all, offline and quickly, is the check
        assertNotNull(parse(score(sp("P1", "M"), measure(1, attrs(1) + note('C', 4, 4)))))
    }

    @Test
    fun theStaffEngineReadsAScoreThatCameFromMusicXml() {
        val s = parse(score(sp("P1", "Melody"),
            measure(1, attrs(1) + note('C', 4, 1), " implicit=\"yes\"") +
                measure(2, note('D', 4, 2) + note('E', 4, 2)) +
                measure(3, note('F', 4, 4))))
        val e = Notation.engrave(s, 0)!!
        assertEquals(3, e.bars.size)
        assertEquals(384, e.bars[1].startTick)
        assertEquals(16, e.bars[1].units)
    }

    @Test
    fun theMeterOfAnIrregularLengthIsWrittenInThePlainestTerms() {
        assertEquals(Pair(2, 4), MusicXml.meterFor(192, 96))
        assertEquals(Pair(3, 8), MusicXml.meterFor(144, 96))
        assertEquals(Pair(1, 4), MusicXml.meterFor(96, 96))
        assertEquals(Pair(5, 16), MusicXml.meterFor(120, 96))
    }
}
