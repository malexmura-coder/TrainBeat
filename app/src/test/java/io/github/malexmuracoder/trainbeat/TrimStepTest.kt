package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One switch, one meaning.
 *
 * Before this existed, "snap to beat" governed the sliders while the fine buttons
 * always jumped a whole beat regardless, so switching it off gave a free slider
 * and steppers that still moved in beats. Nobody had ever tried the switch. These
 * tests exist so that cannot drift apart again.
 */
class TrimStepTest {

    private val sr = 44100
    private val beat = 22050            // a beat at 120 BPM

    @Test
    fun withSnapOnAPositionLandsOnTheNearestBeat() {
        assertEquals(0, TrimStep.align(100, beat, true))
        assertEquals(beat, TrimStep.align(beat - 100, beat, true))
        assertEquals(beat, TrimStep.align(beat + 100, beat, true))
        assertEquals(beat * 4, TrimStep.align(beat * 4 - 3, beat, true))
    }

    @Test
    fun withSnapOffAPositionIsLeftExactlyWhereItWasPut() {
        assertEquals(100, TrimStep.align(100, beat, false))
        assertEquals(beat + 137, TrimStep.align(beat + 137, beat, false))
    }

    @Test
    fun aNegativePositionIsNeverReturned() {
        assertEquals(0, TrimStep.align(-500, beat, true))
        assertEquals(0, TrimStep.align(-500, beat, false))
    }

    @Test
    fun withSnapOnTheButtonsMoveOneBeat() {
        assertEquals(beat, TrimStep.step(beat, sr, true))
    }

    @Test
    fun withSnapOffTheButtonsMoveTenMilliseconds() {
        assertEquals(441, TrimStep.step(beat, sr, false))
        assertEquals(TrimStep.MANUAL_MS * sr / 1000, TrimStep.step(beat, sr, false))
    }

    @Test
    fun withNoTempoKnownTheButtonsMoveTenMillisecondsEvenWithSnapOn() {
        // this is the recorded-file case: there is no beat to snap to
        assertEquals(441, TrimStep.step(0, sr, true))
    }

    @Test
    fun nudgeStaysInsideItsBounds() {
        assertEquals(0, TrimStep.nudge(200, -1, beat, sr, true, 0, sr))
        assertEquals(sr, TrimStep.nudge(sr - 200, 1, beat, sr, true, 0, sr))
        assertEquals(1000 + 441, TrimStep.nudge(1000, 1, beat, sr, false, 0, sr))
        assertEquals(1000 - 441, TrimStep.nudge(1000, -1, beat, sr, false, 0, sr))
    }

    @Test
    fun theLabelSaysWhatTheStepActuallyIs() {
        assertEquals("start, one beat", TrimStep.label("start", beat, true))
        assertEquals("start, 10 ms", TrimStep.label("start", beat, false))
        assertEquals("end, 10 ms", TrimStep.label("end", 0, true))
    }

    @Test
    fun aStepIsNeverZero() {
        // an absurd sample rate must not produce a button that does nothing
        assertEquals(1, TrimStep.step(0, 10, false))
    }
}
