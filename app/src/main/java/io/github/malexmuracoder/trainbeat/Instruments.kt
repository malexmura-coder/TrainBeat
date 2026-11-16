package io.github.malexmuracoder.trainbeat

/**
 * Which instruments of a score are switched on, and what to call them.
 *
 * Free of Android, like everything else that can be got wrong quietly. A mask that
 * named the wrong channel would silence the wrong instrument, and on a piece you
 * are still learning that is a mistake you might not catch by ear.
 *
 * A MASK AND NOT A LIST. Sixteen channels fit in an Int, so the selection is one
 * number: it compares in one instruction, it goes into the render cache key without
 * ceremony, and it is stored beside the speed and the key as a seventh column
 * rather than as a nested list of its own.
 *
 * WHY MUTING IS EXACT HERE, and it is worth being clear about the difference. On
 * audio, taking an instrument out means separating it from a mixture that has
 * already been made, which is a hard problem and an approximate answer. A score is
 * not a mixture: it is a list of notes each carrying the channel it belongs to, so
 * the instrument is removed by not playing it. Nothing is subtracted from anything.
 */
object Instruments {

    /** Everything sounding, which is what an unopened mixer means. */
    const val ALL = 0xFFFF

    /** One row of the mixer: a channel of the score, with something to call it. */
    class Part(
        val channel: Int,
        val program: Int,
        val drums: Boolean,
        val label: String
    )

    /**
     * The metronome is not a channel of the score but it sits in the mixer like one,
     * and for the same reason: it is something that sounds or does not. It is bit 16
     * of the mask, which ALL does not include, so it starts switched off.
     */
    const val METRONOME = 16

    fun isOn(mask: Int, channel: Int): Boolean =
        channel in 0..METRONOME && (mask shr channel) and 1 == 1

    fun toggled(mask: Int, channel: Int): Int =
        if (channel !in 0..METRONOME) mask else mask xor (1 shl channel)

    fun metronomeOn(mask: Int): Boolean = isOn(mask, METRONOME)

    fun maskOf(channels: Collection<Int>): Int {
        var m = 0
        for (c in channels) if (c in 0..15) m = m or (1 shl c)
        return m
    }

    /**
     * The rows to show, one per channel that actually carries notes.
     *
     * The program is taken from the FIRST program change on that channel. A file
     * can change instrument half way through a channel and a few do, but naming a
     * row after the instrument it ends on would be stranger than naming it after
     * the one it starts on, and a row has space for one name.
     */
    fun partsOf(score: Score): List<Part> =
        score.usedChannels.map { ch ->
            val drums = ch == Score.DRUM_CHANNEL
            val program = score.programs.firstOrNull { it.channel == ch }?.program ?: 0
            Part(ch, program, drums, label(ch, program, drums))
        }

    /** "10 · Drums", "3 · Acoustic Bass" - the channel as a musician counts them. */
    fun label(channel: Int, program: Int, drums: Boolean): String {
        val n = channel + 1                          // channels are 1..16 to everyone but a parser
        val what = if (drums) "Drums" else GM.getOrNull(program) ?: "Program $program"
        return "$n · $what"
    }

    /** True when at least one channel carrying notes is still switched on. */
    fun anySounding(mask: Int, score: Score): Boolean =
        score.usedChannels.any { isOn(mask, it) }

    /** How many of the score's own channels are on, for a line that says so. */
    fun soundingCount(mask: Int, score: Score): Int =
        score.usedChannels.count { isOn(mask, it) }

    /**
     * The General MIDI instrument names, in program order.
     *
     * Written out rather than fetched from the SoundFont, deliberately. A .sf2 is
     * free to call program 33 whatever its author liked, and often does; the score
     * means the General MIDI instrument, and that is what the row should say.
     */
    val GM: List<String> = listOf(
        "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano",
        "Honky-tonk Piano", "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavi",
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone", "Marimba", "Xylophone",
        "Tubular Bells", "Dulcimer",
        "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ", "Reed Organ",
        "Accordion", "Harmonica", "Tango Accordion",
        "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)",
        "Electric Guitar (clean)", "Electric Guitar (muted)", "Overdriven Guitar",
        "Distortion Guitar", "Guitar Harmonics",
        "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
        "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
        "Violin", "Viola", "Cello", "Contrabass", "Tremolo Strings", "Pizzicato Strings",
        "Orchestral Harp", "Timpani",
        "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
        "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet", "French Horn", "Brass Section",
        "Synth Brass 1", "Synth Brass 2",
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax", "Oboe", "English Horn",
        "Bassoon", "Clarinet",
        "Piccolo", "Flute", "Recorder", "Pan Flute", "Blown Bottle", "Shakuhachi",
        "Whistle", "Ocarina",
        "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
        "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
        "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
        "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
        "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
        "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
        "Sitar", "Banjo", "Shamisen", "Koto", "Kalimba", "Bag pipe", "Fiddle", "Shanai",
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock", "Taiko Drum", "Melodic Tom",
        "Synth Drum", "Reverse Cymbal",
        "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet", "Telephone Ring",
        "Helicopter", "Applause", "Gunshot"
    )
}
