package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The cache is plain text and dependency-free, so it is tested on a desktop JVM. */
class LibraryStoreTest {

    private fun tempStore(): Pair<LibraryStore, File> {
        val f = File.createTempFile("library", ".tsv")
        f.delete()
        return Pair(LibraryStore(f), f)
    }

    private fun rec(name: String, size: Long = 100, bpm: Float = 120f, key: String? = null): TrackRecord {
        val probs = FloatArray(24)
        probs[Analyzer.keyIndex("Am")] = 0.6f
        probs[Analyzer.keyIndex("C")] = 0.4f
        return TrackRecord(
            name = name, size = size, uri = "content://x/$name",
            sampleRate = 44100, channels = 2, durationMs = 42327,
            bpm = bpm, beats = 127, bars = 31, barsRemainder = 3,
            keyProbs = probs, manualKey = key
        )
    }

    @Test
    fun roundTrip() {
        val r = rec("gipsy_Am_180bpm.wav")
        val back = TrackRecord.parse(r.serialise())
        assertEquals(r, back)
    }

    @Test
    fun tabsAndNewlinesInNamesSurvive() {
        val r = rec("odd\tname\nwith breaks.wav")
        val back = TrackRecord.parse(r.serialise())
        assertEquals(r.name, back!!.name)
    }

    @Test
    fun nullFieldsStayNull() {
        val back = TrackRecord.parse(rec("a.wav").serialise())!!
        assertNull(back.manualKey)
        assertNull(back.error)
    }

    @Test
    fun lastLineWinsForTheSameTrack() {
        val (store, f) = tempStore()
        try {
            store.append(rec("a.wav", bpm = 100f))
            store.append(rec("b.wav", bpm = 90f))
            store.append(rec("a.wav", bpm = 140f))          // re-analysed
            val all = store.loadAll()
            assertEquals(2, all.size)
            assertEquals(140f, all.first { it.name == "a.wav" }.bpm, 0.01f)
            assertEquals(3, store.lineCount())
        } finally {
            f.delete()
        }
    }

    @Test
    fun compactCollapsesDuplicates() {
        val (store, f) = tempStore()
        try {
            store.append(rec("a.wav", bpm = 100f))
            store.append(rec("a.wav", bpm = 110f))
            store.append(rec("a.wav", bpm = 120f))
            store.compact()
            assertEquals(1, store.lineCount())
            assertEquals(120f, store.loadAll().first().bpm, 0.01f)
        } finally {
            f.delete()
        }
    }

    @Test
    fun differentSizeIsADifferentTrack() {
        val (store, f) = tempStore()
        try {
            store.append(rec("a.wav", size = 100))
            store.append(rec("a.wav", size = 200))
            assertEquals(2, store.loadAll().size)
        } finally {
            f.delete()
        }
    }

    @Test
    fun missingFileReadsAsEmpty() {
        val (store, f) = tempStore()
        assertTrue(store.loadAll().isEmpty())
        f.delete()
    }

    @Test
    fun garbageLinesAreIgnored() {
        val (store, f) = tempStore()
        try {
            f.writeText("this is not a record\n" + rec("good.wav").serialise() + "\n\n")
            assertEquals(1, store.loadAll().size)
        } finally {
            f.delete()
        }
    }

    @Test
    fun manualKeyMeansCertainty() {
        val r = rec("x.wav", key = "Em")
        assertEquals(1f, r.p("Em"), 1e-6f)
        assertEquals(0f, r.p("Am"), 1e-6f)      // even though the analysis said Am
        assertTrue(r.keyLabel().contains("set by you"))
    }
}
