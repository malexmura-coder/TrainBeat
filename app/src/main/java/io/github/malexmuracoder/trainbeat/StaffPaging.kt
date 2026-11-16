package io.github.malexmuracoder.trainbeat

/**
 * Which bars the staff shows, and when it turns the page. Pure arithmetic, in staff
 * spaces, so that it can be tested without a screen.
 *
 * A PAGE starts at a bar and stays there. The cursor walks through the bars of the
 * page, and the page turns only when the music enters the LAST bar that is visible:
 * that bar then becomes the first of the next page. The reader sees the cursor move
 * up to the last-but-one bar and then the staff scrolls, with a whole bar of the
 * past already behind it and the one being played at the left edge.
 *
 * A bar counts as visible when at least half of it is on screen: a bar of which only
 * a sliver shows is the future, not something to read ahead in, and the page must
 * not wait for it. If less than about a bar and a half fits, the page turns at every
 * bar, as the staff always did before.
 *
 * Known limit, accepted: the time signature in the header is that of the first bar
 * of the page, so when the metre changes in the middle of a page it is stale until
 * the next turn.
 */
object StaffPaging {
    // The header, in staff spaces. StaffView draws with these same numbers, so what is
    // measured here is exactly what is painted.
    const val CLEF_X = 0.5f
    const val CLEF_ADVANCE = 3.5f
    const val SIGNATURE_STEP = 1.1f
    const val SIGNATURE_EXTRA = 0.4f
    const val DIGIT_WIDTH = 1.8f
    const val HEADER_TAIL = 0.8f

    /** Width of the header (clef, key signature, time signature), in staff spaces. */
    fun headerSpaces(sharps: Int, numerator: Int, denominator: Int): Float {
        val digits = maxOf(numerator.toString().length, denominator.toString().length)
        val signature = Math.abs(sharps) * SIGNATURE_STEP + if (sharps != 0) SIGNATURE_EXTRA else 0f
        return CLEF_X + CLEF_ADVANCE + signature + digits * DIGIT_WIDTH + HEADER_TAIL
    }

    /**
     * How many bars, from [start], count as visible in [available] staff spaces
     * (the width after the header). [barSpaces] gives the width of bar i. Never less
     * than 1: even a bar that does not fit is the page.
     */
    fun visibleCount(start: Int, total: Int, available: Float, barSpaces: (Int) -> Float): Int {
        var x = 0f
        var count = 0
        var i = start
        while (i < total && x < available) {
            val w = barSpaces(i)
            val shown = minOf(w, available - x)
            if (w > 0f && shown * 2f < w) break
            count++
            x += w
            i++
        }
        return maxOf(1, count)
    }

    /**
     * The first bar of the page that the music is in. [pageStart] is the page as it
     * was, [current] the bar of the cursor, [visible] how many bars the page shows
     * and [total] how many there are.
     */
    fun nextStart(pageStart: Int, current: Int, visible: Int, total: Int): Int {
        if (total <= 0) return 0
        val cur = current.coerceIn(0, total - 1)
        val start = pageStart.coerceIn(0, total - 1)
        if (cur < start) return cur                       // seek back, loop restart
        if (start + visible >= total) return start        // the rest of the piece is on screen
        if (cur >= start + visible - 1) return cur        // entering the last visible bar
        return start
    }
}
