package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silence detector, checked against takes built on purpose.
 *
 * The point of these is not that the numbers come out pretty. It is that the two
 * ways of getting this wrong are caught: declaring a performance where there is
 * only hiss, and cutting into the performance itself. Every case below is built
 * with a KNOWN noise floor and a KNOWN sound, so the answer can be checked against
 * arithmetic rather than against taste.
 */
class SilenceTest {

    private val sr = 44100

    /** Deterministic pseudo-noise: a test that changes its mind is worse than none. */
    private fun noise(frames: Int, amplitude: Int, seed: Long = 12345L): ShortArray {
        var s = seed
        return ShortArray(frames) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val r = ((s shr 33).toInt() % 1000) / 1000.0      // -1 .. 1
            (r * amplitude).toInt().toShort()
        }
    }

    private fun tone(frames: Int, amplitude: Int, freq: Double = 220.0): ShortArray =
        ShortArray(frames) {
            (Math.sin(2 * Math.PI * freq * it / sr) * amplitude).toInt().toShort()
        }

    /** noise everywhere, with a tone added over [at, at+len). */
    private fun take(totalMs: Int, atMs: Int, lenMs: Int, floor: Int, level: Int): ShortArray {
        val total = sr * totalMs / 1000
        val out = noise(total, floor)
        val at = sr * atMs / 1000
        val body = tone(sr * lenMs / 1000, level)
        for (i in body.indices) {
            val j = at + i
            if (j < total) out[j] = (out[j] + body[i]).toShort()
        }
        return out
    }

    @Test
    fun aTakeWithDeadAirAtBothEndsIsTrimmedToThePerformance() {
        // 5 s of hiss, with 2 s of tone starting at 1.5 s
        val s = take(5000, 1500, 2000, floor = 200, level = 8000)
        val b = Silence.detect(s, 1, sr)
        assertTrue("should have found something to cut", b.usable)

        // the head may only be cut, never eaten into the sound
        assertTrue("start ${b.start} is past the tone", b.start <= sr * 1500 / 1000)
        assertTrue("start ${b.start} cut too little", b.start >= sr * 1300 / 1000)

        // and the tail keeps everything up to the last sound, plus the margin
        assertTrue("end ${b.end} cut into the tone", b.end >= sr * 3500 / 1000)
        assertTrue("end ${b.end} cut too little", b.end <= sr * 3800 / 1000)
    }

    @Test
    fun aTakeOfNothingButHissIsLeftAlone() {
        val s = noise(sr * 4, 300)
        val b = Silence.detect(s, 1, sr)
        assertFalse("hiss must not look like a performance", b.usable)
        assertEquals(0, b.start)
        assertEquals(s.size, b.end)
    }

    @Test
    fun trueSilenceIsLeftAlone() {
        val b = Silence.detect(ShortArray(sr * 3), 1, sr)
        assertFalse(b.usable)
        assertEquals(sr * 3, b.frames)
    }

    @Test
    fun aTakeThatStartsPlayingImmediatelyKeepsItsFirstSample() {
        // tone from frame 0, hiss after it
        val s = take(4000, 0, 1500, floor = 200, level = 8000)
        val b = Silence.detect(s, 1, sr)
        assertTrue(b.usable)
        assertEquals("nothing may be cut from the head", 0, b.start)
        assertTrue("the tail should still be cut", b.end < s.size)
    }

    @Test
    fun aTakeThatIsPlayingWhenItStopsKeepsItsLastSample() {
        val s = take(4000, 2000, 2000, floor = 200, level = 8000)
        val b = Silence.detect(s, 1, sr)
        assertTrue(b.usable)
        assertEquals("nothing may be cut from the tail", s.size, b.end)
        assertTrue("the head should still be cut", b.start > 0)
    }

    @Test
    fun aDecayingNoteIsNotTruncatedWhileItIsStillAboveTheNoise() {
        val total = sr * 5
        val out = noise(total, 150)
        val start = sr * 1
        // a note that decays over two seconds from loud to just above the floor
        val len = sr * 2
        for (i in 0 until len) {
            val env = Math.exp(-3.0 * i / len)
            val v = Math.sin(2 * Math.PI * 196.0 * i / sr) * 9000 * env
            val j = start + i
            out[j] = (out[j] + v.toInt()).toShort()
        }
        val b = Silence.detect(out, 1, sr)
        assertTrue(b.usable)
        // the note is inaudible well before its nominal end; what must not happen is
        // the cut landing in the loud half of the decay
        assertTrue("end ${b.end} truncated the decay", b.end >= sr * 2)
    }

    @Test
    fun aTakeTooShortToJudgeIsLeftAlone() {
        val b = Silence.detect(tone(sr / 20, 8000), 1, sr)   // 50 ms
        assertFalse(b.usable)
    }

    @Test
    fun aTrimThatWouldLeaveAlmostNothingIsRefused() {
        // a single click in the middle of four seconds of hiss
        val total = sr * 4
        val s = noise(total, 200)
        val at = total / 2
        for (i in 0 until 20) s[at + i] = 12000
        val b = Silence.detect(s, 1, sr)
        // either it refuses, or it keeps at least the minimum - never a 20 sample file
        if (b.usable) assertTrue(b.frames >= sr * Silence.MIN_KEEP_MS / 1000)
    }

    @Test
    fun theCutsLandNearAZeroCrossing() {
        val s = take(5000, 1500, 2000, floor = 200, level = 8000)
        val b = Silence.detect(s, 1, sr)
        assertTrue(b.usable)
        // within the snap window there is always a near-zero sample; the exact value
        // depends on the noise, so this asserts "small", not "zero"
        assertTrue("start value ${s[b.start]}", Math.abs(s[b.start].toInt()) < 200)
    }

    @Test
    fun stereoIsHandledWithoutSplittingTheChannels() {
        val mono = take(5000, 1500, 2000, floor = 200, level = 8000)
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) { stereo[i * 2] = mono[i]; stereo[i * 2 + 1] = mono[i] }
        val a = Silence.detect(mono, 1, sr)
        val b = Silence.detect(stereo, 2, sr)
        assertTrue(b.usable)
        // frame numbers, not sample numbers: the same take must give the same answer
        assertTrue("mono ${a.start} vs stereo ${b.start}", Math.abs(a.start - b.start) <= 1)
        assertTrue("mono ${a.end} vs stereo ${b.end}", Math.abs(a.end - b.end) <= 1)
    }

    @Test
    fun theOffsetsAreOffsetsAndNothingIsModified() {
        val s = take(5000, 1500, 2000, floor = 200, level = 8000)
        val copy = s.copyOf()
        val b = Silence.detect(s, 1, sr)
        assertTrue(b.usable)
        org.junit.Assert.assertArrayEquals("detect must not touch the samples", copy, s)
        // and applying them is an ordinary slice, so switching the option off restores
        val cut = LoopTrim.slice(s, 1, b.start, b.end)
        assertEquals(b.frames, cut.size)
        assertEquals(s[b.start].toInt(), cut[0].toInt())
    }

    @Test
    fun theLabelSaysHowMuchGoesFromEachEndWithADot() {
        val b = Silence.Bounds(sr * 3 / 2, sr * 4, true)
        // 1.5 s from the head, 1.0 s from the tail of a 5 s take
        assertEquals("-1.5 s / -1.0 s", Silence.describe(b, sr * 5, sr))
    }
}
