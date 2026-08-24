package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** What the trimmer writes must be readable by our own parser, byte for byte. */
class WavWriterTest {

    @Test
    fun writtenFileReadsBackIdentical() {
        val samples = ShortArray(4000) { ((it * 37) % 30000 - 15000).toShort() }
        val out = ByteArrayOutputStream()
        WavWriter.write(out, samples, 44100, 2)
        val clip = WavReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(44100, clip.sampleRate)
        assertEquals(2, clip.channels)
        assertEquals(samples.size, clip.samples.size)
        for (i in samples.indices) assertEquals("sample $i", samples[i].toInt(), clip.samples[i].toInt())
    }

    @Test
    fun monoAndOddLengthsSurvive() {
        val samples = ShortArray(1001) { (it - 500).toShort() }
        val out = ByteArrayOutputStream()
        WavWriter.write(out, samples, 22050, 1)
        val clip = WavReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, clip.channels)
        assertEquals(22050, clip.sampleRate)
        assertEquals(1001, clip.samples.size)
    }
}
