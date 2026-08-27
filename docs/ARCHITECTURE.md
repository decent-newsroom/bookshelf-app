# Architecture

## Application Shape

Bookshelf is a native Android app built with Kotlin and Jetpack Compose. `AppGraph` owns process-wide dependencies; `BookshelfViewModel` coordinates UI state and use cases.

The main source boundaries are:

- `domain`: Nostr events and book/chapter models.
- `data/discovery`: checked-in curated shelf definitions, NIP-19 publication-reference decoding, and shelf metadata caching.
- `data/mercury`: Mercury REST access, publication mapping, chapter-source settings, and relay-backed chapter retrieval.
- `data/rendering`: AsciiDoc rendering and rendered-HTML caching.
- `data/bookshelf`: local saved-book state and kind `30045` directory rules.
- `data/nostr`: Android signer integration and directory relay synchronization.
- `ui`: Compose screens and reader presentation.

## Book and Chapter Loading

Search and publication-index lookup use the Mercury HTTP API. Opening a book then follows this flow:

1. Load and map the kind `30040` publication index through `MercuryApiClient` and `MercuryBookRepository`.
2. Ask `PersistentNostrChapterSource` for the referenced kind `30041` chapter events.
3. Query configured relays by event ID and by author/`d`-tag coordinate. Merge duplicate results by coordinate, keeping the newest `created_at` value.
4. Use Mercury HTTP only for references not resolved by relays. A chapter HTTP failure does not discard chapters already received over WebSocket.
5. Map events into `BookChapter` values. Missing events remain explicit unavailable chapters so publication order is preserved.
6. Render available AsciiDoc chapters and cache the HTML before exposing the `BookDetail` to the reader UI.

The publication index still depends on Mercury HTTP. The relay path specifically reduces the high-volume chapter requests that are most likely to encounter API rate limits.

## Chapter Relay Connections

`PersistentNostrChapterSource` maintains one OkHttp WebSocket per active relay URL. Connections are reused across book loads and kept alive by the shared client's ping interval. Each fetch creates an independent Nostr subscription and sends `CLOSE` for that subscription after `EOSE` or timeout; it does not close the underlying socket.

Concurrent subscriptions are multiplexed by subscription ID. A connection failure completes affected subscriptions, and the next fetch attempts a fresh connection. When settings are changed, the current relay list is read on the next chapter fetch; removed connections are then closed and newly configured connections are created on demand.

Chapter relay defaults are:

- `wss://mercury-relay.imwald.eu`
- `wss://thecitadel.nostr1.com`

`ChapterSourceSettingsStore` persists the ordered list in app-private `SharedPreferences`. Settings accepts one `ws://` or `wss://` URL per line, normalizes and deduplicates entries, and allows an empty list to disable relay chapter loading while retaining Mercury HTTP fallback.

Chapter relays are separate from the relays used for kind `30045` bookshelf-directory synchronization. The two features have different settings, event kinds, failure behavior, and connection lifecycles.

## Rendering and Cache Invariants

Chapter event content is expected to be AsciiDoc. `AsciidoctorChapterRenderer` creates HTML and `ChapterHtmlCache` stores it under `context.cacheDir/chapter-html`.

- `BookChapter.renderedHtml` is preferred by the UI; raw `content` is the fallback.
- `BookshelfViewModel.openBook` renders/caches the complete loaded detail before publishing it to UI state.
- Cache clearing stays explicit, user-visible, and limited to the chapter HTML cache.
- Rendering uses the Kotlin Multiplatform `asciidoc-kmp` parser to produce CSS-free HTML fragments without a JRuby runtime. The reader converts each fragment to a Compose `AnnotatedString`; no embedded Android `TextView` participates in reader gestures.

## Saved Books and Nostr Identity

Saved-book state is local-first. With an Android Nostr signer session, the app reads and publishes a kind `30045` directory through the separate `NostrRelayClient`/`BookshelfRelaySync` path. The signer owns private-key operations; the app stores only session metadata needed to invoke it.

## Verification Notes

The Windows verification command and temporary Gradle-home cleanup procedure are documented in [`DEVELOPMENT.md`](DEVELOPMENT.md) and `AGENTS.md`. `ChapterSourcesTest` covers chapter relay URL rules and Nostr request construction. `CuratedShelfTest` covers catalog decoding and cache behavior, while `MercuryBookRepositorySearchTest` covers exact publication-coordinate lookup and the no-chapter-fetch discovery invariant.

## Decision Records

- [`decisions/0001-persistent-relay-chapter-fetching.md`](decisions/0001-persistent-relay-chapter-fetching.md)
- [`decisions/0002-cached-curated-discovery-shelves.md`](decisions/0002-cached-curated-discovery-shelves.md)

## Curated Discovery Shelves

The Home tab is driven by the checked-in editorial catalog in `data/discovery/CuratedShelfCatalog.kt`. Each shelf stores only its title, stable id, and ordered NIP-19 `naddr` publication references; displayed title, author, cover, and chapter-reference metadata always come from kind `30040` publication-index events.

`NaddrPublicationReferenceDecoder` validates each address as a kind `30040` replaceable publication coordinate. `MercuryApiClient.getPublicationsByCoordinates` resolves exact coordinates using grouped author and `#d` filters, batched at the Mercury filter limit. Shelf loading never requests chapter events or opens chapter WebSockets. Chapters are fetched only by `BookshelfViewModel.openBook` through the existing reader flow.

`ShelfMetadataCache` stores serialized publication-index summaries under `context.cacheDir/shelf-metadata/v1.json`. Entries are fresh for 24 hours. Cached summaries are rendered immediately, stale/missing coordinates then refresh, successful refreshes are atomically written, and stale entries remain available when Mercury is unavailable. The cache contains no chapter bodies or rendered HTML.
