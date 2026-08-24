package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
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
}
