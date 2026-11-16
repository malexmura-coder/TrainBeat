package io.github.malexmuracoder.trainbeat

import java.io.File

/**
 * The analysis cache: one line per track, appended as the scan proceeds.
 *
 * Append-only is what makes an interrupted scan harmless - nothing is ever
 * rewritten, so a scan that dies halfway keeps every result it had reached.
 * Later lines win over earlier ones for the same track, and compact() collapses
 * the duplicates when they pile up.
 *
 * Takes a File rather than a Context, so it can be tested on a desktop JVM.
 */
class LibraryStore(private val file: File) {

    fun loadAll(): List<TrackRecord> {
        if (!file.exists()) return emptyList()
        val byKey = LinkedHashMap<String, TrackRecord>()
        try {
            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    val r = TrackRecord.parse(line)
                    if (r != null) byKey[r.cacheKey] = r
                }
            }
        } catch (_: Exception) {
        }
        return byKey.values.toList()
    }

    fun loadMap(): Map<String, TrackRecord> = loadAll().associateBy { it.cacheKey }

    fun append(record: TrackRecord) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(record.serialise() + "\n")
        } catch (_: Exception) {
        }
    }

    /** Rewrites the file with one line per track. Safe to call at any time. */
    fun compact() {
        rewrite(loadAll())
    }

    /** Drops one track from the cache, by its cache key. */
    fun remove(key: String) {
        rewrite(loadAll().filter { it.cacheKey != key })
    }

    private fun rewrite(records: List<TrackRecord>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(records.joinToString("\n") { it.serialise() } + if (records.isEmpty()) "" else "\n")
            if (tmp.exists()) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    fun lineCount(): Int = if (!file.exists()) 0 else try {
        var n = 0
        file.forEachLine { if (it.isNotBlank()) n++ }
        n
    } catch (_: Exception) {
        0
    }

    fun clear() {
        try { file.delete() } catch (_: Exception) {}
    }
}
