package eu.decentnewsroom.bookshelf.data.mercury

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.min
import kotlin.random.Random

data class MercurySearchRetryConfig(
    val maxAttempts: Int = 2,
    val baseDelayMillis: Long = 250,
    val maxBackoffMillis: Long = 2_000,
    val maxRetryAfterMillis: Long = 10_000,
    val cooldownThreshold: Int = 3,
    val cooldownMillis: Long = 5_000,
    val maxConcurrentRequests: Int = 2,
) {
    init {
        require(maxAttempts >= 1)
        require(baseDelayMillis >= 0)
        require(maxBackoffMillis >= baseDelayMillis)
        require(maxRetryAfterMillis >= 0)
        require(cooldownThreshold >= 1)
        require(cooldownMillis >= 0)
        require(maxConcurrentRequests >= 1)
    }
}

/**
 * Bounds concurrency and retries for user-initiated Mercury search traffic.
 *
 * This controller is owned by the process-wide repository in [eu.decentnewsroom.bookshelf.AppGraph],
 * so its semaphore and cooldown cover all active searches in the app.
 */
class MercurySearchResilience(
    private val config: MercurySearchRetryConfig = MercurySearchRetryConfig(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitterMillis: (Long) -> Long = { upperBound ->
        if (upperBound <= 0) 0 else Random.nextLong(upperBound + 1)
    },
) {
    private val requestGate = Semaphore(config.maxConcurrentRequests)
    private val stateMutex = Mutex()
    private var consecutiveServiceUnavailable = 0
    private var cooldownUntilMillis = 0L

    suspend fun <T> execute(block: suspend () -> T): T =
        requestGate.withPermit {
            rejectDuringCooldown()

            var attempt = 1
            while (true) {
                try {
                    val result = block()
                    recordSuccess()
                    return@withPermit result
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: MercuryApiException) {
                    if (exception.statusCode != HTTP_SERVICE_UNAVAILABLE) {
                        throw exception
                    }

                    val cooldownStarted = recordServiceUnavailable()
                    val retryAfter = exception.retryAfterMillis?.coerceAtLeast(0)
                    val retryAfterIsTooLong = retryAfter != null && retryAfter > config.maxRetryAfterMillis
                    if (attempt >= config.maxAttempts || cooldownStarted || retryAfterIsTooLong) {
                        throw busyException(retryAfter)
                    }

                    val exponent = (attempt - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
                    val exponentialDelay = min(
                        config.maxBackoffMillis,
                        config.baseDelayMillis * (1L shl exponent),
                    )
                    val jitterBound = exponentialDelay / 2
                    val localDelay = exponentialDelay + jitterMillis(jitterBound).coerceIn(0, jitterBound)
                    val boundedDelay = maxOf(localDelay, retryAfter ?: 0).coerceAtMost(config.maxRetryAfterMillis)
                    sleep(boundedDelay)
                    attempt += 1
                }
            }

            error("Unreachable")
        }

    private suspend fun rejectDuringCooldown() {
        val remaining = stateMutex.withLock {
            (cooldownUntilMillis - nowMillis()).coerceAtLeast(0)
        }
        if (remaining > 0) {
            throw busyException(remaining)
        }
    }

    private suspend fun recordSuccess() {
        stateMutex.withLock {
            consecutiveServiceUnavailable = 0
        }
    }

    private suspend fun recordServiceUnavailable(): Boolean =
        stateMutex.withLock {
            consecutiveServiceUnavailable += 1
            if (consecutiveServiceUnavailable >= config.cooldownThreshold) {
                cooldownUntilMillis = maxOf(cooldownUntilMillis, nowMillis() + config.cooldownMillis)
                true
            } else {
                false
            }
        }

    private fun busyException(retryAfterMillis: Long?): MercuryApiException =
        MercuryApiException(
            message = "Mercury is temporarily busy.",
            statusCode = HTTP_SERVICE_UNAVAILABLE,
            retryAfterMillis = retryAfterMillis?.coerceAtMost(config.maxRetryAfterMillis),
        )

    private companion object {
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val MAX_BACKOFF_EXPONENT = 20
    }
}
