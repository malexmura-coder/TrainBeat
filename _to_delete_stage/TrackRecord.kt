package io.github.malexmuracoder.trainbeat

/**
 * One analysed track in the library.
 *
 * The cache key is name + size, not the document URI: moving a file inside the
 * library keeps its analysis, while editing it (which changes the size) forces
 * a fresh one. The URI is kept alongside because playback needs it.
 *
 * Serialisation is a single tab-separated line, deliberately without a JSON
 * library: org.json is an Android stub in desktop unit tests, and this way the
 * store can be tested on a plain JVM with no extra dependency.
 */
data class TrackRecord(
    val name: String,
    val size: Long,
    val uri: String,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val durationMs: Long = 0,
    val bpm: Float = 0f,
    val beats: Int = 0,
    val bars: Int = 0,
    val barsRemainder: Int = 0,
    val keyProbs: FloatArray = FloatArray(24),
    val manualKey: String? = null,
    val error: String? = null
) {
    val cacheKey: String get() = cacheKeyOf(name, size)

    val analysed: Boolean get() = error == null && bpm > 0f

    /** Probability that this track is in the given key. A manual fix means certainty. */
    fun p(keyName: String): Float {
        val m = manualKey
        if (m != null) return if (m == keyName) 1f else 0f
        val i = Analyzer.keyIndex(keyName)
        return if (i < 0 || i >= keyProbs.size) 0f else keyProbs[i]
    }

    /** Index in Analyzer.KEY_NAMES of the key this track counts as being in. */
    val effectiveKeyIndex: Int
        get() {
            val m = manualKey
            if (m != null) {
                val i = Analyzer.keyIndex(m)
                if (i >= 0) return i
            }
            val top = Analyzer.topKeys(keyProbs).firstOrNull() ?: return 0
            val i = Analyzer.keyIndex(top.name)
            return if (i >= 0) i else 0
        }

    fun keyLabel(max: Int = 3): String {
        val m = manualKey
        if (m != null) return "$m (set by you)"
        if (!analysed) return "not analysed"
        val top = Analyzer.topKeys(keyProbs)
        if (top.isEmpty()) return "not analysed"
        return top.take(max).joinToString("  ·  ") { it.name + " " + Math.round(it.p * 100) + "%" }
    }

    fun tempoLabel(): String {
        if (!analysed) return "not analysed"
        // beats and bars only for material that really is a loop: on a song the
        // beat count would be a number nobody measured
        if (beats <= 0) return String.format("%.1f BPM", bpm)
        val barText = if (barsRemainder == 0) "$bars bars" else "$bars bars + $barsRemainder"
        return String.format("%.2f BPM (%d beats, %s)", bpm, beats, barText)
    }

    fun serialise(): String {
        val probs = StringBuilder()
        for (i in keyProbs.indices) {
            if (i > 0) probs.append(',')
            probs.append(Math.round(keyProbs[i] * 10000) / 10000f)
        }
        return listOf(
            esc(name), size.toString(), esc(uri), sampleRate.toString(), channels.toString(),
            durationMs.toString(), bpm.toString(), beats.toString(), bars.toString(),
            barsRemainder.toString(), probs.toString(), esc(manualKey ?: ""), esc(error ?: "")
        ).joinToString("\t")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackRecord) return false
        return name == other.name && size == other.size && uri == other.uri &&
            sampleRate == other.sampleRate && channels == other.channels &&
            durationMs == other.durationMs && bpm == other.bpm && beats == other.beats &&
            bars == other.bars && barsRemainder == other.barsRemainder &&
            keyProbs.contentEquals(other.keyProbs) && manualKey == other.manualKey &&
            error == other.error
    }

    override fun hashCode(): Int {
        var h = name.hashCode()
        h = 31 * h + size.hashCode()
        h = 31 * h + keyProbs.contentHashCode()
        h = 31 * h + (manualKey?.hashCode() ?: 0)
        h = 31 * h + (error?.hashCode() ?: 0)
        return h
    }

    companion object {
        fun cacheKeyOf(name: String, size: Long) = "$name|$size"

        fun parse(line: String): TrackRecord? {
            val f = line.split("\t")
            if (f.size < 13) return null
            return try {
                val probs = FloatArray(24)
                val parts = f[10].split(",")
                for (i in 0 until minOf(24, parts.size)) probs[i] = parts[i].toFloatOrNull() ?: 0f
                TrackRecord(
                    name = unesc(f[0]),
                    size = f[1].toLongOrNull() ?: 0,
                    uri = unesc(f[2]),
                    sampleRate = f[3].toIntOrNull() ?: 0,
                    channels = f[4].toIntOrNull() ?: 0,
                    durationMs = f[5].toLongOrNull() ?: 0,
                    bpm = f[6].toFloatOrNull() ?: 0f,
                    beats = f[7].toIntOrNull() ?: 0,
                    bars = f[8].toIntOrNull() ?: 0,
                    barsRemainder = f[9].toIntOrNull() ?: 0,
                    keyProbs = probs,
                    manualKey = unesc(f[11]).ifEmpty { null },
                    error = unesc(f[12]).ifEmpty { null }
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unesc(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> { sb.append('\t'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        else -> { sb.append(c); i++ }
                    }
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
    }
}
