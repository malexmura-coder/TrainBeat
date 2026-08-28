package io.github.malexmuracoder.trainbeat

/**
 * Reduces a track to one min/max pair per pixel column: the shape you can draw.
 *
 * Kept free of Android so the arithmetic can be tested on a desktop, and computed
 * ONCE when a file is opened - moving the cursors only draws two more lines over a
 * picture that has not changed.
 *
 * Two decisions worth stating.
 *
 * It keeps min AND max per column, not the average, and not the peak of the
 * absolute value. A waveform drawn from averages looks like a fog; drawn from the
 * two extremes it looks like a waveform, and a single sharp transient still leaves
 * a visible spike instead of being averaged into nothing.
 *
 * And it applies gain, with a limit. A guitar recorded into a phone sits far below
 * full scale, and drawn honestly it would be a flat line 44 dp tall - useless for
 * the one question being asked, which is WHERE THE SILENCE IS. So the shape is
 * scaled up to nearly fill the height. The limit matters just as much: without it,
 * a take containing nothing but room noise would be amplified into a full-height
 * band and look like a performance.
 */
object Envelope {

    /** Beyond this the picture would be inventing a signal. About 18 dB. */
    const val MAX_GAIN = 8f

    /** How much of the height the loudest peak should reach. */
    const val FILL = 0.95f

    /**
     * @param min lowest sample in each column, -1..1
     * @param max highest sample in each column, -1..1
     * @param peak loudest absolute sample in the whole track, 0..1, BEFORE gain
     * @param gain what was applied to fit the height, 1 or more
     */
    class Shape(
        val min: FloatArray,
        val max: FloatArray,
        val peak: Float,
        val gain: Float
    ) {
        val columns: Int get() = min.size
    }

    private val EMPTY = Shape(FloatArray(0), FloatArray(0), 0f, 1f)

    fun of(samples: ShortArray, channels: Int, columns: Int): Shape {
        if (columns <= 0 || channels <= 0 || samples.isEmpty()) return EMPTY
        val frames = samples.size / channels
        if (frames <= 0) return EMPTY

        val lo = FloatArray(columns)
        val hi = FloatArray(columns)
        var worst = 0

        for (col in 0 until columns) {
            // integer arithmetic so no column is ever skipped or counted twice, and
            // so that a track shorter than the canvas still fills every column
            var from = (col.toLong() * frames / columns).toInt()
            var to = ((col + 1).toLong() * frames / columns).toInt()
            if (to <= from) to = from + 1
            if (to > frames) { to = frames; from = minOf(from, frames - 1) }

            var mn = Int.MAX_VALUE
            var mx = Int.MIN_VALUE
            for (f in from until to) {
                val base = f * channels
                for (ch in 0 until channels) {
                    val v = samples[base + ch].toInt()
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
            }
            if (mn == Int.MAX_VALUE) { mn = 0; mx = 0 }
            lo[col] = mn / 32768f
            hi[col] = mx / 32768f
            worst = maxOf(worst, maxOf(Math.abs(mn), Math.abs(mx)))
        }

        val peak = worst / 32768f
        val gain = if (peak <= 0f) 1f else minOf(MAX_GAIN, FILL / peak).coerceAtLeast(1f)
        if (gain > 1f) {
            for (i in 0 until columns) {
                lo[i] = (lo[i] * gain).coerceIn(-1f, 1f)
                hi[i] = (hi[i] * gain).coerceIn(-1f, 1f)
            }
        }
        return Shape(lo, hi, peak, gain)
    }
}
