package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engraving, checked as arithmetic: which letter a key gets, where a note may be
 * cut, which way the stem points, where a tick falls. Nothing here draws anything.
 */
class NotationTest {

    private val ppq = 480

    private fun note(tick: Int, dur: Int, key: Int, ch: Int = 0) = MidiNote(tick, dur, ch, key, 80)

    private fun score(
        notes: List<MidiNote>,
        endTick: Int = ppq * 8,
        tempos: List<TempoPoint> = emptyList(),
        signatures: List<TimeSignature> = emptyList(),
        programs: List<ProgramChange> = emptyList()
    ) = Score(ppq, notes, tempos, signatures, emptyList(), programs, emptyList(), endTick, null)

    // ------------------------------------------------------------------ time

    @Test
    fun tickAtFollowsTheTempoMap() {
        // 120 BPM for the first second (960 ticks at 480 per quarter), then 60 BPM
        val s = score(
            emptyList(),
            tempos = listOf(TempoPoint(0, 500_000), TempoPoint(960, 1_000_000))
        )
        assertEquals(0, Notation.tickAt(s, 0.0))
        assertEquals(480, Notation.tickAt(s, 0.5))
        assertEquals(960, Notation.tickAt(s, 1.0))
        assertEquals(1440, Notation.tickAt(s, 2.0))
    }

    @Test
    fun tickAtWithNoTempoUsesTheDefault() {
        assertEquals(960, Notation.tickAt(score(emptyList()), 1.0))
    }

    @Test
    fun tickAtIsTheInverseOfSecondsAt() {
        val s = score(
            emptyList(),
            tempos = listOf(TempoPoint(0, 400_000), TempoPoint(1000, 700_000))
        )
        for (tick in listOf(0, 10, 999, 1000, 1001, 5000)) {
            assertEquals(tick, Notation.tickAt(s, s.secondsAt(tick) + 1e-9))
        }
    }

    // ------------------------------------------------------------- spelling

    @Test
    fun signaturesHaveTheRightScales() {
        val g = Notation.Signature(1)
        assertEquals(1, g.alt[3])                    // F is raised in G major
        assertEquals(0, g.alt[0])
        val f = Notation.Signature(-1)
        assertEquals(-1, f.alt[6])                   // B is lowered in F major
        val dFlat = Notation.Signature(-5)
        assertEquals(-1, dFlat.alt[6])
        assertEquals(-1, dFlat.alt[2])
        assertEquals(-1, dFlat.alt[5])
        assertEquals(-1, dFlat.alt[1])
        assertEquals(-1, dFlat.alt[4])
    }

    @Test
    fun scaleNotesAreSpelledTheWayTheScaleSpellsThem() {
        val g = Notation.Signature(1)
        val fSharp = Notation.spell(66, g)           // F#4
        assertEquals(3, fSharp.letter)
        assertEquals(1, fSharp.alt)
        assertEquals(4 * 7 + 3, fSharp.diatonic)

        val f = Notation.Signature(-1)
        val bFlat = Notation.spell(70, f)            // Bb4
        assertEquals(6, bFlat.letter)
        assertEquals(-1, bFlat.alt)
        assertEquals(4 * 7 + 6, bFlat.diatonic)
    }

    @Test
    fun outsideTheScaleItIsAWhiteKeyIfItCanBeElseSharpOrFlatByTheKey() {
        val g = Notation.Signature(1)
        val fNatural = Notation.spell(65, g)         // F natural in G major, not E sharp
        assertEquals(3, fNatural.letter)
        assertEquals(0, fNatural.alt)

        val cSharp = Notation.spell(61, Notation.Signature(0))
        assertEquals(0, cSharp.letter)
        assertEquals(1, cSharp.alt)

        val dFlat = Notation.spell(61, Notation.Signature(-2))
        assertEquals(1, dFlat.letter)
        assertEquals(-1, dFlat.alt)
    }

