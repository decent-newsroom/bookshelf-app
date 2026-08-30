package eu.decentnewsroom.bookshelf.data.nostr

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSignerNostrRelayAuthenticatorTest {
    private val session = NostrSignerSession("a".repeat(64), "example.signer")
    private val draft = NostrAuthEventValidator.draft(
        pubkey = session.pubkey,
        relayUrl = "wss://relay.example",
        challenge = "challenge",
        createdAt = 1_700_000_000L,
    )

    @Test
    fun publishesPendingRequestAndUnsignedCanonicalPayload() = runBlocking {
        val authenticator = ExternalSignerNostrRelayAuthenticator(session)
        val signing = async { authenticator.signAuthEvent(draft) }
        while (authenticator.pending.value == null) yield()

        val pending = authenticator.pending.value
        assertNotNull(pending)
        assertEquals(
            """{"pubkey":"${session.pubkey}","created_at":1700000000,"kind":22242,"tags":[["relay","wss://relay.example"],["challenge","challenge"]],"content":""}""",
            pending!!.unsignedEventJson,
        )

        authenticator.completePending(pending.id, "{}")
        assertEquals("", signing.await().id)
        assertEquals(null, authenticator.pending.value)
    }

    @Test
    fun mismatchedResponseFailsPendingRequest() = runBlocking {
        supervisorScope {
            val authenticator = ExternalSignerNostrRelayAuthenticator(session)
            val signing = async { authenticator.signAuthEvent(draft) }
            while (authenticator.pending.value == null) yield()

            authenticator.completePending("wrong-id", "{}")
            val failure = runCatching { signing.await() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(authenticator.pending.value == null)
        }
    }

    @Test
    fun sessionChangeCancelsPendingRequest() = runBlocking {
        val authenticator = ExternalSignerNostrRelayAuthenticator(session)
        val signing = async { authenticator.signAuthEvent(draft) }
        while (authenticator.pending.value == null) yield()

        authenticator.updateSession(null)
        assertNotNull(runCatching { signing.await() }.exceptionOrNull())
        assertEquals(null, authenticator.pending.value)
    }

    @Test
    fun concurrentChallengesAreSerialized(): Unit = runBlocking {
        val authenticator = ExternalSignerNostrRelayAuthenticator(session)
        val first = async { authenticator.signAuthEvent(draft) }
        while (authenticator.pending.value == null) yield()
        val firstRequest = authenticator.pending.value!!

        val second = async { authenticator.signAuthEvent(draft.copy(challenge = "second")) }
        yield()
        assertEquals(firstRequest.id, authenticator.pending.value?.id)

        authenticator.completePending(firstRequest.id, "{}")
        first.await()
        while (authenticator.pending.value == null) yield()
        val secondRequest = authenticator.pending.value!!
        assertTrue(secondRequest.id != firstRequest.id)
        authenticator.completePending(secondRequest.id, "{}")
        second.await()
    }
}
