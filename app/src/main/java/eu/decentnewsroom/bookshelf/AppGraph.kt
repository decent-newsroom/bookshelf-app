package eu.decentnewsroom.bookshelf

import android.content.Context
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.nostr.QuartzBookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AppGraph {
    private const val MERCURY_BASE_URL = "https://mercury-relay.imwald.eu"

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    private var readerSettingsStore: ReaderSettingsStore? = null

    val mercuryBooks =
        MercuryBookRepository(
            apiClient = MercuryApiClient(
                httpClient = httpClient,
                mercuryApiBaseUrl = MERCURY_BASE_URL,
            ),
        )

    val localBookshelf = LocalBookshelfStore()
    val relaySync = QuartzBookshelfRelaySync()

    val readerSettings: ReaderSettingsStore
        get() = readerSettingsStore ?: error("AppGraph.initialize(context) must be called before using reader settings.")

    fun initialize(context: Context) {
        if (readerSettingsStore == null) {
            readerSettingsStore = ReaderSettingsStore(context.applicationContext)
        }
    }
}
