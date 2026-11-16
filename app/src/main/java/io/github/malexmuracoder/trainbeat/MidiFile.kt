package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.net.Uri

/**
 * Reading a MIDI document, and the small decisions around it.
 *
 * A MIDI file is tiny - a whole symphony is a few tens of kilobytes - so unlike
 * audio there is no windowing, no partial decode and no memory worry: it is read
 * whole and parsed in milliseconds. That is also why scanning a library of MIDI
 * files is effectively instant.
 */
object MidiFile {

    /** Above this a file is not a score, it is somebody's mistake. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * Whether a name is a SCORE: a MIDI file, MusicXML, or Guitar Pro 3-5. The name
     * says "isMidi" for historical reasons; everything that is a Score is read,
     * played, scanned and trimmed the same way whichever of the three it came from.
     */
    fun isMidi(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mid") || n.endsWith(".midi") || n.endsWith(".kar") ||
            MusicXml.isMusicXmlName(n) || GuitarPro.isGuitarProName(n)
    }

    /**
     * MIDI, Guitar Pro or MusicXML, told apart by the first bytes and, failing that,
     * by the name.
     */
    fun parseScore(bytes: ByteArray, name: String? = null): Score {
        val midi = bytes.size >= 4 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'T'.code.toByte() &&
            bytes[2] == 'h'.code.toByte() && bytes[3] == 'd'.code.toByte()
        if (midi) return Smf.parse(bytes)
        // Guitar Pro 3-5 begins with its own name; a file NAMED .gp3/.gp4/.gp5 that
        // does not is refused by the reader with a message that says why
        if (GuitarPro.looksLikeGuitarPro(bytes) || (name != null && GuitarPro.isGuitarProName(name))) {
            return GuitarPro.parse(bytes)
        }
        val first = bytes.firstOrNull()?.toInt() ?: 0
        val xmlLike = first == '<'.code || first == 'P'.code || first == 0xEF || first == 0xFE || first == 0xFF
        val named = name != null && MusicXml.isMusicXmlName(name)
        return if (named || xmlLike) MusicXml.parse(bytes) else Smf.parse(bytes)
    }

    fun loadScore(ctx: Context, uri: Uri, name: String? = null): Score {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) throw MalformedMidi("this file is too large to be a MIDI score")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw MalformedMidi("the file could not be opened")
        return parseScore(bytes, name)
    }

    /** What the info card shows for a score. */
    fun describe(score: Score, res: MidiAnalysis.Result): String {
        val channels = score.usedChannels.size
        val drums = if (score.hasDrums) ", drums" else ""
        return "%d notes · %d part%s%s · %d/%d".format(
            score.notes.size, channels, if (channels == 1) "" else "s", drums,
            res.numerator, res.denominator)
    }

    /**
     * A record for the library, filled from the score instead of from a spectrum.
     * Tempo and metre are read, not estimated, and the key is read when the file
     * declares one - which is why a MIDI entry deserves to be marked as such.
     */
    fun recordFor(name: String, size: Long, uri: String, score: Score, res: MidiAnalysis.Result) =
        TrackRecord(
            name = name,
            size = size,
            uri = uri,
            sampleRate = 0,
            channels = 0,
            durationMs = (score.durationSec * 1000).toLong(),
            bpm = res.bpm,
            beats = res.beats,
            bars = res.bars,
            barsRemainder = res.barsRemainder,
            keyProbs = res.keyProbs,
            manualKey = null,
            error = null,
            kind = TrackRecord.KIND_MIDI,
            fromFile = res.keySource == MidiAnalysis.Source.FROM_FILE,
            tempoVariable = res.bpmVariable
        )
}
