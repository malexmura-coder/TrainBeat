package io.github.malexmuracoder.trainbeat

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The staff on the screen: what Notation decided, drawn with a music font.
 *
 * NOTHING HERE IS DRAWN BY HAND except straight lines (staff lines, stems, ledger
 * lines, barlines) and the arc of a tie. Every note head, clef, accidental, rest,
 * flag, dot and digit is a glyph of Bravura, a font made for this (SMuFL layout,
 * SIL Open Font License, shipped unmodified in assets/fonts), which is why it can
 * be done in a couple of hundred lines. The code points were checked against the
 * font's own outlines and its published bounding boxes, not copied from memory.
 *
 * THE SIZE of everything is the staff space - the gap between two lines - and a
 * SMuFL font is designed so that its em is four of them. All distances below are
 * therefore written in staff spaces and multiplied by one number, which is also
 * why the whole thing scales with a single factor: see StaffScale.
 *
 * WHAT IS SHOWN. A page: its first bar at the left and as many of the following
 * bars as fit the width, the last one cut off wherever the screen ends. The page
 * stays where it is while the cursor walks through it, and turns only when the
 * music enters the last bar that is visible (see StaffPaging): that bar becomes the
 * first of the next page. The number of bars on screen is whatever the width
 * allows, and a wider screen simply shows more of the future. The clef, the key
 * signature and the time signature stay at the left, so that a bar cut loose from
 * the start of the piece can still be read. Within a bar the spacing is
 * proportional to time, so the cursor moves at an even pace and sits exactly where
 * the music is.
 */
@Composable
fun StaffView(
    engraved: Notation.Engraved,
    tick: Int,
    typeface: Typeface,
    ink: Color,
    accent: Color,
    scale: Float = 1f,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val spacePx = with(LocalDensity.current) { (StaffScale.BASE_DP * scale).dp.toPx() }
        val widthSpaces = constraints.maxWidth / spacePx
        val bars = engraved.bars
        // The page lives as long as this engraving: a new part or key starts from the top.
        val page = remember(engraved) { PageStart() }
        val current = if (bars.isEmpty()) 0 else engraved.barIndexAt(tick)
        if (bars.isNotEmpty()) {
            val from = page.value.coerceIn(0, bars.size - 1)
            page.value = StaffPaging.nextStart(from, current, visibleBars(engraved, from, widthSpaces), bars.size)
        }
        val first = page.value
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height((StaffScale.BASE_DP * scale).dp * StaffLayout.heightInSpaces(engraved.clefs.size))
        ) {
            paintStaff(engraved, first, current, tick, typeface, ink, accent, scale)
        }
    }
}

/** The first bar on the page; kept between frames, never read by anything but StaffView. */
private class PageStart {
    var value: Int = 0
}

/** How many bars the page that starts at [from] shows, given the width in staff spaces. */
private fun visibleBars(e: Notation.Engraved, from: Int, widthSpaces: Float): Int {
    val b = e.bars[from]
    val available = widthSpaces - StaffPaging.headerSpaces(e.sharps, b.numerator, b.denominator)
    return StaffPaging.visibleCount(from, e.bars.size, available) { StaffLayout.barSpaces(e.bars[it].units) }
}

/** The measures of the picture, in staff spaces unless the name says otherwise. */
internal object StaffLayout {
    /** Room above the top staff and below the bottom one, for ledger lines. */
    const val MARGIN = 5f
    const val STAFF = 4f

    /** Between the bottom line of one staff and the top line of the next. */
    const val GAP = 9f

    /** Left padding inside a bar: where an accidental of the first note goes. */
    const val BAR_PAD = 1.5f

    /** Width of one sixteenth. */
    const val UNIT = 2.3f

    const val HEAD = 1.18f
    const val WHOLE_HEAD = 1.688f
    const val STEM = 0.12f
    const val LINE = 0.13f
    const val LEDGER = 0.16f
    const val ACC_COLUMN = 1.05f

