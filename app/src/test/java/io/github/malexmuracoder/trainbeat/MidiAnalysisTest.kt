package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tempo, metre and key read from a score instead of guessed from a spectrum. */
class MidiAnalysisTest {

    private val ppq = 480

    private fun score(
        notes: List<MidiNote>,
        tempos: List<TempoPoint> = listOf(TempoPoint(0, 500_000)),
        sig: List<TimeSignature> = listOf(TimeSignature(0, 4, 4)),
        keys: List<KeySignature> = emptyList(),
        endTick: Int = ppq * 16
    ) = Score(ppq, notes, tempos, sig, keys, emptyList(), emptyList(), endTick, null)

    /** A scale plus a cadence, which is enough for any profile to find the key. */
    private fun inKey(root: Int, minor: Boolean): List<MidiNote> {
        val steps = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val out = ArrayList<MidiNote>()
        var t = 0
        for (s in steps) { out.add(MidiNote(t, ppq, 0, 60 + root + s, 100)); t += ppq }
        // tonic triad, held, with the root in the bass
        out.add(MidiNote(t, ppq * 4, 0, 48 + root, 110))
        out.add(MidiNote(t, ppq * 4, 0, 60 + root, 100))
        out.add(MidiNote(t, ppq * 4, 0, 60 + root + (if (minor) 3 else 4), 100))
        out.add(MidiNote(t, ppq * 4, 0, 60 + root + 7, 100))
        return out
    }

    @Test
    fun tempoComesFromTheTempoMap() {
        val r = MidiAnalysis.analyse(score(inKey(0, false)))
        assertEquals(120f, r.bpm, 0.01f)
        assertFalse(r.bpmVariable)
    }

    @Test
    fun thePredominantTempoWins() {
        // a stray event for one beat, then the real tempo for the rest
        val r = MidiAnalysis.analyse(score(
            inKey(0, false),
            tempos = listOf(TempoPoint(0, 250_000), TempoPoint(ppq, 500_000))
        ))
        assertEquals(120f, r.bpm, 0.01f)
        assertTrue("a real change is admitted", r.bpmVariable)
    }

    @Test
    fun aTinyRitardandoIsNotCalledVariable() {
        val r = MidiAnalysis.analyse(score(
            inKey(0, false),
            tempos = listOf(TempoPoint(0, 500_000), TempoPoint(ppq * 8, 505_000))
        ))
        assertFalse("one per cent is not a tempo change", r.bpmVariable)
    }

    @Test
    fun metreComesFromTheTimeSignature() {
        val r = MidiAnalysis.analyse(score(inKey(0, false), sig = listOf(TimeSignature(0, 3, 4))))
        assertEquals(3, r.numerator)
        assertEquals(4, r.denominator)
        assertEquals(16, r.beats)
        assertEquals(5, r.bars)                     // sixteen quarter notes in three-four
        assertEquals(1, r.barsRemainder)
    }

    @Test
    fun keySignatureIsBelievedWhenItSaysSomething() {
        val r = MidiAnalysis.analyse(score(inKey(0, false), keys = listOf(KeySignature(0, 3, true))))
        assertEquals(MidiAnalysis.Source.FROM_FILE, r.keySource)
        assertEquals("F#m", r.keys.first().name)
    }

    @Test
    fun aDefaultCMajorSignatureIsNotBelievedOverTheNotes() {
        // every sequencer writes "no sharps, major" when nobody set a key, so it is
        // only taken at its word when the notes agree with it
        val r = MidiAnalysis.analyse(score(inKey(7, false), keys = listOf(KeySignature(0, 0, false))))
        assertEquals(MidiAnalysis.Source.ESTIMATED, r.keySource)
        assertEquals("G", r.keys.first().name)
    }

    @Test
    fun keyFromTheNotesAloneForEveryMajorRoot() {
        for (root in 0 until 12) {
            val probs = MidiAnalysis.estimateKey(score(inKey(root, false)))
            val top = Analyzer.topKeys(probs).first().name
            assertEquals("root $root", Analyzer.KEY_NAMES[root * 2], top)
        }
    }

    @Test
    fun keyFromTheNotesAloneForEveryMinorRoot() {
        for (root in 0 until 12) {
            val probs = MidiAnalysis.estimateKey(score(inKey(root, true)))
            val top = Analyzer.topKeys(probs).first().name
            assertEquals("root $root", Analyzer.KEY_NAMES[root * 2 + 1], top)
        }
    }

    @Test
    fun theDrumChannelIsIgnoredWhenWeighingTheKey() {
        val withDrums = inKey(0, false) + (0 until 16).map {
            MidiNote(it * ppq / 2, 60, Score.DRUM_CHANNEL, 42, 110)
        }
        val probs = MidiAnalysis.estimateKey(score(withDrums))
        assertEquals("C", Analyzer.topKeys(probs).first().name)
    }

    @Test
    fun keySignatureNumbersMapToTheRightNames() {
        assertEquals("C", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(0, false)])
        assertEquals("G", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(1, false)])
        assertEquals("D", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(2, false)])
        assertEquals("F", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(-1, false)])
        assertEquals("A#", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(-2, false)])
        assertEquals("Am", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(0, true)])
        assertEquals("Em", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(1, true)])
        assertEquals("Dm", Analyzer.KEY_NAMES[MidiAnalysis.keyIndexOf(-1, true)])
        assertEquals(-1, MidiAnalysis.keyIndexOf(9, false))
    }

    @Test
    fun aScoreWithNoTempoEventIsTheStandardHundredAndTwenty() {
        val r = MidiAnalysis.analyse(score(inKey(0, false), tempos = emptyList()))
        assertEquals(120f, r.bpm, 0.01f)
    }
}
