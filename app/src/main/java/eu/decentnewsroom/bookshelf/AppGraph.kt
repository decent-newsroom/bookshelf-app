package eu.decentnewsroom.bookshelf

import android.content.Context
import android.util.Log
import eu.decentnewsroom.bookshelf.data.highlights.HighlightStore
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutbox
import eu.decentnewsroom.bookshelf.data.highlights.HighlightOutboxDispatcher
import eu.decentnewsroom.bookshelf.data.bookshelf.LocalBookshelfStore
import eu.decentnewsroom.bookshelf.data.connectivity.ValidatedInternetConnectivity
import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationCache
import eu.decentnewsroom.bookshelf.data.discovery.BookRecommendationRepository
import eu.decentnewsroom.bookshelf.data.discovery.CuratedShelfRepository
import eu.decentnewsroom.bookshelf.data.discovery.ShelfMetadataCache
import eu.decentnewsroom.bookshelf.data.mercury.ChapterSourceSettingsStore
import eu.decentnewsroom.bookshelf.data.mercury.MercuryApiClient
import eu.decentnewsroom.bookshelf.data.mercury.MercuryBookRepository
import eu.decentnewsroom.bookshelf.data.mercury.NaddrPublicationIndexRelaySource
import eu.decentnewsroom.bookshelf.data.mercury.PublicationIndexRelaySource
import eu.decentnewsroom.bookshelf.data.mercury.PersistentNostrChapterSource
import eu.decentnewsroom.bookshelf.data.nostr.BookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.nostr.NostrProfileCache
import eu.decentnewsroom.bookshelf.data.nostr.NostrProfileRepository
import eu.decentnewsroom.bookshelf.data.nostr.NostrRelayClient
import eu.decentnewsroom.bookshelf.data.nostr.NostrSignerSessionStore
import eu.decentnewsroom.bookshelf.data.nostr.LocalRelaySettingsStore
import eu.decentnewsroom.bookshelf.data.onboarding.OnboardingTipStore
import eu.decentnewsroom.bookshelf.data.nostr.ExternalSignerNostrRelayAuthenticator
import eu.decentnewsroom.bookshelf.data.nostr.QuartzBookshelfRelaySync
import eu.decentnewsroom.bookshelf.data.reader.ReaderSettingsStore
import eu.decentnewsroom.bookshelf.data.reader.OfflineBookCache
import eu.decentnewsroom.bookshelf.data.reader.ReaderContentCoordinator
import eu.decentnewsroom.bookshelf.data.rendering.ChapterHtmlCache
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingsRepository
import eu.decentnewsroom.bookshelf.data.ratings.BookRatingCache
import eu.decentnewsroom.bookshelf.data.ratings.ReviewOutbox
import eu.decentnewsroom.bookshelf.data.ratings.ReviewOutboxDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import eu.decentnewsroom.bookshelf.data.reading.ReadingStateRepository
import eu.decentnewsroom.bookshelf.data.reading.ReadingStateStore
import eu.decentnewsroom.bookshelf.data.reading.ReadingTransport
import eu.decentnewsroom.bookshelf.data.nostr.AndroidExternalSigner
import eu.decentnewsroom.bookshelf.domain.BookReference
import eu.decentnewsroom.bookshelf.domain.BookSummary
import eu.decentnewsroom.bookshelf.domain.NostrEvent
import java.io.File

object AppGraph {
    private const val DECENT_NEWSROOM_BOOKS_API_BASE_URL = "https://decentnewsroom.com/books"
    private const val MERCURY_FALLBACK_API_BASE_URL = "https://mercury-relay.imwald.eu"
    private const val MERCURY_RELAY_URL = "wss://mercury-relay.imwald.eu"

    val defaultRelays =
        listOf(
            "wss://relay.decentnewsroom.com",
            "wss://thecitadel.nostr1.com",
            "wss://pipe.imwald.eu",
        )

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

