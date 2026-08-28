package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid, pinned as arithmetic.
 *
 * The one that matters most is theTripletsMarkTheSamePlacesAsThePlainMultiple: 4T
 * and 12 are the same twelve positions drawn with different weight, and if that
 * ever stops being true the grid has started lying about where the beats are.
 */
class GridTest {

    private val sr = 44100

    @Test
    fun offDrawsNothing() {
        val g = Grid.of("off", 0, sr * 4)
        assertEquals(0, g.total)
    }

    @Test
    fun fourDivisionsMeanThreeInternalLines() {
        val g = Grid.of("4", 0, 4000)
        assertArrayEquals(intArrayOf(1000, 2000, 3000), g.strong)
        assertEquals(0, g.weak.size)
    }

    @Test
    fun theLinesAreOffsetFromTheStartCursorNotFromZero() {
        val g = Grid.of("4", 10_000, 14_000)
        assertArrayEquals(intArrayOf(11_000, 12_000, 13_000), g.strong)
    }

    @Test
    fun sixteenDivisionsMeanFifteenLines() {
        assertEquals(15, Grid.of("16", 0, sr * 8).strong.size)
        assertEquals(0, Grid.of("16", 0, sr * 8).weak.size)
    }

    @Test
    fun aTripletKeepsItsMainLinesAndAddsTwoFaintOnesInEachInterval() {
        val g = Grid.of("4T", 0, 12_000)
        assertArrayEquals("main lines moved", intArrayOf(3000, 6000, 9000), g.strong)
        assertArrayEquals(
            intArrayOf(1000, 2000, 4000, 5000, 7000, 8000, 10_000, 11_000), g.weak
        )
    }

    @Test
    fun theTripletsMarkTheSamePlacesAsThePlainMultiple() {
        // four triplets are twelve: same positions, different weight
        val t = Grid.of("4T", 0, 12_000)
        val plain = Grid.of("12", 0, 12_000)
        val all = (t.strong + t.weak).sorted().toIntArray()
        assertArrayEquals(plain.strong.sorted().toIntArray(), all)
        assertEquals(11, all.size)
        // and the weight is what differs
        assertEquals(3, t.strong.size)
        assertEquals(11, plain.strong.size)
    }

    @Test
    fun everyLineIsStrictlyInsideTheSelection() {
        for (opt in Grid.OPTIONS) {
            val g = Grid.of(opt, 5000, 9000)
            for (x in g.strong + g.weak) {
                assertTrue("$opt put a line at $x", x > 5000 && x < 9000)
            }
        }
    }

    @Test
    fun aSelectionTooShortToDivideDrawsNothing() {
        assertEquals(0, Grid.of("16", 100, 101).total)
        assertEquals(0, Grid.of("16", 500, 500).total)
        assertEquals(0, Grid.of("16", 500, 400).total)
    }

    @Test
    fun nonsenseOptionsAreRefusedInsteadOfGuessed() {
        assertEquals(0, Grid.of("", 0, 4000).total)
        assertEquals(0, Grid.of("T", 0, 4000).total)
        assertEquals(0, Grid.of("banana", 0, 4000).total)
        assertEquals(0, Grid.of("999", 0, 4000).total)
    }

    @Test
    fun theOptionIsParsedIntoACountAndAFlag() {
        assertEquals(8, Grid.divisionsOf("8"))
        assertEquals(8, Grid.divisionsOf("8T"))
        assertEquals(0, Grid.divisionsOf("off"))
        assertFalse(Grid.isTriplet("8"))
        assertTrue(Grid.isTriplet("8T"))
    }

    @Test
    fun theFirstOptionIsOffSoTheGridStartsHidden() {
        assertEquals(Grid.OFF, Grid.OPTIONS.first())
    }

    @Test
    fun theDivisionLengthIsTheOneShownBesideTheMenu() {
        // a two second selection in four: half a second per division, 120 BPM
        assertEquals(0.5, Grid.divisionSeconds("4", 0, sr * 2, sr), 1e-9)
        // a triplet option still reports its MAIN division
        assertEquals(0.5, Grid.divisionSeconds("4T", 0, sr * 2, sr), 1e-9)
        assertEquals(0.0, Grid.divisionSeconds("off", 0, sr * 2, sr), 1e-9)
    }
}
