package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The click, as arithmetic. */
class MetronomeTest {

    private fun score(end: Int, vararg sigs: TimeSignature) = Score(
        96, listOf(MidiNote(0, 96, 0, 60, 80)), emptyList(), sigs.toList(),
        emptyList(), emptyList(), emptyList(), end, null
    )

    @Test
    fun aClickOnEveryBeatWithTheFirstOfTheBarAccented() {
        val c = Metronome.clicks(score(768, TimeSignature(0, 4, 4)))
        assertEquals(listOf(0, 96, 192, 288, 384, 480, 576, 672), c.map { it.tick })
        assertEquals(Metronome.ACCENT_KEY, c[0].key)
        assertEquals(Metronome.BEAT_KEY, c[1].key)
        assertEquals(Metronome.ACCENT_KEY, c[4].key)
        assertTrue(c.all { it.channel == Score.DRUM_CHANNEL })
        assertTrue(c[0].velocity > c[1].velocity)
    }

    @Test
    fun noTimeSignatureMeansFourFour() {
        assertEquals(2, Metronome.clicks(score(100)).size)
    }

    @Test
    fun compoundMetresBeatInDottedQuarters() {
        val c = Metronome.clicks(score(576, TimeSignature(0, 6, 8)))
        assertEquals(listOf(0, 144, 288, 432), c.map { it.tick })
        assertEquals(Metronome.ACCENT_KEY, c[2].key)
    }

    @Test
    fun anOddMeterBeatsInEighths() {
        val c = Metronome.clicks(score(240, TimeSignature(0, 5, 8)))
        assertEquals(5, c.size)
        assertEquals(48, c[1].tick - c[0].tick)
    }

    @Test
    fun aChangeOfMeterStartsANewBar() {
        val c = Metronome.clicks(score(672, TimeSignature(0, 4, 4), TimeSignature(384, 3, 4)))
        assertEquals(7, c.size)
        assertEquals(Metronome.ACCENT_KEY, c[4].key)
        assertEquals(384, c[4].tick)
    }

    @Test
    fun nothingIsClickedAfterTheEnd() {
        assertTrue(Metronome.clicks(score(768, TimeSignature(0, 4, 4))).all { it.tick < 768 })
    }

    @Test
    fun theScoreKeepsItsNotesAndGainsTheClicks() {
        val s = score(768, TimeSignature(0, 4, 4))
        val m = s.withMetronome()
        assertEquals(1 + 8, m.notes.size)
        assertEquals(s.endTick, m.endTick)
        assertEquals(m.notes.sortedBy { it.tick }.map { it.tick }, m.notes.map { it.tick })
    }

    @Test
    fun theMetronomeIsAChannelOfTheMixerThatStartsSwitchedOff() {
        assertFalse(Instruments.metronomeOn(Instruments.ALL))
        val on = Instruments.toggled(Instruments.ALL, Instruments.METRONOME)
        assertTrue(Instruments.metronomeOn(on))
        assertEquals(Instruments.ALL, Instruments.toggled(on, Instruments.METRONOME))
        for (c in 0..15) assertTrue(Instruments.isOn(on, c))
    }
}
