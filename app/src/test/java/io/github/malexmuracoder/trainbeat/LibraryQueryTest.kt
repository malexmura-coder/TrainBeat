package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agreed behaviour of the library list, as tests: alphabetical with no
 * filter, ranked by probability when a key is asked for, tempo as a hard filter.
 */
class LibraryQueryTest {

    private fun track(
        name: String,
        bpm: Float = 120f,
        am: Float = 0f,
        c: Float = 0f,
        manual: String? = null,
        analysed: Boolean = true
    ): TrackRecord {
        val probs = FloatArray(24)
        probs[Analyzer.keyIndex("Am")] = am
        probs[Analyzer.keyIndex("C")] = c
        return TrackRecord(
            name = name, size = name.length.toLong(), uri = "u/$name",
            bpm = if (analysed) bpm else 0f, beats = 100,
            keyProbs = probs, manualKey = manual,
            error = if (analysed) null else "boom"
        )
    }

    @Test
    fun defaultOrderIsByName() {
        val rows = LibraryQuery.run(listOf(track("zeta"), track("alpha"), track("Mike")))
        assertEquals(listOf("alpha", "Mike", "zeta"), rows.map { it.record.name })
    }

    @Test
    fun keyFilterRanksByProbability() {
        val rows = LibraryQuery.run(
            listOf(
                track("weak", am = 0.10f),
                track("strong", am = 0.90f),
                track("middle", am = 0.45f),
                track("none", c = 0.99f)
            ),
            key = "Am"
        )
        assertEquals(listOf("strong", "middle", "weak"), rows.map { it.record.name })
        assertTrue("a track with no Am mass is dropped", rows.none { it.record.name == "none" })
    }

    @Test
    fun belowThresholdIsDropped() {
        val rows = LibraryQuery.run(listOf(track("tiny", am = 0.01f)), key = "Am")
        assertTrue(rows.isEmpty())
    }

    @Test
    fun manualCorrectionComesFirst() {
        val rows = LibraryQuery.run(
            listOf(track("guessed", am = 0.80f), track("fixed", am = 0.0f, manual = "Am")),
            key = "Am"
        )
        assertEquals("fixed", rows.first().record.name)
        assertEquals(1f, rows.first().score, 1e-6f)
    }

    @Test
    fun tempoRangeIsAHardFilter() {
        val list = listOf(track("slow", bpm = 80f), track("mid", bpm = 110f), track("fast", bpm = 170f))
        val rows = LibraryQuery.run(list, bpmMin = 100f, bpmMax = 120f)
        assertEquals(listOf("mid"), rows.map { it.record.name })
    }

    @Test
    fun halfAndDoubleCanBeIncluded() {
        val list = listOf(track("double", bpm = 180f), track("half", bpm = 45f), track("off", bpm = 130f))
        val strict = LibraryQuery.run(list, bpmMin = 85f, bpmMax = 95f)
        assertTrue(strict.isEmpty())
        val loose = LibraryQuery.run(list, bpmMin = 85f, bpmMax = 95f, halfDouble = true)
        assertEquals(setOf("double", "half"), loose.map { it.record.name }.toSet())
    }

    @Test
    fun notAnalysedCannotSatisfyATempoFilter() {
        val rows = LibraryQuery.run(listOf(track("unknown", analysed = false)), bpmMin = 60f, bpmMax = 200f)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun notAnalysedStillShowsWithNoFilter() {
        val rows = LibraryQuery.run(listOf(track("unknown", analysed = false)))
        assertEquals(1, rows.size)
    }

    @Test
    fun searchIsCaseInsensitiveSubstring() {
        val list = listOf(track("Reggae_C_no_woman"), track("gipsy_Am"), track("BLUES shuffle"))
        assertEquals(listOf("Reggae_C_no_woman"), LibraryQuery.run(list, search = "REGGAE").map { it.record.name })
        assertEquals(listOf("BLUES shuffle"), LibraryQuery.run(list, search = "shuff").map { it.record.name })
    }

    @Test
    fun separatorSitsWhereConfidenceDrops() {
        val rows = LibraryQuery.run(
            listOf(track("a", am = 0.90f), track("b", am = 0.40f), track("c", am = 0.10f)),
            key = "Am"
        )
        // 0.90 and 0.40 are above the 0.25 cut, 0.10 is below it
        assertEquals(2, LibraryQuery.uncertainFrom(rows))
    }

    @Test
    fun searchAndKeyCombine() {
        val rows = LibraryQuery.run(
            listOf(track("blues in Am", am = 0.7f), track("rock in Am", am = 0.9f)),
            search = "blues", key = "Am"
        )
        assertEquals(listOf("blues in Am"), rows.map { it.record.name })
    }
}
