package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Checks the WAV parser on a desktop JVM: no phone, no emulator.
 * Each test builds a synthetic WAV and verifies the decoded samples.
 */
class WavReaderTest {

    private fun read(bytes: ByteArray): AudioClip = WavReader.read(ByteArrayInputStream(bytes))

    @Test
    fun pcm16StereoIsExact() {
        val s = shortArrayOf(0, 1, -1, 32767, -32768, 1234, -4321, 100)
        val clip = read(WavTestUtil.build(1, 2, 44100, 16, WavTestUtil.bytesPcm16(s)))
        assertEquals(44100, clip.sampleRate)
        assertEquals(2, clip.channels)
        assertEquals(4, clip.frames)
        assertSamplesEqual(s, clip.samples)
    }

    @Test
    fun pcm8IsConverted() {
        // unsigned 8-bit: 0 -> minimum, 128 -> zero, 255 -> near maximum
        val clip = read(WavTestUtil.build(1, 1, 22050, 8, WavTestUtil.bytesPcm8(intArrayOf(0, 128, 255, 64))))
        assertEquals(-32768, clip.samples[0].toInt())
        assertEquals(0, clip.samples[1].toInt())
        assertEquals(32512, clip.samples[2].toInt())
        assertEquals(-16384, clip.samples[3].toInt())
    }

    @Test
    fun pcm24IsTruncated() {
        // 24-bit -> keep the top 16: expected value is v >> 8
        val vals = intArrayOf(0x123456, 0x7FFFFF, -0x800000, -1, 0)
        val clip = read(WavTestUtil.build(1, 1, 48000, 24, WavTestUtil.bytesPcm24(vals)))
        assertEquals(48000, clip.sampleRate)
        for (i in vals.indices) {
            val expected = (vals[i] shl 8 shr 8) shr 8   // sign-extend to 24 bit, then scale
            assertEquals("sample $i", expected.toShort().toInt(), clip.samples[i].toInt())
        }
    }

    @Test
    fun pcm32IsTruncated() {
        val vals = intArrayOf(0x12345678, Int.MAX_VALUE, Int.MIN_VALUE, -1, 0)
        val clip = read(WavTestUtil.build(1, 1, 96000, 32, WavTestUtil.bytesPcm32(vals)))
        assertEquals(96000, clip.sampleRate)
        for (i in vals.indices) {
            assertEquals("sample $i", (vals[i] shr 16).toShort().toInt(), clip.samples[i].toInt())
        }
    }

    @Test
    fun float32IsClipped() {
        val f = floatArrayOf(0f, 1f, -1f, 0.5f, 2f, -3f)
        val clip = read(WavTestUtil.build(3, 1, 44100, 32, WavTestUtil.bytesFloat32(f)))
        assertEquals(0, clip.samples[0].toInt())
        assertEquals(32767, clip.samples[1].toInt())
        assertEquals(-32767, clip.samples[2].toInt())
        assertEquals(16383, clip.samples[3].toInt())
        assertEquals(32767, clip.samples[4].toInt())    // clippato
        assertEquals(-32767, clip.samples[5].toInt())   // clippato
        assertTrue(clip.info.encoding.contains("float"))
    }

    @Test
    fun float64Works() {
        val d = doubleArrayOf(0.0, 1.0, -0.25)
        val clip = read(WavTestUtil.build(3, 1, 44100, 64, WavTestUtil.bytesFloat64(d)))
        assertEquals(0, clip.samples[0].toInt())
        assertEquals(32767, clip.samples[1].toInt())
        assertEquals(-8191, clip.samples[2].toInt())
    }

    @Test
    fun extensibleWithPcmSubformat() {
        // the case of DAW-written WAVs: the real format sits in the GUID
        val s = shortArrayOf(500, -500, 1000, -1000)
        val clip = read(WavTestUtil.build(1, 2, 48000, 16, WavTestUtil.bytesPcm16(s), extensible = true))
        assertEquals(2, clip.channels)
        assertSamplesEqual(s, clip.samples)
        assertTrue(clip.info.notes.any { it.contains("EXTENSIBLE") })
    }

    @Test
    fun extraChunksAreSkipped() {
        // odd-sized bext (to exercise padding), LIST, cue: all to be skipped
        val s = shortArrayOf(7, -7, 21, -21)
        val bytes = WavTestUtil.build(
            1, 1, 44100, 16, WavTestUtil.bytesPcm16(s),
            chunksBeforeFmt = listOf("JUNK" to ByteArray(30)),
            chunksAfterFmt = listOf("bext" to ByteArray(603), "LIST" to "INFOhello".toByteArray())
        )
        val clip = read(bytes)
        assertSamplesEqual(s, clip.samples)
    }

    @Test
    fun streamingDataSize() {
        // some recorders write 0xFFFFFFFF: read to the end of the file
        val s = ShortArray(600) { (it * 37).toShort() }
        val clip = read(
            WavTestUtil.build(1, 2, 44100, 16, WavTestUtil.bytesPcm16(s), dataSizeField = 0xFFFFFFFFL)
        )
        assertEquals(600, clip.samples.size)
        assertSamplesEqual(s, clip.samples)
    }

    @Test
    fun rf64WithDs64() {
        val s = ShortArray(200) { (it - 100).toShort() }
        val data = WavTestUtil.bytesPcm16(s)
        val clip = read(
            WavTestUtil.build(
                1, 2, 48000, 16, data,
                dataSizeField = 0xFFFFFFFFL, riffId = "RF64", ds64DataSize = data.size.toLong()
            )
        )
        assertEquals(200, clip.samples.size)
        assertTrue(clip.info.container.startsWith("RF64"))
    }

    @Test
    fun g711MulawAndAlaw() {
        // G.711 reference values
        val mu = read(WavTestUtil.build(7, 1, 8000, 8, WavTestUtil.bytesPcm8(intArrayOf(0xFF, 0x00, 0x80))))
        assertEquals(0, mu.samples[0].toInt())
        assertEquals(-32124, mu.samples[1].toInt())
        assertEquals(32124, mu.samples[2].toInt())

        val a = read(WavTestUtil.build(6, 1, 8000, 8, WavTestUtil.bytesPcm8(intArrayOf(0xD5, 0x2A, 0xAA))))
        assertEquals(8, a.samples[0].toInt())
        assertEquals(-32256, a.samples[1].toInt())
        assertEquals(32256, a.samples[2].toInt())
    }

    @Test
    fun adpcmIsHandedToTheCodec() {
        // tag 0x11 = IMA ADPCM: not handled here, must be passed to the system codec
        try {
            read(WavTestUtil.build(0x11, 1, 44100, 4, ByteArray(64)))
            fail("should have thrown UnsupportedWav")
        } catch (e: WavReader.UnsupportedWav) {
            assertEquals(0x11, e.tag)
        }
    }

    @Test
    fun invalidHeaderIsRejected() {
        try {
            read(WavTestUtil.build(1, 0, 44100, 16, WavTestUtil.bytesPcm16(shortArrayOf(1, 2))))
            fail("zero channels should have been rejected")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("channels"))
        }
    }

    @Test
    fun nonWavIsRejected() {
        try {
            read("questo non e' un wav".toByteArray())
            fail("should have been rejected")
        } catch (_: Exception) {
        }
    }

    private fun assertSamplesEqual(expected: ShortArray, actual: ShortArray) {
        assertEquals("sample count", expected.size, actual.size)
        for (i in expected.indices) assertEquals("sample $i", expected[i].toInt(), actual[i].toInt())
    }
}
