package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The folders the user granted as library roots, one tree uri per line. */
object LibraryFolders {

    private const val PREFS = "trainbeat"
    private const val KEY = "folders"

    fun load(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun add(ctx: Context, treeUri: String): List<String> {
        val next = (load(ctx).filter { it != treeUri } + treeUri)
        save(ctx, next)
        return next
    }

    fun remove(ctx: Context, treeUri: String): List<String> {
        val next = load(ctx).filter { it != treeUri }
        save(ctx, next)
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        return next
    }

    private fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun storeFile(ctx: Context) = java.io.File(ctx.filesDir, "library.tsv")

    /**
     * Writes a hand-set key, or clears it with key = null.
     *
     * Works even for a track the scan has never seen: a record is created with
     * just the correction in it, so a file opened straight from a folder can be
     * labelled while you listen to it.
     */
    fun saveManualKey(
        ctx: Context,
        existing: TrackRecord?,
        name: String,
        size: Long,
        uri: String,
        key: String?
    ): TrackRecord {
        val base = existing ?: TrackRecord(name = name, size = size, uri = uri)
        val updated = base.copy(manualKey = key, uri = if (uri.isNotEmpty()) uri else base.uri)
        store(ctx).append(updated)
        return updated
    }

    fun store(ctx: Context) = LibraryStore(storeFile(ctx))
}
