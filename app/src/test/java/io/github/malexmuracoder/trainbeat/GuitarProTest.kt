package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * Guitar Pro 3, 4 and 5 files. The three samples at the bottom hold the same little
 * piece, written once in each version, so that what is checked here is also that the
 * three layouts of the format give the same music.
 *
 * The piece, in a guitar with a capo on the second fret and a drum track:
 *   measure 1  (repeat opens)   fret 5 on string 3; fret 3 on string 2 held by a tie
 *                               over two beats; a dead note on string 6; a rest
 *   measure 2  (first ending)   string 1 open together with fret 2 on string 4, a half note;
 *                               the repeat closes here, played twice
 *   measure 3  (second ending)  fret 12 on string 1 - played on the second pass only
 *   measure 4  (3/4, tempo 140) fret 7 on string 5, then three eighth-note triplets
 *   drums      bass drum then snare in measure 1
 * Played, the measures run 1, 2, 1, 3, 4: five bars, the third one the repeat of the first.
 * The samples were written with a library that writes the format, from this
 * description, and are small enough to keep here.
 */
class GuitarProTest {

    private fun samples(): List<Pair<String, ByteArray>> = listOf(
        "3.00" to decode(GP3),
        "4.06" to decode(GP4),
        "5.10" to decode(GP5)
    )

    private fun decode(s: String) = Base64.getDecoder().decode(s)

    private fun everyVersion(check: (String, Score) -> Unit) {
        for ((name, bytes) in samples()) check(name, GuitarPro.parse(bytes))
    }

    @Test
    fun theGuitarIsPlayedAsWritten() = everyVersion { v, s ->
        val got = s.notes.filter { it.channel == 0 }.map { Triple(it.tick, it.key, it.durTicks) }.toSet()
        // key = open string + capo (2) + fret
        val want = setOf(
            Triple(0, 62, 960),          // string 3 fret 5
            Triple(960, 64, 1920),       // string 2 fret 3, and its tie: one note two beats long
            Triple(3840, 66, 1920),      // string 1 open
            Triple(3840, 54, 1920),      // string 4 fret 2
            Triple(7680, 62, 960),       // the repeat: measure 1 again
            Triple(8640, 64, 1920),
            Triple(15360, 54, 960),      // string 5 fret 7
            Triple(16320, 54, 320),      // the triplet: three in the time of two
            Triple(16640, 55, 320),
            Triple(16960, 56, 320)
        )
        // the second ending, string 1 fret 12, is the one other note, in the fourth bar
        assertEquals("version $v", want, got.filter { it.first != 11520 }.toSet())
        assertEquals("version $v", 1, s.notes.count { it.channel == 0 && it.tick == 11520 && it.key == 78 })
        assertEquals("version $v", 11, s.notes.count { it.channel == 0 })
    }

    @Test
    fun theDrumsAreOnTheDrumChannel() = everyVersion { v, s ->
        val drums = s.notes.filter { it.channel == Score.DRUM_CHANNEL }.map { Pair(it.tick, it.key) }
        assertEquals("version $v", listOf(Pair(0, 36), Pair(960, 38), Pair(7680, 36), Pair(8640, 38)), drums)
        assertTrue(s.programs.none { it.channel == Score.DRUM_CHANNEL })
    }

    @Test
    fun theRepeatIsPlayedAndEachEndingOnItsOwnPass() = everyVersion { v, s ->
        // fret 12 of the second ending is 66 + 12 = 78: heard once, after the second pass
        assertEquals("version $v", listOf(11520), s.notes.filter { it.key == 78 }.map { it.tick })
        // five bars: 1, 2, 1, 3 in 4/4 and then 4 in 3/4
        assertEquals("version $v", 4 * 3840 + 2880, s.endTick)
        assertEquals(listOf(Pair(0, 4), Pair(15360, 3)), s.timeSignatures.map { Pair(it.tick, it.numerator) })
    }

    @Test
    fun theTempoAndItsChangeAreFollowed() = everyVersion { v, s ->
        assertEquals("version $v", 2, s.tempos.size)
        assertEquals(0, s.tempos[0].tick)
        assertEquals(100.0, s.tempos[0].bpm, 0.01)
        assertEquals(15360, s.tempos[1].tick)
        assertEquals(140.0, s.tempos[1].bpm, 0.01)
    }

