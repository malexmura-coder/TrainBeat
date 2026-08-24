package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the player prepares before the sound starts, and the train ramp on top of it. */
class RendererTest {

    private fun clip(seconds: Double = 1.0): AudioClip {
        val sr = 44100
        val frames = (sr * seconds).toInt()
        val s = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (Math.sin(2.0 * Math.PI * 220.0 * i / sr) * 8000).toInt().toShort()
            s[i * 2] = v
            s[i * 2 + 1] = v
        }
        return AudioClip(s, sr, 2, FormatInfo("test", "test", "PCM 16 bit", sr, 2))
    }

    @Test
    fun theDefaultSettingIsRecognisedAsNeedingNothing() {
        assertTrue(Renderer.isPlain(1.0f, 0))
        assertTrue(!Renderer.isPlain(0.95f, 0))
        assertTrue(!Renderer.isPlain(1.0f, 2))
    }

    @Test
    fun atTheDefaultSettingTheOriginalSamplesAreUsed() {
        Renderer.clearCache()
        val c = clip()
        assertSame("no copy, no work", c.samples, Renderer.forAudio(c, 1.0f, 0, 1L))
    }

    @Test
    fun aRenderedSettingIsCachedAndComesBackIdentical() {
        Renderer.clearCache()
        val c = clip()
        val first = Renderer.forAudio(c, 0.8f, 0, 7L)
        val second = Renderer.forAudio(c, 0.8f, 0, 7L)
        assertSame("the second ask must not render again", first, second)
        assertNotNull(Renderer.cached(0.8f, 0, 7L))
        assertNull("another file must not get this one's audio", Renderer.cached(0.8f, 0, 8L))
    }

    @Test
    fun theCacheDoesNotGrowWithoutBound() {
        Renderer.clearCache()
        val c = clip(0.3)
        for (i in 1..8) Renderer.forAudio(c, 0.5f + i * 0.05f, 0, 1L)
        var kept = 0
        for (i in 1..8) if (Renderer.cached(0.5f + i * 0.05f, 0, 1L) != null) kept++
        assertTrue("kept $kept", kept in 1..4)
    }

    @Test
    fun theRampAskesForTheRightSpeedAtEachRepetition() {
        assertEquals(1.0f, Renderer.rampSpeed(1.0f, 0.05f, 0), 0.0001f)
        assertEquals(1.05f, Renderer.rampSpeed(1.0f, 0.05f, 1), 0.0001f)
        assertEquals(1.10f, Renderer.rampSpeed(1.0f, 0.05f, 2), 0.0001f)
    }

    @Test
    fun theTrainRendererHandsOverOneRepetitionAhead() {
        val asked = ArrayList<Float>()
        val tr = TrainRenderer(1.0f, 0.05f) { speed ->
            synchronized(asked) { asked.add(speed) }
            ShortArray(8) { speed.toInt().toShort() }
        }
        try {
            // it prepares repetition 1 by itself; give the worker a moment
            var buf: ShortArray? = null
            for (i in 0 until 100) {
                buf = tr.nextFor(1)
                if (buf != null) break
                Thread.sleep(20)
            }
            assertNotNull("the next repetition should be ready in under two seconds", buf)
            synchronized(asked) {
                assertTrue("it must render the ramp speed, got $asked", asked.contains(1.05f))
            }
        } finally {
            tr.close()
        }
    }

    @Test
    fun withNoRampNothingIsPreparedAtAll() {
        val tr = TrainRenderer(1.0f, 0f) { ShortArray(4) }
        try {
            assertNull(tr.nextFor(1))
            assertEquals(0, tr.misses)
        } finally {
            tr.close()
        }
    }

    @Test
    fun aRepetitionThatIsNotReadyIsCountedAndNotWaitedFor() {
        val tr = TrainRenderer(1.0f, 0.05f) { Thread.sleep(5000); ShortArray(4) }
        try {
            val t0 = System.currentTimeMillis()
            val buf = tr.nextFor(1)
            val ms = System.currentTimeMillis() - t0
            assertNull("the audio thread must never block", buf)
            assertTrue("it returned in $ms ms", ms < 200)
            assertEquals(1, tr.misses)
        } finally {
            tr.close()
        }
    }
}
