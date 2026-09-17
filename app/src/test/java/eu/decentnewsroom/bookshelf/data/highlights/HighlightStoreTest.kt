package eu.decentnewsroom.bookshelf.data.highlights

import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HighlightStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun remembersTheSelectedOccurrenceOfRepeatedTextWhenContentIsUnchanged() {
        val text = "echo echo echo"
        val highlight = highlight(text, 5, 9)
        assertEquals(5 until 9, HighlightAnchors.resolve(highlight, text))
    }

    @Test fun relocatesUsingContextWhenAnEarlierParagraphIsInserted() {
        val text = "A passage to remember."
        val highlight = highlight(text, 2, 9)
        assertEquals(13 until 20, HighlightAnchors.resolve(highlight, "New intro.\n$text"))
    }

    @Test fun doesNotGuessBetweenTwoIdenticalPassagesAfterARevision() {
        val text = "A passage to remember."
        val highlight = highlight(text, 2, 9)
        assertNull(HighlightAnchors.resolve(highlight, "$text\n$text"))
    }

    @Test fun doesNotAttachToAChangedQuote() {
        val highlight = highlight("A passage to remember.", 2, 9)
        assertNull(HighlightAnchors.resolve(highlight, "A sentence to remember."))
    }

    @Test fun usesUtf16OffsetsAcrossSupplementaryCharacters() {
        val text = "Read \uD83D\uDCDA with care"
        val start = text.indexOf("with")
        assertEquals(start until start + 4, HighlightAnchors.resolve(highlight(text, start, start + 4), text))
    }

    @Test fun privateHighlightsAndPublishedAssociationsSurviveReopening() = runBlocking {
        val file = File(temporary.root, "highlights-v1.json")
        val value = highlight("A passage to remember.", 2, 9)
        HighlightStore(file).save(value)
        assertEquals(value, HighlightStore(file).all().single())

        HighlightStore(file).markPublished(value.id, "signed-highlight-id", "My comment")
        val restored = HighlightStore(file).all().single()
        assertEquals("signed-highlight-id", restored.publishedEventId)
        assertEquals("My comment", restored.comment)
        assertEquals(value.chapterEvent, restored.chapterEvent)
    }

    @Test fun corruptPrivateStoreIsNeverReplacedByAnEmptyCollection() = runBlocking {
        val file = temporary.newFile("highlights-v1.json").apply { writeText("damaged") }
        val failure = runCatching { HighlightStore(file).save(highlight("some quote", 0, 4)) }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals("damaged", file.readText())
    }

    private fun highlight(text: String, start: Int, end: Int) = ReaderHighlight(
        id = "local-highlight",
        bookCoordinate = "30040:publisher:book",
        chapterCoordinate = "30041:publisher:chapter",
        chapterTitle = "Chapter",
        chapterEvent = NostrEvent(id = "retained-chapter", content = text),
        quote = text.substring(start, end),
        context = HighlightAnchors.context(text, start, end),
        startOffset = start,
        endOffset = end,
        prefix = HighlightAnchors.prefix(text, start),
        suffix = HighlightAnchors.suffix(text, end),
        createdAtMillis = 1,
        displayedTextHash = HighlightAnchors.textHash(text),
    )
}
