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

## Mercury Search

Search discovery uses the typed `BookSearchQuery` and returns transient
`BookSearchResult` values. A normal all-scope query makes one metadata
`q` request and, when the term is at least four characters, one chapter
section request. Structured scopes select one corresponding metadata field;
chapter-content scope selects only the section request. Exact publication and
chapter coordinates use author-plus-`#d` filters, never a broad author
window.

HTTP requests use an ordered API chain. The preferred endpoint is
`https://decentnewsroom.com/books/api`; the legacy Mercury API remains an
HTTPS fallback for transport failures and HTTP 5xx responses. A successful
HTTP response, including an empty result or 4xx validation failure, is
authoritative and does not issue a duplicate request. The preferred endpoint
is HTTP-only: it is never converted into a WebSocket relay URL. Chapter relay
connections use the separate chapter-source settings as their baseline, with
valid relay hints from the loaded publication index added for that fetch.

Mercury search responses are accepted only for kinds 30040 (publication
indexes) and 30041 (chapter sections). Results retain provenance, an optional
matched chapter coordinate/title, and a maximum 320-character excerpt derived
only from the verified section event returned by the search. Metadata and
section channels are merged by bounded rank fusion while preserving the
ordering supplied by Mercury. Reciprocal-rank fusion uses k=60, so a result
present in both channels gains score without allowing absolute endpoint
weights to override channel rank. Duplicate publication coordinates combine
provenance and keep the newest index event. Search discovery never fetches or
renders complete chapters; that remains the `openBook` boundary.

Independent Mercury search branches return a `BookSearchOutcome` classified as
complete, partial, or unavailable. A search-only resilience controller limits
the process to two active Mercury search calls, retries HTTP 503 once with
bounded exponential backoff and jitter, honors bounded `Retry-After`, and
opens a five-second cooldown after repeated 503 responses. Non-503 failures
are not retried. Successful branches remain visible when a peer branch fails.

Only complete outcomes are stored in a process-memory cache: normalized query
keys expire after 30 seconds and the cache holds at most 20 entries. Query
text, excerpts, and search history are never persisted. `BookshelfViewModel`
cancels a previous search when a newer query is submitted, the search panel is
closed, or the user changes tabs. These controls reduce client-contributed
load and visible transient failures; they do not substitute for Mercury
server capacity.

## Book and Chapter Loading

Search and curated-shelf publication lookup use the Mercury HTTP API. My Books resolves its referenced kind `30040` coordinates through both the APIs and the known bookshelf relays, querying each relay by exact author/`d`-tag coordinate and retaining the newest verified event.

1. Open the already-resolved kind `30040` publication index. Search and Home summaries originate from Mercury; My Books may originate from a verified bookshelf relay, so opening never requires Mercury to mirror the same index event.
2. Ask `PersistentNostrChapterSource` for the referenced kind `30041` chapter events.
3. Query configured relays plus valid `wss://` hints from the publication's chapter `a` tags by event ID and by author/`d`-tag coordinate. Merge duplicate results by coordinate, keeping the newest `created_at` value.
4. Use Mercury HTTP only for references not resolved by relays. A chapter HTTP failure does not discard chapters already received over WebSocket.
5. Map events into `BookChapter` values. Missing events remain explicit unavailable chapters so publication order is preserved.
6. Render available AsciiDoc chapters and cache the HTML before exposing the `BookDetail` to the reader UI.

The reader's publication index and chapter relay path remains separate from the My Books metadata lookup. The latter uses the known bookshelf relays only, so third-party publication sources can appear in My Books without changing Home or search discovery.

## Chapter Relay Connections

`PersistentNostrChapterSource` maintains one OkHttp WebSocket per active relay URL. Connections are reused across book loads and kept alive by the shared client's ping interval. Each fetch creates an independent Nostr subscription and sends `CLOSE` for that subscription after `EOSE` or timeout; it does not close the underlying socket. The directory/profile relay client has a signer-neutral NIP-42 authentication boundary: it recognizes `AUTH` challenges, stores them without prompting the signer, and can perform one lazy authenticated retry for a protected publish or read when an authenticator is injected.

