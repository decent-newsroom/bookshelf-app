package eu.decentnewsroom.bookshelf.data.mercury

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class MercurySearchResilienceTest {
    @Test
    fun retriesOne503ThenReturnsTheSuccessfulResult() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val resilience = MercurySearchResilience(
            config = MercurySearchRetryConfig(
                maxAttempts = 2,
                baseDelayMillis = 100,
                maxBackoffMillis = 100,
                cooldownThreshold = 10,
            ),
            sleep = { delays += it },
            jitterMillis = { 0 },
        )

        val result = resilience.execute {
            attempts += 1
            if (attempts == 1) {
                throw MercuryApiException("busy", statusCode = 503)
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        assertEquals(listOf(100L), delays)
    }

    @Test
    fun retryAfterRaisesTheBoundedDelay() = runBlocking {
        val delays = mutableListOf<Long>()
        var attempts = 0
        val resilience = MercurySearchResilience(
            config = MercurySearchRetryConfig(
                maxAttempts = 2,
                baseDelayMillis = 100,
                maxBackoffMillis = 100,
                maxRetryAfterMillis = 5_000,
                cooldownThreshold = 10,
            ),
            sleep = { delays += it },
            jitterMillis = { 0 },
        )

        resilience.execute {
            attempts += 1
            if (attempts == 1) {
                throw MercuryApiException("busy", statusCode = 503, retryAfterMillis = 3_000)
            }
        }

        assertEquals(listOf(3_000L), delays)
    }

    @Test
    fun doesNotRetryOtherHttpFailures() = runBlocking {
        val expected = MercuryApiException("bad request", statusCode = 400)
        var attempts = 0
        val resilience = MercurySearchResilience(
            config = MercurySearchRetryConfig(cooldownThreshold = 10),
            sleep = { fail("Unexpected retry delay") },
        )

        try {
            resilience.execute {
                attempts += 1
                throw expected
            }
            fail("Expected MercuryApiException")
        } catch (actual: MercuryApiException) {
            assertSame(expected, actual)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherAttempt() = runBlocking {
        val backoffStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val resilience = MercurySearchResilience(
            config = MercurySearchRetryConfig(cooldownThreshold = 10),
            sleep = {
                backoffStarted.complete(Unit)
                awaitCancellation()
            },
        )

        val job = launch {
            resilience.execute {
                attempts += 1
                throw MercuryApiException("busy", statusCode = 503)
            }
        }

        backoffStarted.await()
        job.cancelAndJoin()

        assertEquals(1, attempts)
    }

    @Test
    fun opensCooldownAfterRepeated503Responses() = runBlocking {
        var now = 1_000L
        var blockCalls = 0
        val resilience = MercurySearchResilience(
            config = MercurySearchRetryConfig(
                maxAttempts = 1,
                cooldownThreshold = 2,
                cooldownMillis = 5_000,
            ),
            nowMillis = { now },
        )

        repeat(2) {
            try {
                resilience.execute {
                    blockCalls += 1
                    throw MercuryApiException("busy", statusCode = 503)
                }
                fail("Expected MercuryApiException")
            } catch (_: MercuryApiException) {
                // Expected.
            }
        }

        try {
            resilience.execute {
                blockCalls += 1
                "unexpected"
            }
            fail("Expected cooldown rejection")
        } catch (exception: MercuryApiException) {
            assertEquals(503, exception.statusCode)
            assertEquals(5_000L, exception.retryAfterMillis)
        }
        assertEquals(2, blockCalls)

        now += 5_000
        assertEquals("available", resilience.execute { "available" })
    }
}
