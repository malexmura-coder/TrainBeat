package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * What comes with the app: a handful of example loops and scores, and a SoundFont to
 * play the scores with.
 *
 * They live in the assets of the APK and are copied once into the app's private
 * storage, because everything downstream - the library, the player, the SoundFont
 * reader - works on uris and wants a real file with random access, which an asset
 * inside a compressed archive is not. The copies go in noBackupFilesDir: they can be
 * made again from the APK at any time, so there is no reason to put them in a backup.
 *
 * The loops are a library root of their own, hidden: they are not in the list of
 * folders the user granted and cannot be removed from it. Nothing is measured when the
 * app starts: assets/builtin/loops.tsv holds what is known about each audio file
 * (tempo, length, key, as the author gives them), and the first start only puts those
 * lines into the library, so the first thing a new user sees in the library is not an
 * empty page. A score is parsed, which takes milliseconds, and takes its key from the
 * same file. A loop that is not in the file is measured like any other.
 *
 * Removing one is allowed. The name is remembered, so that it does not come back at
 * the next start, and [restore] brings the lot back.
 *
 * To change what ships, replace the files under assets/builtin, change loops.tsv and
 * raise [VERSION]: an installed copy, and its line in the library, is made again when
 * the number it was made at is lower.
 */
object BuiltIn {

    /** Raise when the files in assets/builtin change. */
    const val VERSION = 2

    /** What the SoundFont is called wherever the app names the one in use. */
    const val SOUNDFONT_LABEL = "Built-in: Chaos Bank V1.9 (CC0)"

    private const val ASSET_LOOPS = "builtin/loops"
    private const val ASSET_DIR = "builtin"
    private const val ASSET_INDEX = "builtin/loops.tsv"
    private const val ASSET_SF2 = "chaosbank.sf2"

    private const val PREFS = "trainbeat"
    private const val KEY_LOOPS_VERSION = "builtin_loops_version"
    private const val KEY_SF2_VERSION = "builtin_sf2_version"
    private const val KEY_RECORDS_VERSION = "builtin_records_version"
    private const val KEY_REMOVED = "builtin_removed"

    private val lock = Any()

    @Volatile private var started = false
    @Volatile private var sfShips: Boolean? = null

    private fun root(ctx: Context) = File(ctx.noBackupFilesDir, "builtin")
    private fun loopsDir(ctx: Context) = File(root(ctx), "loops")
    private fun sfFile(ctx: Context) = File(root(ctx), ASSET_SF2)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Gets everything ready, once per start of the app, off the main thread: copies
     * what is missing and enters what the library does not know yet. A moment on the
     * first start, nothing after that.
     */
    fun prepareAsync(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val t = Thread({
            try {
                syncLoops(app)
                soundFontUri(app)
                analyseNew(app)
            } catch (_: Throwable) {
                // the examples are a courtesy: never a reason for the app not to start
            }
        }, "BuiltInPrepare")
        t.priority = Thread.NORM_PRIORITY - 1
        t.start()
    }

    // ---------- the SoundFont ----------

    /** True when this build carries a SoundFont at all. */
    fun hasSoundFont(ctx: Context): Boolean {
        sfShips?.let { return it }
        val has = try {
            ctx.assets.list(ASSET_DIR)?.contains(ASSET_SF2) == true
        } catch (_: Exception) {
            false
        }
        sfShips = has
        return has
    }

    /**
     * The uri of the built-in SoundFont, copied first if it is not there yet or was
     * copied by an older version. Null when this build carries none or the copy fails.
     */
    fun soundFontUri(ctx: Context): String? {
        if (!hasSoundFont(ctx)) return null
        return synchronized(lock) {
            val f = sfFile(ctx)
            val current = prefs(ctx).getInt(KEY_SF2_VERSION, 0) == VERSION
            var ok = true
            if (!f.exists() || f.length() == 0L || !current) {
                try {
                    copyAsset(ctx, "$ASSET_DIR/$ASSET_SF2", f)
                    prefs(ctx).edit().putInt(KEY_SF2_VERSION, VERSION).apply()
                } catch (_: Exception) {
                    ok = false
                }
            }
            if (ok) Uri.fromFile(f).toString() else null
        }
    }

    // ---------- the loops ----------