    @Test
    fun middleCIsTwentyEight() {
        val c4 = Notation.spell(60, Notation.Signature(0))
        assertEquals(28, c4.diatonic)
        val c5 = Notation.spell(72, Notation.Signature(0))
        assertEquals(35, c5.diatonic)
    }

    @Test
    fun theKeyIsChosenForTheFewestAccidentals() {
        val cMajor = DoubleArray(12)
        for (pc in intArrayOf(0, 2, 4, 5, 7, 9, 11)) cMajor[pc] = 1.0
        assertEquals(0, Notation.bestSignature(cMajor))

        val gMajor = DoubleArray(12)
        for (pc in intArrayOf(7, 9, 11, 0, 2, 4, 6)) gMajor[pc] = 1.0
        assertEquals(1, Notation.bestSignature(gMajor))

        val bFlatMajor = DoubleArray(12)
        for (pc in intArrayOf(10, 0, 2, 3, 5, 7, 9)) bFlatMajor[pc] = 1.0
        assertEquals(-2, Notation.bestSignature(bFlatMajor))
    }

    @Test
    fun nothingToGoOnMeansNoAccidentals() {
        assertEquals(0, Notation.bestSignature(DoubleArray(12)))
    }

    // ------------------------------------------------------------- cutting

    @Test
    fun aLengthThatIsNotANoteValueIsTied() {
        // five sixteenths from the start of a bar: a quarter and a sixteenth
        val p = Notation.pieces(0, 5, 4)
        assertEquals(listOf(Pair(0, 4), Pair(4, 1)), p)
    }

    @Test
    fun aSyncopationIsWrittenAsTwoEighths() {
        // a quarter note starting on the offbeat eighth crosses beat two
        val p = Notation.pieces(2, 6, 4)
        assertEquals(listOf(Pair(2, 2), Pair(4, 2)), p)
    }

    @Test
    fun aHalfNoteOnBeatTwoStaysWhole() {
        assertEquals(listOf(Pair(4, 8)), Notation.pieces(4, 12, 4))
    }

    @Test
    fun aDottedLengthIsOneFigure() {
        assertEquals(listOf(Pair(0, 12)), Notation.pieces(0, 12, 4))
        assertEquals(listOf(Pair(0, 6)), Notation.pieces(0, 6, 4))
    }

