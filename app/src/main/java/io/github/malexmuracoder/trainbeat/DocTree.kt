package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Walks the folders granted as library roots.
 *
 * A tree grant makes every file underneath readable, including files added
 * later, which is why the library needs it: a per-file grant could only ever
 * reach files the user had already picked by hand.
 */
object DocTree {

    data class Entry(val uri: String, val name: String, val size: Long)

    private val AUDIO_EXT = listOf(
        ".wav", ".wave", ".bwf", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".aif", ".aiff"
    )

    fun isAudio(name: String): Boolean {
        val n = name.lowercase()
        return AUDIO_EXT.any { n.endsWith(it) }
    }

    /** What the library accepts: audio to analyse, and scores to read. */
    fun isPlayable(name: String): Boolean = isAudio(name) || MidiFile.isMidi(name)

    fun listAudio(ctx: Context, treeUri: Uri, limitDepth: Int = 8): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val root = DocumentsContract.getTreeDocumentId(treeUri)
            walk(ctx, treeUri, root, out, 0, limitDepth)
        } catch (_: Exception) {
        }
        return out
    }

    private fun walk(
        ctx: Context,
        treeUri: Uri,
        docId: String,
        out: MutableList<Entry>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            ctx.contentResolver.query(children, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(ctx, treeUri, id, out, depth + 1, maxDepth)
                    } else if (isPlayable(name)) {
                        out.add(
                            Entry(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                                name,
                                size
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Last path segment of a tree uri, cleaned up enough to show to a human. */
    fun folderLabel(treeUri: String): String {
        val decoded = Uri.decode(treeUri)
        val cut = decoded.substringAfterLast(':', "")
        return if (cut.isNotBlank()) cut else decoded.substringAfterLast('/')
    }
}
