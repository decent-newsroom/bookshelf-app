package eu.decentnewsroom.bookshelf.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundSignerQueueTest {
    @Test
    fun simultaneousRequestsLaunchOneAtATimeInDeclaredPriority() {
        val auth = ForegroundSignerRequest(ForegroundSignerType.AUTH, "auth")
        val reading = ForegroundSignerRequest(ForegroundSignerType.READING, "reading")
        val requests = listOf(auth, reading)
        assertEquals(auth, nextForegroundSignerRequest(requests, emptySet(), null))
        assertNull(nextForegroundSignerRequest(requests, setOf(auth.key), auth.key))
        assertEquals(reading, nextForegroundSignerRequest(requests, setOf(auth.key), null))
    }

    @Test
    fun returnedRequestCannotLaunchAgainWhileAsyncStateCompletionIsPending() {
        val request = ForegroundSignerRequest(ForegroundSignerType.READING, "original")
        assertNull(nextForegroundSignerRequest(listOf(request), setOf(request.key), null))
        val replacement = request.copy(id = "replacement")
        assertEquals(replacement, nextForegroundSignerRequest(listOf(replacement), setOf(request.key), null))
    }

    @Test
    fun loginSharesTheSameExclusiveActivitySlot() {
        val login = ForegroundSignerRequest(ForegroundSignerType.LOGIN, "login")
        val review = ForegroundSignerRequest(ForegroundSignerType.RATING, "review")
        assertNull(nextForegroundSignerRequest(listOf(review), emptySet(), login.key))
    }

    @Test
    fun idsFromDifferentRequestTypesDoNotCollide() {
        val directory = ForegroundSignerRequest(ForegroundSignerType.DIRECTORY, "same-id")
        val reading = ForegroundSignerRequest(ForegroundSignerType.READING, "same-id")
        assertEquals(reading, nextForegroundSignerRequest(listOf(directory, reading), setOf(directory.key), null))
    }
}