    /** Copies the loops that are missing, leaves alone the ones the user removed. */
    fun syncLoops(ctx: Context) {
        synchronized(lock) {
            val names = try {
                // a file whose name starts with a dot is the system's litter, not a loop
                ctx.assets.list(ASSET_LOOPS)?.filter { !it.startsWith(".") } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val removed = removedNames(ctx)
            val dir = loopsDir(ctx)
            dir.mkdirs()
            val current = prefs(ctx).getInt(KEY_LOOPS_VERSION, 0) == VERSION
            for (n in names) {
                if (n in removed) continue
                val f = File(dir, n)
                if (!f.exists() || f.length() == 0L || !current) {
                    try {
                        copyAsset(ctx, "$ASSET_LOOPS/$n", f)
                    } catch (_: Exception) {
                    }
                }
            }
            // a later version may stop shipping one: its copy goes with it
            dir.listFiles()?.forEach { if (it.isFile && it.name !in names) it.delete() }
            prefs(ctx).edit().putInt(KEY_LOOPS_VERSION, VERSION).apply()
        }
    }

    /** The loops as library entries, in name order. */
    fun entries(ctx: Context): List<DocTree.Entry> =
        (loopsDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isFile && DocTree.isPlayable(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { DocTree.Entry(Uri.fromFile(it).toString(), it.name, it.length()) }

    /** Whether a library uri points at one of the copied loops. */
    fun isBuiltIn(ctx: Context, uri: String): Boolean {
        if (!uri.startsWith("file:")) return false
        val path = Uri.parse(uri).path ?: return false
        return path.startsWith(loopsDir(ctx).path + "/")
    }

    /**
     * Enters the loops the library does not know yet. An audio file that loops.tsv
     * describes is entered from that line, without being opened; a score is parsed, and
     * takes the key the line gives; anything else is measured. Appended one by one, like
     * the scan does, so an interrupted run loses nothing.
     *
     * When [VERSION] is higher than the one the library lines were made at, the lines of
     * the loops are made again first, so that a change in loops.tsv reaches an app that
     * is already installed. Lines of loops that are no longer shipped go in the same way.
     */
    fun analyseNew(ctx: Context) {
        val store = LibraryFolders.store(ctx)
        val entries = entries(ctx)
        val keys = entries.map { TrackRecord.cacheKeyOf(it.name, it.size) }.toSet()
        val again = prefs(ctx).getInt(KEY_RECORDS_VERSION, 0) != VERSION
        for (r in store.loadAll()) {
            if (isBuiltIn(ctx, r.uri) && (again || r.cacheKey !in keys)) store.remove(r.cacheKey)
        }
        val index = BuiltInIndex.parse(readIndex(ctx))
        val known = store.loadMap()
        for (e in entries) {
            val cached = known[TrackRecord.cacheKeyOf(e.name, e.size)]
            if (cached != null && cached.error == null && !again) continue
            val row = index[e.name]
            var record = (row?.let { BuiltInIndex.audioRecord(it, e.size, e.uri) })
                ?: analyseEntry(ctx, e) ?: continue
            if (row != null && record.error == null && record.bpm > 0f) {
                BuiltInIndex.oneHot(row.key)?.let { record = record.copy(keyProbs = it) }
            }
            store.append(record)
        }
        prefs(ctx).edit().putInt(KEY_RECORDS_VERSION, VERSION).apply()
    }

    private fun readIndex(ctx: Context): String =
        try {
            ctx.assets.open(ASSET_INDEX).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: Exception) {
            ""
        }

    // ---------- removing and restoring ----------

    /** Takes a loop out of the library for good, until [restore] is used. */
    fun remove(ctx: Context, record: TrackRecord) {
        if (!isBuiltIn(ctx, record.uri)) return
        synchronized(lock) {
            setRemoved(ctx, removedNames(ctx) + record.name)
            try {
                File(Uri.parse(record.uri).path ?: "").delete()
            } catch (_: Exception) {
            }
        }
        LibraryFolders.store(ctx).remove(record.cacheKey)
        LibraryFolders.prefs(ctx).remove(record.uri)
    }

    /** Brings back every loop that was removed. Returns how many came back. */
    fun restore(ctx: Context): Int {
        val before = entries(ctx).size
        synchronized(lock) { setRemoved(ctx, emptySet()) }
        syncLoops(ctx)
        analyseNew(ctx)
        return (entries(ctx).size - before).coerceAtLeast(0)
    }

    private fun removedNames(ctx: Context): Set<String> =
        (prefs(ctx).getString(KEY_REMOVED, "") ?: "")
            .split("\n").filter { it.isNotEmpty() }.toSet()

    private fun setRemoved(ctx: Context, names: Set<String>) {
        prefs(ctx).edit().putString(KEY_REMOVED, names.joinToString("\n")).apply()
    }

    // ---------- plumbing ----------

    /** Written to a side file and renamed, so a copy cut short never looks finished. */
    private fun copyAsset(ctx: Context, asset: String, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        ctx.assets.open(asset).use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("could not write ${target.name}")
    }
}

/**
 * loops.tsv: what is known about the loops that ship, as a pure function of its text so
 * that it is tested without Android.
 */
internal object BuiltInIndex {

    class Row(
        val name: String,
        val key: String,
        val bpm: Float,
        val durationMs: Long,
        val sampleRate: Int,
        val channels: Int,
        val beats: Int
    ) {
        /** A line that says enough to build a library entry for an audio file. */
        val isAudioLine: Boolean get() = bpm > 0f && durationMs > 0L && sampleRate > 0 && channels > 0
    }

    fun parse(text: String): Map<String, Row> {
        val out = HashMap<String, Row>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split("\t")
            if (f.size < 2 || f[0].isEmpty()) continue
            out[f[0]] = Row(
                name = f[0],
                key = f[1].trim(),
                bpm = f.getOrNull(2)?.trim()?.toFloatOrNull() ?: 0f,
                durationMs = f.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L,
                sampleRate = f.getOrNull(4)?.trim()?.toIntOrNull() ?: 0,
                channels = f.getOrNull(5)?.trim()?.toIntOrNull() ?: 0,
                beats = f.getOrNull(6)?.trim()?.toIntOrNull() ?: 0
            )
        }
        return out
    }

    /** All the probability on one key: the author says what it is, nobody estimates. */
    fun oneHot(key: String): FloatArray? {
        val i = Analyzer.keyIndex(key)
        if (i < 0) return null
        return FloatArray(24).also { it[i] = 1f }
    }

    /** The library entry for an audio file, or null when the line does not describe one. */
    fun audioRecord(row: Row, size: Long, uri: String): TrackRecord? {
        if (!row.isAudioLine) return null
        val beats = row.beats.coerceAtLeast(0)
        return TrackRecord(
            name = row.name,
            size = size,
            uri = uri,
            sampleRate = row.sampleRate,
            channels = row.channels,
            durationMs = row.durationMs,
            bpm = row.bpm,
            beats = beats,
            bars = beats / 4,
            barsRemainder = beats % 4,
            keyProbs = oneHot(row.key) ?: FloatArray(24)
        )
    }
}