    @Test
    fun theInstrumentVolumeAndTitleComeFromTheFile() = everyVersion { v, s ->
        assertEquals("Test song", s.title)
        val program = s.programs.single { it.channel == 0 }
        assertEquals(25, program.program)
        assertEquals(0, program.tick)
        assertTrue(s.controllers.any { it.channel == 0 && it.number == 7 })
        assertTrue(s.controllers.any { it.channel == 0 && it.number == 10 })
        assertTrue("version $v", s.notes.filter { it.channel == 0 }.all { it.velocity == 95 })
    }

    @Test
    fun theTabletureSaysStringAndFret() = everyVersion { v, s ->
        val tab = s.tab
        assertNotNull(tab)
        assertEquals("version $v", 1, tab!!.parts.size)          // the drums have none
        val part = tab.parts[0]
        assertEquals(0, part.channel)
        assertEquals("Guitar", part.name)
        // the open strings include the capo, so that pitch = open string + fret
        assertEquals(listOf(66, 61, 57, 52, 47, 42), part.open.toList())
        val got = part.notes.map { listOf(it.tick, it.string, it.fret, if (it.tiedFrom) 1 else 0) }.toSet()
        val want = setOf(
            listOf(0, 3, 5, 0),
            listOf(960, 2, 3, 0),
            listOf(1920, 2, 3, 1),         // the tie is shown, with the fret it holds
            listOf(3840, 1, 0, 0),
            listOf(3840, 4, 2, 0),
            listOf(7680, 3, 5, 0),         // the repeat: measure 1 again
            listOf(8640, 2, 3, 0),
            listOf(9600, 2, 3, 1),
            listOf(11520, 1, 12, 0),       // the second ending
            listOf(15360, 5, 7, 0),
            listOf(16320, 5, 7, 0),
            listOf(16640, 5, 8, 0),
            listOf(16960, 5, 9, 0)
        )
        assertEquals("version $v", want, got)
        // and the bars are those of the music that is played, the repeated one twice
        assertEquals(listOf(0, 3840, 7680, 11520, 15360), tab.bars.map { it.startTick })
        assertEquals(listOf(4, 4, 4, 4, 3), tab.bars.map { it.numerator })
    }

    @Test
    fun theTabletureEngravesWithTheExistingLayout() = everyVersion { v, s ->
        val e = TabLayout.engrave(s.tab!!, 0)
        assertNotNull(e)
        assertEquals(6, e!!.strings)
        assertEquals("version $v", listOf(3, 1, 3, 1, 4), e.bars.map { it.figures.size })
        // a transposition moves the notes, string and fret, and keeps the figures
        val up = TabLayout.engrave(s.tab!!, 0, 3)
        assertNotNull(up)
        assertEquals(listOf(3, 1, 3, 1, 4), up!!.bars.map { it.figures.size })
    }

