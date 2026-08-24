package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MIDI reader and writer, on files built here with known contents. */
class SmfTest {

    private val ppq = 480

    private fun twoBars(): Score {
        val notes = ArrayList<MidiNote>()
        for (bar in 0 until 2) for (beat in 0 until 4) {
            val t = (bar * 4 + beat) * ppq
            notes.add(MidiNote(t, ppq / 2, 0, 60 + beat, 100))
            if (beat % 2 == 1) notes.add(MidiNote(t, 60, Score.DRUM_CHANNEL, 38, 110))
        }
        return Score(
            ppq, notes,
            listOf(TempoPoint(0, 500_000)),
            listOf(TimeSignature(0, 4, 4)),
            listOf(KeySignature(0, 2, false)),
            listOf(ProgramChange(0, 0, 33, 0)),
            listOf(Controller(0, 0, 7, 100)),
            ppq * 8, "two bars"
        )
    }

    @Test
    fun writesAndReadsBackTheSameScore() {
        val src = twoBars()
        val back = Smf.parse(SmfWriter.write(src))
        assertEquals(src.ppq, back.ppq)
        assertEquals(src.notes.size, back.notes.size)
        assertEquals(src.endTick, back.endTick)
        assertEquals("two bars", back.title)
        assertEquals(120.0, back.bpmAt(0), 0.001)
        assertEquals(1920, back.ticksPerBar())
        assertEquals(2, back.keySignatures.first().sharps)
        assertEquals(33, back.programs.first().program)
        assertEquals(4.0, back.durationSec, 0.001)

        val a = src.notes.sortedWith(compareBy({ it.tick }, { it.channel }, { it.key }))
        val b = back.notes.sortedWith(compareBy({ it.tick }, { it.channel }, { it.key }))
        for (i in a.indices) {
            assertEquals("tick $i", a[i].tick, b[i].tick)
            assertEquals("key $i", a[i].key, b[i].key)
            assertEquals("duration $i", a[i].durTicks, b[i].durTicks)
            assertEquals("velocity $i", a[i].velocity, b[i].velocity)
        }
    }

    @Test
    fun transposeLeavesTheDrumChannelAlone() {
        val s = twoBars()
        val up = s.transpose(3)
        val melodyBefore = s.notes.filter { it.channel == 0 }.map { it.key }
        val melodyAfter = up.notes.filter { it.channel == 0 }.map { it.key }
        assertEquals(melodyBefore.map { it + 3 }, melodyAfter)
        // 38 is the snare: on channel 10 the number is a name, not a pitch
        assertTrue(up.notes.filter { it.channel == Score.DRUM_CHANNEL }.all { it.key == 38 })
    }

    @Test
    fun transposeDropsNotesThatWouldLeaveTheKeyboard() {
        val s = Score(ppq, listOf(MidiNote(0, ppq, 0, 125, 100)), listOf(TempoPoint(0, 500_000)),
            emptyList(), emptyList(), emptyList(), emptyList(), ppq, null)
        assertEquals(1, s.transpose(2).notes.size)
        assertEquals(0, s.transpose(5).notes.size)
    }

    @Test
    fun sliceKeepsTheStateInForceAtTheCut() {
        val s = twoBars()
        val bar = s.ticksPerBar()
        val cut = s.slice(bar, bar * 2)
        assertEquals(bar, cut.endTick)
        assertEquals(s.notes.count { it.tick in bar until bar * 2 }, cut.notes.size)
        // the instrument chosen before the cut has to survive it
        assertEquals(33, cut.programs.first().program)
        assertEquals(0, cut.programs.first().tick)
        assertEquals(120.0, cut.bpmAt(0), 0.001)
        assertEquals(100, cut.controllers.first { it.number == 7 }.value)
    }

