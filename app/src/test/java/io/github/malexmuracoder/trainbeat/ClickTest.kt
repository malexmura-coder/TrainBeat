package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The count-in, checked as arithmetic: four clicks land exactly one beat apart,
 * and a tempo or format that makes no sense gives nothing rather than garbage.
 */
class ClickTest {

    private fun isSilent(buf: ShortArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (buf[i] != 0.toShort()) return false
        return true
    }

    private fun hasSound(buf: ShortArray, from: Int, to: Int): Boolean {
        for (i in from until to) if (buf[i] != 0.toShort()) return true
        return false
    }

    @Test
    fun fourBeatsAtTheRightSpacing() {
        val sampleRate = 44100
        val bpm = 120f
        val buf = Click.countIn(sampleRate, 1, bpm)!!
        val beatFrames = Math.round(60f / bpm * sampleRate)
        assertEquals(beatFrames * Click.COUNT, buf.size)

        for (beat in 0 until Click.COUNT) {
            val start = beat * beatFrames
            // the click itself, right at the top of the beat
            assertTrue("beat $beat has no click", hasSound(buf, start, start + 50))
            // and silence for the rest of that beat, before the next one starts
            assertTrue(
                "beat $beat never goes quiet",
                isSilent(buf, start + 700, start + beatFrames - 1)
            )
        }
    }

    @Test
    fun stereoCarriesTheClickOnBothChannels() {
        val buf = Click.countIn(44100, 2, 100f)!!
        // every frame of the first click: left and right must carry the same thing
        for (frame in 0 until 30) {
            assertEquals("frame $frame diverges between channels", buf[frame * 2], buf[frame * 2 + 1])
        }
        // the sine starts at zero phase, so frame 0 alone is silent by construction -
        // check that the click is actually there, not just that silence matches itself
        assertTrue(hasSound(buf, 0, 60))
    }

    @Test
    fun fasterTempoMeansAShorterCountIn() {
        val slow = Click.countIn(44100, 1, 60f)!!
        val fast = Click.countIn(44100, 1, 180f)!!
        assertTrue(fast.size < slow.size)
    }

    @Test
    fun unusableArgumentsGiveNullInsteadOfCrashing() {
        assertNull(Click.countIn(0, 1, 120f))
        assertNull(Click.countIn(44100, 0, 120f))
        assertNull(Click.countIn(44100, 1, 0f))
        assertNull(Click.countIn(44100, 1, -10f))
        assertNull(Click.countIn(44100, 1, Click.MIN_BPM - 1f))
        assertNull(Click.countIn(44100, 1, Click.MAX_BPM + 1f))
    }

    @Test
    fun boundaryTemposAreAccepted() {
        assertTrue(Click.countIn(44100, 1, Click.MIN_BPM) != null)
        assertTrue(Click.countIn(44100, 1, Click.MAX_BPM) != null)
    }
}
