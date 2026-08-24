package io.github.malexmuracoder.trainbeat

/**
 * An audio clip fully decoded in memory, interleaved 16-bit PCM.
 * Keeping everything in RAM is what makes the gapless loop possible: the
 * end->start seam is a plain array copy, not a file being reopened.
 */
class AudioClip(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val info: FormatInfo,
    /** Length of the whole file, when only a window of it was decoded. */
    val totalMs: Long = 0,
    /** True when these samples are a window taken out of a longer file. */
    val partial: Boolean = false
) {
    val frames: Int get() = if (channels > 0) samples.size / channels else 0
    val durationMs: Long get() = if (sampleRate > 0) frames * 1000L / sampleRate else 0L
    val megabytes: Double get() = samples.size * 2.0 / (1024 * 1024)

    /** Whole-file length, falling back to what we hold when nothing was skipped. */
    val fullMs: Long get() = if (totalMs > 0) totalMs else durationMs

    /**
     * A file short enough to be a loop: only then is a whole number of beats a
     * safe assumption, and only then are beats and bars worth reporting.
     */
    val loopExact: Boolean get() = !partial && fullMs in 1..LOOP_MAX_MS

    companion object {
        /** Above this length a file is a track, not a loop. */
        const val LOOP_MAX_MS = 60_000L
    }
}

/** What was actually found inside the file: used to diagnose awkward WAVs. */
data class FormatInfo(
    val path: String,          // "native WAV" oppure "MediaCodec"
    val container: String,     // RIFF/WAVE, RF64/WAVE, mp3, mp4, ...
    val encoding: String,      // PCM 24 bit, IEEE float 32 bit, MPEG audio, ...
    val sampleRate: Int,
    val channels: Int,
    val notes: List<String> = emptyList()
)
