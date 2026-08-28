package io.github.malexmuracoder.trainbeat

import java.io.File

/**
 * What the user decided about a track: is it a favourite, and how was it last played.
 *
 * Two features live in one store on purpose. A favourite is one boolean and the
 * remembered settings are three numbers, both indexed by the same thing - the
 * document uri - and both written on the same occasions. Two separate stores would
 * have meant writing the same persistence twice and then wondering whether to
 * merge them.
 *
 * The remembered settings are the point of it, more than the star: practising means
 * coming back tomorrow to the same loop at the same speed, and re-entering speed,
 * key and train every time is friction on the one gesture that gets repeated a
 * hundred times.
 *
 * The NAME is stored beside the uri, and that is not redundancy. A uri can stop
 * resolving - the file moved, was renamed, or the folder grant was revoked - and
 * without the name a dead favourite would be an unprintable row of percent-escapes.
 * With it, the list can say which loop went missing and offer to forget it.
 *
 * Rewritten whole rather than appended to, unlike the analysis cache: this file is
 * a few hundred bytes and is edited by hand, one tap at a time, so the append-only
 * trick that protects a long scan buys nothing here.
 *
 * Takes a File rather than a Context, so it can be tested on a desktop JVM.
 */
class TrackPrefs(private val file: File) {

    class Entry(
        val uri: String,
        val name: String,
        val favourite: Boolean = false,
        val speed: Float = 1.0f,
        val semitones: Int = 0,
        val train: Float = 0f
    ) {
        /** True when there is nothing worth writing down any more. */
        val empty: Boolean
            get() = !favourite && speed == 1.0f && semitones == 0 && train == 0f

        fun serialise(): String = listOf(
            esc(uri), esc(name), if (favourite) "1" else "0",
            speed.toString(), semitones.toString(), train.toString()
        ).joinToString("\t")

        override fun equals(other: Any?): Boolean {
            if (other !is Entry) return false
            return uri == other.uri && name == other.name && favourite == other.favourite &&
                speed == other.speed && semitones == other.semitones && train == other.train
        }

        override fun hashCode(): Int = uri.hashCode()

        companion object {
            fun parse(line: String): Entry? {
                val f = line.split("\t")
                if (f.size < 6) return null
                val uri = unesc(f[0])
                if (uri.isEmpty()) return null
                return try {
                    Entry(
                        uri = uri,
                        name = unesc(f[1]),
                        favourite = f[2] == "1",
                        speed = f[3].toFloat(),
                        semitones = f[4].toInt(),
                        train = f[5].toFloat()
                    )
                } catch (_: Exception) {
                    null
                }
            }

            private fun esc(s: String) =
                s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

            private fun unesc(s: String): String {
                val out = StringBuilder(s.length)
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        when (s[i + 1]) {
                            't' -> { out.append('\t'); i += 2 }
                            'n' -> { out.append('\n'); i += 2 }
                            '\\' -> { out.append('\\'); i += 2 }
                            else -> { out.append(c); i++ }
                        }
                    } else {
                        out.append(c); i++
                    }
                }
                return out.toString()
            }
        }
    }

    /** In insertion order, which is the order the favourites were starred in. */
    fun loadAll(): List<Entry> {
        if (!file.exists()) return emptyList()
        val byUri = LinkedHashMap<String, Entry>()
        try {
            file.forEachLine { line ->
                if (line.isNotBlank()) Entry.parse(line)?.let { byUri[it.uri] = it }
            }
        } catch (_: Exception) {
        }
        return byUri.values.toList()
    }

    fun get(uri: String): Entry? = loadAll().firstOrNull { it.uri == uri }

    fun favourites(): List<Entry> = loadAll().filter { it.favourite }

    fun isFavourite(uri: String): Boolean = get(uri)?.favourite == true

    /**
     * Writes one entry, replacing any earlier one for the same uri. An entry that
     * has nothing left to say is dropped instead of stored, so unstarring a track
     * whose settings are all default removes the row rather than leaving a husk.
     */
    fun put(entry: Entry) {
        val all = loadAll().filter { it.uri != entry.uri }.toMutableList()
        if (!entry.empty) all.add(entry)
        writeAll(all)
    }

    fun remove(uri: String) {
        writeAll(loadAll().filter { it.uri != uri })
    }

    /** Turns the star on or off, keeping whatever settings were remembered. */
    fun toggleFavourite(uri: String, name: String): Boolean {
        val old = get(uri)
        val now = !(old?.favourite ?: false)
        put(
            Entry(
                uri = uri,
                name = if (name.isNotEmpty()) name else old?.name ?: "",
                favourite = now,
                speed = old?.speed ?: 1.0f,
                semitones = old?.semitones ?: 0,
                train = old?.train ?: 0f
            )
        )
        return now
    }

    /** Remembers how a track was last played, keeping the star as it was. */
    fun remember(uri: String, name: String, speed: Float, semitones: Int, train: Float) {
        val old = get(uri)
        put(
            Entry(
                uri = uri,
                name = if (name.isNotEmpty()) name else old?.name ?: "",
                favourite = old?.favourite ?: false,
                speed = speed,
                semitones = semitones,
                train = train
            )
        )
    }

    /** The next, or previous, favourite after this one - for the Bluetooth keys. */
    fun neighbourFavourite(uri: String, forward: Boolean): Entry? {
        val favs = favourites()
        if (favs.isEmpty()) return null
        val at = favs.indexOfFirst { it.uri == uri }
        if (at < 0) return if (forward) favs.first() else favs.last()
        val next = if (forward) at + 1 else at - 1
        return favs[(next + favs.size) % favs.size]
    }

    private fun writeAll(entries: List<Entry>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(entries.joinToString("\n") { it.serialise() } +
                if (entries.isEmpty()) "" else "\n")
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
