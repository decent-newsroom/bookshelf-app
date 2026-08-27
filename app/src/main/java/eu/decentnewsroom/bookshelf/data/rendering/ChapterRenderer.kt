package eu.decentnewsroom.bookshelf.data.rendering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.markup.poet.asciidoc.parser.DefaultAsciidocParser
import org.markup.poet.asciidoc.render.DefaultBlockRenderer
import org.markup.poet.asciidoc.render.DefaultHtmlBuilder
import org.markup.poet.asciidoc.render.DefaultHtmlEscaper
import org.markup.poet.asciidoc.render.DefaultHtmlRenderer
import org.markup.poet.asciidoc.render.DefaultInlineRenderer

sealed interface RenderedChapter {
    data class PlainText(val text: String) : RenderedChapter
    data class Html(val html: String) : RenderedChapter
}

interface ChapterRenderer {
    suspend fun render(source: String): RenderedChapter
}

class PlainTextChapterRenderer : ChapterRenderer {
    override suspend fun render(source: String): RenderedChapter = RenderedChapter.PlainText(source)
}

class AsciidoctorChapterRenderer : ChapterRenderer {
    override suspend fun render(source: String): RenderedChapter =
        withContext(Dispatchers.Default) {
            val document = DefaultAsciidocParser().parse(source).document
            val builder = DefaultHtmlBuilder(DefaultHtmlEscaper())
            val inlineRenderer = DefaultInlineRenderer(builder)
            val renderer = DefaultHtmlRenderer(DefaultBlockRenderer(builder, inlineRenderer), inlineRenderer)

            RenderedChapter.Html(renderer.render(document).getOrThrow())
        }
}
