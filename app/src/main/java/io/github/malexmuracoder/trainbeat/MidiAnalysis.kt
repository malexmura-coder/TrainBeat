package io.github.malexmuracoder.trainbeat

/**
 * Tempo, metre and key from a MIDI file.
 *
 * Deliberately separate from Analyzer.kt, which stays exactly as it is: the audio
 * path took a long time to get to eleven correct keys out of seventeen and there is
 * nothing to gain by disturbing it. Here the problem is a different one and far
 * easier - the notes are known, so nothing has to be guessed from a spectrum.
 *
 * The profiles and the correlation are duplicated from Analyzer rather than shared,
 * which is a real cost in lines. It buys the guarantee that a change on this side
 * cannot move the audio results.
 */
object MidiAnalysis {

    enum class Source { FROM_FILE, ESTIMATED }

    class Result(
        val bpm: Float,
        val bpmVariable: Boolean,
        val beats: Int,
        val bars: Int,
        val barsRemainder: Int,
        val numerator: Int,
        val denominator: Int,
        val keyProbs: FloatArray,
        val keySource: Source
    ) {
        val keys: List<Analyzer.KeyGuess> get() = Analyzer.topKeys(keyProbs)

        /** What to show for the tempo: a single figure, or an honest admission. */
        fun tempoLabel(): String =
            if (bpmVariable) String.format("%.1f BPM (variable)", bpm)
            else String.format("%.1f BPM", bpm)
    }

    // Sha'ath profiles, the ones the audio path settled on
    private val MAJOR = floatArrayOf(7f, 2f, 3.5f, 2f, 4.5f, 4f, 2f, 5.5f, 2f, 3.5f, 1.5f, 4f)
    private val MINOR = floatArrayOf(7f, 2f, 3.5f, 4.5f, 2f, 4f, 2f, 5.5f, 3.5f, 2f, 4.5f, 4f)

    /** How much a bass note counts extra when weighing the pitch classes. */
    private const val BASS_WEIGHT = 1.5f

    fun analyse(score: Score): Result {
        val ppq = score.ppq.coerceAtLeast(1)

        // ---------------- tempo ----------------
        // The predominant tempo is the one that lasts longest, not the first one:
        // plenty of files open with a stray event before the real tempo starts.
        val spans = ArrayList<Pair<Int, Int>>()          // usPerQuarter, ticks
        if (score.tempos.isEmpty()) {
            spans.add(Pair(Score.DEFAULT_US_PER_QUARTER, score.endTick))
        } else {
            for ((i, t) in score.tempos.withIndex()) {
                val until = if (i + 1 < score.tempos.size) score.tempos[i + 1].tick else score.endTick
                val len = (until - t.tick).coerceAtLeast(0)
                if (len > 0) spans.add(Pair(t.usPerQuarter, len))
            }
            if (spans.isEmpty()) spans.add(Pair(score.tempos.first().usPerQuarter, 1))
        }
        val total = spans.sumOf { it.second }.coerceAtLeast(1)
        val main = spans.maxByOrNull { it.second }!!
        // "variable" only when the changes are audible: a two per cent ritardando
        // over a bar is not worth refusing to print a number for
        val variable = spans.any {
            it.second > total / 20 && Math.abs(Math.log(it.first.toDouble() / main.first)) > 0.02
        }
        val bpm = (60_000_000.0 / main.first).toFloat()

        // ---------------- metre ----------------
        val sig = score.timeSignatures.firstOrNull()
        val num = sig?.numerator ?: 4
        val den = sig?.denominator ?: 4
        val beatsTotal = Math.round(score.endTick.toDouble() / ppq).toInt()   // in quarter notes
        val beatsPerBar = (num * 4.0 / den)
        val barsExact = if (beatsPerBar > 0) beatsTotal / beatsPerBar else 0.0
        val bars = Math.floor(barsExact).toInt()
        val remainder = beatsTotal - Math.round(bars * beatsPerBar).toInt()

        // ---------------- key ----------------
        val estimate = estimateKey(score)
        val declared = score.keySignatures.firstOrNull()
        var probs = estimate
        var source = Source.ESTIMATED
        if (declared != null) {
            val idx = keyIndexOf(declared.sharps, declared.minor)
            if (idx >= 0) {
                // A key signature of no sharps and no flats in major is what every
                // sequencer writes when nobody set one, so it is only believed when
                // the notes agree. Anything else in the file is taken at its word.
                val isDefault = declared.sharps == 0 && !declared.minor
                val top = Analyzer.topKeys(estimate).firstOrNull()?.name
                val agrees = top == Analyzer.KEY_NAMES[idx] ||
                    top == Analyzer.KEY_NAMES[relativeOf(idx)]
                if (!isDefault || agrees) {
                    probs = FloatArray(24)
                    probs[idx] = 1f
                    source = Source.FROM_FILE
                }
            }
        }

        return Result(bpm, variable, beatsTotal, bars, remainder, num, den, probs, source)
    }

