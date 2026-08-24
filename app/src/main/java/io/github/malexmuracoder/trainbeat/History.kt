package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val uri: String, val name: String)

/**
 * List of recently opened tracks, stored in SharedPreferences.
 *
 * Why it survives closing the app: when a file is picked we ask Android for a
 * persistable permission on that single file (takePersistableUriPermission).
 * No blanket storage permission is needed.
 */
object History {

    private const val PREFS = "trainbeat"
    private const val KEY = "history"
    const val MAX = 10

    fun load(ctx: Context): List<HistoryItem> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val out = ArrayList<HistoryItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += HistoryItem(o.getString("uri"), o.optString("name", "(unnamed)"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun add(ctx: Context, item: HistoryItem): List<HistoryItem> {
        val cur = load(ctx).filter { it.uri != item.uri }
        val next = (listOf(item) + cur).take(MAX)
        save(ctx, next)
        return next
    }

    fun remove(ctx: Context, uri: String): List<HistoryItem> {
        val next = load(ctx).filter { it.uri != uri }
        save(ctx, next)
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        return next
    }

    private fun save(ctx: Context, items: List<HistoryItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** true if the persisted permission on that uri is still valid. */
    fun stillGranted(ctx: Context, uri: String): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }
}
