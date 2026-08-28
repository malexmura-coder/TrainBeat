package io.github.malexmuracoder.trainbeat

/**
 * How far a cut moves in a score, and how to name where it landed.
 *
 * A whole bar sounds like the obvious step on a score, and it was wrong. Pieces
 * begin with a fill, a pickup, two beats of hi-hat; the chord sequence therefore
 * does NOT start on a bar line, and with a step of one bar that starting point is
 * not merely awkward to reach - it is unreachable.
 *
 * The arithmetic is in TICKS, which is what makes this exact. A score's bar is a
 * whole number of ticks, so a third of a bar is a whole number too, and no cut
 * accumulates the rounding error that dividing samples would bring. That is also
 * why the thirds are worth having at all: in 12/8, and in anything swung, the
 * musical division IS a third and there is nothing to approximate.
 */
object BarStep {

    /** Menu entries, in the order they are shown. */
    val OPTIONS = listOf("1 bar", "1/2 bar", "1/3 bar", "1/4 bar", "1/6 bar", "1/8 bar")

    const val DEFAULT = "1 bar"

    /** How many parts a bar is cut into by this option. */
    fun divisorOf(option: String): Int = when (option) {
        "1 bar" -> 1
        "1/2 bar" -> 2
        "1/3 bar" -> 3
        "1/4 bar" -> 4
        "1/6 bar" -> 6
        "1/8 bar" -> 8
        else -> 1
    }

    /** The step in ticks. Never zero: a button that does nothing is worse than none. */
    fun ticks(option: String, ticksPerBar: Int): Int =
        if (ticksPerBar <= 0) 1
        else (ticksPerBar / divisorOf(option)).coerceAtLeast(1)

    /**
     * Moves a tick position by one step, staying inside its bounds and staying on
     * the grid of that step - so switching to a coarser step tidies the position up
     * instead of carrying an odd offset around for ever.
     */
    fun nudge(tick: Int, direction: Int, option: String, ticksPerBar: Int, lo: Int, hi: Int): Int {
        val step = ticks(option, ticksPerBar)
        val onGrid = (tick / step) * step
        val moved = if (direction > 0) onGrid + step
        else if (tick == onGrid) onGrid - step else onGrid
        return moved.coerceIn(lo, hi)
    }

    /**
     * Where a tick is, in bars: "bar 5" when it lands on a line, "bar 5 + 2/3" when
     * it does not. Naming it in bars only would hide exactly the offset the whole
     * feature exists to allow.
     */
    fun label(tick: Int, ticksPerBar: Int, option: String): String {
        if (ticksPerBar <= 0) return "-"
        val bar = tick / ticksPerBar
        val rest = tick % ticksPerBar
        if (rest == 0) return "bar ${bar + 1}"
        val div = divisorOf(option).coerceAtLeast(1)
        val part = Math.round(rest.toDouble() * div / ticksPerBar).toInt()
        return if (part <= 0 || part >= div) "bar ${bar + 1}"
        else "bar ${bar + 1} + $part/$div"
    }

    /** For the file name: bars are what a musician counts, so keep them there. */
    fun barNumber(tick: Int, ticksPerBar: Int): Int =
        if (ticksPerBar <= 0) 1 else tick / ticksPerBar + 1
}
