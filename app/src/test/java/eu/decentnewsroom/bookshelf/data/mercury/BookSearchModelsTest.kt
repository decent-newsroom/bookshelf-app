package eu.decentnewsroom.bookshelf.data.mercury

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookSearchModelsTest {
    @Test
    fun parsesStructuredScopesWithoutChangingTheSearchText() {
        assertEquals(SearchScope.TITLE, BookSearchQuery.from("title: Pride").scope)
        assertEquals("Pride", BookSearchQuery.from("title: Pride").text)
        assertEquals(SearchScope.AUTHOR, BookSearchQuery.from("author: Austen").scope)
        assertEquals(SearchScope.SUBJECT, BookSearchQuery.from("topic: gothic").scope)
        assertEquals(SearchScope.IDENTIFIER, BookSearchQuery.from("identifier: pg1").scope)
        assertEquals(SearchScope.SLUG, BookSearchQuery.from("d:book-slug").scope)
    }

    @Test
    fun parsesLanguageAndExactReferencesAsTypedFields() {
        val language = BookSearchQuery.from("language: en")
        assertEquals(SearchScope.METADATA, language.scope)
        assertEquals("en", language.language)
        assertEquals("", language.text)

        val event = BookSearchQuery.from("a".repeat(64))
        assertEquals("", event.text)
        assertEquals("a".repeat(64), event.eventId)
        assertNull(event.coordinate)
    }
}
