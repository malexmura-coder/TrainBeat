package io.github.malexmuracoder.trainbeat

/**
 * One channel of a score, put on a staff.
 *
 * Free of Android, like the rest of the arithmetic in this app, and for the same
 * reason: engraving is a long chain of small decisions (which letter, which
 * octave, where a note may be split, which way a stem points) and every one of
 * them can be wrong by a single step without anything crashing. So the decisions
 * live here, as numbers, where a test can check them; the screen only draws what
 * this object has already decided.
 *
 * WHAT IT IS, AND WHAT IT IS NOT. It is a reading aid for a practice loop, not an
 * engraver. The rhythm is put on a grid of sixteenth notes: a note is its onset
 * and its length rounded to the nearest sixteenth, which is right for anything
 * written on a computer and approximate for a performance played by hand. There
 * are no triplets, no thirty-second notes and no beams (flags are used instead),
 * and a channel is read as ONE line per staff: where several notes begin together
 * they are a chord, and a chord lasts until the next onset on that staff. A bass
 * note held under a moving melody is therefore cut short on the page. That is
 * the trade made on purpose: one voice per staff is what can be read at a glance,
 * and a reading aid that needs studying is not one.
 *
 * COORDINATES. Time is in sixteenths from the start of the bar. Height is in
 * STEPS: one step is one line or one space of the staff, counted from the bottom
 * line (step 0), so the five lines are the even steps 0 to 8 and the middle line
 * is step 4. Pixels are the renderer's business.
 *
 * THE KEY SIGNATURE is not read from the file. It is chosen from the notes that
 * are actually on the staff, as the one that needs the fewest accidentals. A file
 * is free to declare C major for a piece in three flats, and often does (it is
 * what most editors write when nobody has set it), and after a transposition
 * whatever the file declared is wrong anyway. Fewest accidentals is what makes a
 * page readable, which is the only purpose it serves here.
 */
object Notation {

    enum class Clef { TREBLE, BASS }

    /** No accidental is printed in front of the note. */
    const val NO_ACC = -2
    const val FLAT = -1
    const val NATURAL = 0
    const val SHARP = 1

    /** Sixteenths in a whole note. */
    const val WHOLE = 16

    /** Middle C: at and above it goes to the upper staff of a grand staff. */
    const val SPLIT_KEY = 60

    /** Beyond this a file is not a piece, it is a mistake: stop rather than fill memory. */
    private const val MAX_BARS = 20_000

    /** A gap shorter than this share of the note before it is bridged, not written as a rest. */
    private const val LEGATO = 0.35

    /** The lengths a single note or rest may have, in sixteenths, longest first. */
    private val DURATIONS = intArrayOf(16, 12, 8, 6, 4, 3, 2, 1)