    /** Width of a bar of [units] sixteenths. */
    fun barSpaces(units: Int): Float = BAR_PAD + units * UNIT

    fun heightInSpaces(staves: Int): Float = 2 * MARGIN + staves * STAFF + (staves - 1) * GAP
}

// SMuFL code points (Private Use Area), each checked against the font file.
private const val G_CLEF = 0xE050
private const val F_CLEF = 0xE062
private const val HEAD_WHOLE = 0xE0A2
private const val HEAD_HALF = 0xE0A3
private const val HEAD_BLACK = 0xE0A4
private const val ACC_FLAT = 0xE260
private const val ACC_NATURAL = 0xE261
private const val ACC_SHARP = 0xE262
private const val REST_WHOLE = 0xE4E3
private const val REST_HALF = 0xE4E4
private const val REST_QUARTER = 0xE4E5
private const val REST_EIGHTH = 0xE4E6
private const val REST_SIXTEENTH = 0xE4E7
private const val FLAG_8_UP = 0xE240
private const val FLAG_8_DOWN = 0xE241
private const val FLAG_16_UP = 0xE242
private const val FLAG_16_DOWN = 0xE243
private const val DOT = 0xE1E7
private const val DIGIT_0 = 0xE080

private fun DrawScope.paintStaff(
    e: Notation.Engraved,
    firstBar: Int,
    currentBar: Int,
    tick: Int,
    typeface: Typeface,
    ink: Color,
    accent: Color,
    scale: Float
) {
    if (e.bars.isEmpty()) return
    val sp = (StaffScale.BASE_DP * scale).dp.toPx()
    val width = size.width
    val n = e.clefs.size

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = 4f * sp                  // the em of a SMuFL font is four staff spaces
        color = ink.toArgb()
    }
    fun glyph(code: Int, x: Float, y: Float) {
        drawIntoCanvas { it.nativeCanvas.drawText(code.toChar().toString(), x, y, paint) }
    }

    val topOf = FloatArray(n) { (StaffLayout.MARGIN + it * (StaffLayout.STAFF + StaffLayout.GAP)) * sp }
    // step 8 is the top line, step 0 the bottom one, half a space apart
    fun yOf(staff: Int, step: Int): Float = topOf[staff] + (8 - step) * 0.5f * sp
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float) =
        drawLine(ink, Offset(x1, y1), Offset(x2, y2), strokeWidth = thickness * sp)

    // ---- the five lines of each staff
    for (i in 0 until n) {
        for (k in 0..4) {
            val y = topOf[i] + k * sp
            line(0f, y, width, y, StaffLayout.LINE)
        }
    }
    val systemTop = topOf[0]
    val systemBottom = topOf[n - 1] + StaffLayout.STAFF * sp

    // ---- the header: clef, key signature, time signature
    var x = StaffPaging.CLEF_X * sp
    if (n > 1) line(0.25f * sp, systemTop, 0.25f * sp, systemBottom, 0.16f)
    for (i in 0 until n) {
        val treble = e.clefs[i] == Notation.Clef.TREBLE
        glyph(if (treble) G_CLEF else F_CLEF, x + 0.4f * sp, yOf(i, if (treble) 2 else 6))
    }
    x += StaffPaging.CLEF_ADVANCE * sp

    val flats = e.sharps < 0
    for (k in 0 until Math.abs(e.sharps)) {
        for (i in 0 until n) {
            val step = Notation.signatureSteps(e.sharps, e.clefs[i])[k]
            glyph(if (flats) ACC_FLAT else ACC_SHARP, x, yOf(i, step))
        }
        x += StaffPaging.SIGNATURE_STEP * sp
    }
    if (e.sharps != 0) x += StaffPaging.SIGNATURE_EXTRA * sp

    // the time signature is that of the first bar of the page
    val first = e.bars[firstBar]
    val digitsLong = maxOf(first.numerator.toString().length, first.denominator.toString().length)
    val digitsWidth = digitsLong * StaffPaging.DIGIT_WIDTH * sp
    fun number(staff: Int, value: Int, centreStep: Int) {
        val s = value.toString()
        val start = x + (digitsWidth - s.length * StaffPaging.DIGIT_WIDTH * sp) / 2f
        for ((k, ch) in s.withIndex()) {
            glyph(DIGIT_0 + (ch - '0'), start + k * StaffPaging.DIGIT_WIDTH * sp, yOf(staff, centreStep))
        }
    }
    for (i in 0 until n) {
        number(i, first.numerator, 6)
        number(i, first.denominator, 2)
    }
    x += digitsWidth + StaffPaging.HEADER_TAIL * sp

    // ---- the bars, from the first of the page to the edge of the screen
    val headerEnd = x
    val pad = StaffLayout.BAR_PAD * sp
    val perUnit = StaffLayout.UNIT * sp
    var bx = headerEnd
    var bi = firstBar
    var cursorBarX = -1f
    while (bx < width && bi < e.bars.size) {
        val bar = e.bars[bi]
        val bw = pad + bar.units * perUnit
        if (bi == currentBar) cursorBarX = bx
        for (si in 0 until n) {
            val figs = bar.staves[si]
            for ((fi, f) in figs.withIndex()) {
                val fx = bx + pad + f.at * perUnit
                // where the next figure of this staff is, for a tie to reach it
                val nextX: Float? = when {
                    fi + 1 < figs.size -> bx + pad + figs[fi + 1].at * perUnit
                    else -> e.bars.getOrNull(bi + 1)?.staves?.get(si)?.firstOrNull()
                        ?.let { bx + bw + pad + it.at * perUnit }
                }
                if (f.rest) paintRest(si, f, fx, bx, bw, sp, ::yOf, ::glyph)
                else paintFigure(si, f, fx, nextX, width, sp, ink, ::yOf, ::glyph, ::line, this)
            }
        }
        line(bx + bw, systemTop, bx + bw, systemBottom, 0.16f)
        bx += bw
        bi++
    }

    // ---- the cursor: where the music is, inside its bar (which is on the page, or the
    // page would have turned; if it is somehow not, nothing is drawn)
    val cx = cursorBarX + pad + e.unitsInto(e.bars[currentBar], tick).toFloat() * perUnit
    if (cursorBarX >= 0f && cx in 0f..width) {
        drawLine(
            accent,
            Offset(cx, systemTop - 2f * sp),
            Offset(cx, systemBottom + 2f * sp),
            strokeWidth = 2.dp.toPx()
        )
    }
}

