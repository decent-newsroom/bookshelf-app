package eu.decentnewsroom.bookshelf

import android.content.Context
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.NostrRelayClient
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSessionStore
import eu.decentnewsroom.bookshelf.data.nostr.QuartzBookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AppGraph {
    private const val MERCURY_BASE_URL = "https://mercury-relay.imwald.eu"
    private const val MERCURY_RELAY_URL = "wss://mercury-relay.imwald.eu"

    private val defaultRelays =
        listOf(
            MERCURY_RELAY_URL,
            "wss://nos.lol",
            "wss://relay.damus.io",
        )

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private var readerSettingsStore: ReaderSettingsStore? = null
    private var relaySyncStore: BookshelfRelaySync? = null
    private var chapterHtmlCacheStore: ChapterHtmlCache? = null

    val mercuryBooks =
        MercuryBookRepository(
            apiClient = MercuryApiClient(
                httpClient = httpClient,
                mercuryApiBaseUrl = MERCURY_BASE_URL,
            ),
        )

    val localBookshelf = LocalBookshelfStore()

    val relaySync: BookshelfRelaySync
        get() = relaySyncStore ?: error("AppGraph.initialize(context) must be called before using relay sync.")

    val readerSettings: ReaderSettingsStore
        get() = readerSettingsStore ?: error("AppGraph.initialize(context) must be called before using reader settings.")

    val chapterHtmlCache: ChapterHtmlCache
        get() = chapterHtmlCacheStore ?: error("AppGraph.initialize(context) must be called before using chapter HTML cache.")

    fun initialize(context: Context) {
        val appContext = context.applicationContext

        if (readerSettingsStore == null) {
            readerSettingsStore = ReaderSettingsStore(appContext)
        }
        if (chapterHtmlCacheStore == null) {
            chapterHtmlCacheStore = ChapterHtmlCache(appContext)
        }
        if (relaySyncStore == null) {
            relaySyncStore = QuartzBookshelfRelaySync(
                relayClient = NostrRelayClient(
                    httpClient = httpClient,
                    relayUrls = defaultRelays,
                ),
                sessionStore = NostrSignerSessionStore(appContext),
            )
        }
    }
}