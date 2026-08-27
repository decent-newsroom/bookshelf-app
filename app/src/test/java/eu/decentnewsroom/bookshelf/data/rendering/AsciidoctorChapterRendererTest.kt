package eu.decentnewsroom.bookshelf.data.rendering

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciidoctorChapterRendererTest {
    @Test
    fun convertsAsciiDocToHtmlFragment() = runBlocking {
        val rendered = AsciidoctorChapterRenderer().render("*bold* and _italic_")

        assertTrue(rendered is RenderedChapter.Html)
        val html = (rendered as RenderedChapter.Html).html
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertFalse(html.contains("_italic_"))
        assertFalse(html.contains("<!DOCTYPE"))
        assertFalse(html.contains("<html"))
        assertFalse(html.contains("<title>"))
        assertFalse(html.contains("<style>"))
        assertFalse(html.contains(":root"))
    }
}
