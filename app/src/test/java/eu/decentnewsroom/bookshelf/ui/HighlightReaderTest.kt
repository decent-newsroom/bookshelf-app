package eu.decentnewsroom.bookshelf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightReaderTest {
    @Test
    fun `returns UTF-16 offsets for a uniquely selected passage`() {
        assertEquals(6 until 11, uniquelySelectedRange("Hello world", "world"))
    }

    @Test
    fun `rejects ambiguous selected text`() {
        assertNull(uniquelySelectedRange("echo then echo", "echo"))
    }

    @Test
    fun `rejects text outside the chapter`() {
        assertNull(uniquelySelectedRange("Only this chapter", "another chapter"))
    }
}
