package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playing while still decoding must not cost the loop its guarantee: what comes
 * out has to be the file repeated, sample for sample, even though the file did not
 * exist yet when playback started.
 */
class StreamClipTest {

    private fun clip(frames: Int, channels: Int = 2, expected: Int = 0) =
        StreamClip(44100, channels, FormatInfo("t", "t", "PCM 16", 44100, channels), if (expected > 0) expected else frames)

    @Test
    fun whatComesOutIsTheSourceRepeated() {
        val source = ShortArray(1000) { (it % 997).toShort() }
        val c = clip(500)
        // the decoder delivers in uneven pieces, as a real one does
        var written = 0
        while (written < source.size) {
            val n = minOf(37, source.size - written)
            c.append(source.copyOfRange(written, written + n), n)
            written += n
        }
        c.finish()

        val feeder = StreamFeeder(c)
        val out = ShortArray(3000)
        val buf = ShortArray(128)
        var got = 0
        while (got < out.size) {
            val n = feeder.fill(buf, minOf(128, out.size - got))
            if (n <= 0) break
            System.arraycopy(buf, 0, out, got, n)
            got += n
        }
        assertEquals(3000, got)
        for (i in 0 until 3000) {
            assertEquals("sample $i", source[i % source.size].toInt(), out[i].toInt())
        }
        assertEquals(2, feeder.loopsCompleted)
    }

    @Test
    fun playbackCanStartBeforeDecodingHasFinished() {
        val c = clip(2000)
        val source = ShortArray(4000) { (it % 313).toShort() }
        // half of it is there; the rest arrives while the feeder is already reading
        c.append(source.copyOfRange(0, 2000), 2000)
        val producer = Thread {
            var at = 2000
            while (at < source.size) {
                Thread.sleep(2)
                val n = minOf(200, source.size - at)
                c.append(source.copyOfRange(at, at + n), n)
                at += n
            }
            c.finish()
        }
        producer.start()

        val feeder = StreamFeeder(c)
        val out = ShortArray(4000)
        val buf = ShortArray(256)
        var got = 0
        while (got < out.size) {
            val n = feeder.fill(buf, minOf(256, out.size - got))
            if (n <= 0) break
            System.arraycopy(buf, 0, out, got, n)
            got += n
        }
        producer.join()
        assertEquals(4000, got)
        for (i in 0 until 4000) assertEquals("sample $i", source[i].toInt(), out[i].toInt())
    }

    @Test
    fun theBufferGrowsWhenTheContainerLied() {
        // the duration in the header is an estimate: more data than announced must
        // be kept, not truncated
        val c = clip(frames = 10, expected = 10)
        val piece = ShortArray(500) { 7 }
        repeat(10) { c.append(piece, piece.size) }
        c.finish()
        assertEquals(5000, c.filled)
        assertTrue(c.samples.size >= 5000)
        assertTrue(c.samples.take(5000).all { it.toInt() == 7 })
    }

    @Test
    fun progressGoesFromNothingToAll() {
        val c = clip(1000)
        assertEquals(0f, c.progress, 0.001f)
        c.append(ShortArray(1000), 1000)
        assertTrue(c.progress > 0f && c.progress < 1f)
        c.finish()
        assertEquals(1f, c.progress, 0.0001f)
    }

    @Test
    fun aFailedDecodeIsReportedAndDoesNotHang() {
        val c = clip(100)
        c.append(ShortArray(100), 100)
        c.fail("broken file")
        assertEquals("broken file", c.error)
        assertTrue(c.complete)
        val feeder = StreamFeeder(c)
        assertEquals(64, feeder.fill(ShortArray(64), 64))
    }

    @Test
    fun theLastFrameIsNeverHalfAFrame() {
        val c = clip(100, channels = 2)
        c.append(ShortArray(101), 101)                 // an odd number of samples
        c.finish()
        assertEquals(0, c.filled % 2)
    }

    // ------------------------------------------------------ coming in part way

    @Test
    fun theFirstPassCanStartPartWayIn() {
        val source = ShortArray(1000) { (it % 997).toShort() }
        val c = clip(500)
        c.append(source, source.size)
        c.finish()

        val feeder = StreamFeeder(c, startAt = 400)
        val buf = ShortArray(100)
        assertEquals(100, feeder.fill(buf, 100))
        for (i in 0 until 100) assertEquals("sample $i", source[400 + i].toInt(), buf[i].toInt())
        assertEquals(500, feeder.position)
    }

