package io.github.malexmuracoder.trainbeat

/**
 * How big the staff is drawn, as a multiplier on the one number everything else in
 * the drawing is measured in: the staff space, the gap between two lines.
 *
 * Free of Android, because the arithmetic is the part that can be wrong quietly:
 * a step that lands on 0.7999 instead of 0.8, a limit that lets the staff shrink
 * to nothing, a default that puts a phone-sized staff on a tablet.
 *
 * THE DEFAULT is not a constant but a function of the device. A staff drawn at one
 * fixed size is a good size on a phone and a postage stamp on a tablet, and the
 * number of bars on screen follows from the width, so what the user wants on a
 * tablet is the same page, larger. The smallest dimension of the screen is used and
 * not the current width, so that turning the device sideways does not change the
 * size of the notes: a phone on its side shows more bars, which is the point of the
 * width rule, and not bigger notes, which would not fit the height.
 */
object StaffScale {

    /** One staff space, in dp, at scale 1. */
    const val BASE_DP = 6f

    const val MIN = 0.7f
    const val MAX = 2.5f
    const val STEP = 0.1f

    /** The smallest screen dimension, in dp, at which the default is exactly 1. */
    const val REFERENCE_DP = 360f

    /** Rounded to a tenth, so that repeated steps never drift, and kept inside the limits. */
    fun clamp(v: Float): Float {
        if (!v.isFinite()) return 1f
        return (Math.round(v * 10f) / 10f).coerceIn(MIN, MAX)
    }

    /** The size to use when the user has not chosen one. */
    fun autoFor(smallestWidthDp: Float): Float =
        if (smallestWidthDp <= 0f) 1f else clamp(smallestWidthDp / REFERENCE_DP)

    /** One step up (+1) or down (-1) from a size. */
    fun stepped(current: Float, direction: Int): Float = clamp(current + direction * STEP)

    /** How much larger than the panel's size the full-screen view starts out. */
    const val FULLSCREEN_ZOOM = 1.5f

    /**
     * [scale], made smaller if a picture [heightSpaces] staff spaces tall would not fit
     * in [availableDp]; never larger. The limit MIN does not apply here: a picture that
     * is too tall for a short screen has to shrink, not overflow it, but it never goes
     * below a size at which the notes could still be told apart.
     */
    fun fitted(scale: Float, heightSpaces: Float, availableDp: Float?): Float {
        if (availableDp == null || !availableDp.isFinite() || heightSpaces <= 0f) return scale
        return minOf(scale, availableDp / (BASE_DP * heightSpaces)).coerceAtLeast(FLOOR)
    }

    private const val FLOOR = 0.4f

    /** "110%": what the control shows. */
    fun label(scale: Float): String = "${Math.round(scale * 100f)}%"
}
