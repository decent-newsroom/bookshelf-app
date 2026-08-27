package eu.decentnewsroom.bookshelf.data.bookshelf

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.BookSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalBookshelfStoreTest {
    @Test
    fun signedOutSaveAndRemovePersistAcrossStoreRecreation() = runBlocking {
        val root = Files.createTempDirectory("local-bookshelf").toFile()
        try {
            val book = bookSummary("local-book", "1")
            val firstStore = LocalBookshelfStore(root)

            val saved = firstStore.toggle(book)

            assertTrue(saved.isSaved)
            assertEquals(listOf(book), LocalBookshelfStore(root).savedBooks.value)
            assertFalse(File(root, "bookshelf/local-v1.json.tmp").exists())

            val removed = LocalBookshelfStore(root).toggle(book)

            assertFalse(removed.isSaved)
            assertTrue(LocalBookshelfStore(root).savedBooks.value.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun remoteMergeKeepsExistingDeviceBooks() = runBlocking {
        val root = Files.createTempDirectory("local-bookshelf-merge").toFile()
        try {
            val localBook = bookSummary("local-book", "1")
            val remoteBook = bookSummary("remote-book", "2")
            val store = LocalBookshelfStore(root)
            store.toggle(localBook)
            val remoteTags =
                BookshelfDirectoryRules.toggleBook(
                    BookshelfDirectoryRules.emptyTags(),
                    remoteBook,
                )

            store.merge(remoteTags, listOf(remoteBook))

            assertEquals(
                setOf(localBook.coordinate, remoteBook.coordinate),
                LocalBookshelfStore(root).savedBooks.value.map(BookSummary::coordinate).toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptStorageStartsWithAnEmptyUsableBookshelf() = runBlocking {
        val root = Files.createTempDirectory("local-bookshelf-corrupt").toFile()
        try {
            val file = File(root, "bookshelf/local-v1.json")
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("not json")

            val store = LocalBookshelfStore(root)

            assertTrue(store.savedBooks.value.isEmpty())
            assertEquals(BookshelfDirectoryRules.emptyTags(), store.directoryTags.value)
            assertTrue(store.toggle(bookSummary("recovered", "3")).isSaved)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun bookSummary(identifier: String, keyDigit: String): BookSummary {
        val pubkey = keyDigit.repeat(64)
        val eventId = identifier.first().code.toString(16).padStart(2, '0').repeat(32)

        return BookSummary(
            id = eventId,
            coordinate = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier",
            pubkey = pubkey,
            identifier = identifier,
            title = identifier,
            summary = null,
            authors = listOf("An Author"),
            coverImageUrl = null,
            sourceUrl = null,
            language = null,
            releaseDate = null,
            version = null,
            type = "book",
            topics = emptyList(),
            relay = "wss://relay.example",
            createdAt = 1,
            chapterCount = 1,
            chapterRefs = emptyList(),
        )
    }
}
