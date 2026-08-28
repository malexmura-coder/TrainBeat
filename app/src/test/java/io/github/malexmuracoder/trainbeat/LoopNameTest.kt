package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Saved loop names.
 *
 * The test that matters is twoLoopsOfTheSameLengthFromDifferentPlacesGetDifferentNames:
 * that is the whole bug. Everything else guards the shape of the name.
 */
class LoopNameTest {

    private val sr = 44100
    private val beat = 22050            // 120 BPM, so a bar is 88200 frames

    @Test
    fun twoLoopsOfTheSameLengthFromDifferentPlacesGetDifferentNames() {
        val verse = LoopName.suffix(0, beat * 16, beat, sr)
        val chorus = LoopName.suffix(beat * 64, beat * 80, beat, sr)
        assertNotEquals(verse, chorus)
    }

    @Test
    fun withATempoItCountsBarsFromOne() {
        assertEquals("_bars1-5_loop.wav", LoopName.suffix(0, beat * 16, beat, sr))
        assertEquals("_bars17-21_loop.wav", LoopName.suffix(beat * 64, beat * 80, beat, sr))
    }

    @Test
    fun withNoTempoItFallsBackToTheClock() {
        // a recording: nothing to count in, so say when and how long
        assertEquals("_0s+4s_loop.wav", LoopName.suffix(0, sr * 4, 0, sr))
        assertEquals("_1m23s+8s_loop.wav", LoopName.suffix(sr * 83, sr * 91, 0, sr))
    }

    @Test
    fun theseNamesAreDifferentToo() {
        assertNotEquals(
            LoopName.suffix(0, sr * 4, 0, sr),
            LoopName.suffix(sr * 30, sr * 34, 0, sr)
        )
    }

    @Test
    fun theClockIsShortUnderAMinuteAndPaddedAboveIt() {
        assertEquals("0s", LoopName.clock(0, sr))
        assertEquals("59s", LoopName.clock(sr * 59, sr))
        assertEquals("1m00s", LoopName.clock(sr * 60, sr))
        assertEquals("2m05s", LoopName.clock(sr * 125, sr))
    }

    @Test
    fun aLoopShorterThanABarIsStillCalledOneBar() {
        // rounding to zero bars would produce "bars3-3", which reads as empty
        assertEquals("_bars1-2_loop.wav", LoopName.suffix(0, beat, beat, sr))
    }

    @Test
    fun absurdInputsGiveSomethingUsableInsteadOfCrashing() {
        assertEquals("0s", LoopName.clock(1000, 0))
        assertEquals("_0s+0s_loop.wav", LoopName.suffix(0, 0, 0, 0))
        assertEquals("_bars1-2_loop.wav", LoopName.suffix(100, 50, beat, sr))
    }
}
