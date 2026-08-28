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

    /**
     * True when this file was recorded by the app itself. It matters in the
     * trimmer: a phrase played into the microphone has no measurable tempo, so
     * the beat grid would place cuts by a number nobody measured.
     */
    val recorded: Boolean get() = info.software?.startsWith(RECORDER_TAG) == true

    companion object {
        /** Above this length a file is a track, not a loop. */
        const val LOOP_MAX_MS = 60_000L

        /** Written into the WAV we record, and looked for when reading one back. */
        const val RECORDER_TAG = "TrainBeat recorder"

        /**
         * A recording judged by its name. The mark inside the file is the reliable
         * one; this is the fallback for a file that has been through another
         * program and lost it, and it is why recordings are named this way.
         */
        fun looksRecorded(name: String): Boolean = name.lowercase().startsWith("rec_")

        /** rec_yyyyMMdd_HHmm.wav - says what it is and when it was made. */
        fun recordingName(millis: Long): String {
            val f = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            return "rec_" + f.format(java.util.Date(millis)) + ".wav"
        }
    }
}

/** What was actually found inside the file: used to diagnose awkward WAVs. */
data class FormatInfo(
    val path: String,          // "native WAV" oppure "MediaCodec"
    val container: String,     // RIFF/WAVE, RF64/WAVE, mp3, mp4, ...
    val encoding: String,      // PCM 24 bit, IEEE float 32 bit, MPEG audio, ...
    val sampleRate: Int,
    val channels: Int,
    val notes: List<String> = emptyList(),
    /** Name of the program that wrote the file, when it says so. */
    val software: String? = null
)