    private val NATURAL_PC = intArrayOf(0, 2, 4, 5, 7, 9, 11)       // C D E F G A B
    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    /** Signatures tried in this order, so that a tie goes to the simpler one. */
    private val SIGNATURE_ORDER = intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6)

    // Where the signature's accidentals stand, in steps, for each clef. Sharps go
    // F C G D A E B and flats B E A D G C F; the pattern is fixed by convention and
    // differs between the clefs only in which octave keeps it inside the staff.
    private val SHARP_STEPS_TREBLE = intArrayOf(8, 5, 9, 6, 3, 7, 4)
    private val FLAT_STEPS_TREBLE = intArrayOf(4, 7, 3, 6, 2, 5, 1)
    private val SHARP_STEPS_BASS = intArrayOf(6, 3, 7, 4, 1, 5, 2)
    private val FLAT_STEPS_BASS = intArrayOf(2, 5, 1, 4, 0, 3, -1)

    /** Steps of the signature's accidentals, nearest to the clef first. */
    fun signatureSteps(sharps: Int, clef: Clef): List<Int> {
        val n = Math.abs(sharps).coerceAtMost(7)
        val table = when {
            sharps >= 0 && clef == Clef.TREBLE -> SHARP_STEPS_TREBLE
            sharps >= 0 -> SHARP_STEPS_BASS
            clef == Clef.TREBLE -> FLAT_STEPS_TREBLE
            else -> FLAT_STEPS_BASS
        }
        return table.take(n)
    }

    // ------------------------------------------------------------------ model

    /** One note of a figure: where it sits, and what has to be printed with it. */
    class Head(
        val key: Int,
        val step: Int,
        /** NO_ACC, FLAT, NATURAL or SHARP. */
        val acc: Int,
        /** 0 is the column nearest the note; chords stack theirs to the left. */
        val accCol: Int,
        /** On the other side of the stem: the upper note of a second. */
        val shifted: Boolean,
        /** Joined to the same note in the next figure. */
        val tieNext: Boolean
    )

    /**
     * A note, a chord or a rest, with its place in the bar.
     *
     * A rest has no heads. A rest that is the whole bar is a whole rest whatever
     * the length of the bar is, which is how it is written.
     */
    class Figure(
        val at: Int,
        val dur: Int,
        val heads: List<Head>,
        val stemUp: Boolean,
        val wholeBar: Boolean
    ) {
        val rest: Boolean get() = heads.isEmpty()

        /** The undotted value: 16 whole, 8 half, 4 quarter, 2 eighth, 1 sixteenth. */
        val base: Int get() = baseOf(dur)
        val dotted: Boolean get() = !wholeBar && dur != base
        val hasStem: Boolean get() = heads.isNotEmpty() && base < WHOLE

        /** One flag for an eighth, two for a sixteenth. */
        val flags: Int get() = if (!hasStem) 0 else when (base) { 2 -> 1; 1 -> 2; else -> 0 }

        val lowStep: Int get() = heads.minOf { it.step }
        val highStep: Int get() = heads.maxOf { it.step }

        /** The stem starts at the note farthest from where it is going... */
        val stemFromStep: Int get() = if (stemUp) lowStep else highStep

        /** ...and is an octave long, or reaches the middle line if that is farther. */
        val stemToStep: Int get() = if (stemUp) maxOf(highStep + 7, 4) else minOf(lowStep - 7, 4)
    }

    /** One bar: what each staff holds in it, in time order. */
    class Bar(
        val startTick: Int,
        val lengthTicks: Int,
        /** Length in sixteenths. */
        val units: Int,
        val numerator: Int,
        val denominator: Int,
        val staves: List<List<Figure>>
    )

    /** The whole channel, bar by bar. */
    class Engraved(
        val clefs: List<Clef>,
        /** Key signature as a count of sharps, negative for flats. */
        val sharps: Int,
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

    fun baseOf(dur: Int): Int = when {
        dur >= 16 -> 16
        dur >= 8 -> 8
        dur >= 4 -> 4
        dur >= 2 -> 2
        else -> 1
    }

    // ------------------------------------------------------------------- time

    /**
     * The tick that a moment of the piece falls on: the tempo map read backwards.
     *
     * The same walk as Score.secondsAt, turned round, because the cursor of the
     * player speaks in milliseconds and a staff speaks in ticks. The piece is
     * measured at its own tempo, which is also what the player's position bar
     * measures, so the two agree whatever the speed is set to.
     */
    fun tickAt(score: Score, seconds: Double): Int {
        val ppq = score.ppq.coerceAtLeast(1)
        var t = 0.0
        var prevTick = 0
        var us = Score.DEFAULT_US_PER_QUARTER
        for (p in score.tempos) {
            val span = (p.tick - prevTick).toDouble() * us / (ppq * 1_000_000.0)
            if (t + span > seconds) break
            t += span
            prevTick = p.tick
            us = p.usPerQuarter
        }
        val ticks = prevTick + (seconds - t).coerceAtLeast(0.0) * ppq * 1_000_000.0 / us
        return ticks.toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    // ------------------------------------------------------------- the pitch

    /** A key signature: which letters are raised or lowered, and which pitch classes belong. */
    class Signature(val sharps: Int) {
        /** Alteration of each letter C..B: -1, 0 or +1. */
        val alt = IntArray(7)

        /** For a pitch class that belongs to the scale, its letter; otherwise -1. */
        val letterOf = IntArray(12) { -1 }

        init {
            val tonicPc = ((sharps * 7) % 12 + 12) % 12
            // each fifth up is four letters up: C G D A E B F
            val tonicLetter = ((sharps * 4) % 7 + 7) % 7
            for (i in 0 until 7) {
                val letter = (tonicLetter + i) % 7
                val pc = (tonicPc + MAJOR[i]) % 12
                alt[letter] = wrap(pc - NATURAL_PC[letter])
                letterOf[pc] = letter
            }
        }
    }

    /** A MIDI key written as a letter and an alteration. */
    class Spelled(
        val key: Int,
        /** Diatonic number: octave * 7 + letter, so that C4 is 28. */
        val diatonic: Int,
        val letter: Int,
        val alt: Int
    )

    private fun wrap(d: Int): Int {
        val x = ((d % 12) + 12) % 12
        return if (x > 6) x - 12 else x
    }

    /**
     * The name of a key within a signature.
     *
     * A note that belongs to the scale is spelled the way the scale spells it. One
     * that does not is a white key if it can be, and otherwise a sharp in a sharp
     * key and a flat in a flat one, which is what keeps a page of F major from
     * being full of A sharps.
     */
    fun spell(key: Int, sig: Signature): Spelled {
        val pc = ((key % 12) + 12) % 12
        var letter = sig.letterOf[pc]
        var alt: Int
        if (letter >= 0) {
            alt = sig.alt[letter]
        } else {
            letter = -1
            alt = 0
            val want = if (sig.sharps >= 0) 1 else -1
            for (l in 0 until 7) {
                val a = wrap(pc - NATURAL_PC[l])
                if (a == 0) { letter = l; alt = 0; break }
                if (a == want) { letter = l; alt = a }
            }
        }
        val octave = (key - NATURAL_PC[letter] - alt) / 12 - 1
        return Spelled(key, octave * 7 + letter, letter, alt)
    }

    /**
     * The signature whose scale holds the most of the notes, by how long they last.
     *
     * [hist] is the weight of each pitch class. Ties go to the signature with the
     * fewer accidentals, because the order the candidates are tried in says so.
     */
    fun bestSignature(hist: DoubleArray): Int {
        var best = 0
        var bestScore = -1.0
        for (s in SIGNATURE_ORDER) {
            val sig = Signature(s)
            var score = 0.0
            for (pc in 0 until 12) if (sig.letterOf[pc] >= 0) score += hist[pc]
            if (score > bestScore + 1e-9) {
                best = s
                bestScore = score
            }
        }
        return best
    }

    // -------------------------------------------------------------- engraving

    private class Ev(val start: Int, val end: Int, val keys: List<Int>)

    private class BarSpec(
        val startTick: Int,
        val lengthTicks: Int,
        val startU: Int,
        val endU: Int,
        val num: Int,
        val den: Int
    ) {
        val units: Int get() = endU - startU

        /** The beat in sixteenths: a note may cross a beat only if it starts on one. */
        val beat: Int
            get() = if (den == 8 && num % 3 == 0 && num >= 6) 6 else (16 / den).coerceAtLeast(1)
    }

    /**
     * Puts a channel on one staff, or on two if it is a keyboard.
     *
     * Returns null when there is nothing to show: the drum channel, where a key
     * number is an instrument and not a pitch, or a channel with no notes.
     *
     * A keyboard - a piano program, or any part that spans the instrument - gets
     * the grand staff, split at middle C, provided both hands have something to do;
     * a right-hand-only part would otherwise leave a whole staff of rests on a
     * screen that has no room to spare.
     */
    fun engrave(score: Score, channel: Int, semitones: Int = 0): Engraved? {
        if (channel == Score.DRUM_CHANNEL) return null
        val notes = score.transpose(semitones).notes
            .filter { it.channel == channel && it.durTicks > 0 }
            .sortedBy { it.tick }
        if (notes.isEmpty()) return null

        val ppq = score.ppq.coerceAtLeast(1)
        val unitTicks = ppq / 4.0

        val hist = DoubleArray(12)
        for (n in notes) hist[((n.key % 12) + 12) % 12] += minOf(n.durTicks.toDouble(), 2.0 * ppq)
        val sharps = bestSignature(hist)
        val sig = Signature(sharps)

        val program = score.programs.firstOrNull { it.channel == channel }?.program ?: 0
        val low = notes.minOf { it.key }
        val high = notes.maxOf { it.key }
        val below = notes.count { it.key < SPLIT_KEY }
        val above = notes.size - below
        val keyboard = program in 0..7 || (low < 53 && high > 71)
        val grand = keyboard && below * 20 >= notes.size && above * 20 >= notes.size

        val clefs: List<Clef>
        val perStaff: List<List<MidiNote>>
        if (grand) {
            clefs = listOf(Clef.TREBLE, Clef.BASS)
            perStaff = listOf(
                notes.filter { it.key >= SPLIT_KEY },
                notes.filter { it.key < SPLIT_KEY }
            )
        } else {
            val mean = notes.sumOf { it.key } / notes.size
            clefs = listOf(if (mean >= 57) Clef.TREBLE else Clef.BASS)
            perStaff = listOf(notes)
        }

        // ---- the bars: lengths come from the time signature in force at each one
        val endTick = maxOf(score.endTick, notes.maxOf { it.endTick })
        val specs = ArrayList<BarSpec>()
        var t = 0
        while (t < endTick && specs.size < MAX_BARS) {
            val len = score.ticksPerBar(t)
            var num = 4
            var den = 4
            for (s in score.timeSignatures) {
                if (s.tick > t) break
                num = s.numerator
                den = s.denominator
            }
            val startU = Math.round(t / unitTicks).toInt()
            val endU = Math.round((t + len) / unitTicks).toInt()
            specs.add(BarSpec(t, len, startU, maxOf(endU, startU + 1), num, den.coerceAtLeast(1)))
            t += len
        }

        // ---- the figures, staff by staff
        val spelled = HashMap<Int, Spelled>()
        val staffBars = ArrayList<List<List<Figure>>>()
        for ((si, staffNotes) in perStaff.withIndex()) {
            val events = eventsOf(staffNotes, unitTicks)
            var ei = 0
            val perBar = ArrayList<List<Figure>>()
            for (b in specs) {
                val figs = ArrayList<Figure>()
                // what an accidental earlier in the bar has already done to a line
                // or space: it holds to the barline and no further
                val state = HashMap<Int, Int>()
                while (ei < events.size && events[ei].end <= b.startU) ei++
                var j = ei
                while (j < events.size && events[j].start < b.endU) {
                    val ev = events[j]
                    val s0 = maxOf(ev.start, b.startU) - b.startU
                    val s1 = minOf(ev.end, b.endU) - b.startU
                    val fromPrev = ev.start < b.startU
                    val toNext = ev.end > b.endU
                    if (ev.keys.isEmpty()) {
                        if (s0 == 0 && s1 == b.units) {
                            figs.add(Figure(0, b.units, emptyList(), true, true))
                        } else {
                            for ((off, d) in pieces(s0, s1, b.beat)) {
                                figs.add(Figure(off, d, emptyList(), true, false))
                            }
                        }
                    } else {
                        val ps = pieces(s0, s1, b.beat)
                        for ((k, p) in ps.withIndex()) {
                            val last = k == ps.size - 1
                            figs.add(
                                figure(
                                    p.first, p.second, ev.keys, sig, clefs[si], state, spelled,
                                    tieNext = !last || toNext,
                                    // a note tied over the barline is not announced twice
                                    silentAcc = k == 0 && fromPrev
                                )
                            )
                        }
                    }
                    j++
                }
                perBar.add(figs)
            }
            staffBars.add(perBar)
        }

        val bars = specs.mapIndexed { i, b ->
            Bar(b.startTick, b.lengthTicks, b.units, b.num, b.den, staffBars.map { it[i] })
        }
        return Engraved(clefs, sharps, unitTicks, bars)
    }

    /**
     * The notes of a staff as a line of events that covers all of time.
     *
     * Notes that begin on the same sixteenth are one chord. A chord lasts until the
     * next onset, or until its own end if that comes first and leaves a real gap;
     * a small gap is the player lifting the finger and is bridged, because writing
     * every one of them as a rest would turn a legato line into a page of dust.
     */
    private fun eventsOf(notes: List<MidiNote>, unitTicks: Double): List<Ev> {
        val groups = java.util.TreeMap<Int, MutableList<MidiNote>>()
        for (n in notes) {
            val onset = Math.round(n.tick / unitTicks).toInt()
            groups.getOrPut(onset) { ArrayList() }.add(n)
        }
        val onsets = groups.keys.toList()
        val out = ArrayList<Ev>()
        var cursor = 0
        for ((i, on) in onsets.withIndex()) {
            val g = groups[on]!!
            if (on > cursor) out.add(Ev(cursor, on, emptyList()))
            val hasNext = i + 1 < onsets.size
            val next = if (hasNext) onsets[i + 1] else Int.MAX_VALUE
            val rawEnd = g.maxOf { it.endTick / unitTicks }
            var end = when {
                hasNext && rawEnd >= next -> next
                hasNext && (next - rawEnd) <= LEGATO * (next - on) -> next
                else -> Math.round(rawEnd).toInt()
            }
            if (end < on + 1) end = on + 1
            if (end > next) end = next
            out.add(Ev(on, end, g.map { it.key }.distinct().sorted()))
            cursor = end
        }
        // the rest of time is silence, which keeps every bar covered
        out.add(Ev(cursor, Int.MAX_VALUE, emptyList()))
        return out
    }

    /**
     * Cuts a stretch of a bar into lengths that can be written.
     *
     * The rule that does the work: a figure may run across a beat only if it starts
     * on one. So a quarter note that starts on an offbeat eighth becomes two tied
     * eighths, which is how a musician writes a syncopation, while a half note on
     * beat two stays a half note.
     */
    internal fun pieces(from: Int, to: Int, beat: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var s = from
        while (s < to) {
            val rem = to - s
            var chosen = 1
            for (v in DURATIONS) {
                if (v > rem) continue
                val into = s % beat
                if (into != 0 && into + v > beat) continue
                chosen = v
                break
            }
            out.add(Pair(s, chosen))
            s += chosen
        }
        return out
    }

    private fun accOf(alt: Int): Int = when {
        alt > 0 -> SHARP
        alt < 0 -> FLAT
        else -> NATURAL
    }

    private fun figure(
        at: Int,
        dur: Int,
        keys: List<Int>,
        sig: Signature,
        clef: Clef,
        state: HashMap<Int, Int>,
        cache: HashMap<Int, Spelled>,
        tieNext: Boolean,
        silentAcc: Boolean
    ): Figure {
        val sp = keys.map { k -> cache.getOrPut(k) { spell(k, sig) } }
            .sortedWith(compareBy<Spelled>({ it.diatonic }, { it.alt }))
        val n = sp.size
        val bottom = if (clef == Clef.TREBLE) 30 else 18         // E4 and G2: the bottom lines
        val steps = IntArray(n) { sp[it].diatonic - bottom }

        val accs = IntArray(n) { NO_ACC }
        for (i in 0 until n) {
            val s = sp[i]
            val current = state[s.diatonic] ?: sig.alt[s.letter]
            if (current != s.alt) {
                if (!silentAcc) accs[i] = accOf(s.alt)
                state[s.diatonic] = s.alt
            }
        }

        // the stem goes away from the note that is farther from the middle line
        val stemUp = (4 - steps[0]) > (steps[n - 1] - 4)

        // seconds: the neighbour of a note on the stem side goes to the other side
        val shifted = BooleanArray(n)
        var prev = false
        if (stemUp) {
            for (i in 0 until n) {
                if (i > 0 && steps[i] - steps[i - 1] <= 1 && !prev) { shifted[i] = true; prev = true }
                else prev = false
            }
        } else {
            for (i in n - 1 downTo 0) {
                if (i < n - 1 && steps[i + 1] - steps[i] <= 1 && !prev) { shifted[i] = true; prev = true }
                else prev = false
            }
        }

        // accidentals stack leftwards, top to bottom, in the first column with room
        val cols = IntArray(n)
        val lowestInCol = IntArray(4) { Int.MAX_VALUE }
        for (i in n - 1 downTo 0) {
            if (accs[i] == NO_ACC) continue
            var c = 0
            while (c < 3 && lowestInCol[c] != Int.MAX_VALUE && lowestInCol[c] - steps[i] < 6) c++
            cols[i] = c
            lowestInCol[c] = steps[i]
        }

        val heads = (0 until n).map { i ->
            Head(sp[i].key, steps[i], accs[i], cols[i], shifted[i], tieNext)
        }
        return Figure(at, dur, heads, stemUp, false)
    }
}
