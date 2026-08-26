package eu.decentnewsroom.bookshelf.data.rendering

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
