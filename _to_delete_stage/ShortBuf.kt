package io.github.malexmuracoder.trainbeat

/** Self-growing short buffer, without the boxing of an ArrayList<Short>. */
class ShortBuf(initial: Int = 1 shl 18) {
    private var buf = ShortArray(initial.coerceAtLeast(1024))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap = if (cap > Int.MAX_VALUE / 2) Int.MAX_VALUE else cap * 2
        buf = buf.copyOf(cap)
    }

    fun add(s: Short) {
        ensure(1)
        buf[size++] = s
    }

    fun add(src: ShortArray, count: Int) {
        ensure(count)
        System.arraycopy(src, 0, buf, size, count)
        size += count
    }

    fun toArray(): ShortArray = buf.copyOf(size)
}