    @Test
    fun theNamesAndTheStartOfTheFileAreRecognised() {
        assertTrue(GuitarPro.isGuitarProName("song.gp3"))
        assertTrue(GuitarPro.isGuitarProName("SONG.GP5"))
        assertFalse(GuitarPro.isGuitarProName("song.gp"))      // Guitar Pro 7: not read
        assertFalse(GuitarPro.isGuitarProName("song.mid"))
        for ((_, bytes) in samples()) assertTrue(GuitarPro.looksLikeGuitarPro(bytes))
        assertFalse(GuitarPro.looksLikeGuitarPro(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())))
        assertFalse(GuitarPro.looksLikeGuitarPro(ByteArray(0)))
    }

    private fun refused(bytes: ByteArray): String =
        try {
            GuitarPro.parse(bytes)
            fail("should have been refused")
            ""
        } catch (e: NotGuitarPro) {
            e.message ?: ""
        }

    @Test
    fun whatIsNotGuitarProIsRefusedWithAReason() {
        assertTrue(refused(ByteArray(0)).isNotEmpty())
        assertTrue(refused("<?xml version=\"1.0\"?><score-partwise/>".toByteArray()).isNotEmpty())
        assertTrue(refused(ByteArray(2000) { (it * 31).toByte() }).isNotEmpty())
        // Guitar Pro 6 and 7 are another, packed format: a message that says so
        assertTrue(refused("BCFZ".toByteArray() + ByteArray(100)).contains("6 or 7"))
        assertTrue(refused("PK".toByteArray() + ByteArray(100)).contains("6 or 7"))
    }

    @Test
    fun aVersionThatIsNotKnownIsRefused() {
        val bytes = decode(GP5).copyOf()
        // "v5.10" -> "v9.10"
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("v5.10")
        assertTrue(at > 0)
        bytes[at + 1] = '9'.code.toByte()
        assertTrue(refused(bytes).contains("version"))
    }

    @Test
    fun aTruncatedFileIsRefusedNeverCrashes() {
        for ((_, bytes) in samples()) {
            var n = 0
            while (n < bytes.size - 1) {
                // every length, in steps: either refused, or (the last lines missing) readable
                try { GuitarPro.parse(bytes.copyOf(n)) } catch (_: NotGuitarPro) {}
                n += 7
            }
        }
    }

    @Test
    fun aDamagedFileGivesAScoreOrARefusalAndNothingElse() {
        val rnd = Random(7)
        for ((name, original) in samples()) {
            repeat(300) {
                val b = original.copyOf()
                repeat(1 + rnd.nextInt(6)) { b[30 + rnd.nextInt(b.size - 30)] = rnd.nextInt(256).toByte() }
                try {
                    GuitarPro.parse(b)
                } catch (_: NotGuitarPro) {
                } catch (t: Throwable) {
                    fail("version $name: ${t.javaClass.name}: ${t.message}")
                }
            }
        }
    }

    private companion object {
        const val GP3 =
        "GEZJQ0hJRVIgR1VJVEFSIFBSTyB2My4wMAAAAAAAAAoAAAAJVGVzdCBzb25nAQAAAAABAAAAAAEAAAAAAQAAAAABAAAAAAEAAAAA" +
        "AQAAAAAAAAAAAGQAAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAA" +
        "AAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAAGQAAAA0IAAAAAAAA" +
        "GQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAA" +
        "AA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0I" +
        "AAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAA" +
        "AAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA" +
        "GQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAAGQAA" +
        "AA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0I" +
        "AAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAA" +
        "AAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA" +
        "GQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAABAAAAAIAAAAHBAQYAQEQAgEDAAZHdWl0YXIAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAABgAAAEAAAAA7AAAANwAAADIAAAAtAAAAKAAAAAAAAAABAAAAAQAAAAEAAAAYAAAAAgAAAP8A" +
        "AAABBURydW1zAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGAAAAQAAAADsAAAA3AAAAMgAAAC0AAAAoAAAAAAAA" +
        "AAEAAAAKAAAACgAAABgAAAAAAAAA/wAAAAQAAAAAABAgAQUAACAgAQMAACAgAgAAAAIgAwACAAAAAABAIAEkAABAIAEmAgAAAAD/" +
        "SCABACABAkAC/wAAAAAAAgAAAAD/QCABDEAC/wAAAAAABQAAABAA/////////4wAAAAABCABByABAwAAAAQgAQcgAQMAAAAEIAEI" +
        "IAEDAAAABCABCUACAAAAAAAAAAAAAA=="

        const val GP4 =
        "GEZJQ0hJRVIgR1VJVEFSIFBSTyB2NC4wNgAAAAAAAAoAAAAJVGVzdCBzb25nAQAAAAABAAAAAAEAAAAAAQAAAAABAAAAAAEAAAAA" +
        "AQAAAAAAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAEAAAAAAAAAZAAAAAAAAAAAGQAAAA0IAAAAAAAA" +
        "GQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAA" +
        "AA0IAAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0I" +
        "AAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAA" +
        "AAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAA" +
        "GQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAA" +
        "AA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0I" +
        "AAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA/////w0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAA" +
        "AAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA" +
        "GQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAA////" +
        "/w0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0IAAAAAAAAGQAAAA0I" +
        "AAAAAAAABAAAAAIAAABHBAQAABgBARACAQMABkd1aXRhcgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGAAAAQAAA" +
        "ADsAAAA3AAAAMgAAAC0AAAAoAAAAAAAAAAEAAAABAAAAAQAAABgAAAACAAAA/wAAAAEFRHJ1bXMAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAYAAABAAAAAOwAAADcAAAAyAAAALQAAACgAAAAAAAAAAQAAAAoAAAAKAAAAGAAAAAAAAAD/AAAABAAA" +
        "AAAAECABBQAAICABAwAAICACAAAAAiADAAIAAAAAAEAgASQAAEAgASYCAAAAAP9IIAEAIAECQAL/AAAAAAACAAAAAP9AIAEMQAL/" +
        "AAAAAAAFAAAAEAD/////////jAAAAAAABCABByABAwAAAAQgAQcgAQMAAAAEIAEIIAEDAAAABCABCUACAAAAAAAA"

        const val GP5 =
        "GEZJQ0hJRVIgR1VJVEFSIFBSTyB2NS4xMAAAAAAAAAoAAAAJVGVzdCBzb25nAQAAAAABAAAAAAEAAAAAAQAAAAABAAAAAAEAAAAA" +
        "AQAAAAABAAAAAAAAAAAAAAAAAQAAAAAAAAABAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAABAAAAAAAAAGQAAAAAAAAAAAAAAAAAAAAA" +
        "AADSAAAAKQEAAAoAAAAKAAAADwAAAAoAAABkAAAA/wEIAAAAByV0aXRsZSULAAAACiVzdWJ0aXRsZSUJAAAACCVhcnRpc3QlCAAA" +
        "AAclYWxidW0lEQAAABBXb3JkcyBieSAld29yZHMlEQAAABBNdXNpYyBieSAlbXVzaWMlHgAAAB1Xb3JkcyAmIE11c2ljIGJ5ICVX" +
        "T1JEU01VU0lDJRYAAAAVQ29weXJpZ2h0ICVjb3B5cmlnaHQlNgAAADVBbGwgUmlnaHRzIFJlc2VydmVkIC0gSW50ZXJuYXRpb25h" +
        "bCBDb3B5cmlnaHQgU2VjdXJlZA0AAAAMUGFnZSAlTiUvJVAlCQAAAAhNb2RlcmF0ZWQAAAAAAAAAAAAZAAAADQgAAAAAAAAZAAAA" +
        "DQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgA" +
        "AAAAAAAZAAAADQgAAAAAAAD/////DQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAA" +
        "AAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZ" +
        "AAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAD/////DQgAAAAAAAAZAAAA" +
        "DQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgA" +
        "AAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAA" +
        "AAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAD/////DQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZ" +
        "AAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAA" +
        "DQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAD/////DQgA" +
        "AAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAAAAAZAAAADQgAAAAA" +
        "AAD//////////////////////////////////////////////////wAAAAAEAAAAAgAAAEcEBAAAAgICAgAAABgCAQAAEAIAAAED" +
        "AgICAgAAAAgGR3VpdGFyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYAAABAAAAAOwAAADcAAAAyAAAALQAAACgA" +
        "AAAAAAAAAQAAAAEAAAABAAAAGAAAAAIAAAD/AAAAQwAAAAAAAAAAAAAAAGQAAAAAAAAAAAAAAAAAAAD/////////////////////" +
        "AAAAAAEAAAAAAQAAAAAJBURydW1zAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGAAAAQAAAADsAAAA3AAAAMgAA" +
        "AC0AAAAoAAAAAAAAAAEAAAAKAAAACgAAABgAAAAAAAAA/wAAAEMAAAAAAAAAAAAAAABkAAAAAAAAAAAAAAAAAAAA////////////" +
        "/////////wAAAAABAAAAAAEAAAAAAAQAAAAAABAgAQUAAAAAACAgAQMAAAAAACAgAgAAAAAAAAIgAwAAAAAAAAAAAAIAAAAAAEAg" +
        "ASQAAAAAAEAgASYAAAAAAAAAAAIAAAAA/0ggAQAAIAECAAAAQAL/AAAAAAAAAAAAAAAAAAAAAAACAAAAAP9AIAEMAAAAQAL/AAAA" +
        "AAAAAAAAAAAAAAAAAAAFAAAAEAD//////////////////////////////wEAAAAAjAAAAAABAP8BAAAAAAEAAAAABCABBwAAACAB" +
        "AwAAAAQgAQcAAAAgAQMAAAAEIAEIAAAAIAEDAAAABCABCQAAAEACAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
