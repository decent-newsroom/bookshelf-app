package eu.decentnewsroom.bookshelf.data.mercury

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedCoverImagePolicyTest {
    @Test
    fun allowsOnlyExplicitHttpsGutenbergHosts() {
        listOf("https://gutenberg.org/cache/epub/1/cover.jpg", "https://www.gutenberg.org/cache/epub/1/cover.jpg", "https://images.gutenberg.org/cover.jpg", "https://aleph.gutenberg.org/cover.jpg")
            .forEach { url -> assertEquals(url, TrustedCoverImagePolicy.sanitize(url)) }
    }

    @Test
    fun rejectsCleartextArbitraryAndDeceptiveHosts() {
        listOf("http://www.gutenberg.org/cover.jpg", "https://covers.example/cover.jpg", "https://www.gutenberg.org.evil.example/cover.jpg", "https://www.gutenberg.org@evil.example/cover.jpg", "javascript:alert(1)", "not a url")
            .forEach { url -> assertNull(url, TrustedCoverImagePolicy.sanitize(url)) }
    }

    @Test
    fun nullOrBlankCoverFallsBackToMonogram() {
        assertNull(TrustedCoverImagePolicy.sanitize(null))
        assertNull(TrustedCoverImagePolicy.sanitize("  "))
    }
}
