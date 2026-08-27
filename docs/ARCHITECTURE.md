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

`ChapterSourceSettingsStore` persists the ordered list in app-private `SharedPreferences`. Settings accepts up to eight `wss://` URLs, normalizes and deduplicates entries, and allows an empty list to disable relay chapter loading while retaining Mercury HTTPS fallback.

Chapter relays are separate from the relays used for kind `30045` bookshelf-directory synchronization. The two features have different settings, event kinds, failure behavior, and connection lifecycles.

## Rendering and Cache Invariants

Chapter event content is expected to be AsciiDoc. `AsciidoctorChapterRenderer` creates HTML and `ChapterHtmlCache` stores it under `context.cacheDir/chapter-html`.

- `BookChapter.renderedHtml` is preferred by the UI; raw `content` is the fallback.
- `BookshelfViewModel.openBook` renders/caches the complete loaded detail before publishing it to UI state.
- Cache clearing stays explicit, user-visible, and limited to the chapter HTML cache.
- AsciiDoc sources above 2 MiB and rendered fragments above 4 MiB are not cached or exposed as rendered HTML. Serialized atomic writes are pruned by last access to a 64 MiB / 1,000-entry ceiling.
- Rendering uses the Kotlin Multiplatform `asciidoc-kmp` parser to produce CSS-free HTML fragments without a JRuby runtime. The reader converts each fragment to a Compose `AnnotatedString`; no embedded Android `TextView` participates in reader gestures.

## Application Appearance

The persisted reader theme is the app-wide color scheme. Paper, Sepia, and Night each supply both the reader-specific content colors and the Material 3 colors used by discovery, search, My Books, Settings, sheets, dialogs, and navigation. The same choice also controls status-bar and navigation-bar icon contrast and scrims while the activity remains edge-to-edge. `MainActivity` applies the persisted scheme before the first Compose frame, and `BookshelfTheme` reapplies system-bar appearance whenever the setting changes.

## Untrusted Content Navigation and Covers

Chapter HTML is untrusted remote content. Compose links are intercepted by the
reader and parsed by `ChapterLinkPolicy`; only absolute HTTPS URLs with a
host and no user-info are eligible. Before opening an eligible URL through the
platform URI handler, the reader shows the normalized destination host and
requires an explicit confirmation. Malformed, cleartext, custom-scheme,
relative, and user-info URLs are ignored safely.

Cover art is an automatic network request, so author-provided `image` tags are
accepted only when they are HTTPS URLs on the explicit Gutenberg host allowlist
(`gutenberg.org`, `www.gutenberg.org`, `images.gutenberg.org`, or
`aleph.gutenberg.org`). Rejected or missing cover URLs leave the monogram
fallback visible. Inferred Project Gutenberg covers continue to use the
trusted `www.gutenberg.org` endpoint. This policy does not add permissions or
make arbitrary author hosts reachable by the image loader.

## Saved Books and Nostr Identity

Saved-book state is device-local and does not require a Nostr login. `LocalBookshelfStore` atomically persists normalized directory tags and the corresponding `BookSummary` values under `context.filesDir/bookshelf/local-v1.json`; My Books is restored on process restart and remains intact on sign-out or relay failure.

With an Android Nostr signer session, the app additionally reads and publishes a kind `30045` directory through the separate `NostrRelayClient`/`BookshelfRelaySync` path. Saving or removing a book commits locally before requesting a signature. Remote directory reads merge into device state instead of clearing or replacing local books, and a missing remote directory leaves local state unchanged. The signer owns private-key operations; the app stores only session metadata needed to invoke it.

After a signer session becomes active, `NostrProfileRepository` reads the account's cached kind `0` metadata event and refreshes it from the configured identity/directory relays. The complete event is cached by pubkey under `context.cacheDir/nostr-profiles/v1`; parsed `display_name` (falling back to `name`) is exposed to Home and Settings. Cache and relay failures do not invalidate the signer session or block kind `30045` directory synchronization.

## Backup and Device Transfer

