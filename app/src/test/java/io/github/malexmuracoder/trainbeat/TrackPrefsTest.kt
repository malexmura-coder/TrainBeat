package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The favourites and the remembered settings.
 *
 * Worth testing on paper rather than on the phone because the failure mode is
 * silent and slow: a store that loses a row, or that writes a husk for every track
 * ever opened, only shows up weeks later as a favourites list full of junk.
 */
class TrackPrefsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(): TrackPrefs = TrackPrefs(File(tmp.root, "prefs.tsv"))

    @Test
    fun anEmptyStoreIsEmptyAndDoesNotThrow() {
        val p = TrackPrefs(File(tmp.root, "nothing-here.tsv"))
        assertEquals(0, p.loadAll().size)
        assertNull(p.get("content://x"))
        assertFalse(p.isFavourite("content://x"))
        assertEquals(0, p.favourites().size)
    }

    @Test
    fun aStarSurvivesBeingWrittenAndReadBack() {
        val p = store()
        assertTrue(p.toggleFavourite("content://a", "one.wav"))
        assertTrue(p.isFavourite("content://a"))
        assertEquals("one.wav", p.get("content://a")?.name)
    }

    @Test
    fun theStarTogglesBothWays() {
        val p = store()
        assertTrue(p.toggleFavourite("content://a", "one.wav"))
        assertFalse(p.toggleFavourite("content://a", "one.wav"))
        assertFalse(p.isFavourite("content://a"))
    }

    @Test
    fun unstarringATrackWithDefaultSettingsLeavesNoRowBehind() {
        // otherwise the file grows by one husk for every track ever touched
        val p = store()
        p.toggleFavourite("content://a", "one.wav")
        p.toggleFavourite("content://a", "one.wav")
        assertEquals(0, p.loadAll().size)
    }

    @Test
    fun unstarringATrackWithSettingsKeepsTheSettings() {
        val p = store()
        p.remember("content://a", "one.wav", 0.8f, -2, 0.01f)
        p.toggleFavourite("content://a", "one.wav")
        p.toggleFavourite("content://a", "one.wav")
        val e = p.get("content://a")
        assertFalse(e!!.favourite)
        assertEquals(0.8f, e.speed, 0f)
        assertEquals(-2, e.semitones)
    }

    @Test
    fun rememberingSettingsDoesNotDisturbTheStar() {
        val p = store()
        p.toggleFavourite("content://a", "one.wav")
        p.remember("content://a", "one.wav", 0.75f, 3, 0f)
        val e = p.get("content://a")!!
        assertTrue("the star was lost", e.favourite)
        assertEquals(0.75f, e.speed, 0f)
        assertEquals(3, e.semitones)
    }

    @Test
    fun defaultSettingsAreNotStored() {
        val p = store()
        p.remember("content://a", "one.wav", 1.0f, 0, 0f)
        assertEquals(0, p.loadAll().size)
    }

    @Test
    fun oneRowPerTrackNoMatterHowOftenItIsWritten() {
        val p = store()
        for (i in 1..20) p.remember("content://a", "one.wav", 0.9f, i % 5, 0f)
        assertEquals(1, p.loadAll().size)
    }

    @Test
    fun theOrderIsTheOrderTheyWereStarredIn() {
        val p = store()
        p.toggleFavourite("content://a", "a.wav")
        p.toggleFavourite("content://b", "b.wav")
        p.toggleFavourite("content://c", "c.wav")
        assertEquals(listOf("a.wav", "b.wav", "c.wav"), p.favourites().map { it.name })
    }

    @Test
    fun namesWithTabsAndNewlinesSurviveTheRoundTrip() {
        // a document name comes from the system and is not ours to trust
        val p = store()
        val nasty = "we\tird\nname\\with everything.wav"
        p.toggleFavourite("content://a", nasty)
        assertEquals(nasty, p.get("content://a")?.name)
        assertEquals(1, p.loadAll().size)
    }

    @Test
    fun aUriWithPercentEscapesIsNotMangled() {
        val p = store()
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fmy%20loops"
        p.toggleFavourite(uri, "x.wav")
        assertTrue(p.isFavourite(uri))
    }

    @Test
    fun aCorruptLineIsSkippedAndTheRestSurvives() {
        val f = File(tmp.root, "prefs.tsv")
        f.writeText(
            "content://a\tone.wav\t1\t1.0\t0\t0.0\n" +
                "this is not a row\n" +
                "content://b\ttwo.wav\t1\tnonsense\t0\t0.0\n" +
                "content://c\tthree.wav\t1\t1.0\t0\t0.0\n"
        )
        val p = TrackPrefs(f)
        assertEquals(listOf("one.wav", "three.wav"), p.loadAll().map { it.name })
    }

    @Test
    fun removingATrackForgetsItCompletely() {
        val p = store()
        p.toggleFavourite("content://a", "a.wav")
        p.toggleFavourite("content://b", "b.wav")
        p.remove("content://a")
        assertEquals(listOf("b.wav"), p.loadAll().map { it.name })
    }

    @Test
    fun theNeighbourWalksTheFavouritesAndWrapsRound() {
        val p = store()
        p.toggleFavourite("content://a", "a.wav")
        p.toggleFavourite("content://b", "b.wav")
        p.toggleFavourite("content://c", "c.wav")
        assertEquals("content://b", p.neighbourFavourite("content://a", true)?.uri)
        assertEquals("content://a", p.neighbourFavourite("content://c", true)?.uri)
        assertEquals("content://c", p.neighbourFavourite("content://a", false)?.uri)
    }

    @Test
    fun theNeighbourOfATrackThatIsNotAFavouriteIsTheFirstOne() {
        // pressing next on the speaker while playing something unstarred should go
        // somewhere sensible instead of nowhere
        val p = store()
        p.toggleFavourite("content://a", "a.wav")
        p.toggleFavourite("content://b", "b.wav")
        assertEquals("content://a", p.neighbourFavourite("content://zzz", true)?.uri)
        assertEquals("content://b", p.neighbourFavourite("content://zzz", false)?.uri)
    }

    @Test
    fun withNoFavouritesTheNeighbourIsNothing() {
        assertNull(store().neighbourFavourite("content://a", true))
    }

    @Test
    fun aTrackThatIsOnlyAFavouriteIsNotConsideredEmpty() {
        assertFalse(TrackPrefs.Entry("content://a", "a.wav", favourite = true).empty)
        assertTrue(TrackPrefs.Entry("content://a", "a.wav").empty)
        assertFalse(TrackPrefs.Entry("content://a", "a.wav", speed = 0.9f).empty)
        assertFalse(TrackPrefs.Entry("content://a", "a.wav", semitones = 1).empty)
        assertFalse(TrackPrefs.Entry("content://a", "a.wav", train = 0.01f).empty)
    }
}