    private val ratingCacheMaintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var readerSettingsStore: ReaderSettingsStore? = null
    private var onboardingTipStore: OnboardingTipStore? = null
    private var chapterSourceSettingsStore: ChapterSourceSettingsStore? = null
    private var localRelaySettingsStore: LocalRelaySettingsStore? = null
    private var localBookshelfStore: LocalBookshelfStore? = null
    private var mercuryBooksStore: MercuryBookRepository? = null
    private var mercuryApiStore: MercuryApiClient? = null
    private var bookRecommendationStore: BookRecommendationRepository? = null
    private var directoryRelayClientStore: NostrRelayClient? = null
    private var relayAuthenticatorStore: ExternalSignerNostrRelayAuthenticator? = null
    private var relaySyncStore: BookshelfRelaySync? = null
    private var nostrProfileRepositoryStore: NostrProfileRepository? = null
    private var chapterHtmlCacheStore: ChapterHtmlCache? = null
    private var offlineBookCacheStore: OfflineBookCache? = null
    private var readerContentCoordinatorStore: ReaderContentCoordinator? = null
    private var shelfMetadataCacheStore: ShelfMetadataCache? = null
    private var curatedShelfRepositoryStore: CuratedShelfRepository? = null
    private var bookRatingsRepositoryStore: BookRatingsRepository? = null
    private var bookRatingCacheStore: BookRatingCache? = null
    private var reviewOutboxStore: ReviewOutbox? = null
    private var reviewOutboxDispatcherStore: ReviewOutboxDispatcher? = null
    private var connectivityStore: ValidatedInternetConnectivity? = null
    private var highlightStore: HighlightStore? = null
    private var highlightOutboxStore: HighlightOutbox? = null
    private var highlightDispatcherStore: HighlightOutboxDispatcher? = null
    private var readingStateStore: ReadingStateRepository? = null

    val readingState: ReadingStateRepository
        get() = checkNotNull(readingStateStore) { "AppGraph.initialize(context) must be called before using reading state." }

    val highlights: HighlightStore
        get() = checkNotNull(highlightStore) { "AppGraph.initialize(context) must be called before using highlights." }
    val highlightOutbox: HighlightOutbox
        get() = checkNotNull(highlightOutboxStore)
    val highlightDispatcher: HighlightOutboxDispatcher
        get() = checkNotNull(highlightDispatcherStore)


    val connectivity: ValidatedInternetConnectivity
        get() = connectivityStore ?: error("AppGraph.initialize(context) must be called before using connectivity.")

    val mercuryBooks: MercuryBookRepository
        get() = mercuryBooksStore ?: error("AppGraph.initialize(context) must be called before using Mercury books.")

    val bookRecommendations: BookRecommendationRepository
        get() = bookRecommendationStore ?: error("AppGraph.initialize(context) must be called before using recommendations.")

    val localBookshelf: LocalBookshelfStore
        get() = localBookshelfStore ?: error("AppGraph.initialize(context) must be called before using the local bookshelf.")

    val relaySync: BookshelfRelaySync
        get() = relaySyncStore ?: error("AppGraph.initialize(context) must be called before using relay sync.")

    val nostrProfiles: NostrProfileRepository
        get() = nostrProfileRepositoryStore ?: error("AppGraph.initialize(context) must be called before using Nostr profiles.")

    val readerSettings: ReaderSettingsStore
        get() = readerSettingsStore ?: error("AppGraph.initialize(context) must be called before using reader settings.")

    val chapterSourceSettings: ChapterSourceSettingsStore
        get() = chapterSourceSettingsStore ?: error("AppGraph.initialize(context) must be called before using chapter source settings.")
    val onboardingTips: OnboardingTipStore
        get() = onboardingTipStore ?: error("AppGraph.initialize(context) must be called before using onboarding tips.")


    val localRelaySettings: LocalRelaySettingsStore
        get() = localRelaySettingsStore ?: error("AppGraph.initialize(context) must be called before using local relay settings.")

    val chapterHtmlCache: ChapterHtmlCache
        get() = chapterHtmlCacheStore ?: error("AppGraph.initialize(context) must be called before using chapter HTML cache.")

    val offlineBookCache: OfflineBookCache
        get() = offlineBookCacheStore ?: error("AppGraph.initialize(context) must be called before using offline book cache.")

    val readerContent: ReaderContentCoordinator
        get() = readerContentCoordinatorStore ?: error("AppGraph.initialize(context) must be called before using reader content.")

    val bookRatings: BookRatingsRepository
        get() = bookRatingsRepositoryStore ?: error("AppGraph.initialize(context) must be called before using book ratings.")