Concurrent subscriptions are multiplexed by subscription ID. A connection failure completes affected subscriptions, and the next fetch attempts a fresh connection. When settings are changed, the current relay list is read on the next chapter fetch; removed connections are then closed and newly configured connections are created on demand. Each fetch adds normalized, valid `wss://` hints from its chapter `a` tags after the configured list, deduplicates them, and caps the combined list at eight relays. Invalid hints are ignored.

Chapter relay defaults are:

- `wss://mercury-relay.imwald.eu`
- `wss://thecitadel.nostr1.com`
- `wss://njump.me`

`ChapterSourceSettingsStore` persists the ordered list in app-private `SharedPreferences`. Settings accepts up to eight `wss://` URLs and normalizes and deduplicates entries. An empty saved list falls back to the built-in chapter relay defaults; valid publication hints are then appended within the same eight-relay limit.

Chapter relays are separate from the relays used for kind `30045` bookshelf-directory synchronization. The two features have different settings, event kinds, failure behavior, and connection lifecycles.

## Rendering and Cache Invariants

Chapter event content is expected to be AsciiDoc. `AsciidoctorChapterRenderer` creates HTML and `ChapterHtmlCache` stores it under `context.cacheDir/chapter-html`.

- `BookChapter.renderedHtml` is preferred by the UI; raw `content` is the fallback.
- `BookshelfViewModel.openBook` renders/caches the complete loaded detail before publishing it to UI state.
- Cache clearing stays explicit, user-visible, and limited to the chapter HTML cache.
- Transient operation confirmations, including cache clearing, are presented by the app-level snackbar host with a dismiss action and short timeout; they are consumed after presentation rather than rendered as persistent screen content.
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

Cover art is an automatic network request. A kind `30040` publication's
`image` tag takes precedence over inferred Project Gutenberg artwork when it
is an absolute HTTPS URL with a host and no user-info. This supports complete
independent publication indexes such as ones that provide a publisher-hosted
cover. Rejected or missing image URLs leave the monogram fallback visible;
Gutenberg indexes without an image tag continue to infer the standard
`www.gutenberg.org` cover URL.

## Saved Books and Nostr Identity

Saved-book state is device-local and does not require a Nostr login. `LocalBookshelfStore` atomically persists normalized directory tags and the corresponding `BookSummary` values under `context.filesDir/bookshelf/local-v1.json`; My Books is restored on process restart and remains intact on sign-out or relay failure.

With an Android Nostr signer session, the app additionally reads and publishes a kind `30045` directory through the separate `NostrRelayClient`/`BookshelfRelaySync` path. Its default outbound relays are `wss://relay.decentnewsroom.com`, `wss://thecitadel.nostr1.com`, and `wss://pipe.imwald.eu`; user relay-list discovery is not yet implemented. Saving or removing a book commits locally before requesting a signature. Settings also provides a signer-approved **Sync to relays** action that signs and publishes the complete current local directory, so a rejected signer request or relay failure can be retried without another book change. **Sync from relays** remains a separate pull action. Published directory drafts contain exactly one `client` tag with the value `Bookshelf`; it is metadata only and is not persisted with the editable collection tags. Remote directory reads merge into device state instead of clearing or replacing local books, and a missing remote directory leaves local state unchanged. The signer owns private-key operations; the app stores only session metadata needed to invoke it.

NIP-42 authentication is represented by `NostrRelayAuthenticator`, which signs
canonical kind `22242` drafts containing exactly the relay and challenge tags.
`NostrAuthEventValidator` verifies the returned event's id, signature, public
key, kind, timestamp, empty content, and exact challenge/relay context before
the relay client sends it. Challenges are authenticated lazily only after an
`auth-required` response, and each connection attempts authentication once and
retries the protected operation at most once after an accepted `OK`; without a
provider, auth-required responses remain failures and no challenge is
invented. `ExternalSignerNostrRelayAuthenticator` exposes one pending request
to the Activity-result bridge at a time, cancels it when the signer session
changes, and allows up to 90 seconds for user approval once auth signing starts.

