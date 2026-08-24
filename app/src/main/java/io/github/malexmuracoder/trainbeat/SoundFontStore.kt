package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The SoundFont the user chose.
 *
 * The app ships none, on purpose: a General MIDI bank is 30 to 150 MB, its licence
 * is somebody else's business, and F-Droid should not be asked to carry it. So the
 * user grants a .sf2 with the system picker, exactly as they grant a library
 * folder, and it is remembered across restarts.
 *
 * The parsed index is kept open for the session, because opening it costs tens of
 * milliseconds and every rendered note needs it.
 */
object SoundFontStore {

    private const val PREFS = "trainbeat"
    private const val KEY_URI = "sf2_uri"
    private const val KEY_NAME = "sf2_name"

    @Volatile private var open: SoundFont? = null
    @Volatile private var openFor: String? = null

    fun uri(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, null)

    fun isSet(ctx: Context): Boolean = uri(ctx) != null

    fun label(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)
            ?: "not chosen yet"

    fun remember(ctx: Context, picked: Uri, name: String) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                picked, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URI, picked.toString())
            .putString(KEY_NAME, name)
            .apply()
        closeOpen()
    }

    /** Forgets the choice and releases the grant. The file itself is not touched. */
    fun clear(ctx: Context) {
        uri(ctx)?.let {
            try {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_URI).remove(KEY_NAME).apply()
        closeOpen()
    }

    /**
     * The parsed SoundFont, opening it if needed. Throws with something readable:
     * a missing SoundFont is the one thing that stops MIDI from working at all, so
     * the message has to say what to do about it.
     */
    fun soundFont(ctx: Context): SoundFont {
        val u = uri(ctx) ?: throw IllegalStateException(
            "no SoundFont chosen: pick a .sf2 file under Folders to play MIDI")
        val already = open
        if (already != null && openFor == u) return already
        closeOpen()
        val sf = try {
            SoundFont.open(UriByteSource(ctx, Uri.parse(u)))
        } catch (e: SecurityException) {
            throw IllegalStateException("the grant on the SoundFont was lost: choose it again")
        }
        open = sf
        openFor = u
        return sf
    }

    /**
     * One line saying what the chosen file actually contains, which is the thing
     * nobody can tell from its name: FluidR3 ships a three megabyte "GS" file that
     * holds only sound effects, and a MIDI played with it comes out as sirens.
     */
    fun summary(ctx: Context): String =
        try { soundFont(ctx).summary() } catch (e: Throwable) { e.message ?: "not readable" }

    fun effectsOnly(ctx: Context): Boolean =
        try { soundFont(ctx).effectsOnly } catch (_: Throwable) { false }

    /** Called when memory is tight, or after the choice changes. */
    fun closeOpen() {
        try { open?.close() } catch (_: Exception) {}
        open = null
        openFor = null
    }
}
