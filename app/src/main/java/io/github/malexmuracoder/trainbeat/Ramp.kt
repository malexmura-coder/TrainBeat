package io.github.malexmuracoder.trainbeat

/**
 * All the arithmetic behind the three controls, free of Android dependencies,
 * so it is checked on a desktop JVM instead of by ear on the phone.
 */
object Ramp {

    const val SPEED_MIN = 0.50f
    const val SPEED_MAX = 1.50f
    const val SPEED_STEP = 0.05f
    const val SPEED_DEFAULT = 1.00f

    const val PITCH_MIN = -7
    const val PITCH_MAX = 4

    const val TRAIN_MAX = 0.10f          // 10% per loop
    const val TRAIN_STEP = 0.005f        // half a point per tap
    const val CEILING = 2.00f            // the ramp never exceeds double speed

    /**
     * Speed at loop n: base + n * step, where the step is a fraction of the
     * ORIGINAL tempo and not of the starting speed.
     *
     * It used to be base * (1 + n * step), and that was the wrong arithmetic for
     * what the ramp is for. From 50% with a 10% step it gave 50, 55, 60 - the step
     * measured against where you started, so it grew as you got faster, and the
     * part where you most need time to adapt was the part where the jumps got
     * bigger. Now it gives 50, 60, 70: equal rungs, and you can count how many
     * repetitions to full tempo before you start.
     *
     * The ceiling stays at double speed. Tempo is not the finishing line - playing
     * a passage faster than the record is how you make it comfortable at tempo.
     */
    fun speedAt(base: Float, step: Float, loop: Int, ceiling: Float = CEILING): Float {
        val b = base.coerceIn(SPEED_MIN, SPEED_MAX)
        if (loop <= 0 || step <= 0f) return minOf(b, ceiling)
        val v = b + loop * step
        return if (v > ceiling) ceiling else v
    }

    /** Transposition factor for n semitones: 2^(n/12). */
    fun pitchFactor(semitones: Int): Float =
        Math.pow(2.0, semitones.coerceIn(PITCH_MIN, PITCH_MAX) / 12.0).toFloat()

    /** Interval name with explicit direction: -7..+4 covers all 12 keys. */
    fun intervalName(semitones: Int): String = when (semitones) {
        4 -> "major 3rd up"
        3 -> "minor 3rd up"
        2 -> "major 2nd up"
        1 -> "minor 2nd up"
        0 -> "unison"
        -1 -> "minor 2nd down"
        -2 -> "major 2nd down"
        -3 -> "minor 3rd down"
        -4 -> "major 3rd down"
        -5 -> "perfect 4th down"
        -6 -> "tritone down"
        -7 -> "perfect 5th down"
        else -> ""
    }

    fun speedLabel(v: Float): String = "${Math.round(v * 100)}%"

    fun pitchLabel(n: Int): String = (if (n > 0) "+$n" else "$n") + "   " + intervalName(n)

    fun trainLabel(step: Float): String =
        if (step <= 0f) "off" else "+" + String.format("%.1f", step * 100) + "% per loop"
}
