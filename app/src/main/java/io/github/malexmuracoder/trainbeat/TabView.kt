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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * A tablature on the screen: six lines (or as many strings as the instrument has),
 * the fret number on the line of its string, and under the lines the rhythm as stems.
 *
 * It follows the staff in everything that is not about the notes: the same size
 * setting, the same page-by-page scrolling (StaffPaging) with the page turning as the
 * last visible bar begins, the same red cursor. What is drawn comes from TabLayout,
 * which got it from the file; nothing is worked out here.
 *
 * The clef, the time signature, the stems, the flags and the dots are glyphs of
 * Bravura, like the staff. The FRET NUMBERS are plain digits of the system font:
 * Bravura's own digits are two staff spaces tall and made for time signatures, far
 * too large to sit on a line.
 *
 * The string lines are broken where a number is, so a number never has a line
 * through it and the picture needs no background colour to hide one behind it.
 */
@Composable
fun TabView(
    engraved: TabLayout.Engraved,
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
        // The page lives as long as this engraving: a new part or a new key starts again.
        val page = remember(engraved) { TabPageStart() }
        val current = if (bars.isEmpty()) 0 else engraved.barIndexAt(tick)
        if (bars.isNotEmpty()) {
            val from = page.value.coerceIn(0, bars.size - 1)
            page.value = StaffPaging.nextStart(from, current, visibleBars(engraved, from, widthSpaces), bars.size)
        }
        val first = page.value
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height((StaffScale.BASE_DP * scale).dp * TabGeometry.heightInSpaces(engraved.strings))
        ) {
            paintTab(engraved, first, current, tick, typeface, ink, accent, scale)
        }
    }
}

private class TabPageStart {
    var value: Int = 0
}

private fun visibleBars(e: TabLayout.Engraved, from: Int, widthSpaces: Float): Int {
    val b = e.bars[from]
    val available = widthSpaces - StaffPaging.headerSpaces(0, b.numerator, b.denominator)
    return StaffPaging.visibleCount(from, e.bars.size, available) { TabGeometry.barSpaces(e.bars[it].units) }
}

/** The measures of the picture, in staff spaces. */
internal object TabGeometry {
    /** Room above the top string. */
    const val MARGIN_TOP = 2.0f

    /**
     * Between two strings. Wider than the staff's space, because a fret number is
     * drawn ON the line and needs the room, and because the tab clef of the font,
     * six spaces tall, is made for strings about this far apart.
     */
    const val GAP = 1.25f

    /** From the bottom string to where the stems start, and their length. */
    const val STEM_FROM = 0.9f
    const val STEM_LEN = 3.5f
    const val MARGIN_BOTTOM = 0.8f

    const val BAR_PAD = 1.5f
    const val UNIT = 2.3f
    const val STEM = 0.12f
    const val LINE = 0.13f

    /** The size of a fret number, in staff spaces. */
    const val DIGIT = 1.45f

    fun barSpaces(units: Float): Float = BAR_PAD + units * UNIT

    fun heightInSpaces(strings: Int): Float =
        MARGIN_TOP + (strings - 1) * GAP + STEM_FROM + STEM_LEN + MARGIN_BOTTOM
}

// SMuFL code points, each checked against the font's own metadata.
private const val TAB_CLEF_6 = 0xE06D
private const val TAB_CLEF_4 = 0xE06E
private const val FLAG_8_DOWN = 0xE241
private const val FLAG_16_DOWN = 0xE243
private const val DOT = 0xE1E7
private const val DIGIT_0 = 0xE080

