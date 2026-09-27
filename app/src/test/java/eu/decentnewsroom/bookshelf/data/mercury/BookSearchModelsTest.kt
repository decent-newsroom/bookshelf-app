package eu.decentnewsroom.bookshelf.data.mercury

import org.junit.Assert.assertEquals
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelfCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BookSearchModelsTest {
    @Test
    fun validatesTextSectionAndStructuredFieldBoundaries() {
        assertNull(BookSearchQuery("x".repeat(160)).validationMessage())
        assertNotNull(BookSearchQuery("x".repeat(161)).validationMessage())
        assertNotNull(BookSearchQuery("abc", SearchScope.CHAPTER_CONTENT).validationMessage())
        assertNull(BookSearchQuery("abcd", SearchScope.CHAPTER_CONTENT).validationMessage())
        assertNull(BookSearchQuery("x".repeat(512), SearchScope.IDENTIFIER).validationMessage())
        assertNotNull(BookSearchQuery("x".repeat(513), SearchScope.IDENTIFIER).validationMessage())
        assertNull(BookSearchQuery(language = "x".repeat(32)).validationMessage())
        assertNotNull(BookSearchQuery(language = "x".repeat(33)).validationMessage())
        assertNull(BookSearchQuery.from("30040:${"a".repeat(64)}:${"x".repeat(200)}").validationMessage())
    }

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
    fun explicitPrefixesOverrideContentToggleScope() {
        val content = BookSearchQuery.from("CONTENT: hidden needle", SearchScope.METADATA)
        assertEquals(SearchScope.CHAPTER_CONTENT, content.scope)
        assertEquals("hidden needle", content.text)
        assertNull(content.validationMessage())

        val title = BookSearchQuery.from("title: Pride", SearchScope.ALL)
        assertEquals(SearchScope.TITLE, title.scope)
        assertEquals("Pride", title.text)
        assertNotNull(BookSearchQuery.from("content: abc", SearchScope.METADATA).validationMessage())
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

    @Test
    fun decodesPublicationNaddrIntoAnExactRelaySearchTarget() {
        val query = BookSearchQuery.from(CuratedShelfCatalog.shelves.first().publicationNaddrs.first())

        assertEquals("", query.text)
        assertEquals(
            "30040:3e1ad0f3a5d3c12245db7788546c43ade3d97c6e046c594f6017cd6cd4164690:pg27780-treasure-island",
            query.coordinate,
        )
        assertNotNull(query.naddrRelayHints)
    }

    @Test
    fun decodesPublicationNaddrWithNostrUriPrefixIntoAnExactRelaySearchTarget() {
        val naddr = CuratedShelfCatalog.shelves.first().publicationNaddrs.first()
        val query = BookSearchQuery.from("nostr:$naddr")

        assertEquals("", query.text)
        assertEquals(
            "30040:3e1ad0f3a5d3c12245db7788546c43ade3d97c6e046c594f6017cd6cd4164690:pg27780-treasure-island",
            query.coordinate,
        )
        assertNotNull(query.naddrRelayHints)
    }
}
