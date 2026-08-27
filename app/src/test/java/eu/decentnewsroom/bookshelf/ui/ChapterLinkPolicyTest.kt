package eu.decentnewsroom.bookshelf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterLinkPolicyTest {
    @Test
    fun acceptsAbsoluteHttpsAndExposesTheActualHost() {
        val link = ChapterLinkPolicy.parse("HTTPS://trusted.example.evil.test/books?id=1")
        assertEquals("trusted.example.evil.test", link?.host)
        assertEquals("HTTPS://trusted.example.evil.test/books?id=1", link?.url)
    }

    @Test
    fun rejectsDangerousCleartextAndCustomSchemes() {
        listOf("javascript:alert(1)", "content://contacts/people/1", "file:///sdcard/book.html", "intent://scan/#Intent;scheme=https;end", "nostrsigner://sign", "http://example.com/book")
            .forEach { url -> assertNull(url, ChapterLinkPolicy.parse(url)) }
    }

    @Test
    fun rejectsMalformedAndUserInfoDeceptiveUrls() {
        assertNull(ChapterLinkPolicy.parse("https://"))
        assertNull(ChapterLinkPolicy.parse("https://trusted.example@evil.example/book"))
        assertNull(ChapterLinkPolicy.parse("relative/chapter"))
    }
}
