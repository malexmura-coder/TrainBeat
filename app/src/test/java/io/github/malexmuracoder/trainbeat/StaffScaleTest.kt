package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The staff size, checked as arithmetic: steps do not drift and limits hold. */
class StaffScaleTest {

    @Test
    fun clampKeepsTheLimits() {
        assertEquals(StaffScale.MIN, StaffScale.clamp(0.1f), 0f)
        assertEquals(StaffScale.MAX, StaffScale.clamp(9f), 0f)
        assertEquals(1f, StaffScale.clamp(Float.NaN), 0f)
        assertEquals(1f, StaffScale.clamp(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun clampRoundsToATenth() {
        assertEquals(1.2f, StaffScale.clamp(1.2349f), 0f)
        assertEquals(0.8f, StaffScale.clamp(0.7999f), 0f)
    }

    @Test
    fun stepsNeverDrift() {
        var v = 1f
        repeat(15) { v = StaffScale.stepped(v, +1) }
        assertEquals(2.5f, v, 0f)
        repeat(30) { v = StaffScale.stepped(v, -1) }
        assertEquals(0.7f, v, 0f)
        // and a step up then down comes back to exactly where it started
        assertEquals(1.3f, StaffScale.stepped(StaffScale.stepped(1.3f, +1), -1), 0f)
    }

    @Test
    fun stepsStopAtTheEnds() {
        assertEquals(StaffScale.MAX, StaffScale.stepped(StaffScale.MAX, +1), 0f)
        assertEquals(StaffScale.MIN, StaffScale.stepped(StaffScale.MIN, -1), 0f)
    }

    @Test
    fun theDefaultFollowsTheSmallestDimension() {
        assertEquals(1.0f, StaffScale.autoFor(360f), 0f)
        assertEquals(1.1f, StaffScale.autoFor(411f), 0f)
        assertEquals(1.7f, StaffScale.autoFor(600f), 0f)
        assertEquals(2.2f, StaffScale.autoFor(800f), 0f)
        assertEquals(StaffScale.MAX, StaffScale.autoFor(2000f), 0f)
        assertEquals(StaffScale.MIN, StaffScale.autoFor(100f), 0f)
    }

    @Test
    fun anUnknownScreenGivesOne() {
        assertEquals(1f, StaffScale.autoFor(0f), 0f)
        assertEquals(1f, StaffScale.autoFor(-5f), 0f)
    }

    @Test
    fun theLabelIsAPercentage() {
        assertEquals("100%", StaffScale.label(1f))
        assertEquals("70%", StaffScale.label(0.7f))
        assertEquals("250%", StaffScale.label(2.5f))
        assertEquals("110%", StaffScale.label(1.1f))
    }

    @Test
    fun aPictureIsOnlyEverMadeSmallerToFit() {
        // no limit given: untouched
        assertEquals(3f, StaffScale.fitted(3f, 25f, null), 0f)
        // room for 25 spaces of 6 dp * 2: exactly 2
        assertEquals(2f, StaffScale.fitted(3f, 25f, 300f), 1e-6f)
        // plenty of room: never made larger
        assertEquals(1f, StaffScale.fitted(1f, 25f, 1000f), 0f)
        // a hopelessly short screen still keeps notes readable
        assertEquals(0.4f, StaffScale.fitted(1f, 25f, 10f), 1e-6f)
    }
}
