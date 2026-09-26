package eu.decentnewsroom.bookshelf.data.mercury

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.utils.Secp256k1InstanceKotlin
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MercuryApiClientContractTest {
    @Test fun recommendationsUseDocumentedPrimaryUrlAndNormalizedBody() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = client { request -> requests += request; response(request, "[]") }
        assertTrue(api.recommendPublications("A".repeat(64), listOf("B".repeat(64), "b".repeat(64)), 50).isEmpty())
        val request = requests.single()
        assertEquals("https://primary.example/books/api/publications/recommendations", request.url.toString())
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(setOf("seed_event_id", "exclude_ids", "limit"), body.keys)
        assertEquals("a".repeat(64), body.getValue("seed_event_id").jsonPrimitive.content)
        assertEquals("[\"${"b".repeat(64)}\"]", body.getValue("exclude_ids").toString())
        assertEquals("50", body.getValue("limit").toString())
    }

    @Test fun invalidParametersNeverReachNetworkAndExclusionCountPrecedesDeduplication() = runBlocking {
        var calls = 0
        val api = client { calls++; response(it, "[]") }
        suspend fun invalid(block: suspend () -> Unit) {
            try { block(); fail("Expected invalid argument") } catch (_: IllegalArgumentException) { }
        }
        invalid { api.recommendPublications("invalid") }
        invalid { api.recommendPublications("a".repeat(64), List(101) { "b".repeat(64) }) }
        invalid { api.recommendPublications("a".repeat(64), listOf("invalid")) }
        invalid { api.recommendPublications("a".repeat(64), limit = 51) }
        invalid { api.recommendPublications("a".repeat(64), limit = 0) }
        invalid { api.searchPublications(MercuryPublicationSearch(title = "x".repeat(161), author = "valid")) }
        invalid { api.searchPublications(MercuryPublicationSearch(language = "x".repeat(33))) }
        invalid { api.searchPublications(MercuryPublicationSearch(identifier = "x".repeat(513))) }
        invalid { api.searchPublicationSections("abc") }
        invalid { api.searchPublicationSections("x".repeat(161)) }
        assertEquals(0, calls)
        api.searchPublications(MercuryPublicationSearch(title = "x".repeat(160), language = "x".repeat(32), identifier = "x".repeat(512)))
        api.searchPublicationSections("abcd", 1)
        api.recommendPublications("a".repeat(64), List(100) { "b".repeat(64) }, 1)
        assertEquals(3, calls)
    }

    @Test fun recommendationErrorsPreserveStatusWithoutLegacyFallbackOrServerText() = runBlocking {
        for (status in listOf(400, 404, 503)) {
            val hosts = mutableListOf<String>()
            val api = client { hosts += it.url.host; response(it, "private server detail", status) }
            try { api.recommendPublications("a".repeat(64)); fail("Expected HTTP error") }
            catch (error: MercuryApiException) {
                assertEquals(status, error.statusCode)
                assertFalse(error.message.orEmpty().contains("private"))
                if (status == 503) assertEquals(2000L, error.retryAfterMillis)
            }
            assertEquals(listOf("primary.example"), hosts)
        }
    }

    @Test fun primary503CannotBeMaskedByLegacy404() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine().split(" ")[1]
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    paths += path
                    val status = if (path.startsWith("/books/")) 503 else 404
                    socket.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (_: java.io.IOException) { /* Closing the fixture terminates accept. */ }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val api = MercuryApiClient(OkHttpClient(), "$base/books", listOf("$base/legacy"))
            try { api.recommendPublications("a".repeat(64)); fail("Expected primary 503") }
            catch (failure: MercuryApiException) { assertEquals(503, failure.statusCode) }
            assertEquals(listOf("/books/api/publications/recommendations"), paths)
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    @Test fun onlyVerifiedPublicationEventsAreAcceptedAndMalformedBodyFails() = runBlocking {
        val valid = signedEvent(30040)
        val wrongKind = signedEvent(30041)
        val invalidSignature = valid.copy(sig = "0".repeat(128))
        val api = client { response(it, Json.encodeToString(listOf(valid, wrongKind, invalidSignature))) }
        assertEquals(listOf(valid), api.recommendPublications("a".repeat(64)))
        try { client { response(it, "{broken") }.recommendPublications("a".repeat(64)); fail("Expected protocol error") }
        catch (_: MercuryApiException) { }
    }

    @Test fun recommendationsRejectNullObjectsAndLegacyEnvelopes() = runBlocking {
        for (body in listOf("null", "{}", "{\"data\":null}", "{\"data\":[]}")) {
            try { client { response(it, body) }.recommendPublications("a".repeat(64)); fail("Expected array protocol error for $body") }
            catch (failure: MercuryApiException) { assertNull(failure.statusCode) }
        }
        // The legacy search endpoint retains its envelope compatibility.
        assertTrue(client { response(it, "{\"data\":[]}") }.searchPublications("test").isEmpty())
    }

    @Test fun coroutineCancellationCancelsUnderlyingCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls += chain.call()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(chain.request(), "[]")
        }.build()
        val api = MercuryApiClient(http, "https://primary.example/books", listOf("https://legacy.example"))
        val job = launch(Dispatchers.Default) { api.searchPublicationSections("chapter") }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(calls.single().isCanceled())
            assertEquals(1, calls.size)
        } finally { release.countDown() }
    }

    @Test fun cancellationDuringResponseBodyReadClosesTheSocket() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val bodyStarted = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        val http = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call }
            })
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val originalBody = response.body
                // Signal entry into the consumer's read, independently of when OkHttp
                // emits its responseBodyStart event for a partially delivered body.
                val observedSource = object : ForwardingSource(originalBody.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        bodyStarted.countDown()
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = originalBody.contentType()
                    override fun contentLength() = originalBody.contentLength()
                    override fun source() = observedSource
                }).build()
            }.build()
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    var length = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    // Deliver a valid header and initial body byte, then leave the bounded body read waiting.
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n[".toByteArray())
                    socket.getOutputStream().flush()
                    try {
                        if (reader.read() == -1) disconnected.countDown()
                    } catch (_: java.net.SocketException) {
                        // Windows may report a reset instead of EOF when the client cancels.
                        disconnected.countDown()
                    }
                }
            } catch (_: java.io.IOException) { /* Fixture timeout/close is handled by bounded assertions. */ }
        }
        val api = MercuryApiClient(http, "http://127.0.0.1:${server.localPort}/books")
        val job = launch(Dispatchers.Default) { api.recommendPublications("a".repeat(64)) }
        try {
            assertTrue("Response body reading should start", bodyStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(calls.single().isCanceled())
            assertTrue("Cancellation must close the in-flight response socket", disconnected.await(5, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
        } finally {
            job.cancelAndJoin()
            server.close()
            worker.join(5_000)
        }
    }

    private fun client(handler: (Request) -> Response) = MercuryApiClient(
        OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build(),
        "https://primary.example/books", listOf("https://legacy.example"),
    )

    private fun response(request: Request, body: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Retry-After", "2").body(body.toResponseBody("application/json".toMediaType())).build()

    private fun signedEvent(kind: Int): NostrEvent {
        val privateKey = ByteArray(32) { 1 }
        val pubkey = Secp256k1InstanceKotlin.compressedPubKeyFor(privateKey).copyOfRange(1, 33).toHex()
        val tags = listOf(listOf("d", "test"))
        val id = EventHasher.hashId(pubkey, 1L, kind, tags.map { it.toTypedArray() }.toTypedArray(), "")
        val signature = Secp256k1InstanceKotlin.signSchnorr(id.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), privateKey, ByteArray(32)).toHex()
        return NostrEvent(id, pubkey, 1L, kind, tags, "", signature)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
