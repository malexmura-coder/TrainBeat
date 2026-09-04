package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mixer's arithmetic, on a desktop JVM.
 *
 * Every one of these is a way of silencing the wrong instrument, which on a piece
 * being learned is a mistake the ear does not necessarily catch: you hear something
 * missing, and you assume it is you.
 */
class InstrumentsTest {

    private val ppq = 96

    /** Piano on channel 1, bass on 3, drums on 10 - counting as a musician does. */
    private fun band(): Score = Score(
        ppq = ppq,
        notes = listOf(
            MidiNote(0, ppq, 0, 60, 100),
            MidiNote(0, ppq, 2, 36, 100),
            MidiNote(0, ppq, Score.DRUM_CHANNEL, 38, 100),
            MidiNote(ppq * 3, ppq, 2, 38, 100)          // the LAST note is the bass
        ),
        tempos = listOf(TempoPoint(0, 500_000)),
        timeSignatures = listOf(TimeSignature(0, 4, 4)),
        keySignatures = emptyList(),
        programs = listOf(ProgramChange(0, 2, 32, 0)),   // channel 3 is an acoustic bass
        controllers = emptyList(),
        endTick = ppq * 4,
        title = null
    )

    // ------------------------------------------------------------------ the mask

    @Test
    fun everythingIsOnUntilSomethingIsSwitchedOff() {
        for (c in 0..15) assertTrue("channel $c", Instruments.isOn(Instruments.ALL, c))
    }

    @Test
    fun switchingOneOffLeavesTheRestAlone() {
        val m = Instruments.toggled(Instruments.ALL, 2)
        assertFalse(Instruments.isOn(m, 2))
        for (c in 0..15) if (c != 2) assertTrue("channel $c", Instruments.isOn(m, c))
        // and back again
        assertEquals(Instruments.ALL, Instruments.toggled(m, 2))
    }

    @Test
    fun aMaskIsBuiltFromTheChannelsItNames() {
        val m = Instruments.maskOf(listOf(0, 2, Score.DRUM_CHANNEL))
        assertTrue(Instruments.isOn(m, 0))
        assertTrue(Instruments.isOn(m, 2))
        assertTrue(Instruments.isOn(m, 9))
        assertFalse(Instruments.isOn(m, 1))
    }

    @Test
    fun nonsenseChannelsDoNotCorruptTheMask() {
        assertFalse(Instruments.isOn(Instruments.ALL, 16))
        assertFalse(Instruments.isOn(Instruments.ALL, -1))
        assertEquals(Instruments.ALL, Instruments.toggled(Instruments.ALL, 99))
        assertEquals(0, Instruments.maskOf(listOf(-3, 16, 40)))
    }

    // ------------------------------------------------------------------ the rows

    @Test
    fun oneRowPerChannelThatActuallyPlaysSomething() {
        val parts = Instruments.partsOf(band())
        assertEquals(3, parts.size)
        assertEquals(listOf(0, 2, Score.DRUM_CHANNEL), parts.map { it.channel })
    }

    @Test
    fun theRowsAreNamedTheWayAMusicianCountsChannels() {
        val parts = Instruments.partsOf(band())
        // channel index 2 is channel 3 on any instrument ever made
        assertEquals("3 · Acoustic Bass", parts[1].label)
        assertEquals("10 · Drums", parts[2].label)
        // no program change on channel 1: General MIDI says program 0
        assertEquals("1 · Acoustic Grand Piano", parts[0].label)
    }

    @Test
    fun thePercussionChannelIsNeverNamedAfterAnInstrument() {
        // program 32 on channel 10 is still drums: there the program means a KIT
        assertEquals("10 · Drums", Instruments.label(Score.DRUM_CHANNEL, 32, true))
    }

    @Test
    fun thereIsANameForEveryProgramAndNoneMissing() {
        assertEquals(128, Instruments.GM.size)
        assertEquals("Acoustic Grand Piano", Instruments.GM[0])
        assertEquals("Acoustic Bass", Instruments.GM[32])
        assertEquals("Gunshot", Instruments.GM[127])
        // a file may name a program outside the standard; say so rather than crash
        assertEquals("9 · Program 200", Instruments.label(8, 200, false))
    }

    // ------------------------------------------------------------- what is heard

    @Test
    fun turningEverythingOffIsNoticedBeforeAnythingIsRendered() {
        val s = band()
        assertTrue(Instruments.anySounding(Instruments.ALL, s))
        // only the channels the score does not use are left on
        val none = Instruments.maskOf(listOf(1, 3, 4))
        assertFalse(Instruments.anySounding(none, s))
        assertEquals(0, Instruments.soundingCount(none, s))
        assertEquals(3, Instruments.soundingCount(Instruments.ALL, s))
    }

    // --------------------------------------------------- muting inside the score

    @Test
    fun mutingAChannelRemovesItsNotesAndNothingElse() {
        val muted = band().onlyChannels(Instruments.toggled(Instruments.ALL, 2))
        assertEquals(listOf(0, Score.DRUM_CHANNEL), muted.usedChannels)
        assertEquals(2, muted.notes.size)
        // the programs and the tempo map are untouched: they cost nothing and the
        // channel may be switched back on a moment later
        assertEquals(1, muted.programs.size)
        assertEquals(1, muted.tempos.size)
    }

    @Test
    fun mutingTheInstrumentThatPlaysLastDoesNotShortenTheLoop() {
        // the whole reason onlyChannels rebuilds the score by hand. The bass has the
        // last note; if silencing it shortened the piece, the seam would move and
        // the loop would fall out of time against everything else.
        val whole = band()
        val muted = whole.onlyChannels(Instruments.toggled(Instruments.ALL, 2))
        assertEquals(whole.endTick, muted.endTick)
        assertEquals(whole.durationSec, muted.durationSec, 1e-9)
        assertEquals(whole.ticksPerBar(), muted.ticksPerBar())
    }

    @Test
    fun anUntouchedMaskCostsNothingAndReturnsTheSameScore() {
        val s = band()
        assertTrue(s === s.onlyChannels(Instruments.ALL))
        // a mask with extra channels on that the score does not use is still "all"
        assertTrue(s === s.onlyChannels(Instruments.maskOf(listOf(0, 2, 9, 5, 11))))
    }

    @Test
    fun mutingEverythingLeavesAScoreOfTheRightLengthAndNoNotes() {
        val muted = band().onlyChannels(0)
        assertTrue(muted.notes.isEmpty())
        assertEquals(ppq * 4, muted.endTick)
    }
}