    @Test
    fun sliceDropsNotesThatBeganBeforeIt() {
        val long = Score(ppq, listOf(MidiNote(0, ppq * 8, 0, 60, 100), MidiNote(ppq * 4, ppq, 0, 62, 100)),
            listOf(TempoPoint(0, 500_000)), listOf(TimeSignature(0, 4, 4)),
            emptyList(), emptyList(), emptyList(), ppq * 8, null)
        val cut = long.slice(ppq * 4, ppq * 8)
        assertEquals(1, cut.notes.size)
        assertEquals(62, cut.notes.first().key)
    }

    @Test
    fun repeatedNoteWhileStillSoundingIsNotLost() {
        // a note restarted before its note-off: a queue keeps both, one slot loses one.
        // This is exactly what four real files out of twenty-eight did.
        val bytes = rawTrack(listOf(
            byteArrayOf(0, 0x90.toByte(), 60, 100),
            byteArrayOf(0x10, 0x90.toByte(), 60, 100),
            byteArrayOf(0x20, 0x80.toByte(), 60, 64),
            byteArrayOf(0x20, 0x80.toByte(), 60, 64)
        ))
        val s = Smf.parse(bytes)
        assertEquals(2, s.notes.size)
    }

    @Test
    fun runningStatusIsUnderstood() {
        val bytes = rawTrack(listOf(
            byteArrayOf(0, 0x90.toByte(), 60, 100),
            byteArrayOf(0x10, 62, 100),                  // status byte omitted
            byteArrayOf(0x10, 60, 0),                    // velocity zero: a note off
            byteArrayOf(0x10, 62, 0)
        ))
        val s = Smf.parse(bytes)
        assertEquals(2, s.notes.size)
        assertEquals(listOf(60, 62), s.notes.map { it.key }.sorted())
    }

    @Test
    fun sustainPedalHoldsANoteBeyondItsNoteOff() {
        val held = rawTrack(listOf(
            byteArrayOf(0, 0xB0.toByte(), 64, 127),      // pedal down
            byteArrayOf(0, 0x90.toByte(), 60, 100),
            byteArrayOf(0x10, 0x80.toByte(), 60, 64),    // released, but the pedal is down
            byteArrayOf(0x60, 0xB0.toByte(), 64, 0)      // pedal up
        ))
        val s = Smf.parse(held)
        assertEquals(1, s.notes.size)
        assertTrue("the note should last until the pedal is lifted",
            s.notes.first().durTicks > 0x60)
    }

    @Test
    fun rejectsSomethingThatIsNotAMidiFile() {
        var failed = false
        try {
            Smf.parse("this is not a MIDI file at all".toByteArray())
        } catch (e: MalformedMidi) {
            failed = true
        }
        assertTrue("a text file must not parse as a score", failed)
    }

    @Test
    fun secondsFollowTheTempoMap() {
        val s = Score(ppq, listOf(MidiNote(0, ppq, 0, 60, 100)),
            listOf(TempoPoint(0, 500_000), TempoPoint(ppq * 4, 250_000)),
            listOf(TimeSignature(0, 4, 4)), emptyList(), emptyList(), emptyList(), ppq * 8, null)
        assertEquals(2.0, s.secondsAt(ppq * 4), 0.001)          // four beats at 120
        assertEquals(3.0, s.secondsAt(ppq * 8), 0.001)          // four more at 240
        assertNotNull(s.usedChannels)
    }

    /** Wraps event bytes into a one-track format 0 file. */
    private fun rawTrack(events: List<ByteArray>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        for (e in events) body.write(e)
        body.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
        val track = body.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1))
        out.write(byteArrayOf(((ppq shr 8) and 0xFF).toByte(), (ppq and 0xFF).toByte()))
        out.write("MTrk".toByteArray())
        out.write(byteArrayOf(
            ((track.size shr 24) and 0xFF).toByte(), ((track.size shr 16) and 0xFF).toByte(),
            ((track.size shr 8) and 0xFF).toByte(), (track.size and 0xFF).toByte()))
        out.write(track)
        return out.toByteArray()
    }
}
