package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
        searchPublications(MercuryPublicationSearch(q = query), limit)

    suspend fun searchPublications(search: MercuryPublicationSearch, limit: Int = 60): List<NostrEvent> {
        val requestBody = search.normalized(limit) ?: return emptyList()

        return requestEventList(
            Request
                .Builder()
                .url(url("/api/publications/search"))
                .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build(),
        )
    }

    suspend fun searchPublicationSections(query: String, limit: Int = 60): List<NostrEvent> {
        val normalized = query.trim().takeIf { it.searchTermLength() >= 4 } ?: return emptyList()

        return requestEventList(
            Request
                .Builder()
                .url(url("/api/publications/sections/search"))
                .post(
                    json
                        .encodeToString(MercurySectionSearchRequest(normalized, limit.coerceToMercuryLimit()))
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .header("Accept", "application/json")
                .build(),
        )
    }

    suspend fun getPublicationsReferencingChapters(chapterCoordinates: List<String>, limit: Int): List<NostrEvent> {
        val normalized = chapterCoordinates.normalizedCoordinates(BookKinds.PUBLICATION_CONTENT)
        if (normalized.isEmpty()) {
            return emptyList()
        }

        return normalized
            .chunked(FILTER_BATCH_SIZE)
            .flatMap { batch ->
                filterEvents(
                    MercuryFilterRequest(
                        kinds = listOf(BookKinds.PUBLICATION_INDEX),
                        limit = limit.coerceToMercuryLimit(),
                        aTags = batch,
                    ),
                )
            }
    }

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

                    decodeEvent(response.body.string())
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
                limit = limit.coerceToMercuryLimit(),
            ),
        )
    }

    suspend fun getChaptersByAuthors(authors: List<String>, limit: Int): List<NostrEvent> {
        val normalized = authors.normalizedHexKeys()
        if (normalized.isEmpty()) {
            return emptyList()
        }

        return filterEvents(
            MercuryFilterRequest(
                authors = normalized,
                kinds = listOf(BookKinds.PUBLICATION_CONTENT),
                limit = limit.coerceToMercuryLimit(),
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
                        limit = batch.size.coerceToMercuryLimit(),
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

                    decodeEventList(response.body.string())
                }
            } catch (exception: MercuryApiException) {
                throw exception
            } catch (exception: Throwable) {
                throw MercuryApiException("Mercury could not be reached.", exception)
            }
        }

    private fun decodeEvent(body: String): NostrEvent? {
        val element = json.parseToJsonElement(body)
        val eventElement =
            if (element is JsonObject && "data" in element) {
                element["data"]
            } else {
                element
            }

        return when (eventElement) {
            null, JsonNull -> null
            else -> json.decodeFromJsonElement(eventElement)
        }
    }

    private fun decodeEventList(body: String): List<NostrEvent> {
        val element = json.parseToJsonElement(body)
        val listElement =
            if (element is JsonObject && "data" in element) {
                element["data"]
            } else {
                element
            }

        return when (listElement) {
            is JsonArray -> json.decodeFromJsonElement(listElement)
            null, JsonNull -> emptyList()
            else -> throw MercuryApiException("Mercury returned an invalid event response.")
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

    private fun List<String>.normalizedCoordinates(kind: Int): List<String> =
        mapNotNull { candidate ->
            val trimmed = candidate.trim()
            val parts = trimmed.split(":", limit = 3)

            if (parts.size != 3 || parts[0].toIntOrNull() != kind || !HEX_64.matches(parts[1]) || parts[2].isBlank()) {
                null
            } else {
                "${parts[0].toInt()}:${parts[1].lowercase()}:${parts[2]}"
            }
        }.distinct()

    private fun MercuryPublicationSearch.normalized(limit: Int): MercuryPublicationSearch? {
        val normalized =
            copy(
                q = q.cleaned(),
                title = title.cleaned(),
                author = author.cleaned(),
                language = language.cleaned()?.lowercase(),
                subject = subject.cleaned(),
                d = d.cleaned(),
                identifier = identifier.cleaned(),
                limit = limit.coerceToMercuryLimit(),
            )

        return normalized.takeIf {
            listOf(
                it.q,
                it.title,
                it.author,
                it.language,
                it.subject,
                it.d,
                it.identifier,
            ).any { value -> value != null }
        }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun String.searchTermLength(): Int = trim('"', '\'').trim().length

    private fun Int.coerceToMercuryLimit(): Int = coerceIn(1, 100)

    @Serializable
    private data class MercuryFilterRequest(
        val ids: List<String> = emptyList(),
        val authors: List<String> = emptyList(),
        val kinds: List<Int>,
        val limit: Int,
        @SerialName("#a")
        val aTags: List<String> = emptyList(),
    )

    @Serializable
    private data class MercurySectionSearchRequest(
        val q: String,
        val limit: Int,
    )

    private companion object {
        const val FILTER_BATCH_SIZE = 100
        val HEX_64 = Regex("^[a-f0-9]{64}$", RegexOption.IGNORE_CASE)
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
data class MercuryPublicationSearch(
    val q: String? = null,
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val subject: String? = null,
    val d: String? = null,
    val identifier: String? = null,
    val limit: Int? = null,
)
