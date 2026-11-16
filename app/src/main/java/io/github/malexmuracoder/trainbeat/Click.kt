package io.github.malexmuracoder.trainbeat

/**
 * The four-click count-in played before a loop's very first entry.
 *
 * One buffer, not four sounds triggered live: a count-in only does its job if
 * the clicks land exactly on the beat, and computing the whole thing as a single
 * array of frames is how that is checked here - on a desktop, as arithmetic -
 * rather than trusted to timing done while it plays.
 */
object Click {

    /** How many clicks lead into the loop. */
    const val COUNT = 4

    /** Tempos outside this range are not a click a musician would count to. */
    const val MIN_BPM = 20f
    const val MAX_BPM = 400f

    private const val TONE_HZ = 1500.0
    private const val PULSE_MS = 15

    /**
     * [COUNT] clicks at [bpm], one beat apart, as interleaved 16-bit frames.
     *
     * @return null if bpm is not a usable tempo (zero, negative, or outside
     *   [MIN_BPM]..[MAX_BPM]), or the sample rate or channel count is not
     *   positive, so the caller has one thing to check rather than a buffer
     *   that divides by zero or takes forever to play.
     */
    fun countIn(sampleRate: Int, channels: Int, bpm: Float): ShortArray? {
        if (sampleRate <= 0 || channels <= 0) return null
        if (bpm < MIN_BPM || bpm > MAX_BPM || bpm.isNaN()) return null
        val beatFrames = Math.round(60f / bpm * sampleRate)
        if (beatFrames <= 0) return null

        val pulse = pulse(sampleRate)
        val totalFrames = beatFrames * COUNT
        val out = ShortArray(totalFrames * channels)
        for (beat in 0 until COUNT) {
            val startFrame = beat * beatFrames
            for (i in pulse.indices) {
                val frame = startFrame + i
                if (frame >= totalFrames) break
                val s = pulse[i]
                val base = frame * channels
                for (ch in 0 until channels) out[base + ch] = s
            }
        }
        return out
    }

    /** One click: a sine burst decaying to silence, so it ticks rather than pops. */
    private fun pulse(sampleRate: Int): ShortArray {
        val frames = maxOf(1, sampleRate * PULSE_MS / 1000)
        return ShortArray(frames) { i ->
            val t = i.toDouble() / sampleRate
            val env = 1.0 - i.toDouble() / frames    // linear decay to zero
            (Math.sin(2.0 * Math.PI * TONE_HZ * t) * env * Short.MAX_VALUE * 0.7).toInt().toShort()
        }
    }
}
