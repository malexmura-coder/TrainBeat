package io.github.malexmuracoder.trainbeat

/** Deterministic test signals: no randomness, so results are reproducible. */
object SignalGen {

    const val SR = 44100

    /** Click train at an exact tempo: duration is a whole number of beats by construction. */
    fun clickTrain(bpm: Double, beats: Int, sr: Int = SR): ShortArray {
        val samplesPerBeat = 60.0 * sr / bpm
        val n = Math.round(samplesPerBeat * beats).toInt()
        val out = ShortArray(n)
        val burst = (sr * 0.02).toInt()
        for (b in 0 until beats) {
            val start = Math.round(b * samplesPerBeat).toInt()
            for (i in 0 until burst) {
                val t = i.toDouble() / sr
                val env = Math.exp(-t * 180.0)
                val v = Math.sin(2.0 * Math.PI * 1000.0 * t) * env * 0.9
                val idx = start + i
                if (idx in 0 until n) {
                    val acc = out[idx] + (v * 32000).toInt()
                    out[idx] = acc.coerceIn(-32768, 32767).toShort()
                }
            }
        }
        return out
    }

    /** Sustained chord of pure tones, mono. */
    fun chord(freqs: DoubleArray, seconds: Double, sr: Int = SR): ShortArray {
        val n = (seconds * sr).toInt()
        val out = ShortArray(n)
        val amp = 0.8 / freqs.size
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var v = 0.0
            for (f in freqs) v += Math.sin(2.0 * Math.PI * f * t)
            out[i] = (v * amp * 32000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Chord plus a click train, i.e. something with both a tempo and a key. */
    fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val n = Math.min(a.size, b.size)
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = ((a[i] + b[i]) / 2).toShort()
        return out
    }
}