After a signer session becomes active, `NostrProfileRepository` reads the account's cached kind `0` metadata event and refreshes it from the configured identity/directory relays. The complete event is cached by pubkey under `context.cacheDir/nostr-profiles/v1`; parsed `display_name` (falling back to `name`) is exposed to Home and Settings. Cache and relay failures do not invalidate the signer session or block kind `30045` directory synchronization.

## Backup and Device Transfer

The app opts into explicit backup rules for both supported Android generations. On Android 11 and lower, `full_backup_content.xml` excludes all shared preferences, databases, and the durable `filesDir/bookshelf` directory because that platform uses one ruleset for cloud backup and device transfer. On Android 12 and newer, `data_extraction_rules.xml` excludes those data classes from cloud backup while allowing user-controlled device-to-device transfer of the local bookshelf, reader preferences (including progress), and chapter relay settings. The signer session preference is not included in either path, so a restored or transferred install must reauthorize with the external signer. This preserves normal migration UX without placing identity or reading data in cloud backup; legacy devices deliberately favor privacy because their platform cannot express the distinction.

## Verification Notes

The Windows verification command and temporary Gradle-home cleanup procedure are documented in [`DEVELOPMENT.md`](DEVELOPMENT.md) and `AGENTS.md`. `ChapterSourcesTest` covers chapter relay URL rules, hint merging, and Nostr request construction. `ChapterLinkPolicyTest` and `TrustedCoverImagePolicyTest` cover untrusted navigation and automatic-cover URL validation. `CuratedShelfTest` covers catalog decoding and cache behavior, while `MercuryBookRepositorySearchTest` covers exact publication-coordinate lookup, independent-publication image and relay tags, partial 503 outcomes, the short search cache, and the no-chapter-fetch discovery invariant. `MercurySearchResilienceTest` covers attempt caps, Retry-After, non-503 behavior, and cooldown.

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
- [`decisions/0009-typed-explainable-mercury-search.md`](decisions/0009-typed-explainable-mercury-search.md)
- [`decisions/0010-resilient-mercury-search.md`](decisions/0010-resilient-mercury-search.md)
- [`decisions/0011-preferred-books-api-with-mercury-fallback.md`](decisions/0011-preferred-books-api-with-mercury-fallback.md)
- [`decisions/0012-nip42-relay-authentication.md`](decisions/0012-nip42-relay-authentication.md)
- [`decisions/0013-my-books-publication-relay-lookup.md`](decisions/0013-my-books-publication-relay-lookup.md)
- [`decisions/0014-publication-image-and-chapter-relay-hints.md`](decisions/0014-publication-image-and-chapter-relay-hints.md)

## Curated Discovery Shelves

The Home tab is driven by the checked-in editorial catalog in `data/discovery/CuratedShelfCatalog.kt`. Each shelf stores only its title, stable id, and ordered NIP-19 `naddr` publication references; displayed title, author, cover, and chapter-reference metadata always come from kind `30040` publication-index events.

`NaddrPublicationReferenceDecoder` validates each address as a kind `30040` replaceable publication coordinate. `MercuryApiClient.getPublicationsByCoordinates` resolves exact coordinates using grouped author and `#d` filters, batched at the Mercury filter limit. Shelf loading never requests chapter events or opens chapter WebSockets. Chapters are fetched only by `BookshelfViewModel.openBook` through the existing reader flow.

`ShelfMetadataCache` stores serialized publication-index summaries under `context.cacheDir/shelf-metadata/v1.json`. Entries are fresh for 24 hours. Cached summaries are rendered immediately, stale/missing coordinates then refresh, successful refreshes are atomically written, and stale entries remain available when Mercury is unavailable. The cache contains no chapter bodies or rendered HTML.
