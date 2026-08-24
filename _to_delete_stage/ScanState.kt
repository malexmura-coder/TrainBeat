package io.github.malexmuracoder.trainbeat

/**
 * Progress of the running scan, read by the UI by polling.
 *
 * Deliberately a plain object with volatile fields instead of a reactive stream:
 * the scan lives in a service that outlives the screen, so the UI has to be able
 * to attach to whatever is already in flight.
 */
object ScanState {

    @Volatile var running = false
    @Volatile var total = 0
    @Volatile var done = 0
    @Volatile var skipped = 0
    @Volatile var failed = 0
    @Volatile var current = ""
    @Volatile var msPerFile = 0.0
    @Volatile var cancelRequested = false
    @Volatile var lastMessage = ""

    /** Seconds left, from measured throughput. Negative when not known yet. */
    fun etaSeconds(): Double {
        if (msPerFile <= 0.0) return -1.0
        val left = total - done - skipped
        if (left <= 0) return 0.0
        return left * msPerFile / 1000.0
    }

    fun reset(totalFiles: Int) {
        total = totalFiles
        done = 0
        skipped = 0
        failed = 0
        current = ""
        msPerFile = 0.0
        cancelRequested = false
        lastMessage = ""
    }
}