    @Test
    fun piecesAlwaysCoverTheWholeStretch() {
        for (beat in intArrayOf(2, 4, 6, 8)) {
            for (from in 0 until 16) {
                for (to in from + 1..16) {
                    val p = Notation.pieces(from, to, beat)
                    assertEquals(from, p.first().first)
                    assertEquals(to - from, p.sumOf { it.second })
                    var at = from
                    for ((off, d) in p) {
                        assertEquals(at, off)
                        assertTrue(Notation.baseOf(d) in intArrayOf(1, 2, 4, 8, 16))
                        at += d
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ engraving

    @Test
    fun theDrumChannelHasNoStaff() {
        val s = score(listOf(note(0, ppq, 36, Score.DRUM_CHANNEL)))
        assertNull(Notation.engrave(s, Score.DRUM_CHANNEL))
    }

    @Test
    fun aChannelWithNoNotesHasNoStaff() {
        val s = score(listOf(note(0, ppq, 60, 0)))
        assertNull(Notation.engrave(s, 3))
    }

    @Test
    fun oneQuarterNoteAndTheRestOfTheBar() {
        val s = score(listOf(note(0, ppq, 60)))             // C4, a quarter, 4/4
        val e = Notation.engrave(s, 0)!!
        assertEquals(listOf(Notation.Clef.TREBLE), e.clefs)
        assertEquals(2, e.bars.size)                        // eight quarters of music

        val first = e.bars[0].staves[0]
        assertEquals(2, first.size)
        val note = first[0]
        assertEquals(0, note.at)
        assertEquals(4, note.dur)
        assertEquals(1, note.heads.size)
        assertEquals(-2, note.heads[0].step)                // C4 hangs under the treble staff
        assertTrue(note.stemUp)
        val rest = first[1]
        assertTrue(rest.rest)
        assertEquals(4, rest.at)
        assertEquals(12, rest.dur)                          // a dotted half rest

        // the second bar is empty: one whole rest, whatever the bar's length
        val second = e.bars[1].staves[0]
        assertEquals(1, second.size)
        assertTrue(second[0].rest)
        assertTrue(second[0].wholeBar)
    }

    @Test
    fun aNoteTiedOverTheBarlineIsNotAnnouncedTwice() {
        // C#4 beginning on beat four of the first bar, lasting a half note
        val s = score(listOf(note(ppq * 3, ppq * 2, 61)), endTick = ppq * 8)
        val e = Notation.engrave(s, 0)!!
        val tail = e.bars[0].staves[0].last()
        assertEquals(12, tail.at)
        assertEquals(4, tail.dur)
        assertTrue(tail.heads[0].tieNext)
        val head = e.bars[1].staves[0].first()
        assertEquals(0, head.at)
        assertEquals(4, head.dur)
        assertFalse(head.heads[0].tieNext)
        assertEquals(Notation.NO_ACC, head.heads[0].acc)
    }

    @Test
    fun anAccidentalIsPrintedOnceAndHoldsToTheBarline() {
        // F# F F# in one bar with the rest of the scale being C major: the sharp is
        // announced, the natural that undoes it is announced, then the sharp again
        val notes = listOf(
            note(0, ppq, 60), note(ppq, ppq, 64), note(ppq * 2, ppq, 67), note(ppq * 3, ppq, 72),
            note(ppq * 4, ppq, 66), note(ppq * 5, ppq, 66), note(ppq * 6, ppq, 65),
            note(ppq * 7, ppq, 66)
        )
        val e = Notation.engrave(score(notes), 0)!!
        val sig = Notation.Signature(e.sharps)
        // whatever signature was chosen, a note equal to what the signature says
        // gets no accidental, and a repeated one in the same bar never gets two
        val bar = e.bars[1].staves[0].filter { !it.rest }
        assertEquals(4, bar.size)
        assertEquals(Notation.NO_ACC, bar[1].heads[0].acc)   // the second F# in a row
        // the signature agrees with what Notation.spell says for these keys
        val letter = Notation.spell(66, sig)
        assertEquals(letter.diatonic - 30, bar[0].heads[0].step)
    }

    @Test
    fun aChordHasItsNotesInOrderAndASecondIsShifted() {
        // C4 and D4 together: a second, so one of them goes to the other side
        val s = score(listOf(note(0, ppq, 60), note(0, ppq, 62)))
        val e = Notation.engrave(s, 0)!!
        val chord = e.bars[0].staves[0][0]
        assertEquals(2, chord.heads.size)
        assertTrue(chord.heads[0].step < chord.heads[1].step)
        assertEquals(1, chord.heads.count { it.shifted })
    }

    @Test
    fun stemsPointAwayFromTheMiddleLine() {
        val high = Notation.engrave(score(listOf(note(0, ppq, 84))), 0)!!      // C6
        assertFalse(high.bars[0].staves[0][0].stemUp)
        val low = Notation.engrave(score(listOf(note(0, ppq, 62))), 0)!!       // D4
        assertTrue(low.bars[0].staves[0][0].stemUp)
    }

    @Test
    fun aLowPartGetsTheBassClef() {
        val s = score(listOf(note(0, ppq, 40), note(ppq, ppq, 43)))
        val e = Notation.engrave(s, 0, 0)!!
        assertEquals(listOf(Notation.Clef.BASS), e.clefs)
    }

    @Test
    fun aKeyboardWithBothHandsGetsTheGrandStaff() {
        val notes = ArrayList<MidiNote>()
        for (i in 0 until 8) {
            notes.add(note(i * ppq, ppq, 72 + i % 3))       // right hand
            notes.add(note(i * ppq, ppq, 48 + i % 3))       // left hand
        }
        val e = Notation.engrave(score(notes), 0)!!
        assertEquals(listOf(Notation.Clef.TREBLE, Notation.Clef.BASS), e.clefs)
        val bar = e.bars[0]
        assertEquals(2, bar.staves.size)
        assertTrue(bar.staves[0].any { !it.rest })
        assertTrue(bar.staves[1].any { !it.rest })
    }

    @Test
    fun aKeyboardWithOnlyOneHandKeepsOneStaff() {
        val notes = (0 until 8).map { note(it * ppq, ppq, 72 + it % 3) }
        val e = Notation.engrave(score(notes), 0)!!
        assertEquals(1, e.clefs.size)
    }

    @Test
    fun aNonKeyboardPartIsNeverSplit() {
        val notes = ArrayList<MidiNote>()
        for (i in 0 until 8) {
            notes.add(note(i * ppq, ppq, 64 + i % 3))
            notes.add(note(i * ppq, ppq, 52 + i % 3))
        }
        val violin = listOf(ProgramChange(0, 0, 40, 0))
        val e = Notation.engrave(score(notes, programs = violin), 0)!!
        assertEquals(1, e.clefs.size)
    }

    @Test
    fun transpositionMovesTheStaff() {
        val s = score(listOf(note(0, ppq, 60)))
        val up = Notation.engrave(s, 0, 12)!!               // an octave up: C5
        assertEquals(5, up.bars[0].staves[0][0].heads[0].step)
    }

    @Test
    fun barLengthsFollowTheTimeSignature() {
        val s = score(
            listOf(note(0, ppq, 60)),
            endTick = ppq * 6,
            signatures = listOf(TimeSignature(0, 3, 4))
        )
        val e = Notation.engrave(s, 0)!!
        assertEquals(2, e.bars.size)
        assertEquals(12, e.bars[0].units)
        assertEquals(3, e.bars[0].numerator)
        assertEquals(4, e.bars[0].denominator)
        assertEquals(ppq * 3, e.bars[1].startTick)
    }

    @Test
    fun theBarOfATickIsFoundByBinarySearch() {
        val s = score(listOf(note(0, ppq, 60)), endTick = ppq * 16)
        val e = Notation.engrave(s, 0)!!
        assertEquals(4, e.bars.size)
        assertEquals(0, e.barIndexAt(0))
        assertEquals(0, e.barIndexAt(ppq * 4 - 1))
        assertEquals(1, e.barIndexAt(ppq * 4))
        assertEquals(3, e.barIndexAt(ppq * 15))
        assertEquals(3, e.barIndexAt(ppq * 1000))
    }

    @Test
    fun aSmallGapBetweenNotesIsBridgedAndALargeOneIsARest() {
        // the first note lifts a sixteenth early out of an eighth: legato, no rest;
        // the second is a staccato sixteenth in a quarter: a real rest follows
        val notes = listOf(
            note(0, ppq / 2 - ppq / 16, 60),
            note(ppq / 2, ppq / 8, 62),
            note(ppq, ppq, 64)
        )
        val e = Notation.engrave(score(notes), 0)!!
        val figs = e.bars[0].staves[0]
        assertEquals(2, figs[0].dur)                         // the eighth is a whole eighth
        assertNotNull(figs.firstOrNull { it.rest && it.at in 1..3 })
    }

    @Test
    fun everyBarIsFilledExactly() {
        val notes = ArrayList<MidiNote>()
        var t = 0
        // an awkward run of lengths, none of them aligned
        for (d in intArrayOf(7, 5, 3, 9, 2, 11, 1, 6)) {
            notes.add(note(t * ppq / 4, d * ppq / 4 - 5, 60 + d % 5))
            t += d
        }
        val e = Notation.engrave(score(notes, endTick = ppq * 20), 0)!!
        for (bar in e.bars) {
            for (staff in bar.staves) {
                var at = 0
                for (f in staff) {
                    assertEquals("figures must follow each other without a gap", at, f.at)
                    at += f.dur
                }
                assertEquals("a bar must be filled exactly", bar.units, at)
            }
        }
    }
}
