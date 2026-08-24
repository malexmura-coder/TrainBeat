package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Where the trimmer writes its loops.
 *
 * Asked once, on entering the trimmer, and remembered. The grant has to include
 * write permission, unlike the library folders which are read-only: the tree uri
 * is kept for the permission and the document uri of the target directory for the
 * writing, because they differ as soon as a subfolder is created.
 *
 * Failures are reported, not swallowed: a save that cannot happen has to say why.
 */
object OutputFolder {

    private const val PREFS = "trainbeat"
    private const val KEY_TREE = "out_tree"
    private const val KEY_DIR = "out_dir"
    const val SUBFOLDER = "TrainBeat loops"

    fun tree(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)

    fun dir(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIR, null)

    fun isSet(ctx: Context): Boolean = dir(ctx) != null

    fun remember(ctx: Context, treeUri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE, treeUri.toString())
            .putString(KEY_DIR, docUri.toString())
            .apply()
    }

    /**
     * Forgets the destination and releases the persistent grant. No file is
     * touched: the loops already written stay where they are.
     */
    fun clear(ctx: Context) {
        val t = tree(ctx)
        if (t != null) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(t),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TREE).remove(KEY_DIR).apply()
    }

    /** Creates the subfolder and makes it the target. Returns its label. */
    fun createSubfolder(ctx: Context, name: String = SUBFOLDER): String? {
        val parent = dir(ctx) ?: return null
        return try {
            val created = DocumentsContract.createDocument(
                ctx.contentResolver, Uri.parse(parent),
                DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DIR, created.toString()).apply()
            name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates a wav document in the target directory. Throws with the reason
     * instead of returning null, so the trimmer can show what went wrong.
     */
    fun createWav(ctx: Context, displayName: String): Uri {
        val parent = dir(ctx) ?: throw IllegalStateException("no destination folder chosen")
        return DocumentsContract.createDocument(
            ctx.contentResolver, Uri.parse(parent), "audio/x-wav", displayName
        ) ?: throw IllegalStateException("the folder refused to create $displayName")
    }

    fun label(ctx: Context): String {
        val d = dir(ctx) ?: return "not chosen yet"
        return DocTree.folderLabel(d)
    }
}
