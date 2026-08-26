package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MercuryApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MercuryApiClient(
    private val httpClient: OkHttpClient,
    mercuryApiBaseUrl: String,
) {
    private val baseUrl = mercuryApiBaseUrl.trimEnd('/')
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    suspend fun searchPublications(query: String, limit: Int = 60): List<NostrEvent> =
        requestEventList(
            Request
                .Builder()
                .url(url("/api/publications/search"))
                .post(
                    json
                        .encodeToString(MercurySearchRequest(query, limit.coerceIn(1, 100)))
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .header("Accept", "application/json")
                .build(),
        )

    suspend fun getEvent(eventId: String): NostrEvent? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url("/api/events/$eventId"))
                    .header("Accept", "application/json")
                    .get()
                    .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@withContext null
                    }
                    if (!response.isSuccessful) {
                        throw MercuryApiException("Mercury returned HTTP ${response.code}.")
                    }

                    val body = response.body.string()
                    json.decodeFromString<MercuryEventEnvelope>(body).data
                        ?: throw MercuryApiException("Mercury returned an invalid event response.")
                }
            } catch (exception: MercuryApiException) {
                throw exception
            } catch (exception: Throwable) {
                throw MercuryApiException("Mercury could not be reached.", exception)
            }
        }

    suspend fun getEventsByIds(eventIds: List<String>): List<NostrEvent> =
        getEventsByIdsAndKind(eventIds, BookKinds.PUBLICATION_CONTENT)

    suspend fun getPublicationEventsByIds(eventIds: List<String>): List<NostrEvent> =
        getEventsByIdsAndKind(eventIds, BookKinds.PUBLICATION_INDEX)

    suspend fun getPublicationsByAuthors(authors: List<String>, limit: Int): List<NostrEvent> {
        val normalized = authors.normalizedHexKeys()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        return filterEvents(
            MercuryFilterRequest(
                authors = normalized,
                kinds = listOf(BookKinds.PUBLICATION_INDEX),
                limit = limit.coerceIn(1, 500),
            ),
        )
    }

    suspend fun getChaptersByAuthors(authors: List<String>, limit: Int): List<NostrEvent> {
        val normalized = authors.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.distinct()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        return filterEvents(
            MercuryFilterRequest(
                authors = normalized,
                kinds = listOf(BookKinds.PUBLICATION_CONTENT),
                limit = limit.coerceIn(1, 500),
            ),
        )
    }

    fun getRelayHint(): String? {
        val scheme =
            when {
                baseUrl.startsWith("https://") -> "wss://"
                baseUrl.startsWith("http://") -> "ws://"
                baseUrl.startsWith("wss://") -> "wss://"
                baseUrl.startsWith("ws://") -> "ws://"
                else -> return null
            }

        val withoutScheme = baseUrl.substringAfter("://")
        val hostAndPort = withoutScheme.substringBefore("/")

        return scheme + hostAndPort
    }

    private suspend fun getEventsByIdsAndKind(eventIds: List<String>, kind: Int): List<NostrEvent> {
        val normalized = eventIds.normalizedHexKeys()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        return normalized
            .chunked(FILTER_BATCH_SIZE)
            .flatMap { batch ->
                filterEvents(
                    MercuryFilterRequest(
                        ids = batch,
                        kinds = listOf(kind),
                        limit = batch.size,
                    ),
                )
            }
    }

    private suspend fun filterEvents(filter: MercuryFilterRequest): List<NostrEvent> =
        requestEventList(
            Request
                .Builder()
                .url(url("/api/events/filter"))
                .post(json.encodeToString(filter).toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build(),
        )

    private suspend fun requestEventList(request: Request): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw MercuryApiException("Mercury returned HTTP ${response.code}.")
                    }

                    val body = response.body.string()
                    json.decodeFromString<MercuryEventListEnvelope>(body).data
                }
            } catch (exception: MercuryApiException) {
                throw exception
            } catch (exception: Throwable) {
                throw MercuryApiException("Mercury could not be reached.", exception)
            }
        }

    private fun url(path: String): String = baseUrl + path

    private fun List<String>.normalizedHexKeys(): List<String> =
        mapNotNull { candidate ->
            candidate
                .trim()
                .lowercase()
                .takeIf { HEX_64.matches(it) }
        }.distinct()

    @Serializable
    private data class MercurySearchRequest(
        val q: String,
        val limit: Int,
    )

    @Serializable
    private data class MercuryFilterRequest(
        val ids: List<String> = emptyList(),
        val authors: List<String> = emptyList(),
        val kinds: List<Int>,
        val limit: Int,
    )

    @Serializable
    private data class MercuryEventEnvelope(
        val data: NostrEvent? = null,
    )

    @Serializable
    private data class MercuryEventListEnvelope(
        val data: List<NostrEvent> = emptyList(),
    )

    private companion object {
        const val FILTER_BATCH_SIZE = 100
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}




