package io.github.malexmuracoder.trainbeat

/**
 * Tablature: where on the neck each note is played.
 *
 * A MIDI file knows which pitch, not which string, so a tablature cannot be made
 * from one without guessing. A MusicXML file written for a tablature says it note by
 * note (string and fret), and that is the whole reason it is worth reading: what
 * is drawn here is what the file says, never a guess.
 *
 * Two layers, like the staff. This file is the data and the arithmetic, free of
 * Android and tested as such; TabView draws it.
 *
 * Strings are numbered as MusicXML numbers them: 1 is the HIGHEST string, the top
 * line of the tablature.
 *
 * What is not shown, on purpose and as a known limit: techniques (hammer-on, pull-off,
 * bends, slides, harmonics), triplets and thirty-second notes come out as the nearest
 * plain value, no beams (flags only), and rests are not written.
 */

/** One note on the neck, in the ticks of the score it belongs to. */
class TabNote(
    val tick: Int,
    val durTicks: Int,
    /** 1 = the highest string. */
    val string: Int,
    val fret: Int,
    /** The continuation of a tied note: shown in brackets, not struck again. */
    val tiedFrom: Boolean
)

/** One bar of the piece AS PLAYED: repeats and endings unrolled, so a repeated bar is listed twice. */
class TabBar(val startTick: Int, val lengthTicks: Int, val numerator: Int, val denominator: Int)

/** One instrument written for a fretboard. */
class TabPart(
    val channel: Int,
    val name: String,
    /** The pitch (MIDI key) of each open string, index 0 = string 1 = the highest. */
    val open: IntArray,
    val notes: List<TabNote>
) {
    val strings: Int get() = open.size
}

/** Everything that can be shown as a tablature, beside the Score it was read with. */
class TabScore(val ppq: Int, val bars: List<TabBar>, val parts: List<TabPart>) {
    fun partFor(channel: Int): TabPart? = parts.firstOrNull { it.channel == channel }
}

object TabLayout {

    /** The highest fret a note is moved to when the key is changed. */
    const val MAX_FRET = 24

    class Cell(val string: Int, val fret: Int, val tied: Boolean)

    /** Everything struck at the same moment: a note or a chord. */
    class Figure(
        /** Position in the bar, in sixteenths. */
        val at: Float,
        /** 16 whole, 8 half, 4 quarter, 2 eighth, 1 sixteenth. */
        val base: Int,
        val dotted: Boolean,
        /** Sorted by string, the highest first. */
        val cells: List<Cell>
    ) {
        val hasStem: Boolean get() = base < 16
        val flags: Int get() = when {
            base >= 4 -> 0
            base == 2 -> 1
            else -> 2
        }
    }

    class Bar(
        val startTick: Int,
        val lengthTicks: Int,
        /** Length in sixteenths (not always whole: a bar can be a tuplet's worth). */
        val units: Float,
        val numerator: Int,
        val denominator: Int,
        val figures: List<Figure>
    )

    class Engraved(
        val strings: Int,
        val ticksPerUnit: Double,
        val bars: List<Bar>
    ) {
        /** The bar a tick falls in: the last one that has started. */
        fun barIndexAt(tick: Int): Int {
            var lo = 0
            var hi = bars.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (bars[mid].startTick <= tick) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** How far into a bar a tick is, in sixteenths, kept inside the bar. */
        fun unitsInto(bar: Bar, tick: Int): Double =
            ((tick - bar.startTick) / ticksPerUnit).coerceIn(0.0, bar.units.toDouble())
    }

    /**
     * Moves a note by [semitones]. It stays on its string when it can; otherwise it
     * goes to the nearest string where it lies between the open string and
     * [MAX_FRET]. Null when no string can play it: that note is left out, which is
     * more honest than a fret that does not exist.
     */
    fun moved(open: IntArray, string: Int, fret: Int, semitones: Int): Pair<Int, Int>? {
        if (semitones == 0) return Pair(string, fret)
        val pitch = open[string - 1] + fret + semitones
        val same = pitch - open[string - 1]
        if (same in 0..MAX_FRET) return Pair(string, same)
        var best = -1
        for (s in open.indices) {
            val f = pitch - open[s]
            if (f !in 0..MAX_FRET) continue
            if (best < 0 || Math.abs(s - (string - 1)) < Math.abs(best - (string - 1))) best = s
        }
        return if (best < 0) null else Pair(best + 1, pitch - open[best])
    }

    /** The written value of a length in sixteenths: the base, and whether it is dotted. */
    fun rhythmOf(units: Double): Pair<Int, Boolean> {
        var base = 1
        for (p in intArrayOf(16, 8, 4, 2, 1)) {
            if (p <= units * 1.0001) { base = p; break }
        }
        val dotted = base in 2..8 && units >= base * 1.4
        return Pair(base, dotted)
    }

    /**
     * The tablature of one instrument, bar by bar.
     *
     * A figure lasts as long as its SHORTEST note, cut at the barline: under a held
     * bass note the melody still reads as the melody. Notes are put in the bar they
     * start in; a note that runs on into the next one is the file's business (it
     * writes a tie), not something to be invented here.
     */
    fun engrave(tab: TabScore, channel: Int, semitones: Int = 0): Engraved? {
        val part = tab.partFor(channel) ?: return null
        if (tab.bars.isEmpty() || part.notes.isEmpty()) return null
        val tpu = tab.ppq / 4.0
        val bars = ArrayList<Bar>(tab.bars.size)

        // notes are sorted by tick: one pass over them, one pointer
        var next = 0
        for (b in tab.bars) {
            val end = b.startTick + b.lengthTicks
            val here = ArrayList<TabNote>()
            while (next < part.notes.size && part.notes[next].tick < end) {
                val n = part.notes[next++]
                if (n.tick >= b.startTick) here.add(n)
            }
            val figures = ArrayList<Figure>()
            for ((tick, group) in here.groupBy { it.tick }.toSortedMap()) {
                val cells = ArrayList<Cell>()
                var shortest = Int.MAX_VALUE
                for (n in group) {
                    val m = moved(part.open, n.string, n.fret, semitones) ?: continue
                    cells.add(Cell(m.first, m.second, n.tiedFrom))
                    shortest = minOf(shortest, minOf(n.durTicks, end - tick))
                }
                if (cells.isEmpty()) continue
                cells.sortBy { it.string }
                val (base, dotted) = rhythmOf(shortest.coerceAtLeast(1) / tpu)
                figures.add(Figure(((tick - b.startTick) / tpu).toFloat(), base, dotted, cells))
            }
            bars.add(Bar(b.startTick, b.lengthTicks, (b.lengthTicks / tpu).toFloat(),
                b.numerator, b.denominator, figures))
        }
        return Engraved(part.strings, tpu, bars)
    }
}
