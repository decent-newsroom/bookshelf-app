package eu.decentnewsroom.bookshelf.data.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class NostrRelayAuthStateTest {
    @Test
    fun proactiveChallengeIsStoredWithoutAuthenticatingPublicOperation() {
        val state = NostrRelayAuthState()

        assertEquals(NostrRelayAuthState.Action.NONE, state.onChallenge("challenge"))
        assertEquals(NostrRelayAuthState.Action.FAIL, state.onAuthResult(true))
    }

    @Test
    fun publishAuthRequiredRetriesOnceAfterAuthSuccess() {
        val state = NostrRelayAuthState()

        assertEquals(NostrRelayAuthState.Action.NONE, state.onChallenge("challenge"))
        assertEquals(NostrRelayAuthState.Action.AUTHENTICATE, state.onAuthRequired())
        assertEquals(NostrRelayAuthState.Action.RETRY, state.onAuthResult(true))
        assertEquals(NostrRelayAuthState.Action.FAIL, state.onAuthRequired())
    }

    @Test
    fun protectedReadAuthRequiredRetriesReqOnceAfterAuthSuccess() {
        val state = NostrRelayAuthState()

        assertEquals(NostrRelayAuthState.Action.NONE, state.onChallenge("challenge"))
        assertEquals(NostrRelayAuthState.Action.AUTHENTICATE, state.onAuthRequired())
        assertEquals(NostrRelayAuthState.Action.RETRY, state.onAuthResult(true))
    }

    @Test
    fun missingAuthenticatorFailsSafely() {
        val state = NostrRelayAuthState(hasAuthenticator = false)

        assertEquals(NostrRelayAuthState.Action.NONE, state.onChallenge("challenge"))
        assertEquals(NostrRelayAuthState.Action.FAIL, state.onAuthRequired())
    }

    @Test
    fun rejectedAuthenticationFailsWithoutRetry() {
        val state = NostrRelayAuthState()

        assertEquals(NostrRelayAuthState.Action.NONE, state.onChallenge("challenge"))
        assertEquals(NostrRelayAuthState.Action.AUTHENTICATE, state.onAuthRequired())
        assertEquals(NostrRelayAuthState.Action.FAIL, state.onAuthResult(false))
    }

    @Test
    fun malformedChallengeFailsAndRepeatedAuthRequiredCannotLoop() {
        val malformed = NostrRelayAuthState()
        assertEquals(NostrRelayAuthState.Action.FAIL, malformed.onChallenge(""))

        val repeated = NostrRelayAuthState()
        assertEquals(NostrRelayAuthState.Action.NONE, repeated.onChallenge("challenge"))
        assertEquals(NostrRelayAuthState.Action.AUTHENTICATE, repeated.onAuthRequired())
        assertEquals(NostrRelayAuthState.Action.RETRY, repeated.onAuthResult(true))
        assertEquals(NostrRelayAuthState.Action.FAIL, repeated.onAuthRequired())
    }
}
