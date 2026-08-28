package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/**
 * The mark a recording carries inside itself.
 *
 * The name of a file is a hint - it survives until somebody renames it. The
 * standard LIST/INFO chunk is the fact, and the trimmer uses it to decide whether
 * to trust a beat grid it should not trust.
 */
class WavMarkerTest {

    private fun tone(frames: Int, channels: Int): ShortArray =
        ShortArray(frames * channels) { ((it * 37) % 20000 - 10000).toShort() }

    @Test
    fun theRecorderMarkSurvivesAWriteAndARead() {
        val src = tone(4410, 1)
        val out = java.io.ByteArrayOutputStream()
        WavWriter.write(out, src, 44100, 1, AudioClip.RECORDER_TAG)
        val back = WavReader.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(AudioClip.RECORDER_TAG, back.info.software)
        assertTrue("the file recognises itself as a recording", back.recorded)
    }

    @Test
    fun theMarkDoesNotDisturbASingleSample() {
        val src = tone(4410, 2)
        val plain = java.io.ByteArrayOutputStream()
        val marked = java.io.ByteArrayOutputStream()
        WavWriter.write(plain, src, 44100, 2, null)
        WavWriter.write(marked, src, 44100, 2, AudioClip.RECORDER_TAG)
        val a = WavReader.read(java.io.ByteArrayInputStream(plain.toByteArray()))
        val b = WavReader.read(java.io.ByteArrayInputStream(marked.toByteArray()))
        assertEquals(a.frames, b.frames)
        assertArrayEquals(src, b.samples)
        assertArrayEquals(a.samples, b.samples)
    }

    @Test
    fun anOrdinaryFileIsNotMistakenForARecording() {
        val out = java.io.ByteArrayOutputStream()
        WavWriter.write(out, tone(2205, 2), 44100, 2)
        val back = WavReader.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(null, back.info.software)
        assertTrue(!back.recorded)
    }

    @Test
    fun theNameIsTheFallbackWhenTheMarkIsGone() {
        assertTrue(AudioClip.looksRecorded("rec_20260825_2312.wav"))
        assertTrue(AudioClip.looksRecorded("REC_20260825_2312.WAV"))
        assertTrue(!AudioClip.looksRecorded("gipsy loop.wav"))
    }

    @Test
    fun aRecordingIsNamedAfterTheMomentItWasMade() {
        val name = AudioClip.recordingName(1_700_000_000_000L)
        // shape rather than length: rec_ + eight digits + _ + four digits + .wav
        assertTrue("got $name", Regex("^rec_\\d{8}_\\d{4}\\.wav$").matches(name))
    }
}
