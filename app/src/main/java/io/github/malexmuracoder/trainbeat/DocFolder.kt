package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * A folder the user granted for writing, remembered across restarts.
 *
 * There are two of them now - where the trimmer saves loops, and where the
 * recorder saves recordings - and they behave identically, so the behaviour lives
 * here once. The grant has to include write permission, unlike the library
 * folders which are read-only: the tree uri is kept for the permission and the
 * document uri of the target directory for the writing, because the two differ as
 * soon as a subfolder is created.
 *
 * Failures are reported, not swallowed: a save that cannot happen has to say why.
 */
open class DocFolder(
    private val keyTree: String,
    private val keyDir: String,
    /** Name offered when the user asks for a subfolder. */
    val subfolderName: String
) {

    private val prefs = "trainbeat"

    fun tree(ctx: Context): String? =
        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString(keyTree, null)

    fun dir(ctx: Context): String? =
        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).getString(keyDir, null)

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
        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
            .putString(keyTree, treeUri.toString())
            .putString(keyDir, docUri.toString())
            .apply()
    }

    /**
     * Forgets the folder and releases the persistent grant. No file is touched:
     * what was already written stays where it is.
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
        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
            .remove(keyTree).remove(keyDir).apply()
    }

    /** Creates the subfolder and makes it the target. Returns its name. */
    fun createSubfolder(ctx: Context, name: String = subfolderName): String? {
        val parent = dir(ctx) ?: return null
        return try {
            val created = DocumentsContract.createDocument(
                ctx.contentResolver, Uri.parse(parent),
                DocumentsContract.Document.MIME_TYPE_DIR, name
            ) ?: return null
            ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
                .putString(keyDir, created.toString()).apply()
            name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates a document in the target directory. Throws with the reason instead
     * of returning null, so the caller can show what went wrong.
     */
    fun createDoc(ctx: Context, displayName: String, mime: String): Uri {
        val parent = dir(ctx) ?: throw IllegalStateException("no folder chosen")
        return DocumentsContract.createDocument(
            ctx.contentResolver, Uri.parse(parent), mime, displayName
        ) ?: throw IllegalStateException("the folder refused to create $displayName")
    }

    fun createWav(ctx: Context, displayName: String): Uri =
        createDoc(ctx, displayName, "audio/x-wav")

    fun createMidi(ctx: Context, displayName: String): Uri =
        createDoc(ctx, displayName, "audio/midi")

    fun label(ctx: Context): String {
        val d = dir(ctx) ?: return "not chosen yet"
        return DocTree.folderLabel(d)
    }
}

/** Where the trimmer writes its loops. */
object OutputFolder : DocFolder("out_tree", "out_dir", "TrainBeat loops") {
    const val SUBFOLDER = "TrainBeat loops"
}

/** Where the recorder writes what it records. */
object RecordFolder : DocFolder("rec_tree", "rec_dir", "TrainBeat recordings")
