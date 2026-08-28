package io.github.malexmuracoder.trainbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual is now a file a person edits by hand, so the parser has to survive
 * what a person does to a file by hand. Each of these is a thing that will happen.
 */
class ManualTest {

    @Test
    fun aTitleAndItsTextComeOutAsASection() {
        val page = Manual.parse(
            """
            The opening words.

            # First

            Something about the first thing.

            # Second

            Something about the second.
            """.trimIndent()
        )
        assertEquals("The opening words.", page.intro)
        assertEquals(2, page.sections.size)
        assertEquals("First", page.sections[0].title)
        assertEquals("Something about the first thing.", page.sections[0].body)
        assertEquals("Second", page.sections[1].title)
    }

    @Test
    fun linesInsideAParagraphAreJoinedWithASpace() {
        // the whole point of the format: the file can be wrapped at any width and
        // the phone still fills its own
        val page = Manual.parse("# T\n\none two\nthree four\nfive.\n")
        assertEquals("one two three four five.", page.sections[0].body)
    }

    @Test
    fun aBlankLineSeparatesParagraphs() {
        val page = Manual.parse("# T\n\nfirst.\n\nsecond.\n")
        assertEquals("first.\n\nsecond.", page.sections[0].body)
    }

    @Test
    fun runsOfBlankLinesDoNotBecomeRunsOfGaps() {
        val page = Manual.parse("# T\n\n\n\nfirst.\n\n\n\n\nsecond.\n\n\n")
        assertEquals("first.\n\nsecond.", page.sections[0].body)
    }

    @Test
    fun trailingSpacesAreNotPartOfTheText() {
        val page = Manual.parse("intro   \n\n#  Title  \n\n  body here   \n")
        assertEquals("intro", page.intro)
        assertEquals("Title", page.sections[0].title)
        assertEquals("body here", page.sections[0].body)
    }

    @Test
    fun aHeadingWithNothingUnderItYetIsStillASection() {
        // half-written, which is what a manual looks like while it is being written
        val page = Manual.parse("# Done\n\ntext.\n\n# Not yet\n")
        assertEquals(2, page.sections.size)
        assertEquals("Not yet", page.sections[1].title)
        assertEquals("", page.sections[1].body)
    }

    @Test
    fun textBeforeTheFirstHeadingIsTheIntroAndNotASection() {
        val page = Manual.parse("only this.\n")
        assertEquals("only this.", page.intro)
        assertTrue(page.sections.isEmpty())
    }

    @Test
    fun aHashInTheMiddleOfALineIsJustAHash() {
        val page = Manual.parse("# T\n\nbar 4 # 3 is not a heading.\n")
        assertEquals(1, page.sections.size)
        assertEquals("bar 4 # 3 is not a heading.", page.sections[0].body)
    }

    @Test
    fun anEmptyFileIsAnEmptyPageAndNotACrash() {
        val page = Manual.parse("")
        assertEquals("", page.intro)
        assertTrue(page.sections.isEmpty())
    }

    @Test
    fun windowsLineEndingsDoNotLeaveCarriageReturnsInTheText() {
        val page = Manual.parse("# T\r\n\r\none\r\ntwo\r\n")
        assertEquals("T", page.sections[0].title)
        assertEquals("one two", page.sections[0].body)
    }
}
