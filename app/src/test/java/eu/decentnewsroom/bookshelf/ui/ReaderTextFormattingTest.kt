package eu.decentnewsroom.bookshelf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextFormattingTest {
    @Test
    fun addsAnExtraBreakAfterEachHtmlParagraph() {
        val html = "<p>First paragraph.</p><p>Second paragraph.</p>"

        assertEquals(
            "<p>First paragraph.</p><br><p>Second paragraph.</p><br>",
            html.withReaderParagraphSpacing(),
        )
    }

    @Test
    fun recognizesParagraphClosingTagsRegardlessOfCaseOrWhitespace() {
        val html = "<P>First paragraph.</P >"

        assertEquals(
            "<P>First paragraph.</P ><br>",
            html.withReaderParagraphSpacing(),
        )
    }
}
