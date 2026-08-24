package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves on paper that the loop has no gap: the stream produced must be exactly
 * the file repeated, sample by sample, even when the block size is not a divisor
 * of the track length - which is the normal case.
 */
class LoopFeederTest {

    @Test
    fun endlessStreamEqualsRepeatedTrack() {
        val src = ShortArray(1000) { (it * 31 - 15000).toShort() }
        val feeder = LoopFeeder(src)
        val chunk = ShortArray(384)          // does not divide 1000: the seam falls inside a block
        val giri = 7
        val out = ShortArray(src.size * giri)
        var written = 0
        while (written < out.size) {
            val n = feeder.fill(chunk, minOf(chunk.size, out.size - written))
            chunk.copyInto(out, written, 0, n)
            written += n
        }
        for (i in out.indices) {
            assertEquals("sample $i", src[i % src.size].toInt(), out[i].toInt())
        }
    }

    @Test
    fun seamWithoutInsertedOrLostSamples() {
        val src = ShortArray(101) { (it + 1).toShort() }
        val feeder = LoopFeeder(src)
        val out = ShortArray(src.size * 3)
        feeder.fill(out, out.size)
        // the last sample of the track must be followed immediately by the first
        assertEquals(src[src.size - 1].toInt(), out[src.size - 1].toInt())
        assertEquals(src[0].toInt(), out[src.size].toInt())
        assertEquals(src[src.size - 1].toInt(), out[src.size * 2 - 1].toInt())
        assertEquals(src[0].toInt(), out[src.size * 2].toInt())
    }

    @Test
    fun trackShorterThanTheBlock() {
        val src = shortArrayOf(1, 2, 3, 4, 5)
        val feeder = LoopFeeder(src)
        val out = ShortArray(64)
        val n = feeder.fill(out, out.size)
        assertEquals(64, n)
        for (i in 0 until 64) assertEquals(src[i % 5].toInt(), out[i].toInt())
    }

    @Test
    fun positionAdvancesCyclically() {
        val src = ShortArray(10)
        val feeder = LoopFeeder(src)
        feeder.fill(ShortArray(25), 25)
        assertEquals(5, feeder.position)
        feeder.reset()
        assertEquals(0, feeder.position)
    }

    @Test
    fun emptyTrackDoesNotHang() {
        assertEquals(0, LoopFeeder(ShortArray(0)).fill(ShortArray(16), 16))
    }
}

/** The v4 addition: the loop can change at the seam, and only there. */
class LoopFeederSwapTest {

    @Test
    fun theReplacementStartsExactlyAtTheSeam() {
        val first = ShortArray(10) { 1 }
        val second = ShortArray(6) { 2 }
        val feeder = LoopFeeder(first) { loop -> if (loop == 1) second else null }
        val out = ShortArray(22)
        assertEquals(22, feeder.fill(out, 22))
        // ten ones, then the replacement repeating: 2 2 2 2 2 2 2 2 2 2 2 2
        for (i in 0 until 10) assertEquals("sample $i", 1, out[i].toInt())
        for (i in 10 until 22) assertEquals("sample $i", 2, out[i].toInt())
    }

    @Test
    fun withoutAReplacementItKeepsRepeatingTheSame() {
        val src = shortArrayOf(1, 2, 3, 4)
        val feeder = LoopFeeder(src) { null }
        val out = ShortArray(10)
        feeder.fill(out, 10)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 1, 2, 3, 4, 1, 2), out)
        assertEquals(2, feeder.loopsCompleted)
    }

    @Test
    fun repetitionsAreCounted() {
        val feeder = LoopFeeder(ShortArray(4))
        feeder.fill(ShortArray(16), 16)
        assertEquals(4, feeder.loopsCompleted)
        feeder.reset()
        assertEquals(0, feeder.loopsCompleted)
    }
}
