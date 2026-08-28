package io.github.malexmuracoder.trainbeat

/**
 * The timing of a button that keeps firing while it is held.
 *
 * Separated from the button itself so the schedule can be reasoned about and
 * tested, instead of being three magic numbers buried in a gesture handler.
 *
 * The first wait is the platform's own long-press timeout, passed in by the caller
 * rather than assumed here: a tap must never turn into a repeat, and the phone
 * already knows how long a tap lasts.
 *
 * After that the rate starts deliberately slower than the platform key repeat.
 * Trimming by ear needs to hear where it is going; at 10 ms per step the whole
 * point is to travel, but at fifty steps a second a second of holding covers half
 * a second of audio and overshoots. So it walks, then runs if the finger stays
 * down - which is when the intent has clearly become "get me far away from here".
 */
object HoldRepeat {

    /** Interval while walking. */
    const val SLOW_MS = 150L

    /** Interval once it is clear the finger is not coming up. */
    const val FAST_MS = 60L

    /** Steps taken at the slow rate before accelerating. */
    const val SLOW_STEPS = 10

    /**
     * Wait before the step numbered [step], counting from 0 for the first repeat
     * (the initial long-press wait is the caller's, and happens before step 0).
     */
    fun delayFor(step: Int): Long = if (step < SLOW_STEPS) SLOW_MS else FAST_MS

    /** How long holding for [steps] repeats takes, for reasoning about the rate. */
    fun elapsedMs(steps: Int): Long {
        var total = 0L
        for (i in 0 until steps) total += delayFor(i)
        return total
    }
}
