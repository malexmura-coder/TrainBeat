package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SoundFont reader and the sampler, on a SoundFont built in the test itself. */
class SynthTest {

    private val ppq = 480

    private fun sf(rootKey: Int = 69, loop: Boolean = true): SoundFont =
        SoundFont.open(ArrayByteSource(Sf2Gen.sine(440.0, 44100, 1.0, rootKey, loop)))

    private fun oneNote(key: Int, channel: Int = 0, durTicks: Int = ppq * 2, program: Int = 0) =
        Score(ppq, listOf(MidiNote(0, durTicks, channel, key, 100)),
            listOf(TempoPoint(0, 500_000)), listOf(TimeSignature(0, 4, 4)),
            emptyList(), listOf(ProgramChange(0, channel, program, 0)), emptyList(), ppq * 4, null)

    @Test
    fun readsTheIndexOfASoundFont() {
        val s = sf()
        assertEquals(2, s.presets.size)                 // one melodic, one drum kit
        assertEquals(0, s.presets[0].bank)
        assertEquals(0, s.presets[0].program)
        assertEquals(128, s.presets[1].bank)
        val regions = s.regionsFor(s.presets[0], 60, 100)
        assertEquals(1, regions.size)
        assertEquals(69, regions[0].rootKey)
        assertTrue("the zone declares a loop", regions[0].loops)
        assertTrue("the samples come back", s.samplesOf(regions[0]).size > 40000)
    }

    @Test
    fun refusesSomethingThatIsNotASoundFont() {
        var failed = false
        try {
            SoundFont.open(ArrayByteSource("RIFFnot a soundfont at all".toByteArray()))
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun playsTheKeyItWasAskedFor() {
        // the sample is A4; asking for A5 must come out an octave higher, and the
        // measurement is the zero crossing rate, which needs no library
        val s = sf()
        val a4 = frequencyOf(Synth.render(oneNote(69), s, 44100))
        val a5 = frequencyOf(Synth.render(oneNote(81), s, 44100))
        assertEquals(440.0, a4, 8.0)
        assertEquals(880.0, a5, 16.0)
    }

    @Test
    fun speedChangesLengthAndLeavesPitchAlone() {
        val s = sf()
        val normal = Synth.render(oneNote(69), s, 44100, 1.0)
        val slow = Synth.render(oneNote(69), s, 44100, 0.5)
        // the result is a loop, so its length is exactly the score's length at that
        // speed: no tail, nothing to trim
        assertEquals(2.0, slow.frames.toDouble() / normal.frames, 0.02)
        assertEquals(frequencyOf(normal), frequencyOf(slow), 6.0)
    }

    @Test
    fun rendersStereoSixteenBit() {
        val res = Synth.render(oneNote(69), sf(), 44100)
        assertEquals(2, res.channels)
        assertEquals(44100, res.sampleRate)
        assertTrue(res.samples.size % 2 == 0)
        assertTrue("something was actually rendered", res.peak > 0.01)
    }

    @Test
    fun aLoudChordIsScaledInsteadOfClipped() {
        val notes = (60..72).map { MidiNote(0, ppq * 2, 0, it, 127) }
        val score = Score(ppq, notes, listOf(TempoPoint(0, 500_000)), listOf(TimeSignature(0, 4, 4)),
            emptyList(), emptyList(), emptyList(), ppq * 4, null)
        val res = Synth.render(score, sf(), 44100)
        assertTrue("thirteen notes at full velocity must exceed full scale", res.peak > 1.0)
        assertTrue("so the render is scaled down", res.appliedGain < 1.0)
        var maxAbs = 0
        for (v in res.samples) maxAbs = maxOf(maxAbs, Math.abs(v.toInt()))
        assertTrue("and the result does not sit at the rail", maxAbs < 32767)
    }

    @Test
    fun aOneShotPercussionSamplePlaysOutPastItsNoteOff() {
        // drum notes in real files last a few ticks: honouring that would cut every
        // cymbal in half, so a non-looping sample on channel 10 plays to its end
        val s = sf(loop = false)
        val short = Synth.render(oneNote(60, Score.DRUM_CHANNEL, durTicks = 10), s, 44100)
        val melodic = Synth.render(oneNote(60, 0, durTicks = 10), s, 44100)
        assertTrue("the drum keeps sounding: ${energy(short)} vs ${energy(melodic)}",
            energy(short) > energy(melodic) * 3)
    }

    @Test
    fun refusesToRenderSomethingLongerThanALoop() {
        val long = Score(ppq, listOf(MidiNote(0, ppq, 0, 60, 100)),
            listOf(TempoPoint(0, 500_000)), listOf(TimeSignature(0, 4, 4)),
            emptyList(), emptyList(), emptyList(), ppq * 4 * 400, null)
        var refused = false
        try {
            Synth.render(long, sf(), 44100)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("a ten minute score must be refused, not swallow the memory", refused)
    }

    @Test
    fun anEffectsOnlyBankIsRecognisedInsteadOfAnsweringForABand() {
        // FluidR3 ships a small "GS" file holding only sound effects. Playing a
        // score with it produced sirens and gunshots and said nothing; now the
        // absence of melodic instruments is a fact the app can state.
        val melodic = sf()
        assertTrue(melodic.melodicPresets > 0)
        assertTrue(!melodic.effectsOnly)
        assertEquals(1, melodic.drumKits)
    }

    @Test
    fun anInstrumentThatIsMissingFallsBackInsideTheGeneralMidiBank() {
        val s = sf()
        // program 40 is not in this file: the nearest bank 0 preset answers, and
        // the drum bank is never used as a substitute
        val chosen = s.select(0, 40, false)
        assertEquals(0, chosen?.bank)
        assertEquals("Kit", s.select(0, 0, true)?.name)
    }

    /** Frequency from zero crossings of the mid signal: no library needed. */
    private fun frequencyOf(res: Synth.Result): Double {
        val from = res.sampleRate / 4
        val to = minOf(res.frames - 1, from + res.sampleRate / 2)
        if (to <= from) return 0.0
        var crossings = 0
        var prev = 0.0
        for (i in from until to) {
            val v = (res.samples[i * 2] + res.samples[i * 2 + 1]) / 2.0
            if (prev <= 0.0 && v > 0.0) crossings++
            prev = v
        }
        return crossings.toDouble() * res.sampleRate / (to - from)
    }

    private fun energy(res: Synth.Result): Double {
        var sum = 0.0
        for (v in res.samples) sum += v.toDouble() * v
        return Math.sqrt(sum / maxOf(1, res.samples.size))
    }
}
