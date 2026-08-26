package eu.decentnewsroom.bookshelf.data.rendering

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciidoctorChapterRendererTest {
    @Test
    fun convertsAsciiDocToHtml() = runBlocking {
        val rendered = AsciidoctorChapterRenderer().render("*bold*")

        assertTrue(rendered is RenderedChapter.Html)
        assertTrue((rendered as RenderedChapter.Html).html.contains("<strong>bold</strong>"))
    }
}