The app opts into explicit backup rules for both supported Android generations. On Android 11 and lower, `full_backup_content.xml` excludes all shared preferences, databases, and the durable `filesDir/bookshelf` directory because that platform uses one ruleset for cloud backup and device transfer. On Android 12 and newer, `data_extraction_rules.xml` excludes those data classes from cloud backup while allowing user-controlled device-to-device transfer of the local bookshelf, reader preferences (including progress), and chapter relay settings. The signer session preference is not included in either path, so a restored or transferred install must reauthorize with the external signer. This preserves normal migration UX without placing identity or reading data in cloud backup; legacy devices deliberately favor privacy because their platform cannot express the distinction.

## Verification Notes

The Windows verification command and temporary Gradle-home cleanup procedure are documented in [`DEVELOPMENT.md`](DEVELOPMENT.md) and `AGENTS.md`. `ChapterSourcesTest` covers chapter relay URL rules and Nostr request construction. `ChapterLinkPolicyTest` and `TrustedCoverImagePolicyTest` cover untrusted navigation and automatic-cover host filtering. `CuratedShelfTest` covers catalog decoding and cache behavior, while `MercuryBookRepositorySearchTest` covers exact publication-coordinate lookup and the no-chapter-fetch discovery invariant.

## Nostr Event Trust Boundary

Every Mercury, relay, profile-cache, and signer-returned event crosses NostrEventVerifier before mapping, selection, persistence, rendering, merging, or publication. It validates bounded event fields and timestamps, recomputes the canonical NIP-01 ID, and verifies the Schnorr signature using Quartz. Callers provide request context where applicable (event ID, kind, author, d-tag, and relay subscription ID), and newest-event selection is deterministic by created_at then ID. Directory signer responses must also retain the exact draft kind, timestamp, tags, and content.

## Decision Records

- [`decisions/0001-persistent-relay-chapter-fetching.md`](decisions/0001-persistent-relay-chapter-fetching.md)
- [`decisions/0002-cached-curated-discovery-shelves.md`](decisions/0002-cached-curated-discovery-shelves.md)
- [`decisions/0003-cache-nostr-profile-on-login.md`](decisions/0003-cache-nostr-profile-on-login.md)
- [`decisions/0004-device-local-bookshelf-with-optional-sync.md`](decisions/0004-device-local-bookshelf-with-optional-sync.md)
- [`decisions/0005-verified-nostr-event-boundary.md`](decisions/0005-verified-nostr-event-boundary.md)
- [`decisions/0006-bound-untrusted-content-resource-use.md`](decisions/0006-bound-untrusted-content-resource-use.md)
- [`decisions/0007-private-cloud-backup-with-explicit-device-transfer.md`](decisions/0007-private-cloud-backup-with-explicit-device-transfer.md)
- [`decisions/0008-untrusted-content-navigation-and-cover-privacy.md`](decisions/0008-untrusted-content-navigation-and-cover-privacy.md)

## Curated Discovery Shelves

The Home tab is driven by the checked-in editorial catalog in `data/discovery/CuratedShelfCatalog.kt`. Each shelf stores only its title, stable id, and ordered NIP-19 `naddr` publication references; displayed title, author, cover, and chapter-reference metadata always come from kind `30040` publication-index events.

`NaddrPublicationReferenceDecoder` validates each address as a kind `30040` replaceable publication coordinate. `MercuryApiClient.getPublicationsByCoordinates` resolves exact coordinates using grouped author and `#d` filters, batched at the Mercury filter limit. Shelf loading never requests chapter events or opens chapter WebSockets. Chapters are fetched only by `BookshelfViewModel.openBook` through the existing reader flow.

`ShelfMetadataCache` stores serialized publication-index summaries under `context.cacheDir/shelf-metadata/v1.json`. Entries are fresh for 24 hours. Cached summaries are rendered immediately, stale/missing coordinates then refresh, successful refreshes are atomically written, and stale entries remain available when Mercury is unavailable. The cache contains no chapter bodies or rendered HTML.
