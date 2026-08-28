package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.content.Intent
import android.net.Uri

/** A file handed to the app from outside: what to play, and what to call it. */
class Arrival(val uri: String, val name: String)

/**
 * Files sent in by other apps - a download, a mail attachment, a file manager, a
 * share.
 *
 * TWO PROBLEMS, and neither is the intent filter.
 *
 * The first is the NAME. Everywhere else in the app a file arrives with a name off
 * a document provider, and MIDI is told apart from audio by that name and by
 * nothing else - a score is read with a completely different reader, so getting it
 * wrong is not a detail. A file sent in may have no name at all, or a name with no
 * extension, or one ending in something that says nothing. What it does have is a
 * declared type, so the extension is derived from that when it has to be.
 *
 * The second is that the file is BORROWED. The permission to read it belongs to
 * the app that sent it and is granted to this task, not to this app: it is
 * withdrawn sooner or later, and certainly by a restart. So it can be played now
 * and cannot be relied on tomorrow, which is why the user is offered a copy - and
 * why, when there is nowhere to put one, the app says so instead of quietly
 * remembering a file it will not be able to open again.
 */
object Incoming {

    /** What comes in, if anything does. Null for an ordinary launch. */
    fun from(ctx: Context, intent: Intent?): Arrival? {
        val i = intent ?: return null
        val uri: Uri = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> streamOf(i)
            else -> null
        } ?: return null

        // Ask to keep the grant. It usually fails, because the sender rarely offers
        // a persistable one, and that failure is not an error: it is exactly the
        // situation the offer of a copy exists for.
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }

        val mime = i.type ?: try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
        val shown = try { displayName(ctx, uri) } catch (_: Exception) { null }
        return Arrival(uri.toString(), nameFor(shown, mime))
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    /**
     * A usable file name, from whatever the sender was willing to say.
     *
     * The extension is the part that matters and the part most often missing. It is
     * appended from the declared type rather than guessed from the bytes, because
     * the one decision that depends on it - score or audio - is taken before a
     * single byte is read. A name that already ends in something we recognise is
     * left exactly as it is, since the sender knew better than we do.
     */
    fun nameFor(displayName: String?, mime: String?): String {
        val cleaned = (displayName ?: "").replace('/', '_').replace('\\', '_').trim()
        val base = if (cleaned.isEmpty()) "shared" else cleaned
        if (KNOWN.any { base.endsWith(it, ignoreCase = true) }) return base
        val ext = extensionFor(mime) ?: return base
        return base + ext
    }

    /** The extension a declared type implies, or null when it implies nothing. */
    fun extensionFor(mime: String?): String? {
        val m = (mime ?: "").lowercase().substringBefore(';').trim()
        return when (m) {
            "audio/midi", "audio/x-midi", "audio/sp-midi", "audio/mid",
            "application/midi", "application/x-midi" -> ".mid"
            "audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3" -> ".mp3"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> ".wav"
            "audio/flac", "audio/x-flac" -> ".flac"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/aacp" -> ".m4a"
            "audio/ogg", "application/ogg", "application/x-ogg", "audio/vorbis" -> ".ogg"
            "audio/opus" -> ".opus"
            else -> null
        }
    }

    /** The type a name implies, for when the sender declared none. */
    fun mimeFor(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".mid") || n.endsWith(".midi") || n.endsWith(".kar") -> "audio/midi"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".wav") || n.endsWith(".wave") -> "audio/x-wav"
            n.endsWith(".flac") -> "audio/flac"
            n.endsWith(".m4a") || n.endsWith(".aac") -> "audio/mp4"
            n.endsWith(".ogg") || n.endsWith(".oga") -> "audio/ogg"
            n.endsWith(".opus") -> "audio/opus"
            else -> "application/octet-stream"
        }
    }

    /**
     * Copies a borrowed file into the recordings folder and hands back what to play.
     *
     * The name that comes back is read from the copy rather than assumed, because
     * the folder is entitled to have chosen a different one - there may already be
     * a file of that name, and a document provider settles that its own way.
     *
     * Throws with the reason. A copy that could not be made has to say why: the
     * alternative is a user who believes they have kept something and has not.
     */
    fun copyInto(ctx: Context, arrival: Arrival): Arrival {
        val src = Uri.parse(arrival.uri)
        // The NAME decides the type, not the sender.
        //
        // This is the wrong way round everywhere else, and right here. The name has
        // already been reconciled with what the sender declared, so by now it is the
        // better of the two: for the case this whole feature exists for - a .mid
        // served as a generic stream - the name says .mid and the sender still says
        // octet-stream. Hand a document provider a name and a type that disagree and
        // it appends an extension of its own, so the copy lands as "tune.mid.bin",
        // and a score saved under a name that no longer ends in .mid goes to the
        // audio decoder next time it is opened.
        val mime = mimeFor(arrival.name).takeIf { it != "application/octet-stream" }
            ?: try { ctx.contentResolver.getType(src) } catch (_: Exception) { null }
            ?: "application/octet-stream"
        val out = RecordFolder.createDoc(ctx, arrival.name, mime)
        try {
            val input = ctx.contentResolver.openInputStream(src)
                ?: throw IllegalStateException("the file could not be read")
            input.use { i ->
                val o = ctx.contentResolver.openOutputStream(out)
                    ?: throw IllegalStateException("the copy could not be written")
                o.use { i.copyTo(it, 64 * 1024) }
            }
        } catch (e: Throwable) {
            // a half written copy is worse than none: it would be found later and
            // played, and it would end in the middle of a bar
            try { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, out) }
            catch (_: Exception) {
            }
            throw e
        }
        return Arrival(out.toString(), displayName(ctx, out))
    }

    private val KNOWN = listOf(
        ".mid", ".midi", ".kar",
        ".mp3", ".wav", ".wave", ".flac", ".m4a", ".aac", ".ogg", ".oga", ".opus"
    )
}
