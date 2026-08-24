package io.github.malexmuracoder.trainbeat

/**
 * Search, filter and ordering of the library. Pure functions, no Android, so the
 * behaviour agreed on paper can be checked by tests instead of by scrolling.
 *
 * Rules:
 *  - no key requested  -> alphabetical by name
 *  - key requested     -> ordered by the probability that the track is in that
 *                         key, descending, dropping whatever falls below the
 *                         threshold. A manual correction counts as certainty and
 *                         therefore comes first.
 *  - tempo range       -> a hard filter; optionally accepts half and double,
 *                         because that is the classic tempo-detection mistake.
 */
object LibraryQuery {

    const val THRESHOLD = 0.05f

    data class Row(val record: TrackRecord, val score: Float)

    fun run(
        records: List<TrackRecord>,
        search: String = "",
        bpmMin: Float? = null,
        bpmMax: Float? = null,
        halfDouble: Boolean = false,
        key: String? = null,
        threshold: Float = THRESHOLD
    ): List<Row> {
        val needle = search.trim().lowercase()

        var rows = records.asSequence()
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .filter { bpmInRange(it, bpmMin, bpmMax, halfDouble) }
            .map { Row(it, if (key == null) 0f else it.p(key)) }

        if (key != null) rows = rows.filter { it.score >= threshold }

        val list = rows.toMutableList()
        if (key == null) {
            list.sortBy { it.record.name.lowercase() }
        } else {
            list.sortWith(
                compareByDescending<Row> { it.score }.thenBy { it.record.name.lowercase() }
            )
        }
        return list
    }

    private fun bpmInRange(r: TrackRecord, min: Float?, max: Float?, halfDouble: Boolean): Boolean {
        if (min == null && max == null) return true
        if (!r.analysed) return false          // no tempo known: cannot satisfy a tempo filter
        val lo = min ?: 0f
        val hi = max ?: Float.MAX_VALUE
        if (r.bpm in lo..hi) return true
        if (halfDouble) {
            if (r.bpm / 2f in lo..hi) return true
            if (r.bpm * 2f in lo..hi) return true
        }
        return false
    }

    /**
     * Index in the result list where confidence stops meaning much, or -1.
     * The UI draws its separator there instead of hiding the tail outright.
     */
    fun uncertainFrom(rows: List<Row>, cut: Float = 0.25f): Int {
        if (rows.isEmpty()) return -1
        for (i in rows.indices) if (rows[i].score < cut) return i
        return -1
    }
}
