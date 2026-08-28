package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming a file that came from somewhere else.
 *
 * This looks like tidiness and is not. MidiFile.isMidi decides by the name alone
 * whether a file is read as a score or handed to the audio decoder, and that
 * decision is taken before a byte is read. A score arriving as "download" with no
 * extension goes to the wrong reader and fails for a reason nobody could guess
 * from the message.
 */
class IncomingTest {

    @Test
    fun aScoreWithNoExtensionIsStillRecognisedAsAScore() {
        val name = Incoming.nameFor("download", "audio/midi")
        assertTrue("got $name", MidiFile.isMidi(name))
    }

    @Test
    fun aNameThatAlreadySaysWhatItIsIsLeftAlone() {
        assertEquals("Blue Bossa.mid", Incoming.nameFor("Blue Bossa.mid", "audio/midi"))
        assertEquals("take.MIDI", Incoming.nameFor("take.MIDI", "audio/midi"))
        assertEquals("loop.wav", Incoming.nameFor("loop.wav", "audio/x-wav"))
        // the sender knew better than the declared type, which is often wrong
        assertEquals("loop.wav", Incoming.nameFor("loop.wav", "application/octet-stream"))
    }

    @Test
    fun anUnknownExtensionIsKeptAndTheRealOneAdded() {
        // "score.bin" is not a lie to be corrected, it is a name to be completed
        assertEquals("score.bin.mid", Incoming.nameFor("score.bin", "audio/midi"))
    }

    @Test
    fun aTypeThatSaysNothingLeavesTheNameAsItIs() {
        assertEquals("mystery", Incoming.nameFor("mystery", "application/octet-stream"))
        assertEquals("mystery", Incoming.nameFor("mystery", null))
    }

    @Test
    fun aFileWithNoNameAtAllStillGetsOne() {
        assertEquals("shared.mp3", Incoming.nameFor(null, "audio/mpeg"))
        assertEquals("shared.mid", Incoming.nameFor("   ", "audio/midi"))
        assertEquals("shared", Incoming.nameFor("", null))
    }

    @Test
    fun aNameCannotCarryAPathIntoTheFolder() {
        assertEquals("_etc_passwd.mp3", Incoming.nameFor("/etc/passwd", "audio/mpeg"))
    }

    @Test
    fun theUsualTypesAllHaveAnExtension() {
        assertEquals(".mid", Incoming.extensionFor("audio/midi"))
        assertEquals(".mid", Incoming.extensionFor("application/x-midi"))
        assertEquals(".mp3", Incoming.extensionFor("audio/mpeg"))
        assertEquals(".wav", Incoming.extensionFor("audio/x-wav"))
        assertEquals(".flac", Incoming.extensionFor("audio/flac"))
        assertEquals(".m4a", Incoming.extensionFor("audio/mp4"))
        assertEquals(".ogg", Incoming.extensionFor("application/ogg"))
        assertNull(Incoming.extensionFor("text/plain"))
        assertNull(Incoming.extensionFor(null))
    }

    @Test
    fun aTypeWithACharsetOnItIsStillTheSameType() {
        // senders do this, and a naive comparison then matches nothing
        assertEquals(".mid", Incoming.extensionFor("audio/midi; charset=binary"))
        assertEquals(".mp3", Incoming.extensionFor("AUDIO/MPEG"))
    }

    @Test
    fun aNameImpliesATypeWhenTheSenderDeclaredNone() {
        assertEquals("audio/midi", Incoming.mimeFor("tune.MID"))
        assertEquals("audio/mpeg", Incoming.mimeFor("tune.mp3"))
        assertEquals("audio/x-wav", Incoming.mimeFor("loop.wav"))
        assertEquals("application/octet-stream", Incoming.mimeFor("whatever"))
    }

    @Test
    fun theNameAndTheTypeAgreeWithEachOtherBothWays() {
        // whatever a file is called after nameFor, mimeFor must recognise it, or a
        // copy would be written into the folder under a type that contradicts it
        for (mime in listOf("audio/midi", "audio/mpeg", "audio/x-wav", "audio/flac",
                            "audio/mp4", "audio/ogg", "audio/opus")) {
            val name = Incoming.nameFor("thing", mime)
            assertTrue("$mime became $name", Incoming.mimeFor(name) != "application/octet-stream")
        }
    }
}
