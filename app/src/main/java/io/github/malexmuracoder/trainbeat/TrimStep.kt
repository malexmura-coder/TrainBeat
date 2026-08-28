package io.github.malexmuracoder.trainbeat

/**
 * How far a cursor moves in the trimmer, and where it is allowed to land.
 *
 * One switch, one meaning. Until now "snap to beat" governed only the sliders,
 * while the fine buttons always jumped a whole beat regardless - so switching it
 * off gave you a free slider and steppers that still moved in beats, which is
 * manual by half. Both now follow the same switch:
 *
 *   snap on  -> the slider lands on a beat, the buttons move one beat
 *   snap off -> the slider lands anywhere, the buttons move ten milliseconds
 *
 * Ten milliseconds is chosen for a reason: it is roughly the shortest interval a
 * cut can move without the ear noticing it moved at all, so it is the finest
 * useful step rather than the finest possible one.
 *
 * Free of Android dependencies, so the arithmetic is checked by tests instead of
 * by dragging a slider on a phone.
 */
object TrimStep {

    const val MANUAL_MS = 10

    /** Where a position dropped at [frame] should land. */
    fun align(frame: Int, framesPerBeat: Int, snap: Boolean): Int {
        if (!snap || framesPerBeat <= 0) return frame.coerceAtLeast(0)
        return (Math.round(frame.toDouble() / framesPerBeat).toInt() * framesPerBeat).coerceAtLeast(0)
    }

    /** How many frames one tap of minus or plus moves. */
    fun step(framesPerBeat: Int, sampleRate: Int, snap: Boolean): Int {
        if (snap && framesPerBeat > 0) return framesPerBeat
        return (sampleRate * MANUAL_MS / 1000).coerceAtLeast(1)
    }

    /** The new position after a tap, kept inside its bounds. */
    fun nudge(
        frame: Int,
        direction: Int,
        framesPerBeat: Int,
        sampleRate: Int,
        snap: Boolean,
        lo: Int,
        hi: Int
    ): Int = (frame + direction * step(framesPerBeat, sampleRate, snap)).coerceIn(lo, hi)

    /** What the fine buttons should be labelled, so the step is never a mystery. */
    fun label(what: String, framesPerBeat: Int, snap: Boolean): String =
        if (snap && framesPerBeat > 0) "$what, one beat" else "$what, $MANUAL_MS ms"
}
