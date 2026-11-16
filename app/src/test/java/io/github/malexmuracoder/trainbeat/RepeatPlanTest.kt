package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order measures are played in, repeats and voltas unrolled, checked as arithmetic. */
class RepeatPlanTest {

    private fun flags(n: Int, set: Map<Int, RepeatPlan.Flags.() -> Unit>): List<RepeatPlan.Flags> =
        List(n) { i -> RepeatPlan.Flags().also { set[i]?.invoke(it) } }

    @Test
    fun noMarksIsJustTheOrderWritten() {
        assertEquals(listOf(0, 1, 2), RepeatPlan.order(flags(3, emptyMap())))
        assertEquals(emptyList<Int>(), RepeatPlan.order(emptyList()))
    }

    @Test
    fun onlyTheFirstEndingIsKept() {
        val f = flags(4, mapOf(
            1 to { endingStart = listOf(1); endingStop = true },
            2 to { endingStart = listOf(2); endingStop = true }
        ))
        assertEquals(listOf(0, 1, 3), RepeatPlan.order(f))
    }

    @Test
    fun anEndingCanSpanSeveralMeasures() {
        val f = flags(6, mapOf(
            1 to { endingStart = listOf(1) },
            2 to { endingStop = true },
            3 to { endingStart = listOf(2) },
            4 to { endingStop = true }
        ))
        assertEquals(listOf(0, 1, 2, 5), RepeatPlan.order(f))
    }

    @Test
    fun anEndingForSeveralPassesThatIncludesTheFirstIsKept() {
        val f = flags(3, mapOf(1 to { endingStart = listOf(1, 2); endingStop = true }))
        assertEquals(listOf(0, 1, 2), RepeatPlan.order(f))
    }

    @Test
    fun theThirdEndingIsSkippedToo() {
        val f = flags(5, mapOf(
            1 to { endingStart = listOf(1); endingStop = true },
            2 to { endingStart = listOf(2); endingStop = true },
            3 to { endingStart = listOf(3); endingStop = true }
        ))
        assertEquals(listOf(0, 1, 4), RepeatPlan.order(f))
    }

    @Test
    fun anEndingWithNoReadableNumberIsPlayed() {
        val f = flags(3, mapOf(1 to { endingStart = emptyList(); endingStop = true }))
        assertEquals(listOf(0, 1, 2), RepeatPlan.order(f))
    }

    @Test
    fun anEndingThatRunsToTheEndOfTheScoreIsSkippedToTheEnd() {
        val f = flags(3, mapOf(2 to { endingStart = listOf(2) }))
        assertEquals(listOf(0, 1), RepeatPlan.order(f))
    }

    @Test
    fun aSectionBetweenTheSignsIsPlayedTwice() {
        val f = flags(4, mapOf(1 to { repeatStart = true }, 2 to { repeatEnd = 1 }))
        assertEquals(listOf(0, 1, 2, 1, 2, 3), RepeatPlan.order(f))
    }

    @Test
    fun withNoForwardSignTheSectionStartsAtTheTopOrAfterTheLastOneClosed() {
        val f = flags(5, mapOf(1 to { repeatEnd = 1 }, 3 to { repeatEnd = 1 }))
        assertEquals(listOf(0, 1, 0, 1, 2, 3, 2, 3, 4), RepeatPlan.order(f))
    }

    @Test
    fun aRepeatCanAskForMoreThanOneExtraPass() {
        val f = flags(2, mapOf(0 to { repeatEnd = 2 }))
        assertEquals(listOf(0, 0, 0, 1), RepeatPlan.order(f))
    }

    @Test
    fun eachEndingIsPlayedOnItsOwnPass() {
        val f = flags(4, mapOf(
            1 to { endingStart = listOf(1); endingStop = true; repeatEnd = 1 },
            2 to { endingStart = listOf(2); endingStop = true }
        ))
        assertEquals(listOf(0, 1, 0, 2, 3), RepeatPlan.order(f))
    }

    @Test
    fun endingsThatSpanSeveralMeasuresAreSkippedWhole() {
        val f = flags(6, mapOf(
            1 to { endingStart = listOf(1) },
            2 to { endingStop = true; repeatEnd = 1 },
            3 to { endingStart = listOf(2) },
            4 to { endingStop = true }
        ))
        assertEquals(listOf(0, 1, 2, 0, 3, 4, 5), RepeatPlan.order(f))
    }

    @Test
    fun withThreePassesTheThirdEndingIsPlayedLast() {
        val f = flags(5, mapOf(
            1 to { endingStart = listOf(1); endingStop = true; repeatEnd = 2 },
            2 to { endingStart = listOf(2); endingStop = true; repeatEnd = 2 },
            3 to { endingStart = listOf(3); endingStop = true }
        ))
        assertEquals(listOf(0, 1, 0, 2, 0, 3, 4), RepeatPlan.order(f))
    }

    @Test
    fun aNestedRepeatIsPlayedInsideTheOuterOne() {
        val f = flags(4, mapOf(
            0 to { repeatStart = true },
            1 to { repeatStart = true; repeatEnd = 1 },
            2 to { repeatEnd = 1 }
        ))
        assertEquals(listOf(0, 1, 1, 2, 0, 1, 1, 2, 3), RepeatPlan.order(f))
    }

    @Test
    fun aDamagedFileCannotRunAway() {
        val f = flags(3, mapOf(
            0 to { repeatStart = true; repeatEnd = 31 },
            1 to { repeatStart = true; repeatEnd = 31 },
            2 to { repeatStart = true; repeatEnd = 31 }
        ))
        assertTrue(RepeatPlan.order(f).size <= RepeatPlan.MAX_OUT)
    }

    @Test
    fun aSectionAfterAVoltaDoesNotInheritItsPass() {
        val f = flags(7, mapOf(
            0 to { repeatStart = true },
            1 to { endingStart = listOf(1); endingStop = true; repeatEnd = 1 },
            2 to { endingStart = listOf(2); endingStop = true },
            4 to { endingStart = listOf(1); endingStop = true; repeatEnd = 1 },
            5 to { endingStart = listOf(2); endingStop = true }
        ))
        assertEquals(listOf(0, 1, 0, 2, 3, 4, 3, 5, 6), RepeatPlan.order(f))
    }

    @Test
    fun endingNumbersAreRead() {
        assertEquals(listOf(1), RepeatPlan.numbers("1"))
        assertEquals(listOf(1, 2), RepeatPlan.numbers("1, 2"))
        assertEquals(listOf(1, 2, 3), RepeatPlan.numbers("1-3"))
        assertEquals(emptyList<Int>(), RepeatPlan.numbers("x"))
    }
}
