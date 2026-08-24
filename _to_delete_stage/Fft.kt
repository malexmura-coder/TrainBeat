package io.github.malexmuracoder.trainbeat

/**
 * In-place radix-2 FFT. Size must be a power of two.
 * Twiddle tables are computed once per size and cached, because the analyser
 * runs thousands of transforms over a single file.
 */
object Fft {

    private val cosTables = HashMap<Int, FloatArray>()
    private val sinTables = HashMap<Int, FloatArray>()

    private fun tables(n: Int): Pair<FloatArray, FloatArray> {
        synchronized(cosTables) {
            val c = cosTables[n]
            val s = sinTables[n]
            if (c != null && s != null) return Pair(c, s)
            val nc = FloatArray(n / 2)
            val ns = FloatArray(n / 2)
            for (k in 0 until n / 2) {
                val a = -2.0 * Math.PI * k / n
                nc[k] = Math.cos(a).toFloat()
                ns[k] = Math.sin(a).toFloat()
            }
            cosTables[n] = nc
            sinTables[n] = ns
            return Pair(nc, ns)
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size) { "re and im must have the same size" }
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
        val (cosT, sinT) = tables(n)

        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val half = len / 2
            val stride = n / len
            var base = 0
            while (base < n) {
                for (k in 0 until half) {
                    val cr = cosT[k * stride]
                    val ci = sinT[k * stride]
                    val a = base + k
                    val b = a + half
                    val vr = re[b] * cr - im[b] * ci
                    val vi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                }
                base += len
            }
            len = len shl 1
        }
    }

    /** Magnitude spectrum of a real signal window (first n/2 bins). */
    fun magnitudes(window: FloatArray): FloatArray {
        val n = window.size
        val re = window.copyOf()
        val im = FloatArray(n)
        transform(re, im)
        val out = FloatArray(n / 2)
        for (k in 0 until n / 2) {
            out[k] = Math.sqrt((re[k] * re[k] + im[k] * im[k]).toDouble()).toFloat()
        }
        return out
    }

    /** Blackman window: what the KeyFinder chain uses for the long analysis frame. */
    fun blackman(n: Int): FloatArray = FloatArray(n) {
        (0.42 - 0.5 * Math.cos(2.0 * Math.PI * it / (n - 1)) +
            0.08 * Math.cos(4.0 * Math.PI * it / (n - 1))).toFloat()
    }

    fun hann(n: Int): FloatArray =
        FloatArray(n) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (n - 1))).toFloat() }
}
