package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cursor arithmetic, on a desktop JVM: no phone, no ear.
 *
 * The point of these is that every one of them describes a mistake that would look
 * plausible on screen. A cursor that is off by a frame looks right; a cursor that
 * sweeps the bar twice per repetition looks like a bug in the player.
 */
class CursorTest {

    // 44100 Hz stereo, two seconds
    private val rate = 44100
    private val frames = rate * 2
    private val duration = 2000L

    @Test
    fun aPositionAndItsMillisecondsAgreeBothWays() {
        for (ms in longArrayOf(0, 1, 250, 999, 1000, 1999, 2000)) {
            val f = Cursor.frameAt(ms, frames, duration)
            val back = Cursor.millisAt(f, frames, duration)
            // a frame is a fortieth of a millisecond here: the round trip must not
            // lose more than the millisecond it is quantised to
            assertTrue("$ms came back as $back", Math.abs(back - ms.coerceAtMost(duration)) <= 1)
        }
    }

    @Test
    fun halfWayThroughIsHalfWayThrough() {
        assertEquals(frames / 2, Cursor.frameAt(1000L, frames, duration))
        assertEquals(1000L, Cursor.millisAt(frames / 2, frames, duration))
    }

    @Test
    fun renderedMaterialIsReadOnTheOriginalTimeline() {
        // Hi-Fi at half speed: the same two seconds of music, rendered to four
        // seconds of material. Half way through the MATERIAL is still half way
        // through the PIECE, and saying otherwise is the bug that makes the
        // cursor sweep the bar twice per repetition.
        val rendered = frames * 2
        assertEquals(1000L, Cursor.millisAt(rendered / 2, rendered, duration))
        assertEquals(rendered / 2, Cursor.frameAt(1000L, rendered, duration))
    }

    @Test
    fun anEntryIndexIsAlwaysOnAFrameBoundary() {
        val channels = 2
        val total = frames * channels
        for (f in intArrayOf(0, 1, 7, frames / 3, frames - 1, frames, frames * 2)) {
            val idx = Cursor.sampleAt(f, channels, total)
            assertEquals("frame $f gave $idx", 0, idx % channels)
            assertTrue(idx in 0..(total - channels))
        }
    }

    @Test
    fun theEndOfTheFileDoesNotSwapTheChannels() {
        // the specific accident: clamping to size - 1 on a stereo file is an odd
        // index, and coming in on an odd index plays left as right for the whole
        // repetition
        val channels = 2
        val total = frames * channels
        val idx = Cursor.sampleAt(Int.MAX_VALUE, channels, total)
        assertEquals(0, idx % channels)
        assertEquals(total - channels, idx)
    }

    @Test
    fun anOddSizedArrayIsStillClampedToAWholeFrame() {
        // a truncated file can hold half a frame; the clamp must not hand it out
        val idx = Cursor.sampleAt(Int.MAX_VALUE, 2, 9)
        assertEquals(0, idx % 2)
        assertTrue(idx <= 9 - 2)
    }

    @Test
    fun theCursorDoesNotJumpToTheEndOnTheFirstBuffer() {
        // written 1000 frames, a lead of 11025: nothing has been heard yet. The
        // wrong answer here is loopFrames - 10025, which puts the cursor at the
        // far end of a loop that has only just started.
        assertEquals(0, Cursor.heard(1000, 11025, frames, 0))
    }

    @Test
    fun afterTheFirstPassTheCursorWrapsRatherThanClamping() {
        // one repetition done, 1000 frames into the second: what is being HEARD is
        // still near the end of the first
        val heard = Cursor.heard(1000, 11025, frames, 1)
        assertEquals(frames - 10025, heard)
        assertTrue(heard in 0 until frames)
    }

    @Test
    fun theOrdinaryCaseIsAPlainSubtraction() {
        assertEquals(20000, Cursor.heard(31025, 11025, frames, 0))
    }

    @Test
    fun nothingDividesByZero() {
        assertEquals(0, Cursor.frameAt(500L, 0, 0L))
        assertEquals(0L, Cursor.millisAt(500, 0, 0L))
        assertEquals(0, Cursor.sampleAt(500, 0, 0))
        assertEquals(0, Cursor.heard(500, 10, 0, 3))
    }

    @Test
    fun theLabelCountsMinutesAndSeconds() {
        assertEquals("0:00", Cursor.label(0))
        assertEquals("0:07", Cursor.label(7_400))
        assertEquals("1:00", Cursor.label(60_000))
        assertEquals("3:05", Cursor.label(185_999))
        assertEquals("12:00", Cursor.label(720_000))
    }

    @Test
    fun theLabelNeverNamesASecondThatHasNotArrived() {
        // truncation, not rounding: at 3.9 seconds it must still say 0:03
        assertEquals("0:03", Cursor.label(3_900))
        assertEquals("0:00", Cursor.label(-50))
    }
}
