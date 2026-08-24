package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trimmer is judged on signals whose right answer is known: a periodic tone
 * has exactly one correct end for a given start, and the search must find it.
 */
class LoopTrimTest {

    private val sr = 44100

    /** Sine at an exact number of samples per period, so periods are frames. */
    private fun tone(periodFrames: Int, periods: Int, channels: Int = 1): ShortArray {
        val frames = periodFrames * periods
        val out = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val v = Math.sin(2.0 * Math.PI * f / periodFrames) * 20000
            for (c in 0 until channels) out[f * channels + c] = v.toInt().toShort()
        }
        return out
    }

    /** assertEquals with a delta is only defined for doubles, so integers get this. */
    private fun assertNear(msg: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue(
            "$msg: expected $expected within $tolerance, got $actual",
            Math.abs(actual - expected) <= tolerance
        )
    }

    @Test
    fun endMovesToAWholeNumberOfPeriods() {
        val period = 441                       // 100 Hz at 44100
        val x = tone(period, 60)
        val start = period * 5
        val exact = start + period * 20        // the seamless end
        val wrong = exact + 137                // off by a fraction of a period
        val t = LoopTrim.refine(x, 1, sr, start, wrong, searchMs = 10.0, matchMs = 5.0)
        assertNear("end should snap to a whole period", exact, t.end, 3)
    }

    @Test
    fun startLandsOnAZeroCrossing() {
        val period = 400
        val x = tone(period, 40)
        val start = period * 3 + 100           // mid-slope
        val t = LoopTrim.refine(x, 1, sr, start, start + period * 10)
        val v = x[t.start].toInt()
        assertTrue("expected a value near zero, got $v", Math.abs(v) < 4000)
    }

    @Test
    fun seamStepDropsAfterRefining() {
        val period = 441
        val x = tone(period, 60)
        val start = period * 4 + 90
        val end = start + period * 15 + 200
        val before = LoopTrim.seamStep(x, 1, start, end)
        val t = LoopTrim.refine(x, 1, sr, start, end, searchMs = 15.0, matchMs = 6.0)
        val after = LoopTrim.seamStep(x, 1, t.start, t.end)
        assertTrue("step should shrink: was $before, now $after", after <= before + 1e-9)
        assertTrue("step should be small after refining, got $after", after < 0.05)
    }

    @Test
    fun stereoIsHandledOnFrameBoundaries() {
        val period = 441
        val x = tone(period, 40, channels = 2)
        val start = period * 3
        val end = start + period * 10 + 90
        val t = LoopTrim.refine(x, 2, sr, start, end, searchMs = 10.0, matchMs = 5.0)
        val slice = LoopTrim.slice(x, 2, t.start, t.end)
        assertEquals("even number of samples for stereo", 0, slice.size % 2)
        assertNear("loop length", period * 10, t.end - t.start, 3)
    }

    @Test
    fun endNeverGoesBeforeTheStart() {
        val x = tone(400, 20)
        val start = 4000
        val t = LoopTrim.refine(x, 1, sr, start, start + 20)
        assertTrue(t.end > t.start)
    }

    @Test
    fun sliceIsClamped() {
        val x = tone(100, 10)               // 1000 frames
        val s = LoopTrim.slice(x, 1, -50, 99999)
        assertEquals(1000, s.size)
    }

    @Test
    fun refineLeavesAGoodLoopAlone() {
        val period = 441
        val x = tone(period, 60)
        val start = period * 5
        val end = start + period * 20
        val t = LoopTrim.refine(x, 1, sr, start, end, searchMs = 10.0, matchMs = 5.0)
        assertNear("start untouched", start, t.start, 2)
        assertNear("end untouched", end, t.end, 2)
    }
}
