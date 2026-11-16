package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tablature as arithmetic: rhythm, bars, and a note moved to another string. */
class TabLayoutTest {

    private val open = intArrayOf(64, 59, 55, 50, 45, 40)

    private fun tab(vararg notes: TabNote) = TabScore(
        96,
        listOf(TabBar(0, 384, 4, 4), TabBar(384, 384, 4, 4)),
        listOf(TabPart(0, "Guitar", open, notes.toList()))
    )

    @Test
    fun rhythmIsTheNearestPlainValue() {
        assertEquals(Pair(16, false), TabLayout.rhythmOf(16.0))
        assertEquals(Pair(16, false), TabLayout.rhythmOf(24.0))
        assertEquals(Pair(8, true), TabLayout.rhythmOf(12.0))
        assertEquals(Pair(4, false), TabLayout.rhythmOf(4.0))
        assertEquals(Pair(4, true), TabLayout.rhythmOf(6.0))
        assertEquals(Pair(2, true), TabLayout.rhythmOf(3.0))
        assertEquals(Pair(2, false), TabLayout.rhythmOf(2.0))
        assertEquals(Pair(1, false), TabLayout.rhythmOf(1.0))
        assertEquals(Pair(1, false), TabLayout.rhythmOf(1.5))
        assertEquals(Pair(1, false), TabLayout.rhythmOf(0.5))
    }

    @Test
    fun aFigureIsAsLongAsItsShortestNoteAndCutAtTheBarline() {
        val e = TabLayout.engrave(tab(
            TabNote(0, 48, 6, 0, false),
            TabNote(96, 96, 5, 2, false), TabNote(96, 48, 1, 0, false),
            TabNote(360, 96, 3, 4, false)
        ), 0)!!
        val f = e.bars[0].figures
        assertEquals(3, f.size)
        assertEquals(2, f[0].base)                      // an eighth
        assertEquals(4f, f[1].at, 1e-4f)                // 96 ticks = 4 sixteenths in
        assertEquals(2, f[1].base)                      // the chord is as long as its shortest
        assertEquals(listOf(1, 5), f[1].cells.map { it.string })     // highest string first
        assertEquals(1, f[2].base)                      // cut at the barline: one sixteenth
        assertEquals(16f, e.bars[0].units, 1e-4f)
        assertEquals(0, e.bars[1].figures.size)
    }

    @Test
    fun theCursorFindsItsBar() {
        val e = TabLayout.engrave(tab(TabNote(0, 48, 6, 0, false)), 0)!!
        assertEquals(0, e.barIndexAt(-5))
        assertEquals(0, e.barIndexAt(383))
        assertEquals(1, e.barIndexAt(384))
        assertEquals(4.0, e.unitsInto(e.bars[0], 96), 1e-9)
        assertEquals(16.0, e.unitsInto(e.bars[0], 5000), 1e-9)
    }

    @Test
    fun aNoteIsMovedAlongItsStringWhenItCanBe() {
        assertEquals(Pair(6, 2), TabLayout.moved(open, 6, 0, 2))
        assertEquals(Pair(6, 0), TabLayout.moved(open, 6, 0, 0))
    }

    @Test
    fun aNoteThatFallsOffTheNeckGoesToTheNearestStringThatHasIt() {
        // G3 five semitones down is D3: the open fourth string
        assertEquals(Pair(4, 0), TabLayout.moved(open, 3, 0, -5))
        // a fret above the neck moves to a string where it lies in reach
        assertEquals(Pair(5, 20), TabLayout.moved(open, 6, 24, 1))
        val m = TabLayout.moved(open, 6, 24, 1)!!
        assertTrue(m.second in 0..TabLayout.MAX_FRET)
        assertEquals(open[5] + 24 + 1, open[m.first - 1] + m.second)
    }

    @Test
    fun aNoteNoStringCanPlayIsLeftOut() {
        assertNull(TabLayout.moved(open, 6, 0, -2))
        assertNull(TabLayout.moved(open, 1, 22, 12))
        val e = TabLayout.engrave(tab(TabNote(0, 96, 6, 0, false), TabNote(96, 96, 5, 0, false)), 0, -2)!!
        assertEquals(1, e.bars[0].figures.size)         // E2 down two has no home, A2 down two does
    }

    @Test
    fun noPartNoTablature() {
        assertNull(TabLayout.engrave(tab(TabNote(0, 96, 6, 0, false)), 5))
        assertNull(TabLayout.engrave(TabScore(96, emptyList(), emptyList()), 0))
    }
}
