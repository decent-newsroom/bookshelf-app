package eu.decentnewsroom.bookshelf.data.rendering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asciidoctor.Asciidoctor
import org.asciidoctor.Attributes
import org.asciidoctor.Options
import org.asciidoctor.SafeMode

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
    private val asciidoctor: Asciidoctor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Asciidoctor.Factory.create()
    }

    override suspend fun render(source: String): RenderedChapter =
        withContext(Dispatchers.Default) {
            RenderedChapter.Html(asciidoctor.convert(source, conversionOptions()))
        }

    private fun conversionOptions(): Options =
        Options
            .builder()
            .backend("html5")
            .safe(SafeMode.SECURE)
            .standalone(false)
            .toFile(false)
            .attributes(
                Attributes
                    .builder()
                    .showTitle(true)
                    .build(),
            )
            .build()
}
