package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table of what is known about the loops that ship. */
class BuiltInIndexTest {

    private val text = "# comment\n" +
        "\n" +
        "MIDI A.mid\tAm\t\t\t\t\t\n" +
        "MP3 B.mp3\tG#m\t119.97\t16039\t44100\t2\t32\n" +
        "NOKEY.wav\t\t100\t1000\t44100\t2\t8\n"

    @Test
    fun linesAreReadAndCommentsIgnored() {
        val m = BuiltInIndex.parse(text)
        assertEquals(setOf("MIDI A.mid", "MP3 B.mp3", "NOKEY.wav"), m.keys)
        val b = m["MP3 B.mp3"]!!
        assertEquals("G#m", b.key)
        assertEquals(119.97f, b.bpm, 0.001f)
        assertEquals(16039L, b.durationMs)
        assertEquals(32, b.beats)
    }

    @Test
    fun aScoreLineIsNotAnAudioLine() {
        val m = BuiltInIndex.parse(text)
        assertTrue(!m["MIDI A.mid"]!!.isAudioLine)
        assertTrue(m["MP3 B.mp3"]!!.isAudioLine)
        assertNull(BuiltInIndex.audioRecord(m["MIDI A.mid"]!!, 10, "file:///x"))
    }

    @Test
    fun anAudioLineBecomesALibraryEntryWithTheAuthorsKey() {
        val r = BuiltInIndex.audioRecord(BuiltInIndex.parse(text)["MP3 B.mp3"]!!, 256878, "file:///x/MP3 B.mp3")!!
        assertEquals(TrackRecord.KIND_AUDIO, r.kind)
        assertEquals(256878L, r.size)
        assertEquals(8, r.bars)
        assertEquals(0, r.barsRemainder)
        assertTrue(r.analysed)
        assertEquals(Analyzer.keyIndex("G#m"), r.effectiveKeyIndex)
        assertEquals(1f, r.p("G#m"), 0.0001f)
        assertEquals(0f, r.p("Am"), 0.0001f)
    }

    @Test
    fun beatsThatDoNotFillABarLeaveARemainder() {
        val row = BuiltInIndex.parse("X.mp3\tC\t90\t1000\t44100\t2\t30\n")["X.mp3"]!!
        val r = BuiltInIndex.audioRecord(row, 1, "u")!!
        assertEquals(7, r.bars)
        assertEquals(2, r.barsRemainder)
    }

    @Test
    fun aKeyThatIsNotOneIsNoKey() {
        assertNotNull(BuiltInIndex.oneHot("Em"))
        assertNull(BuiltInIndex.oneHot("Ab m"))
        assertNull(BuiltInIndex.oneHot(""))
    }
}