    @Test
    fun seekingForwardWaitsForTheDecoderInsteadOfGivingUp() {
        // the case the app cares about: the slider is dragged to the middle of a
        // track that is still being decoded. The honest answer is a short wait,
        // not silence and not a jump back to the beginning.
        val c = clip(2000)
        val source = ShortArray(4000) { (it % 313).toShort() }
        c.append(source.copyOfRange(0, 1000), 1000)
        val producer = Thread {
            var at = 1000
            while (at < source.size) {
                Thread.sleep(2)
                val n = minOf(200, source.size - at)
                c.append(source.copyOfRange(at, at + n), n)
                at += n
            }
            c.finish()
        }
        producer.start()

        val feeder = StreamFeeder(c, startAt = 3000)
        val buf = ShortArray(500)
        val n = feeder.fill(buf, 500)
        producer.join()
        assertEquals(500, n)
        for (i in 0 until 500) assertEquals("sample $i", source[3000 + i].toInt(), buf[i].toInt())
    }

    @Test
    fun anAbortEndsTheWaitAtOnce() {
        // a stop while the feeder is waiting for a decoder that is behind: it has
        // to come back immediately, or the stop button appears not to work
        val c = clip(2000)
        c.append(ShortArray(100) { 1 }, 100)          // never finished, never grows
        val feeder = StreamFeeder(c, startAt = 0)
        val buf = ShortArray(500)
        val waiter = Thread { Thread.sleep(50); feeder.abort() }
        waiter.start()
        val began = System.nanoTime()
        val n = feeder.fill(buf, 500)
        val tookMs = (System.nanoTime() - began) / 1_000_000
        waiter.join()
        assertEquals(100, n)                          // what there was, and no more
        assertTrue("took $tookMs ms", tookMs < 5_000)
    }

    @Test
    fun aCancelledDecodeEndsTheWaitToo() {
        val c = clip(2000)
        c.append(ShortArray(100) { 1 }, 100)
        c.cancelled = true
        val feeder = StreamFeeder(c)
        assertEquals(100, feeder.fill(ShortArray(500), 500))
    }

    @Test
    fun anAbsurdEntryPointIsClampedInsteadOfCrashing() {
        val c = clip(500)
        c.append(ShortArray(1000) { 7 }, 1000)
        c.finish()
        assertTrue(StreamFeeder(c, startAt = 9_999_999).position in 0..1000)
        assertEquals(0, StreamFeeder(c, startAt = -20).position)
    }
}

/**
 * The memory behaviour, which is what actually broke: a ten minute track was being
 * held twice at once, and the next track then had nowhere to go.
 */
class StreamClipMemoryTest {

    @Test
    fun theFinishedClipSharesTheArrayInsteadOfCopyingIt() {
        val sc = StreamClip(44100, 2, FormatInfo("t", "t", "PCM 16", 44100, 2), 100_000)
        sc.append(ShortArray(200_000) { 7 }, 200_000)
        sc.finish()
        val clip = sc.toClip()
        assertSame("the whole track was copied a second time", sc.samples, clip.samples)
    }

    @Test
    fun theSlackIsTrimmedOnceOnFinishNotOnEveryToClip() {
        // the container over-reports: 100000 frames claimed, 80000 delivered.
        // (Above the one second floor the constructor puts on the first allocation.)
        val sc = StreamClip(44100, 2, FormatInfo("t", "t", "PCM 16", 44100, 2), 100_000)
        assertEquals(200_000, sc.samples.size)
        sc.append(ShortArray(160_000) { 3 }, 160_000)
        assertEquals(200_000, sc.samples.size)         // still the roomy one
        sc.finish()
        assertEquals(160_000, sc.samples.size)         // trimmed to what arrived
        assertSame(sc.samples, sc.toClip().samples)    // and shared from here on
        assertSame(sc.samples, sc.toClip().samples)    // every time, not just the first
    }

    @Test
    fun growingDoesNotDoubleTheAllocation() {
        // an under-reporting container: 100 frames claimed, far more delivered.
        // Doubling at ninety six megabytes is what filled the heap.
        val sc = StreamClip(44100, 1, FormatInfo("t", "t", "PCM 16", 44100, 1), 100_000)
        val before = sc.samples.size
        sc.append(ShortArray(before + 10) { 1 }, before + 10)
        assertTrue("grew to ${sc.samples.size} from $before", sc.samples.size < before * 2)
        assertTrue(sc.samples.size >= before + 10)
    }

    @Test
    fun growingStillKeepsEverySampleThatWasWritten() {
        val sc = StreamClip(44100, 1, FormatInfo("t", "t", "PCM 16", 44100, 1), 10)
        val total = 100_000
        var written = 0
        while (written < total) {
            sc.append(ShortArray(1000) { ((written + it) % 30000).toShort() }, 1000)
            written += 1000
        }
        sc.finish()
        val out = sc.toClip().samples
        assertEquals(total, out.size)
        for (i in 0 until total) assertEquals("sample $i", i % 30000, out[i].toInt())
    }
}