    val reviewOutbox: ReviewOutbox
        get() = reviewOutboxStore ?: error("AppGraph.initialize(context) must be called before using review outbox.")

    val reviewOutboxDispatcher: ReviewOutboxDispatcher
        get() = reviewOutboxDispatcherStore ?: error("AppGraph.initialize(context) must be called before using review outbox dispatcher.")

    val curatedShelves: CuratedShelfRepository
        get() = curatedShelfRepositoryStore ?: error("AppGraph.initialize(context) must be called before using curated shelves.")

    fun initialize(context: Context) {
        val appContext = context.applicationContext

        if (connectivityStore == null) {
            connectivityStore = ValidatedInternetConnectivity(appContext)
        }
        if (readerSettingsStore == null) {
            readerSettingsStore = ReaderSettingsStore(appContext)
        }
        if (onboardingTipStore == null) {
            onboardingTipStore = OnboardingTipStore(appContext)
        }
        if (localBookshelfStore == null) {
            localBookshelfStore = LocalBookshelfStore(appContext)
        }
        if (chapterSourceSettingsStore == null) {
            chapterSourceSettingsStore = ChapterSourceSettingsStore(appContext)
        }
        if (localRelaySettingsStore == null) {
            localRelaySettingsStore = LocalRelaySettingsStore(appContext)
        }
        if (directoryRelayClientStore == null || relayAuthenticatorStore == null) {
            val sessionStore = NostrSignerSessionStore(appContext)
            val authenticator = ExternalSignerNostrRelayAuthenticator(sessionStore.load())
            directoryRelayClientStore = NostrRelayClient(
                httpClient = httpClient,
                relayUrls = defaultRelays + listOfNotNull(checkNotNull(localRelaySettingsStore).relayUrl.value),
                authenticator = authenticator,
            )
            relayAuthenticatorStore = authenticator
        }
        val directoryRelayClient = checkNotNull(directoryRelayClientStore)
        val relayAuthenticator = checkNotNull(relayAuthenticatorStore)
        if (mercuryApiStore == null) {
            mercuryApiStore = MercuryApiClient(
                httpClient = httpClient,
                mercuryApiBaseUrl = DECENT_NEWSROOM_BOOKS_API_BASE_URL,
                fallbackApiBaseUrls = listOf(MERCURY_FALLBACK_API_BASE_URL),
                relayHint = MERCURY_RELAY_URL,
            )
        }
        if (mercuryBooksStore == null) {
            val sourceSettings = checkNotNull(chapterSourceSettingsStore)
            mercuryBooksStore = MercuryBookRepository(
                apiClient = checkNotNull(mercuryApiStore),
                isInternetAvailable = { connectivity.isOnline },
                chapterEventSource = PersistentNostrChapterSource(
                    httpClient = httpClient,
                    relayUrls = { sourceSettings.relayUrls.value },
                ),
                publicationIndexRelaySource = { coordinates ->
                    directoryRelayClient.fetchPublicationIndexes(coordinates)
                },
                naddrPublicationIndexRelaySource = { coordinate, relayHints ->
                    directoryRelayClient.fetchPublicationIndexes(listOf(coordinate), relayHints)
                },
            )
        }
        if (bookRecommendationStore == null) {
            bookRecommendationStore = BookRecommendationRepository(
                api = checkNotNull(mercuryApiStore),
                books = mercuryBooks,
                endpoint = "$DECENT_NEWSROOM_BOOKS_API_BASE_URL/api",
                cache = BookRecommendationCache(appContext),
                isInternetAvailable = { connectivity.isOnline },
            )
        }
        if (bookRatingCacheStore == null) {
            val cache = BookRatingCache(appContext)
            bookRatingCacheStore = cache
            // Reading once compacts legacy revisions even if the user never opens ratings.
            ratingCacheMaintenanceScope.launch {
                try {
                    cache.stats()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w("AppGraph", "Could not compact the rating cache at startup.", error)
                }
            }
        }
        if (bookRatingsRepositoryStore == null) {
            bookRatingsRepositoryStore = BookRatingsRepository(
                directoryRelayClient,
                checkNotNull(bookRatingCacheStore),
                isInternetAvailable = { checkNotNull(connectivityStore).isOnline },
            )
        }
        if (chapterHtmlCacheStore == null) {
            chapterHtmlCacheStore = ChapterHtmlCache(appContext)
        }
        if (offlineBookCacheStore == null) {
            offlineBookCacheStore = OfflineBookCache(appContext)
        }
        if (readerContentCoordinatorStore == null) {
            readerContentCoordinatorStore = ReaderContentCoordinator(
                repository = mercuryBooks,
                offlineBookCache = checkNotNull(offlineBookCacheStore),
                chapterHtmlCache = checkNotNull(chapterHtmlCacheStore),
                isInternetAvailable = { checkNotNull(connectivityStore).isOnline },
            )
        }
        if (shelfMetadataCacheStore == null) {
            shelfMetadataCacheStore = ShelfMetadataCache(appContext)
        }
        if (curatedShelfRepositoryStore == null) {
            curatedShelfRepositoryStore = CuratedShelfRepository(mercuryBooks, checkNotNull(shelfMetadataCacheStore))
        }
        if (relaySyncStore == null || nostrProfileRepositoryStore == null) {
            val sessionStore = NostrSignerSessionStore(appContext)
            relaySyncStore = QuartzBookshelfRelaySync(
                relayClient = directoryRelayClient,
                sessionStore = sessionStore,
                authenticator = relayAuthenticator,
                defaultRelayUrls = defaultRelays,
            )
            nostrProfileRepositoryStore = NostrProfileRepository(
                relayClient = directoryRelayClient,
                cache = NostrProfileCache(appContext),
            )
        }
        if (highlightStore == null) highlightStore = HighlightStore(appContext)
        if (highlightOutboxStore == null) highlightOutboxStore = HighlightOutbox(appContext)
        if (highlightDispatcherStore == null) {
            highlightDispatcherStore = HighlightOutboxDispatcher(
                outbox = checkNotNull(highlightOutboxStore),
                relaySync = checkNotNull(relaySyncStore),
                localRelayUrl = { checkNotNull(localRelaySettingsStore).relayUrl.value },
                isOnline = { checkNotNull(connectivityStore).isOnline },
            )
        }
        if (reviewOutboxStore == null) {
            reviewOutboxStore = ReviewOutbox(appContext)
        }
        if (reviewOutboxDispatcherStore == null) {
            reviewOutboxDispatcherStore = ReviewOutboxDispatcher(
                outbox = checkNotNull(reviewOutboxStore),
                relaySync = checkNotNull(relaySyncStore),
                localRelayUrl = { checkNotNull(localRelaySettingsStore).relayUrl.value },
                isOnline = { checkNotNull(connectivityStore).isOnline },
            )
        }
        if (readingStateStore == null) {
            val readingRepository = ReadingStateRepository(
                store = ReadingStateStore(File(File(appContext.filesDir, "bookshelf"), "reading-state-v1.json")),
                transport = object : ReadingTransport {
                    override suspend fun snapshot(pubkey: String) = directoryRelayClient.fetchReadingSnapshot(pubkey)
                    override suspend fun finished(pubkey: String) = directoryRelayClient.fetchFinishedLabels(pubkey)
                    override suspend fun resolve(coordinates: List<String>): List<BookSummary> = mercuryBooks.getMyBooksForReferences(
                        coordinates.map { BookReference("a", it, null, null, null) },
                    )
                    override suspend fun writeRelays(pubkey: String) = directoryRelayClient.readingRelayUrls(pubkey)
                    override suspend fun publish(event: NostrEvent, relay: String) =
                        directoryRelayClient.publishReadingEventToRelays(event, listOf(relay))
                },
                online = { connectivity.isOnline },
                localRelay = { localRelaySettings.relayUrl.value },
                backgroundSign = { session, eventJson -> AndroidExternalSigner.signEventInBackground(appContext, session, eventJson) },
                initialSession = relaySync.activeSession.value,
            )
            readingStateStore = readingRepository
            ratingCacheMaintenanceScope.launch {
                relaySync.activeSession.collect { readingRepository.setSession(it) }
            }
            ratingCacheMaintenanceScope.launch {
                connectivity.online.collect { readingRepository.connectivityChanged() }
            }
        }
    }
}
