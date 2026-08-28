package io.github.malexmuracoder.trainbeat

import android.content.Context

/**
 * The manual, which is a text file and not code.
 *
 * It used to be a list of Kotlin strings, and every correction meant minding
 * quotes, plus signs and escaped newlines in order to change a sentence. It now
 * lives in res/raw/manual.txt and can be edited in any editor by anybody, which is
 * the only way a manual stays true: one that is awkward to change ends up
 * describing last month's app, and that is worse than no manual at all.
 *
 * THE FORMAT is as small as it can be, because a format is one more thing to learn:
 *
 *   - a line beginning with "# " starts a section, and the rest of it is the title
 *   - anything before the first such line is the opening paragraph
 *   - a blank line separates paragraphs
 *   - lines inside a paragraph are joined with a space, so the file can be wrapped
 *     to whatever width is comfortable to write at and the phone still fills its own
 *
 * There is deliberately no way to force a line break inside a paragraph. A manual
 * read on a screen of unknown width has no business deciding where lines end.
 */
object Manual {

    class Section(val title: String, val body: String)

    class Page(val intro: String, val sections: List<Section>)

    /** So a missing or unreadable file shows an empty screen rather than crashing. */
    val EMPTY = Page("", emptyList())

    /**
     * Reads and parses the manual. Cheap enough to do when the screen opens: a few
     * thousand characters, once, from a resource already held in memory.
     */
    fun load(ctx: Context): Page =
        try {
            ctx.resources.openRawResource(R.raw.manual).use { input ->
                parse(input.readBytes().toString(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
            EMPTY
        }

    /**
     * The whole of the format, and free of Android so it can be proved on a desktop.
     *
     * Tolerant on purpose. A hand-edited file will have trailing spaces, runs of
     * blank lines, a heading with nothing under it yet; none of that should lose a
     * section or leave a gap where a paragraph was meant to be.
     */
    fun parse(text: String): Page {
        val intro = StringBuilder()
        val sections = ArrayList<Section>()
        var title: String? = null
        val body = StringBuilder()
        val line = StringBuilder()

        fun endParagraph() {
            if (line.isEmpty()) return
            val target = if (title == null) intro else body
            if (target.isNotEmpty()) target.append("\n\n")
            target.append(line)
            line.setLength(0)
        }

        fun endSection() {
            endParagraph()
            val t = title
            if (t != null && (t.isNotEmpty() || body.isNotEmpty())) {
                sections += Section(t, body.toString())
            }
            body.setLength(0)
        }

        for (raw in text.lineSequence()) {
            val s = raw.trim()
            when {
                s.startsWith("# ") || s == "#" -> {
                    endSection()
                    title = s.removePrefix("#").trim()
                }
                s.isEmpty() -> endParagraph()
                else -> {
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(s)
                }
            }
        }
        endSection()

        return Page(intro.toString(), sections)
    }
}