private fun paintRest(
    staff: Int,
    f: Notation.Figure,
    x: Float,
    barX: Float,
    barWidth: Float,
    sp: Float,
    yOf: (Int, Int) -> Float,
    glyph: (Int, Float, Float) -> Unit
) {
    if (f.wholeBar) {
        // a whole rest, centred, whatever the length of the bar
        glyph(REST_WHOLE, barX + (barWidth - 1.128f * sp) / 2f, yOf(staff, 6))
        return
    }
    when (f.base) {
        16 -> glyph(REST_WHOLE, x, yOf(staff, 6))           // hangs from the fourth line
        8 -> glyph(REST_HALF, x, yOf(staff, 4))             // sits on the middle line
        4 -> glyph(REST_QUARTER, x, yOf(staff, 4))
        2 -> glyph(REST_EIGHTH, x, yOf(staff, 4))
        else -> glyph(REST_SIXTEENTH, x, yOf(staff, 4))
    }
    if (f.dotted) glyph(DOT, x + 1.4f * sp, yOf(staff, 5))
}

private fun paintFigure(
    staff: Int,
    f: Notation.Figure,
    x: Float,
    nextX: Float?,
    width: Float,
    sp: Float,
    ink: Color,
    yOf: (Int, Int) -> Float,
    glyph: (Int, Float, Float) -> Unit,
    line: (Float, Float, Float, Float, Float) -> Unit,
    scope: DrawScope
) {
    val headW = (if (f.base >= Notation.WHOLE) StaffLayout.WHOLE_HEAD else StaffLayout.HEAD) * sp
    val stemSide = StaffLayout.HEAD * sp
    val code = when {
        f.base >= Notation.WHOLE -> HEAD_WHOLE
        f.base >= 8 -> HEAD_HALF
        else -> HEAD_BLACK
    }

    // a note on the other side of the stem is one head width away from it
    fun headX(h: Notation.Head): Float = when {
        !h.shifted -> x
        f.stemUp -> x + stemSide
        else -> x - stemSide
    }
    val left = f.heads.minOf { headX(it) }
    val right = f.heads.maxOf { headX(it) } + headW

    // ledger lines, once per figure, for the farthest note each way
    var l = -2
    while (l >= f.lowStep) {
        line(left - 0.4f * sp, yOf(staff, l), right + 0.4f * sp, yOf(staff, l), StaffLayout.LEDGER)
        l -= 2
    }
    l = 10
    while (l <= f.highStep) {
        line(left - 0.4f * sp, yOf(staff, l), right + 0.4f * sp, yOf(staff, l), StaffLayout.LEDGER)
        l += 2
    }

    for (h in f.heads) glyph(code, headX(h), yOf(staff, h.step))

    if (f.hasStem) {
        // stem up hangs on the right edge of the head, stem down on the left one
        val sx = if (f.stemUp) x + stemSide - StaffLayout.STEM * sp / 2f
        else x + StaffLayout.STEM * sp / 2f
        val yTo = yOf(staff, f.stemToStep)
        line(sx, yOf(staff, f.stemFromStep), sx, yTo, StaffLayout.STEM)
        if (f.flags > 0) {
            val flag = when {
                f.flags == 1 && f.stemUp -> FLAG_8_UP
                f.flags == 1 -> FLAG_8_DOWN
                f.stemUp -> FLAG_16_UP
                else -> FLAG_16_DOWN
            }
            glyph(flag, sx - StaffLayout.STEM * sp / 2f, yTo)
        }
    }

    for (h in f.heads) {
        if (h.acc == Notation.NO_ACC) continue
        val acc = when (h.acc) {
            Notation.SHARP -> ACC_SHARP
            Notation.FLAT -> ACC_FLAT
            else -> ACC_NATURAL
        }
        glyph(acc, left - 0.2f * sp - (h.accCol + 1) * StaffLayout.ACC_COLUMN * sp, yOf(staff, h.step))
    }

    if (f.dotted) {
        // a dot lives in a space: a note on a line gets it from the space above
        val seen = HashSet<Int>()
        for (h in f.heads) {
            val dotStep = if (Math.floorMod(h.step, 2) == 0) h.step + 1 else h.step
            if (seen.add(dotStep)) glyph(DOT, right + 0.35f * sp, yOf(staff, dotStep))
        }
    }

    // ties: an arc on the side away from the stem, up to the next figure
    for (h in f.heads) {
        if (!h.tieNext) continue
        val x1 = headX(h) + headW + 0.1f * sp
        val x2 = minOf(nextX ?: width, width) - 0.15f * sp
        if (x2 - x1 < 0.6f * sp) continue
        val depth = 0.5f * sp
        val below = f.stemUp
        val y0 = yOf(staff, h.step) + if (below) 0.4f * sp else -0.4f * sp
        scope.drawArc(
            color = ink,
            startAngle = if (below) 0f else 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(x1, y0 - depth),
            size = Size(x2 - x1, 2f * depth),
            style = Stroke(width = 0.16f * sp)
        )
    }
}
