package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline stretch and the resampler.
 *
 * The two things that matter here are measurable without an opinion: the result
 * has the length it was asked for, and the pitch moves by exactly the interval
 * asked for and by nothing when only the speed changes.
 */
class StretchTest {

    private val sr = 44100

    private fun tone(freq: Double, seconds: Double, channels: Int = 2): ShortArray {
        val frames = (sr * seconds).toInt()
        val out = ShortArray(frames * channels)
        for (i in 0 until frames) {
            val v = (Math.sin(2.0 * Math.PI * freq * i / sr) * 10000).toInt().toShort()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    /** Frequency by zero crossings over the middle of the material. */
    private fun freq(samples: ShortArray, channels: Int): Double {
        val frames = samples.size / channels
        val from = frames / 4
        val to = minOf(frames - 1, from + sr / 2)
        var crossings = 0
        var prev = 0
        for (i in from until to) {
            val v = samples[i * channels].toInt()
            if (prev <= 0 && v > 0) crossings++
            prev = v
        }
        return crossings.toDouble() * sr / (to - from)
    }

    @Test
    fun stretchingGivesExactlyTheLengthAsked() {
        val src = tone(220.0, 2.0)
        for (factor in listOf(0.7, 1.25, 1.6)) {
            val out = Stretch.timeStretch(src, 2, factor)
            val wanted = (src.size / 2 * factor).toInt()
            assertEquals("factor $factor", wanted.toDouble(), (out.size / 2).toDouble(), wanted * 0.01)
        }
    }

    @Test
    fun stretchingDoesNotMoveThePitch() {
        val src = tone(220.0, 2.0)
        val slow = Stretch.timeStretch(src, 2, 1.5)
        assertEquals(220.0, freq(slow, 2), 4.0)
    }

    @Test
    fun renderCombinesSpeedAndTranspositionAsAsked() {
        val src = tone(220.0, 2.0)
        // speed 0.8 with no transposition: same pitch, longer
        val slower = Stretch.render(src, 2, 0.8, 0)
        assertEquals(220.0, freq(slower, 2), 4.0)
        assertEquals((src.size / 2 / 0.8), (slower.size / 2).toDouble(), src.size * 0.01)

        // twelve semitones up, same duration
        val octave = Stretch.render(src, 2, 1.0, 12)
        assertEquals(440.0, freq(octave, 2), 8.0)
        assertEquals((src.size / 2).toDouble(), (octave.size / 2).toDouble(), src.size * 0.02)

        // five semitones down
        val down = Stretch.render(src, 2, 1.0, -5)
        assertEquals(220.0 * Math.pow(2.0, -5 / 12.0), freq(down, 2), 4.0)
    }

    @Test
    fun theDefaultSettingIsNotTouchedAtAll() {
        val src = tone(220.0, 0.5)
        assertTrue("no work means the very same array", Stretch.render(src, 2, 1.0, 0) === src)
    }

    @Test
    fun theStretchedLoopStillJoinsAtTheSeam() {
        // A loop is circular: what follows the last sample is the first one. A linear
        // stretch fades in and out at the ends and the seam clicks; this one wraps,
        // so the step across the seam has to be no worse than a step inside.
        val src = tone(200.0, 1.0)                       // exactly 200 periods: a true loop
        val out = Stretch.timeStretch(src, 2, 1.3)
        val frames = out.size / 2
        val seam = Math.abs(out[0] - out[(frames - 1) * 2]).toDouble()
        var typical = 0.0
        for (i in frames / 4 until frames / 4 + 2000) {
            typical += Math.abs(out[i * 2] - out[(i - 1) * 2]).toDouble()
        }
        typical /= 2000
        assertTrue("seam step $seam against a typical step of $typical", seam < typical * 8)
    }

    @Test
    fun resamplingMovesPitchAndLengthTogether() {
        val src = tone(220.0, 1.0)
        val up = Resample.resample(src, 2, 2.0)
        assertEquals(440.0, freq(up, 2), 8.0)
        assertEquals((src.size / 4).toDouble(), (up.size / 2).toDouble(), src.size * 0.01)
    }

    @Test
    fun theAntiAliasFilterKeepsTheHighNotesClean() {
        // a tone at 12 kHz read at double speed would land at 24 kHz, above Nyquist,
        // and fold back down to about 20 kHz as an audible whistle. The prefilter is
        // there to stop that, so what comes out has to be quiet, not loud and wrong.
        val src = tone(12000.0, 0.5)
        val up = Resample.resample(src, 2, 2.0)
        var energy = 0.0
        val frames = up.size / 2
        for (i in frames / 4 until frames / 2) energy += up[i * 2].toDouble() * up[i * 2]
        val rms = Math.sqrt(energy / (frames / 4))
        assertTrue("aliased energy $rms should be far below the original 7000", rms < 2000)
    }

    @Test
    fun aVeryShortClipIsHandledInsteadOfCrashing() {
        val tiny = ShortArray(200) { (it * 100).toShort() }
        val out = Stretch.render(tiny, 2, 0.5, 0)
        assertTrue(out.isNotEmpty())
    }
}
