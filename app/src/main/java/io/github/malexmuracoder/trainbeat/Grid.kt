package io.github.malexmuracoder.trainbeat

/**
 * The fine grid drawn between the two cursors.
 *
 * It divides THE SELECTION, not the tempo. That is the whole point: a detected BPM
 * is an estimate, and on a phrase recorded into the microphone there is no BPM at
 * all - but the loop itself is a hard fact. So if the cut is right, N equal
 * divisions of it land on the beats, and the peaks sit on the lines. If they drift
 * away from the lines, the cut is wrong. The grid is a measuring instrument for the
 * cut, not a decoration on top of it.
 *
 * The T entries draw triplets: the same main lines, with two thinner ones inside
 * each interval. Note that 4T and 12 mark exactly the same positions - four
 * triplets are twelve. What differs is the weight: 12 is twelve equal lines, 4T is
 * four strong and eight faint, which is what makes a swung or a straight division
 * readable at a glance instead of countable.
 */
object Grid {

    /** In the order they appear in the menu. The first is the default: no grid. */
    val OPTIONS = listOf("off", "4", "8", "12", "16", "4T", "8T", "12T", "16T")

    const val OFF = "off"

    /**
     * Frame positions, absolute. Both lists are strictly INSIDE the selection: the
     * boundaries are already drawn as cursors, and repeating them there would make
     * the first and last division look narrower than they are.
     */
    class Lines(val strong: IntArray, val weak: IntArray) {
        val total: Int get() = strong.size + weak.size
    }

    private val NONE = Lines(IntArray(0), IntArray(0))

    /** How many divisions the option asks for, and whether they are tripletted. */
    fun divisionsOf(option: String): Int {
        val body = if (option.endsWith("T")) option.dropLast(1) else option
        return body.toIntOrNull()?.takeIf { it in 1..64 } ?: 0
    }

    fun isTriplet(option: String): Boolean = option.endsWith("T")

    fun of(option: String, startFrame: Int, endFrame: Int): Lines {
        val n = divisionsOf(option)
        val len = endFrame - startFrame
        if (option == OFF || n <= 0 || len <= 1) return NONE

        return if (!isTriplet(option)) {
            val strong = IntArray(n - 1) { startFrame + ((it + 1).toLong() * len / n).toInt() }
            Lines(strong, IntArray(0))
        } else {
            // one pass over 3N steps: every third one is a main line, the rest faint
            val steps = 3 * n
            val strong = ArrayList<Int>(n)
            val weak = ArrayList<Int>(steps)
            for (i in 1 until steps) {
                val at = startFrame + (i.toLong() * len / steps).toInt()
                if (i % 3 == 0) strong.add(at) else weak.add(at)
            }
            Lines(strong.toIntArray(), weak.toIntArray())
        }
    }

    /** How long one main division lasts, for the readout beside the menu. */
    fun divisionSeconds(option: String, startFrame: Int, endFrame: Int, sampleRate: Int): Double {
        val n = divisionsOf(option)
        if (n <= 0 || sampleRate <= 0 || endFrame <= startFrame) return 0.0
        return (endFrame - startFrame).toDouble() / n / sampleRate
    }
}