    /**
     * Key from the notes: how long each pitch class sounds, correlated with the
     * twenty-four profiles. The bass line counts extra, because what is underneath
     * decides between a major key and its relative minor far more than the top line.
     */
    fun estimateKey(score: Score): FloatArray {
        val weight = FloatArray(12)
        val ppq = score.ppq.coerceAtLeast(1)

        // the lowest note sounding in each beat, for the bass bonus
        val lowestPerBeat = HashMap<Int, Int>()
        for (n in score.notes) {
            if (n.channel == Score.DRUM_CHANNEL) continue
            val beat = n.tick / ppq
            val prev = lowestPerBeat[beat]
            if (prev == null || n.key < prev) lowestPerBeat[beat] = n.key
        }

        for (n in score.notes) {
            if (n.channel == Score.DRUM_CHANNEL) continue
            val dur = (n.durTicks.toFloat() / ppq).coerceIn(0.05f, 8f)
            val vel = (n.velocity / 127f).coerceIn(0.2f, 1f)
            var w = dur * vel
            if (lowestPerBeat[n.tick / ppq] == n.key) w *= BASS_WEIGHT
            weight[((n.key % 12) + 12) % 12] += w
        }

        var sum = 0f
        for (v in weight) sum += v
        if (sum <= 0f) return FloatArray(24)
        for (i in weight.indices) weight[i] /= sum

        val scores = FloatArray(24)
        for (root in 0 until 12) {
            for (mode in 0..1) {
                val profile = rotate(if (mode == 0) MAJOR else MINOR, root)
                scores[root * 2 + mode] = pearson(weight, profile).toFloat()
            }
        }
        return display(scores)
    }

    /** Key signature (sharps, minor flag) to the index used by Analyzer.KEY_NAMES. */
    fun keyIndexOf(sharps: Int, minor: Boolean): Int {
        if (sharps < -7 || sharps > 7) return -1
        // major keys by number of sharps: C G D A E B F#/Gb ... and flats the other way
        val majorRoot = ((sharps * 7) % 12 + 12) % 12
        val root = if (minor) ((majorRoot + 9) % 12) else majorRoot
        return root * 2 + (if (minor) 1 else 0)
    }

    /** The relative major of a minor key and the other way round. */
    private fun relativeOf(index: Int): Int {
        val root = index / 2
        val minor = index % 2 == 1
        return if (minor) (((root + 3) % 12) * 2) else (((root + 9) % 12) * 2 + 1)
    }

    private fun rotate(profile: FloatArray, by: Int): FloatArray =
        FloatArray(12) { profile[((it - by) % 12 + 12) % 12] }

    /** Same shaping as the audio path, so the percentages read the same way. */
    private fun display(raw: FloatArray): FloatArray {
        var mean = 0f
        for (v in raw) mean += v
        mean /= raw.size
        val out = FloatArray(raw.size)
        var sum = 0f
        for (i in raw.indices) {
            val v = raw[i] - mean
            out[i] = if (v > 0f) Math.pow(v.toDouble(), 2.5).toFloat() else 0f
            sum += out[i]
        }
        if (sum > 0f) for (i in out.indices) out[i] /= sum
        return out
    }

    private fun pearson(a: FloatArray, b: FloatArray): Double {
        val n = a.size
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val d = Math.sqrt(da * db)
        return if (d <= 0.0) 0.0 else num / d
    }
}
