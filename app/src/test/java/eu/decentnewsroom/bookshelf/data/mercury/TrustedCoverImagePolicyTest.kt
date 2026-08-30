package eu.decentnewsroom.bookshelf.data.mercury

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedCoverImagePolicyTest {
    @Test
    fun allowsHttpsCoverImagesFromPublicationHosts() {
        listOf("https://gutenberg.org/cache/epub/1/cover.jpg", "https://covers.example/cover.jpg", "https://raw.githubusercontent.com/publisher/book/main/cover.jpg")
            .forEach { url -> assertEquals(url, TrustedCoverImagePolicy.sanitize(url)) }
    }

    @Test
    fun rejectsCleartextUserInfoAndMalformedUrls() {
        listOf("http://www.gutenberg.org/cover.jpg", "https://www.gutenberg.org@evil.example/cover.jpg", "javascript:alert(1)", "not a url")
            .forEach { url -> assertNull(url, TrustedCoverImagePolicy.sanitize(url)) }
    }

    @Test
    fun nullOrBlankCoverFallsBackToMonogram() {
        assertNull(TrustedCoverImagePolicy.sanitize(null))
        assertNull(TrustedCoverImagePolicy.sanitize("  "))
    }
}
