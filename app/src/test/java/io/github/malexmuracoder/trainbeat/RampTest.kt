package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the arithmetic of the three controls on a desktop JVM: no phone. */
class RampTest {

    @Test
    fun linearRampOnTheStartingSpeed() {
        // agreed example: base 70%, step 0.5% -> 0.7 * [1, 1.005, 1.010, 1.015...]
        val base = 0.70f
        val step = 0.005f
        assertEquals(0.7000f, Ramp.speedAt(base, step, 0), 1e-5f)
        assertEquals(0.7035f, Ramp.speedAt(base, step, 1), 1e-5f)
        assertEquals(0.7070f, Ramp.speedAt(base, step, 2), 1e-5f)
        assertEquals(0.7105f, Ramp.speedAt(base, step, 3), 1e-5f)
    }

    @Test
    fun incrementIsNotCompounded() {
        // compounded would give 0.7 * 1.005^100 = 1.152; linear gives 0.7 * 1.5 = 1.05
        assertEquals(1.05f, Ramp.speedAt(0.70f, 0.005f, 100), 1e-4f)
    }

    @Test
    fun ceilingAtDoubleSpeed() {
        assertEquals(2.0f, Ramp.speedAt(1.50f, 0.10f, 10), 1e-5f)
        assertEquals(2.0f, Ramp.speedAt(1.00f, 0.10f, 999), 1e-5f)
        // once at the ceiling it stays there and never exceeds it
        for (n in 0..500) assertTrue(Ramp.speedAt(1.2f, 0.05f, n) <= Ramp.CEILING + 1e-6f)
    }

    @Test
    fun trainOffChangesNothing() {
        for (n in 0..50) assertEquals(0.85f, Ramp.speedAt(0.85f, 0f, n), 1e-6f)
    }

    @Test
    fun baseOutOfRangeIsClamped() {
        assertEquals(Ramp.SPEED_MIN, Ramp.speedAt(0.1f, 0f, 0), 1e-6f)
        assertEquals(Ramp.SPEED_MAX, Ramp.speedAt(9f, 0f, 0), 1e-6f)
    }

    @Test
    fun equalTemperedTransposition() {
        assertEquals(1.0f, Ramp.pitchFactor(0), 1e-6f)
        assertEquals(0.66742f, Ramp.pitchFactor(-7), 1e-4f)    // quinta sotto
        assertEquals(1.25992f, Ramp.pitchFactor(4), 1e-4f)     // terza maggiore sopra
        // out of range is clamped to the ends, not extrapolated
        assertEquals(Ramp.pitchFactor(-7), Ramp.pitchFactor(-12), 1e-6f)
        assertEquals(Ramp.pitchFactor(4), Ramp.pitchFactor(9), 1e-6f)
    }

    @Test
    fun twelveSemitonesWithoutRedundancy() {
        val shifts = (Ramp.PITCH_MIN..Ramp.PITCH_MAX).toList()
        assertEquals(12, shifts.size)
        // every pitch class covered exactly once
        val classi = shifts.map { ((it % 12) + 12) % 12 }.toSet()
        assertEquals(12, classi.size)
        // every interval has a name, and the names are all distinct
        val nomi = shifts.map { Ramp.intervalName(it) }
        assertTrue(nomi.none { it.isBlank() })
        assertEquals(12, nomi.toSet().size)
    }

    @Test
    fun labels() {
        assertEquals("100%", Ramp.speedLabel(1.0f))
        assertEquals("70%", Ramp.speedLabel(0.70f))
        assertEquals("off", Ramp.trainLabel(0f))
        assertTrue(Ramp.pitchLabel(0).contains("unison"))
        assertTrue(Ramp.pitchLabel(-5).startsWith("-5"))
        assertTrue(Ramp.pitchLabel(2).startsWith("+2"))
    }
}
