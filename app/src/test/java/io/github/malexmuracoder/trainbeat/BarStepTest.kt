package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stepping through a score by fractions of a bar.
 *
 * The test that matters most is theStartOfAChordSequenceAfterAFillIsReachable: with
 * a step of one whole bar it was not, which is the bug this exists to fix.
 */
class BarStepTest {

    private val bar = 1920          // 480 ticks per quarter, four quarters

    @Test
    fun aWholeBarIsTheDefaultAndTheCoarsestStep() {
        assertEquals(BarStep.DEFAULT, BarStep.OPTIONS.first())
        assertEquals(bar, BarStep.ticks("1 bar", bar))
    }

    @Test
    fun theDivisionsAreExactInTicks() {
        assertEquals(960, BarStep.ticks("1/2 bar", bar))
        assertEquals(640, BarStep.ticks("1/3 bar", bar))
        assertEquals(480, BarStep.ticks("1/4 bar", bar))
        assertEquals(320, BarStep.ticks("1/6 bar", bar))
        assertEquals(240, BarStep.ticks("1/8 bar", bar))
        // and they really do add back up to a bar
        assertEquals(bar, BarStep.ticks("1/3 bar", bar) * 3)
        assertEquals(bar, BarStep.ticks("1/6 bar", bar) * 6)
    }

    @Test
    fun theStartOfAChordSequenceAfterAFillIsReachable() {
        // two beats of drum fill, then the chords: half a bar in, which a step of
        // one whole bar can never land on
        val target = bar / 2
        var tick = 0
        var guard = 0
        while (tick < target && guard++ < 100) {
            tick = BarStep.nudge(tick, 1, "1/2 bar", bar, 0, bar * 8)
        }
        assertEquals(target, tick)
    }

    @Test
    fun aStepNeverGoesOutsideItsBounds() {
        assertEquals(0, BarStep.nudge(0, -1, "1/4 bar", bar, 0, bar * 4))
        assertEquals(bar * 4, BarStep.nudge(bar * 4, 1, "1/4 bar", bar, 0, bar * 4))
    }

    @Test
    fun goingBackFromAnOffGridPositionLandsOnTheGridFirst() {
        // at 700 ticks with a 640 step, back means 640, not 60
        assertEquals(640, BarStep.nudge(700, -1, "1/3 bar", bar, 0, bar * 4))
        // and from exactly on the grid it really moves back a step
        assertEquals(0, BarStep.nudge(640, -1, "1/3 bar", bar, 0, bar * 4))
    }

    @Test
    fun aCoarserStepTidiesAnOddPositionInsteadOfCarryingItForever() {
        // sitting at 1/8 of a bar, then switching to whole bars: forward goes to the
        // next bar line, not to "one bar after the odd offset"
        assertEquals(bar, BarStep.nudge(240, 1, "1 bar", bar, 0, bar * 8))
    }

    @Test
    fun aPositionOnABarLineIsNamedByItsBarAlone() {
        assertEquals("bar 1", BarStep.label(0, bar, "1/4 bar"))
        assertEquals("bar 5", BarStep.label(bar * 4, bar, "1/4 bar"))
    }

    @Test
    fun aPositionInsideABarSaysHowFarIn() {
        assertEquals("bar 1 + 1/2", BarStep.label(bar / 2, bar, "1/2 bar"))
        assertEquals("bar 3 + 2/3", BarStep.label(bar * 2 + 1280, bar, "1/3 bar"))
        assertEquals("bar 1 + 3/8", BarStep.label(720, bar, "1/8 bar"))
    }

    @Test
    fun theBarNumberForTheFileNameCountsFromOne() {
        assertEquals(1, BarStep.barNumber(0, bar))
        assertEquals(1, BarStep.barNumber(bar - 1, bar))
        assertEquals(2, BarStep.barNumber(bar, bar))
        assertEquals(5, BarStep.barNumber(bar * 4 + 700, bar))
    }

    @Test
    fun absurdInputsGiveSomethingUsableInsteadOfCrashing() {
        assertEquals(1, BarStep.ticks("1/4 bar", 0))
        assertEquals(1, BarStep.ticks("banana", 1))
        assertEquals("-", BarStep.label(100, 0, "1 bar"))
        assertEquals(1, BarStep.barNumber(100, 0))
        assertTrue(BarStep.ticks("1/8 bar", 3) >= 1)
    }
}
