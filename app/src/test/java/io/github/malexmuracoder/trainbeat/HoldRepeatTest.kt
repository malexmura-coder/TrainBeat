package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold-to-repeat schedule. Small, but worth pinning: these numbers decide
 * whether the trimmer is usable with one thumb, and a plausible-looking change
 * (say, matching the platform key repeat of 50 ms) makes the fine steps unusable.
 */
class HoldRepeatTest {

    @Test
    fun itWalksBeforeItRuns() {
        assertEquals(HoldRepeat.SLOW_MS, HoldRepeat.delayFor(0))
        assertEquals(HoldRepeat.SLOW_MS, HoldRepeat.delayFor(HoldRepeat.SLOW_STEPS - 1))
        assertEquals(HoldRepeat.FAST_MS, HoldRepeat.delayFor(HoldRepeat.SLOW_STEPS))
        assertEquals(HoldRepeat.FAST_MS, HoldRepeat.delayFor(500))
    }

    @Test
    fun theFastRateIsFasterThanTheSlowOne() {
        assertTrue(HoldRepeat.FAST_MS < HoldRepeat.SLOW_MS)
    }

    @Test
    fun aShortHoldCoversASensibleAmountOfAudio() {
        // one second of holding, at 10 ms of audio per step
        var steps = 0
        var t = 0L
        while (t + HoldRepeat.delayFor(steps) <= 1000L) { t += HoldRepeat.delayFor(steps); steps++ }
        // fast enough to be worth holding, slow enough to hear where it is going
        assertTrue("only $steps steps in a second", steps >= 6)
        assertTrue("$steps steps in a second is a blur", steps <= 12)
    }

    @Test
    fun aLongHoldGetsYouAcrossAWholeSong() {
        // five seconds of holding, at 10 ms per step, must cross more than a bar
        val steps = (0..500).first { HoldRepeat.elapsedMs(it) > 5000L }
        assertTrue("$steps steps in five seconds", steps * 10 >= 500)
    }

    @Test
    fun theElapsedTimeAddsUp() {
        assertEquals(0L, HoldRepeat.elapsedMs(0))
        assertEquals(HoldRepeat.SLOW_MS, HoldRepeat.elapsedMs(1))
        assertEquals(
            HoldRepeat.SLOW_MS * HoldRepeat.SLOW_STEPS + HoldRepeat.FAST_MS * 3,
            HoldRepeat.elapsedMs(HoldRepeat.SLOW_STEPS + 3)
        )
    }
}
