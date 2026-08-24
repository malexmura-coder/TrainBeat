package io.github.malexmuracoder.trainbeat

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Random access to a document uri, so a 150 MB SoundFont never has to be read
 * whole. The provider is asked for a file descriptor and the channel is read at
 * absolute positions; if a provider refuses a descriptor there is no fallback that
 * would be honest, so it says so.
 */
class UriByteSource(ctx: Context, uri: Uri) : ByteSource {

    private val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("this file cannot be opened for reading")
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel = stream.channel

    override val size: Long =
        if (pfd.statSize > 0) pfd.statSize else channel.size()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(dst, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, p)
            if (n <= 0) break
            p += n
        }
    }

    override fun close() {
        try { channel.close() } catch (_: Exception) {}
        try { stream.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
    }
}
