package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the staff turns the page, checked as arithmetic. */
class StaffPagingTest {

    private val four = { _: Int -> 10f }

    @Test
    fun countsWholeBars() {
        // 35 spaces hold three whole bars and half of the fourth: the fourth counts
        assertEquals(4, StaffPaging.visibleCount(0, 20, 35f, four))
        // 34.9: just under half of the fourth: it does not
        assertEquals(3, StaffPaging.visibleCount(0, 20, 34.9f, four))
        assertEquals(3, StaffPaging.visibleCount(0, 20, 30f, four))
    }

    @Test
    fun neverLessThanOneAndNeverPastTheEnd() {
        assertEquals(1, StaffPaging.visibleCount(0, 20, 3f, four))
        assertEquals(1, StaffPaging.visibleCount(0, 20, 0f, four))
        assertEquals(2, StaffPaging.visibleCount(18, 20, 100f, four))
        assertEquals(1, StaffPaging.visibleCount(19, 20, 100f, four))
    }

    @Test
    fun barsOfDifferentLengthsAreAddedUp() {
        val w = { i: Int -> if (i == 0) 20f else 5f }
        // 20 + 5 + 5 = 30, then a half of the next bar is 2.5 and 3 are left over
        assertEquals(3, StaffPaging.visibleCount(0, 10, 30f, w))
        assertEquals(4, StaffPaging.visibleCount(0, 10, 32.5f, w))
    }

    @Test
    fun theCursorWalksAndThePageTurnsOnTheLastVisibleBar() {
        // four bars on screen, a piece of twenty
        var start = 0
        val seen = ArrayList<Int>()
        for (cur in 0 until 20) {
            start = StaffPaging.nextStart(start, cur, 4, 20)
            seen.add(start)
        }
        // bars 0, 1, 2 are played on the first page; entering bar 3 turns it. At the end
        // the last page (18, 19) shows the rest and does not turn
        assertEquals(listOf(0, 0, 0, 3, 3, 3, 6, 6, 6, 9, 9, 9, 12, 12, 12, 15, 15, 15, 18, 18), seen)
    }

    @Test
    fun noTurnWhenTheRestOfThePieceIsOnScreen() {
        // page at 16, four visible, twenty bars: 16+4 >= 20 and it stays
        for (cur in 16 until 20) assertEquals(16, StaffPaging.nextStart(16, cur, 4, 20))
    }

    @Test
    fun seekingBackOrLoopingRestartsThePage() {
        assertEquals(2, StaffPaging.nextStart(9, 2, 4, 20))
        assertEquals(0, StaffPaging.nextStart(15, 0, 4, 20))
    }

    @Test
    fun seekingAheadLandsOnTheNewBar() {
        assertEquals(12, StaffPaging.nextStart(0, 12, 4, 20))
        // inside the page: nothing moves
        assertEquals(3, StaffPaging.nextStart(3, 5, 4, 20))
    }

    @Test
    fun withOneBarVisibleItTurnsAtEveryBar() {
        for (cur in 0 until 6) assertEquals(cur, StaffPaging.nextStart(maxOf(0, cur - 1), cur, 1, 6))
    }

    @Test
    fun aNarrowerScreenRealignsByItself() {
        // the page was at 6 with four bars; the screen rotates and only two fit:
        // the cursor in bar 7 is already in the last visible bar
        assertEquals(7, StaffPaging.nextStart(6, 7, 2, 20))
        // and if more fit than before the page simply stays
        assertEquals(6, StaffPaging.nextStart(6, 7, 6, 20))
    }

    @Test
    fun staysInsideTheScoreWhateverItIsGiven() {
        assertEquals(0, StaffPaging.nextStart(5, 5, 4, 0))
        assertEquals(2, StaffPaging.nextStart(9, 99, 4, 3))
        assertEquals(0, StaffPaging.nextStart(-3, -3, 4, 10))
    }

    @Test
    fun theHeaderIsMeasuredLikeItIsPainted() {
        // clef 0.5 + 3.5, no signature, one digit 1.8, tail 0.8
        assertEquals(6.6f, StaffPaging.headerSpaces(0, 4, 4), 1e-4f)
        // two sharps: 2.2 + 0.4
        assertEquals(6.6f + 2.6f, StaffPaging.headerSpaces(2, 4, 4), 1e-4f)
        // flats count the same, and 12/8 has two digits
        assertEquals(6.6f + 1.1f + 0.4f + 1.8f, StaffPaging.headerSpaces(-1, 12, 8), 1e-4f)
    }
}