private fun DrawScope.paintTab(
    e: TabLayout.Engraved,
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
    val n = e.strings

    val music = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = 4f * sp                  // the em of a SMuFL font is four staff spaces
        color = ink.toArgb()
    }
    val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = Typeface.DEFAULT_BOLD
        textSize = TabGeometry.DIGIT * sp
        textAlign = Paint.Align.CENTER
        color = ink.toArgb()
    }
    fun glyph(code: Int, x: Float, y: Float) {
        drawIntoCanvas { it.nativeCanvas.drawText(code.toChar().toString(), x, y, music) }
    }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float) =
        drawLine(ink, Offset(x1, y1), Offset(x2, y2), strokeWidth = thickness * sp)

    val gap = TabGeometry.GAP * sp
    val topY = TabGeometry.MARGIN_TOP * sp
    fun yOf(string: Int): Float = topY + (string - 1) * gap
    val bottomY = yOf(n)
    val stemFrom = bottomY + TabGeometry.STEM_FROM * sp
    val stemTo = stemFrom + TabGeometry.STEM_LEN * sp

    // ---- the header: the tab clef and the time signature
    var x = StaffPaging.CLEF_X * sp
    glyph(if (n <= 4) TAB_CLEF_4 else TAB_CLEF_6, x + 0.4f * sp, (topY + bottomY) / 2f)
    x += StaffPaging.CLEF_ADVANCE * sp

    // the time signature is that of the first bar of the page
    val first = e.bars[firstBar]
    val digitsLong = maxOf(first.numerator.toString().length, first.denominator.toString().length)
    val digitsWidth = digitsLong * StaffPaging.DIGIT_WIDTH * sp
    fun number(value: Int, centreY: Float) {
        val s = value.toString()
        val start = x + (digitsWidth - s.length * StaffPaging.DIGIT_WIDTH * sp) / 2f
        for ((k, ch) in s.withIndex()) {
            glyph(DIGIT_0 + (ch - '0'), start + k * StaffPaging.DIGIT_WIDTH * sp, centreY)
        }
    }
    number(first.numerator, topY + (bottomY - topY) * 0.25f)
    number(first.denominator, topY + (bottomY - topY) * 0.75f)
    x += digitsWidth + StaffPaging.HEADER_TAIL * sp

    // ---- the bars, from the first of the page to the edge of the screen
    val headerEnd = x
    val pad = TabGeometry.BAR_PAD * sp
    val perUnit = TabGeometry.UNIT * sp
    val breaks = Array(n) { ArrayList<FloatArray>() }      // where a number interrupts a string
    var bx = headerEnd
    var bi = firstBar
    var cursorBarX = -1f
    val centre = 0.36f * digits.textSize                   // half the height of a digit
    while (bx < width && bi < e.bars.size) {
        val bar = e.bars[bi]
        val bw = pad + bar.units * perUnit
        if (bi == currentBar) cursorBarX = bx
        for (f in bar.figures) {
            val fx = bx + pad + f.at * perUnit
            for (c in f.cells) {
                val text = if (c.tied) "(${c.fret})" else c.fret.toString()
                val w = digits.measureText(text)
                breaks[c.string - 1].add(floatArrayOf(fx - w / 2f - 0.25f * sp, fx + w / 2f + 0.25f * sp))
                drawIntoCanvas { it.nativeCanvas.drawText(text, fx, yOf(c.string) + centre, digits) }
            }
            if (f.hasStem) {
                line(fx, stemFrom, fx, stemTo, TabGeometry.STEM)
                when (f.flags) {
                    1 -> glyph(FLAG_8_DOWN, fx - TabGeometry.STEM * sp / 2f, stemTo)
                    2 -> glyph(FLAG_16_DOWN, fx - TabGeometry.STEM * sp / 2f, stemTo)
                }
                if (f.dotted) glyph(DOT, fx + 0.5f * sp, stemFrom + 0.9f * sp)
            }
        }
        line(bx + bw, topY, bx + bw, bottomY, 0.16f)
        bx += bw
        bi++
    }

    // ---- the strings, in pieces, so that no line runs through a number
    for (s in 1..n) {
        val y = yOf(s)
        var from = 0f
        for (b in breaks[s - 1].sortedBy { it[0] }) {
            if (b[0] > from) line(from, y, b[0], y, TabGeometry.LINE)
            from = maxOf(from, b[1])
        }
        if (from < width) line(from, y, width, y, TabGeometry.LINE)
    }

    // ---- the cursor: where the music is, inside its bar
    if (cursorBarX >= 0f) {
        val cx = cursorBarX + pad + e.unitsInto(e.bars[currentBar], tick).toFloat() * perUnit
        if (cx in 0f..width) {
            drawLine(
                accent,
                Offset(cx, topY - 1.2f * sp),
                Offset(cx, stemTo + 0.3f * sp),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}
