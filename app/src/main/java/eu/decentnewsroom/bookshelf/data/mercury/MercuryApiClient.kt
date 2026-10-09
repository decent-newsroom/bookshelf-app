package eu.decentnewsroom.bookshelf.data.mercury

import eu.decentnewsroom.bookshelf.domain.BookKinds
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventContext
import eu.decentnewsroom.bookshelf.data.nostr.NostrEventVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MercuryApiException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val retryAfterMillis: Long? = null,
) : RuntimeException(message, cause)

data class PublicationCoordinate(val pubkey: String, val identifier: String) {
    val coordinate: String get() = "${BookKinds.PUBLICATION_INDEX}:$pubkey:$identifier"
}


class MercuryApiClient(
    private val httpClient: OkHttpClient,
    mercuryApiBaseUrl: String,
    fallbackApiBaseUrls: List<String> = emptyList(),
    private val relayHint: String? = null,
) {
    private val baseUrl = mercuryApiBaseUrl.trimEnd('/')
    private val apiBaseUrls = (listOf(baseUrl) + fallbackApiBaseUrls.map { it.trimEnd('/') }).distinct()
    private val endpointHttpClient =
        httpClient.newBuilder().addInterceptor { chain ->
            val original = chain.request()
            var lastFailure: Throwable? = null
            for ((index, endpoint) in apiBaseUrls.withIndex()) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                val request = if (index == 0) original else original.withApiBaseUrl(endpoint)
                try {
                    val response = chain.proceed(request)
                    if (response.code !in 500..599 || index == apiBaseUrls.lastIndex) {
                        return@addInterceptor response
                    }
                    response.close()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    if (chain.call().isCanceled()) throw exception
                    lastFailure = exception
                    if (index == apiBaseUrls.lastIndex) throw exception
                }
            }
            throw checkNotNull(lastFailure)
        }.build()
    // Section search scans chapter text; retain the endpoint chain and shared pool,
    // but allow substantially more server processing time than ordinary lookups.
    private val sectionSearchHttpClient = endpointHttpClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
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
            expectedKind = BookKinds.PUBLICATION_INDEX,
        )
    }

    suspend fun searchPublicationSections(query: String, limit: Int = 60): List<NostrEvent> {
        val normalized = query.trim()
        require(normalized.length in BookSearchLimits.SECTION_MIN..BookSearchLimits.TEXT) {
            "Inside books searches must contain 4 to 160 characters."
        }
        require(limit in 1..100) { "Search limit must be between 1 and 100." }

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
            expectedKind = BookKinds.PUBLICATION_CONTENT,
            sectionSearch = true,
        )
    }

    /** Similarity discovery is supported only by the configured primary Books API. */
    suspend fun recommendPublications(
        seedEventId: String,
        excludeIds: List<String> = emptyList(),
        limit: Int = 10,
    ): List<NostrEvent> {
        require(excludeIds.size <= 100) { "At most 100 exclusions are supported." }
        require(limit in 1..50) { "Recommendation limit must be between 1 and 50." }
        fun normalizeId(value: String): String = value.trim().lowercase().also {
            require(HEX_64.matches(it)) { "A valid 64-character hexadecimal event ID is required." }
        }
        val body = MercuryRecommendationRequest(
            seedEventId = normalizeId(seedEventId),
            excludeIds = excludeIds.map(::normalizeId).distinct(),
            limit = limit,
        )
        return requestEventList(
            Request.Builder()
                .url(url("/api/publications/recommendations"))
                .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build(),
            expectedKind = BookKinds.PUBLICATION_INDEX,
            primaryOnly = true,
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

    suspend fun getEvent(eventId: String, expectedKind: Int? = null): NostrEvent? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url("/api/events/$eventId"))
                    .header("Accept", "application/json")
                    .get()
                    .build()

            try {
                executeRequest(request) { response ->
                    if (response.code == 404) {
                        return@executeRequest null
                    }
                    if (!response.isSuccessful) {
                        throw response.toMercuryApiException()
                    }

                    decodeEvent(response.body.readLimitedUtf8(), eventId, expectedKind)
                }
            } catch (exception: CancellationException) {
                throw exception
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
            expectedAuthors = normalized,
        )
    }

    suspend fun getPublicationsByCoordinates(coordinates: List<PublicationCoordinate>): List<NostrEvent> {
        val normalized = coordinates.mapNotNull { coordinate ->
            val pubkey = coordinate.pubkey.trim().lowercase()
            val identifier = coordinate.identifier.trim()
            if (!HEX_64.matches(pubkey) || identifier.isBlank()) null
            else coordinate.copy(pubkey = pubkey, identifier = identifier)
        }.distinctBy { it.coordinate }
        if (normalized.isEmpty()) return emptyList()

        return normalized.groupBy(PublicationCoordinate::pubkey).flatMap { (pubkey, items) ->
            items.map { it.identifier }.distinct().chunked(FILTER_BATCH_SIZE).flatMap { identifiers ->
                filterEvents(
                    MercuryFilterRequest(
                        authors = listOf(pubkey),
                        kinds = listOf(BookKinds.PUBLICATION_INDEX),
                        limit = identifiers.size.coerceToMercuryLimit(),
                        dTags = identifiers,
                    ),
                    expectedAuthors = listOf(pubkey),
                    expectedDTags = identifiers,
                )
            }
        }
    }

    /** Resolves only the requested replaceable chapter coordinates. */
    suspend fun getChaptersByCoordinates(coordinates: List<String>): List<NostrEvent> {
        val normalized = coordinates.mapNotNull { candidate ->
            val parts = candidate.trim().split(":", limit = 3)
            if (parts.size != 3 || parts[0].toIntOrNull() != BookKinds.PUBLICATION_CONTENT ||
                !HEX_64.matches(parts[1]) || parts[2].isBlank()) {
                null
            } else {
                "" + BookKinds.PUBLICATION_CONTENT + ":" + parts[1].lowercase() + ":" + parts[2]
            }
        }.distinct()
        val grouped = normalized.mapNotNull { coordinate ->
            val parts = coordinate.split(":", limit = 3)
            parts.takeIf { it.size == 3 }?.let { it[1] to it[2] }
        }.groupBy({ it.first }, { it.second })
        return grouped.flatMap { (pubkey, allDTags) ->
            allDTags.distinct().chunked(FILTER_BATCH_SIZE).flatMap { dTags ->
                filterEvents(
                    MercuryFilterRequest(
                        authors = listOf(pubkey),
                        kinds = listOf(BookKinds.PUBLICATION_CONTENT),
                        limit = dTags.size.coerceToMercuryLimit(),
                        dTags = dTags,
                    ),
                    expectedAuthors = listOf(pubkey),
                    expectedKind = BookKinds.PUBLICATION_CONTENT,
                    expectedDTags = dTags,
                )
            }
        }
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
            expectedAuthors = normalized,
        )
    }

    /** API endpoints do not imply a corresponding WebSocket relay. */
    fun getRelayHint(): String? = relayHint

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
                    expectedIds = batch,
                    expectedKind = kind,
                )
            }
    }

    private suspend fun filterEvents(
        filter: MercuryFilterRequest,
        expectedIds: List<String> = emptyList(),
        expectedAuthors: List<String> = emptyList(),
        expectedKind: Int? = filter.kinds.singleOrNull(),
        expectedDTags: List<String> = emptyList(),
    ): List<NostrEvent> =
        requestEventList(
            Request
                .Builder()
                .url(url("/api/events/filter"))
                .post(json.encodeToString(filter).toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .build(),
            expectedIds = expectedIds,
            expectedAuthors = expectedAuthors,
            expectedKind = expectedKind,
            expectedDTags = expectedDTags,
        )

    private suspend fun requestEventList(
        request: Request,
        expectedIds: List<String> = emptyList(),
        expectedAuthors: List<String> = emptyList(),
        expectedKind: Int? = null,
        expectedDTags: List<String> = emptyList(),
        primaryOnly: Boolean = false,
        sectionSearch: Boolean = false,
    ): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            try {
                executeRequest(request, primaryOnly, sectionSearch) { response ->
                    if (!response.isSuccessful) {
                        throw response.toMercuryApiException()
                    }

                    decodeEventList(response.body.readLimitedUtf8(), expectedKind, requireArray = primaryOnly).filter { event ->
                        (expectedIds.isEmpty() || event.id in expectedIds) &&
                            (expectedAuthors.isEmpty() || event.pubkey in expectedAuthors) &&
                            (expectedKind == null || event.kind == expectedKind) &&
                            (expectedDTags.isEmpty() ||
                                event.tags.any { tag ->
                                    tag.getOrNull(0) == ('d').toString() && tag.getOrNull(1) in expectedDTags
                                })
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: MercuryApiException) {
                throw exception
            } catch (exception: Throwable) {
                throw MercuryApiException("Mercury could not be reached.", exception)
            }
        }

    private fun decodeEvent(body: String, eventId: String? = null, expectedKind: Int? = null): NostrEvent? {
        val element = json.parseToJsonElement(body)
        val eventElement =
            if (element is JsonObject && "data" in element) {
                element["data"]
            } else {
                element
            }

        return when (eventElement) {
            null, JsonNull -> null
            else -> json.decodeFromJsonElement<NostrEvent>(eventElement).let { event ->
                NostrEventVerifier.verify(
                    event,
                    context = NostrEventContext(requestedEventId = eventId, expectedKind = expectedKind),
                )?.event
            }
        }
    }

    private fun decodeEventList(body: String, expectedKind: Int? = null, requireArray: Boolean = false): List<NostrEvent> {
        val element = json.parseToJsonElement(body)
        if (requireArray && element !is JsonArray) {
            throw MercuryApiException("Mercury returned an invalid event response.")
        }
        val listElement =
            if (element is JsonObject && "data" in element) {
                element["data"]
            } else {
                element
            }

        return when (listElement) {
            is JsonArray -> json.decodeFromJsonElement<List<NostrEvent>>(listElement).mapNotNull { event ->
                NostrEventVerifier.verify(event, context = NostrEventContext(expectedKind = expectedKind))?.event
            }
            null, JsonNull -> emptyList()
            else -> throw MercuryApiException("Mercury returned an invalid event response.")
        }
    }

    private fun Request.withApiBaseUrl(apiBaseUrl: String): Request {
        val fallbackBase = apiBaseUrl.toHttpUrl()
        val primaryPath = baseUrl.toHttpUrl().encodedPath.trimEnd('/')
        val suffix = url.encodedPath.removePrefix(primaryPath).ifBlank { "/" }
        val target = fallbackBase.newBuilder()
            .encodedPath(fallbackBase.encodedPath.trimEnd('/') + suffix)
            .query(url.encodedQuery)
            .build()
        return newBuilder().url(target).build()
    }

    private fun url(path: String): String = baseUrl + path
    private fun okhttp3.Response.toMercuryApiException(): MercuryApiException =
        MercuryApiException(
            message = "Mercury returned HTTP $code.",
            statusCode = code,
            retryAfterMillis = if (code == HTTP_SERVICE_UNAVAILABLE) retryAfterMillis() else null,
        )

    private fun okhttp3.Response.retryAfterMillis(): Long? {
        val value = header("Retry-After")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        value.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return if (seconds > Long.MAX_VALUE / MILLIS_PER_SECOND) {
                Long.MAX_VALUE
            } else {
                seconds * MILLIS_PER_SECOND
            }
        }

        val retryAtMillis = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return null
        return (retryAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
    }

    private fun okhttp3.ResponseBody.readLimitedUtf8(): String {
        if (contentLength() > MAX_HTTP_RESPONSE_BYTES) {
            throw MercuryApiException("Mercury response exceeded the size limit.")
        }
        if (source().request(MAX_HTTP_RESPONSE_BYTES + 1L)) {
            throw MercuryApiException("Mercury response exceeded the size limit.")
        }
        return string()
    }

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
        require(limit in 1..100) { "Search limit must be between 1 and 100." }
        val normalized = copy(
            q = q.cleaned("Query"), title = title.cleaned("Title"),
            author = author.cleaned("Author"), subject = subject.cleaned("Subject"),
            d = d.cleaned("Publication identifier"),
            language = language.cleaned("Language", BookSearchLimits.LANGUAGE)?.lowercase(),
            identifier = identifier.cleaned("Identifier", BookSearchLimits.IDENTIFIER),
            limit = limit,
        )
        return normalized.takeIf {
            listOf(it.q, it.title, it.author, it.language, it.subject, it.d, it.identifier).any { value -> value != null }
        }
    }

    private fun String?.cleaned(field: String, maxLength: Int = BookSearchLimits.TEXT): String? {
        val value = this?.trim() ?: return null
        require(value.length <= maxLength) { "$field must be at most $maxLength characters." }
        return value.takeIf(String::isNotEmpty)
    }

    /** Cancellation remains attached through the bounded body read and event verification. */
    private suspend fun <T> executeRequest(
        request: Request,
        primaryOnly: Boolean = false,
        sectionSearch: Boolean = false,
        decode: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val client = when {
            primaryOnly -> httpClient
            sectionSearch -> sectionSearchHttpClient
            else -> endpointHttpClient
        }
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!continuation.isActive) throw CancellationException("Mercury request cancelled.")
                        decode(it)
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun Int.coerceToMercuryLimit(): Int = coerceIn(1, 100)

    @Serializable
    private data class MercuryFilterRequest(
        val ids: List<String> = emptyList(),
        val authors: List<String> = emptyList(),
        val kinds: List<Int>,
        val limit: Int,
        @SerialName("#a")
        val aTags: List<String> = emptyList(),
        @SerialName("#d")
        val dTags: List<String> = emptyList(),
    )

    @Serializable
    private data class MercuryRecommendationRequest(
        @SerialName("seed_event_id") val seedEventId: String,
        @SerialName("exclude_ids") val excludeIds: List<String>,
        val limit: Int,
    )

    @Serializable
    private data class MercurySectionSearchRequest(
        val q: String,
        val limit: Int,
    )

    private companion object {
        const val FILTER_BATCH_SIZE = 100
        const val MAX_HTTP_RESPONSE_BYTES = 8L * 1024 * 1024
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val MILLIS_PER_SECOND = 1_000L
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
