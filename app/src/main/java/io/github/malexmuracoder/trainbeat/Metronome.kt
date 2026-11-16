package io.github.malexmuracoder.trainbeat

/**
 * A click on every beat, written into the score as notes of the drum channel.
 *
 * It is made of notes, like everything else the synthesiser plays, so it follows
 * the tempo map, the speed, the train ramp and the loop for free and cannot drift
 * from the music: there is no second clock. It is optional, one row of the mixer,
 * and off until someone switches it on.
 *
 * The beat is the one written in the time signature (a quarter in 4/4, an eighth in
 * 5/8), except in the compound metres (6/8, 9/8, 12/8) where the beat is a dotted
 * quarter, three eighths. The first beat of a bar is accented, and a bar starts at
 * the time signature that opens it.
 */
object Metronome {

    /** General MIDI percussion: Hi Wood Block for the first beat, Low Wood Block for the others. */
    const val ACCENT_KEY = 76
    const val BEAT_KEY = 77
    private const val ACCENT_VELOCITY = 118
    private const val BEAT_VELOCITY = 92

    /** The beat of a time signature, in ticks. */
    fun beatTicks(ppq: Int, numerator: Int, denominator: Int): Int {
        val compound = denominator == 8 && numerator % 3 == 0 && numerator >= 6
        val t = if (compound) ppq * 3L / 2L else ppq * 4L / denominator
        return t.toInt().coerceAtLeast(1)
    }

    fun clicks(score: Score): List<MidiNote> {
        val sigs = ArrayList<TimeSignature>(score.timeSignatures)
        if (sigs.isEmpty() || sigs.first().tick > 0) sigs.add(0, TimeSignature(0, 4, 4))
        val out = ArrayList<MidiNote>()
        val dur = (score.ppq / 8).coerceAtLeast(1)
        for ((i, s) in sigs.withIndex()) {
            val segEnd = if (i + 1 < sigs.size) sigs[i + 1].tick else score.endTick
            val beat = beatTicks(score.ppq, s.numerator, s.denominator)
            val bar = (score.ppq * 4L * s.numerator / s.denominator).toInt().coerceAtLeast(beat)
            var barStart = s.tick
            while (barStart < segEnd && barStart < score.endTick) {
                var t = barStart
                var first = true
                while (t < barStart + bar && t < segEnd && t < score.endTick) {
                    out.add(MidiNote(
                        t, dur, Score.DRUM_CHANNEL,
                        if (first) ACCENT_KEY else BEAT_KEY,
                        if (first) ACCENT_VELOCITY else BEAT_VELOCITY
                    ))
                    first = false
                    t += beat
                }
                barStart += bar
            }
        }
        return out
    }
}
