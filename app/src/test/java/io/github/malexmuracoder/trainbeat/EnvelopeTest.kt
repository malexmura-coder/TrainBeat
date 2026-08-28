package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The waveform picture, checked as arithmetic.
 *
 * The picture exists to answer one question - is the silence at the start or at the
 * end - so these tests are mostly about that: a quiet stretch must read as quiet
 * and a loud stretch as loud, at the right horizontal position, and a whisper must
 * not be blown up until it looks like a performance.
 */
class EnvelopeTest {

    private fun tone(frames: Int, amp: Int): ShortArray =
        ShortArray(frames) { (Math.sin(2 * Math.PI * it / 64.0) * amp).toInt().toShort() }

    /** silence for [quietFrames], then a tone. */
    private fun silenceThenTone(quietFrames: Int, loudFrames: Int, amp: Int): ShortArray {
        val out = ShortArray(quietFrames + loudFrames)
        val body = tone(loudFrames, amp)
        body.copyInto(out, quietFrames)
        return out
    }

    @Test
    fun itProducesExactlyOnePairPerColumn() {
        val s = Envelope.of(tone(10000, 8000), 1, 300)
        assertEquals(300, s.columns)
        assertEquals(300, s.min.size)
        assertEquals(300, s.max.size)
    }

    @Test
    fun silenceIsDrawnAsALine() {
        val s = Envelope.of(ShortArray(5000), 1, 100)
        assertEquals(0f, s.peak, 0f)
        assertEquals(1f, s.gain, 0f)
        for (i in 0 until s.columns) {
            assertEquals(0f, s.min[i], 0f)
            assertEquals(0f, s.max[i], 0f)
        }
    }

    @Test
    fun theQuietHalfIsQuietAndTheLoudHalfIsLoud() {
        // silence for the first half, then a tone: exactly the case this picture
        // exists for - telling which end the dead air is at
        val s = Envelope.of(silenceThenTone(22050, 22050, 8000), 1, 100)
        for (i in 0 until 45) {
            assertTrue("column $i should be silent, got ${s.max[i]}", Math.abs(s.max[i]) < 0.02f)
        }
        for (i in 55 until 100) {
            assertTrue("column $i should be loud, got ${s.max[i]}", s.max[i] > 0.5f)
        }
    }

    @Test
    fun andTheOtherWayRound() {
        val loud = tone(22050, 8000)
        val s = Envelope.of(loud + ShortArray(22050), 1, 100)
        for (i in 0 until 45) assertTrue(s.max[i] > 0.5f)
        for (i in 55 until 100) assertTrue(Math.abs(s.max[i]) < 0.02f)
    }

    @Test
    fun aQuietTakeIsMadeVisibleButNotInvented() {
        // -40 dBFS: without gain this is invisible, with unlimited gain it would look
        // like a full performance
        val s = Envelope.of(tone(20000, 328), 1, 80)
        assertTrue("gain ${s.gain} did not help", s.gain > 4f)
        assertTrue("gain ${s.gain} exceeded the cap", s.gain <= Envelope.MAX_GAIN)
        assertTrue("the shape should not fill the height", s.max.max() < 0.9f)
        assertTrue("but it should be visible", s.max.max() > 0.05f)
    }

    @Test
    fun aNormalTakeFillsTheHeight() {
        val s = Envelope.of(tone(20000, 16000), 1, 80)   // -6 dBFS
        assertTrue("peak ${s.peak}", Math.abs(s.peak - 0.5f) < 0.05f)
        assertTrue("should nearly fill: ${s.max.max()}", s.max.max() > 0.85f)
        assertTrue(s.max.max() <= 1f)
    }

    @Test
    fun aFullScaleTakeIsNotAmplifiedAtAll() {
        val s = Envelope.of(tone(20000, 32000), 1, 80)
        assertEquals(1f, s.gain, 0.001f)
    }

    @Test
    fun theMinAndMaxAreBothKeptSoATransientStillShows() {
        val s = ShortArray(10000)
        s[5000] = 30000        // one single spike in silence
        val e = Envelope.of(s, 1, 100)
        val col = e.max.indices.maxByOrNull { e.max[it] }!!
        assertTrue("the spike vanished", e.max[col] > 0.5f)
        assertTrue("the spike should be in the middle, was at $col", col in 45..55)
    }

    @Test
    fun everyColumnIsFilledEvenWhenTheTrackIsShorterThanTheCanvas() {
        val e = Envelope.of(tone(37, 20000), 1, 300)
        assertEquals(300, e.columns)
        // no column may be left as an untouched hole in the middle of a loud track
        val loud = (0 until 300).count { Math.abs(e.max[it]) > 0.01f || Math.abs(e.min[it]) > 0.01f }
        assertTrue("only $loud of 300 columns were filled", loud > 250)
    }

    @Test
    fun stereoTakesTheExtremeOfBothChannels() {
        // left silent, right loud: the picture must show the loud one
        val st = ShortArray(2000)
        for (i in 0 until 1000) { st[i * 2] = 0; st[i * 2 + 1] = 20000 }
        val e = Envelope.of(st, 2, 50)
        assertTrue("the loud channel was lost", e.max.max() > 0.85f)
    }

    @Test
    fun absurdArgumentsGiveAnEmptyShapeInsteadOfCrashing() {
        assertEquals(0, Envelope.of(ShortArray(0), 1, 100).columns)
        assertEquals(0, Envelope.of(tone(100, 100), 1, 0).columns)
        assertEquals(0, Envelope.of(tone(100, 100), 0, 100).columns)
    }
}
