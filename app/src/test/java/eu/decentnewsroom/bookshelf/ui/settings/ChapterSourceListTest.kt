package eu.decentnewsroom.bookshelf.ui.settings

import eu.decentnewsroom.bookshelf.data.mercury.ChapterRelayUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChapterSourceListTest {
    @Test fun addAndRemovePreserveOrderAndKeepBuiltInsRemovable() {
        val initial = ChapterRelayUrls.DEFAULTS
        val added = ChapterSourceList.add(initial, "wss://custom.example/")
        assertEquals(initial + "wss://custom.example", added)
        assertEquals(listOf(initial[1], initial[2], "wss://custom.example"), ChapterSourceList.remove(added, initial[0]))
    }

    @Test fun rejectsInvalidDuplicateAndLastRemoval() {
        val initial = ChapterRelayUrls.DEFAULTS
        assertThrows(IllegalArgumentException::class.java) { ChapterSourceList.add(initial, "not a url") }
        assertThrows(IllegalArgumentException::class.java) { ChapterSourceList.add(initial, initial[0]) }
        assertThrows(IllegalArgumentException::class.java) { ChapterSourceList.remove(listOf(initial[0]), initial[0]) }
    }

    @Test fun restoreDefaultsAfterEdits() {
        val edited = ChapterSourceList.remove(ChapterRelayUrls.DEFAULTS, ChapterRelayUrls.DEFAULTS[0])
        assertEquals(ChapterRelayUrls.DEFAULTS, ChapterSourceList.defaults())
        assertEquals(ChapterRelayUrls.DEFAULTS.size - 1, edited.size)
    }
}
