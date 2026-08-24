package io.github.malexmuracoder.trainbeat

import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to a plain file. Used by the tests and the bench; the app reads a
 * content uri through a file descriptor instead, with the same interface.
 */
class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
        raf.seek(pos)
        raf.readFully(dst, off, len)
    }
    override fun close() = raf.close()
}
