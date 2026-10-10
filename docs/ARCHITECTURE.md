# Architecture

This guide describes implemented behavior. The [decision index](decisions/README.md) records rationale and supersession; [implementation records](README.md#implementation-records-and-remaining-work) retain acceptance checks and deferred work. Build and device verification are [owner-run](DEVELOPMENT.md#build-and-test-ownership).

## Application Shape

Bookshelf is a native Kotlin/Jetpack Compose Android app. `AppGraph` owns process-wide dependencies; `BookshelfViewModel` coordinates UI state and use cases.

| Source boundary | Responsibility |
| --- | --- |
| `domain` | Nostr events and book/chapter models |
| `data/discovery` | Curated catalog, NIP-19 references, shelf metadata, seeded recommendations |
| `data/mercury` | REST access, publication mapping, chapter-source settings/retrieval |
| `data/rendering` | AsciiDoc rendering and HTML cache |
| `data/bookshelf` | Device-local saved books and kind-30045 directory rules |
| `data/reader` | Reader preferences and device-local resume/presentation |
| `data/reading` | Guest/account tracking, finished history, privacy, reading-event delivery |
| `data/ratings` | Rating normalization, aggregates/cache, review delivery |
| `data/nostr` | External signer, verified relay transport, directory/profile synchronization |
| `ui` | Compose shell and bounded feature screens; `ui/shell` owns Android signer/share/clipboard effects |

Shared controls and contextual tooltips live in `ui/components` and `ui/onboarding`. Settings and tutorials have dedicated UI packages.

System Back returns reader, loading, search, My Books, or Settings to Home. Returning Home clears transient navigation and cancels book opening; request guards prevent dismissed work from reopening a reader. The app shell retains Home's list position across reader visits.

## Nostr Event Trust Boundary

Mercury, relay, profile-cache, and signer-returned events cross `NostrEventVerifier` before use. It bounds fields/timestamps, recomputes the canonical NIP-01 ID, and verifies the Schnorr signature through Quartz. Callers check requested kind, author, event ID, `d` coordinate, and subscription context where applicable. Replaceable-event selection uses deterministic timestamp/event-ID ordering. Signer responses must match the pending draft and active account. Private-key operations remain in the external signer. See ADRs [0005](decisions/0005-verified-nostr-event-boundary.md) and [0006](decisions/0006-bound-untrusted-content-resource-use.md).

## Mercury Search

`BookSearchQuery` produces transient `BookSearchResult` values and complete, partial, or unavailable `BookSearchOutcome`s. Search has one field and a session-only **Search book contents** toggle, off by default. Changing it affects the next explicit Search/IME submission only.

- Default `METADATA` sends one publication `q` request. Structured prefixes select their field; `content:` selects chapter-only search.
- With the toggle on, `CHAPTER_CONTENT` requests only sections plus bounded parent resolution. Metadata prefixes become literal content text; an optional `content:` prefix is stripped.
- Exact IDs, coordinates, and kind-30040 `naddr` references keep dedicated routes. Coordinates use author plus `#d`, never a broad author window. Secure `naddr` relay hints join configured read relays, capped at eight, with parallel HTTP exact lookup.
- Text fields are bounded to 160 characters, language to 32, identifier to 512; section queries require 4–160 characters. Invalid input produces feedback rather than silently broadening a query.

The preferred base is `https://decentnewsroom.com/books/api`. Transport failures and HTTP 5xx may use the legacy Mercury HTTPS fallback; empty success and 4xx are authoritative. The HTTP base is never converted into a relay URL. Sections use a derived OkHttp client with a 120-second read timeout; other requests retain the shared 20-second read timeout. This is per-read, not an overall search deadline.

Only verified kind-30040 indexes and kind-30041 sections are mapped. Parents resolve through bounded reverse `#a` queries and must reference the matched section, including after newest-index reconciliation. Parent lookup failure is not successful empty search. Results retain provenance, chapter coordinate/title, and at most 320 excerpt characters from the returned verified section. An analyzed hit without a literal local match uses generic text-match wording. Content cards keep book and chapter context together; changing the toggle cannot relabel existing results.

Internal `ALL` combines channels with reciprocal-rank fusion (`k=60`), preserving channel rank, merging provenance, and keeping the newest index per coordinate. Search promises no analyzer/phrase semantics, pagination, exhaustive totals, or exact passage locations. Discovery never fetches/renders whole chapters.

Search resilience permits two active calls, retries HTTP 503 once with bounded backoff/jitter and `Retry-After`, and enters a five-second cooldown after repeated 503s. Non-503 failures are not retried. Successful branches survive peer failures with partial warnings. Only complete outcomes enter the process-memory cache: 30-second TTL, 20 entries. Queries, excerpts, and history are never persisted.

Validated connectivity gates HTTP and exact-reference relay discovery. Offline search retains visible or fresh memory-cached results without remote work. New submissions, dismissal, tab changes, and connectivity loss cancel pending work; coroutine cancellation reaches OkHttp through body reading and stops fallback.

**Open matching chapter** carries a transient coordinate through normal loading/rendering. An available target overrides resume at offset zero once per open-request token; rotation preserves the resulting position. Missing targets show a notice and use ordinary resume. Progress/recency are recorded after positioning. See ADRs [0009](decisions/0009-typed-explainable-mercury-search.md), [0010](decisions/0010-resilient-mercury-search.md), [0039](decisions/0039-full-text-and-seeded-discovery.md), [0040](decisions/0040-full-text-search-timeout.md), and [0043](decisions/0043-content-only-search-and-result-cards.md).

## Curated Discovery Shelves

`CuratedShelfCatalog` stores stable shelf IDs, titles, and ordered kind-30040 `naddr` coordinates. Displayed metadata comes from verified indexes resolved through batched author/`#d` HTTP filters. Shelves never request chapter bodies or chapter WebSockets.

`ShelfMetadataCache` displays cached summaries immediately, refreshes stale/missing coordinates, writes atomically, and retains stale data after failures. Its 24-hour cache contains no chapter bodies/HTML. See [ADR 0002](decisions/0002-cached-curated-discovery-shelves.md).

## Book Recommendations

Opening details loads **More like this** independently of profiles and ratings, using Home's carousel. Requests use the displayed index event ID with `POST /publications/recommendations` on the preferred API only; legacy capability is unverified. A raw array of verified kind-30040 events passes the shared publication mapper. Server rank survives coordinate/revision deduplication; search fusion and rating scores are not applied.

Seed and saved coordinates are filtered locally on every display, including other revisions; the saved library is not uploaded as exclusions. Short/empty responses are valid and never trigger fill loops. Seed-not-indexed 404, invalid input, and service failure have distinct states. Library cards open details rather than a reader.

`BookRecommendationCache` stores complete verified summaries, including empty successes, before local filtering. Keys include endpoint, seed ID, limit, and canonical explicit exclusions. Freshness is 24 hours; limits are 20 entries / 8 MiB total / 1 MiB each. Cached results display immediately; only missing/stale entries refresh online. Stale entries remain usable offline and after failure. Atomic writes, bounded decoding, and generation checks prevent corrupt or pre-clear work from restoring cleared data.

A separate one-request resilience controller provides bounded 503 retry/cooldown. Identical requests coalesce; the last departing subscriber cancels upstream work. Seed changes, dismissal, navigation, and connectivity loss cancel UI work with generation guards. Visible cards alone do not request recommendations. See [ADR 0042](decisions/0042-inline-book-details-recommendations.md), superseding ADR 0039's original presentation/cache policy.

## Book and Chapter Loading

My Books resolves kind-30040 coordinates through HTTP and known bookshelf relays, retaining the newest verified index. A resolved relay index need not be mirrored by Mercury before opening. An index without kind-30041 chapter `a` tags is a library card: discoverable, saveable, and rateable, without reader access.

Online `openBook`:

1. Uses the resolved index and ordered chapter references.
2. Queries `PersistentNostrChapterSource` by event ID and author/`#d`, merging newest revisions.
3. Uses Mercury HTTP only for unresolved references; failure preserves relay successes.
4. Retains explicit unavailable chapters to preserve ordering.
5. Renders/caches the loaded `BookDetail` before exposing it to the reader.

The opening screen shows summary, fitted trusted artwork, and full-title/author fallback without delaying loading. Completion, failure, or navigation clears transient loading state.

Saved books retain a self-contained reader snapshot after successful opening, containing ordered chapters, raw fallback and HTML, but no original signed events. Offline opens use the exact saved snapshot with no chapter relay/HTTP work. Online partial loads preserve readable cached chapters; only available fresh chapters replace them. Removing a saved book retains its snapshot. This bounded cache is explicitly clearable, independently of membership/progress. See [ADR 0030](decisions/0030-offline-reader-content-cache.md).

Details use summary fields and the verified index publisher's kind-0 profile. Explicit local broadcast re-fetches original verified index/chapter events and publishes them in index order only to the configured local relay; missing events are reported, never synthesized. See [ADR 0022](decisions/0022-local-full-book-broadcast.md).

## Relay Transport and Identity

Directory, profile, rating, and known-relay index traffic share the process-scoped Quartz `NostrClient` behind `NostrRelayClient`. Chapters use a separate Quartz client because settings, failures, and subscription lifecycles differ. Quartz owns sockets, protocol encoding, reconnects, subscriptions, and publish confirmations.

Chapter fetches multiplex subscription IDs, wait for selected relays' `EOSE` or timeout, then unsubscribe/send `CLOSE`. Peer failures preserve successful chapters. Each fetch reads current settings and appends normalized `wss://` publication hints within an eight-relay cap. Empty saved settings use defaults.

| Route | Built-in relays / scope |
| --- | --- |
| Directory/bootstrap | `wss://relay.decentnewsroom.com`, `wss://thecitadel.nostr1.com`, `wss://pipe.imwald.eu` |
| Chapters | `wss://mercury-relay.imwald.eu`, `wss://thecitadel.nostr1.com`, `wss://njump.me` |
| Profile-only addition | `wss://profiles.nostr1.com`, kind-0 lookup only |
| Optional local relay | Independently stored `ws://` or `wss://`; added to directory read/write routes, not chapter settings |

On login, verified active-user NIP-65 kind-10002 lists augment configured routes. Read/unmarked `r` tags serve directory/profile/index/rating reads; write/unmarked tags serve directory publication. Discovered `wss://` routes are bounded to twelve per role, session-only, and cleared on logout. Another author's profile/relay lookup never replaces active-user routing.

NIP-42 is lazy: unsolicited `AUTH` is stored without prompting. An `auth-required` response may request one validated kind-22242 signature and one protected-operation retry after accepted authentication. Relay/challenge, account, timestamp, empty content, ID, and signature must match. The signer bridge serializes requests, cancels on account change, and allows up to 90 seconds for approval. Directory publication waits up to 15 seconds per relay and reports bounded acceptance/rejection/auth/transport/protocol/timeout details to Settings and safe logs, without content or signer payloads. See ADRs [0012](decisions/0012-nip42-relay-authentication.md), [0017](decisions/0017-quartz-directory-relay-transport.md), [0018](decisions/0018-quartz-chapter-relay-transport.md), and [0019](decisions/0019-nip65-user-relay-routing.md).

## Saved Books and Reader Progress

`LocalBookshelfStore` atomically stores directory tags/summaries. Saving/removing commits locally before signer interaction; logout and relay failure preserve books. Directory sync publishes kind 30045 with one generated `client=Bookshelf` tag. **Sync to relays** retries the complete local directory; **Sync from relays** merges verified remote state without clearing local books on empty response.

`ReaderSettingsStore` separately stores appearance and coordinate-keyed resume metadata: bounded chapter index, nonnegative pixel offset, stable chapter coordinate, started/endpoint/cycle evidence, full section count, fingerprints, and activity timestamps. Legacy fields have defaults; index-only records resume at chapter top. Opening updates recency without starting a reading cycle.

One laid-out observation updates current chapter, physical resume, and explicit tracking projection. Chapter/started/endpoint changes persist immediately; pixel-only writes coalesce for 500 ms, with final actual location flushed on disposal. Footer cards retain the last real chapter's offset. Coordinate recovery handles reordered/missing chapters and invalidates stale endpoints after order/content changes.

Opening precedence: resolved explicit target; valid local reading location; active account's tracked section at offset zero; beginning. Merely opened zero-position records do not suppress tracked fallback. Remote snapshots never move an already-open reader or overwrite its bookmark.

A verified bottom requires known complete order, every body available, no truncation, positive laid-out viewport, and no forward scrolling. It displays 100% locally and may advance tracking only to `total - 1`; it never auto-finishes or sends `position = total`. Backward movement clears the local endpoint while tracking stays monotonic.

`BookReadingPresentation` resolves reader labels, Continue reading, and badges. Local evidence takes precedence over tracked fallback, including unknown fractions for incomplete content. Approximate chapter percentages cap below 100%. Finished history supplies completion until a new tracking cycle or genuine rereading; opening alone is not rereading. Finishing a reread clears cycle evidence while preserving bookmark/history. Stop tracking does not prevent local reread completion.

Eligible started covers, including unsaved books, receive a 26 dp bottom-right badge with 4 dp inset, opaque backing, 2 dp artwork margin, 3 dp round stroke, and neutral track. Zero uses an accent dot; partial an arc; unknown a muted dash; complete a filled circle, with a check for explicit history. Continue reading, opening artwork, and reader-header covers suppress badges. The composition root supplies resolved state; thumbnails perform no preference access, rendering, or relay work. Account handoffs suppress stale overlays while preserving device-wide resume. See ADRs [0032](decisions/0032-precise-reader-progress.md), [0046](decisions/0046-coordinated-reader-progress-and-thumbnail-indicators.md), and [0055](decisions/0055-cover-progress-badge-styling.md). Semantic anchors/text-weighted percentages remain [planned](plans/precise-reader-progress.md).

## Reading Lists and Finished History

`ReadingStateRepository` is independent of membership/local resume. Guest and signer accounts have separate partitions; login never silently transfers guest records. Cached summaries support unresolved offline entries. Tracking/history sharing are independent, default device-only, and require a signer for public mode. Settings previews selected existing entries; unselected entries stay private. Returning to device-only pauses undelivered events of that kind without retracting published data.

| Public event | Contract |
| --- | --- |
| Kind 16374 | One replaceable account snapshot; empty content, edition `a` tags, `client=Bookshelf`, `book` tuples `(edition, zero-based position, full total, optional section ID, updated timestamp)` |
| Kind 1985 | Explicit completion label; empty content, `L=ugc`, `l=read,ugc`, exact edition `a`, `client=Bookshelf` |

Choose the newest verified snapshot, never union older snapshots. Before publishing, refresh/rebase pending advances/resets/stops, preserving unrelated books and unfamiliar tags. Failed reads are not empty lists. Persist operations before signing and exact signed events before delivery. When both lists are public, each relay acknowledges the label before snapshot removal, including new destinations. Compaction retains the newest snapshot baseline, pending events, prerequisite labels, and latest label per edition. Disconnected concurrent writers still follow Nostr replacement ordering.

Full ordered metadata survives the 500-section body-loading cap; missing bodies retain ordinals. Unknown order disables tracking; truncated prefixes cannot expose end cards. Tracking advances immediately on genuine chapter transitions, publishes the latest pending position within a fixed three-second window from the first advance, and never decreases on backward scrolling. Reset sets zero, rejects stale advances, and waits for a new transition. Reset/Stop preserve bookmarks/local progress/history. Mutations are serialized and guarded by reader session, generation, and account. Reading-state/signing failures must not block local resume.

Remote reading work uses configured/active-user NIP-65 routes only online; explicitly public signed events may reach local Citrine offline. Device-only actions start no reading-event relay work. Foreground/account/connectivity recovery and manual Sync request refresh/retry. NIP-55 background signing requires permission; otherwise pending work waits for explicit Sync. Scrolling never launches foreground signer UI.

Explicit **Finish** after the actual final section confirms inline and removes tracking while preserving history. Signed-in readers get the shared inline review composer independently. Reading now includes every tracked book, with compact cover/title/menu cards and coordinate-specific Stop tracking. My Books is saved-only. Home ends with newest-first Finished covers in centered lazy rows of up to seven columns; unresolved entries offer resolution. Continue reading selects the most recently opened eligible saved book only when Reading now is empty; explicit rereading restores eligibility without erasing history. See ADRs [0044](decisions/0044-reading-lists-and-finished-history.md), [0051](decisions/0051-home-reading-history-and-saved-library.md), and [0053](decisions/0053-home-reading-now-actions.md).

## Rendering and Cache Invariants

Chapter content is AsciiDoc. Despite its name, `AsciidoctorChapterRenderer` uses Android-compatible `asciidoc-kmp`, producing CSS-free body HTML without JRuby/AsciidoctorJ. The reader converts fragments to native Compose `AnnotatedString`; `renderedHtml` is preferred, raw `content` is fallback. Sources above 2 MiB or fragments above 4 MiB are not cached/exposed as rendered HTML. Writes are atomic/serialized and pruned by access to 64 MiB / 1,000 entries.

Highlights select displayed text and persist UTF-16 offsets/contextual anchors. Unsigned, unqueued private highlights can be deleted; signed/queued events cannot be locally deleted while delivering. Publishing retains the private record and sends the immutable NIP-84 event through its outbox. Book-root NIP-22 tags are uppercase `A`/`K`/`P`; chapter-parent tags are lowercase `a`/`k`/`p`. See [threading reference](HIGHLIGHT_THREADING.md) and [ADR 0033](decisions/0033-private-highlight-deletion.md).

Highlight completion requires resolved remote routing, at least one destination, and independent acknowledgements for highlight and exact cited chapter at every destination. Duplicate acceptance counts as storage. Moving a resolved remote route to the local slot must not require a remaining remote slot; initial local-only delivery still waits for route discovery. Per-event failures persist independently. Durable writes emit revisions observed by Settings for live pending counts/details and retry. See [ADR 0045](decisions/0045-observable-highlight-delivery-status.md).

## Untrusted Content Navigation and Covers

`ChapterLinkPolicy` permits only absolute HTTPS destinations with host/no user-info, after explicit normalized-host confirmation. Cover `image` tags require HTTPS/host/no user-info and override inferred Gutenberg artwork; missing/rejected artwork uses fallback. See ADRs [0008](decisions/0008-untrusted-content-navigation-and-cover-privacy.md) and [0014](decisions/0014-publication-image-and-chapter-relay-hints.md).

## Book Ratings

`BookRatingsRepository` reads verified kind-34259 events through shared Quartz. Exact `a` or `A` kind-30040 coordinates associate all publications, including library cards. The app creates namespaced `d=<type>:<coordinate>`, matching `a`/`A`, `k=30040`, and author `p`; `d` is not a retrieval key. Typed targets must agree with the address/optional `m`; absent/blank publication type defaults to book. Normalized `rating` deliberately accepts `[0,1]` despite R1's strict interval; stars multiply by five, and nonstandard `s` never drives aggregates.

`BookRatingCache` keeps newest verified revisions per `(kind, pubkey, d)` with deterministic ordering. Startup compaction works offline; process-wide locking serializes cache/outbox merges. Visible reviews share revision identity; aggregates select newest per reviewer. Details/reviews show cache immediately, then refresh online without hiding data on failure. Offline reads/suggestions use only this cache and start no rating relay work.

Rating reads use configured plus active-user NIP-65 read routes, excluding the profile-only relay. Reviewer profiles load cached-first and refresh in batches of four with pubkey fallback. Profile reads never mutate active-user routes. Direct **Rate and review**, community ratings, and inline reader reviews share composer/signing/outbox behavior. Active-user cached prefill does not wait for remote work; dismissal/account changes cancel stale prefill.

Editing signs a new event for the same replaceable target, preserving exact score for opinion-only changes. Same-second edits wait for the clock; future-dated existing reviews produce a clock error. Cancellation preserves the existing review. Signed responses must match draft/account.

`ReviewOutbox` persists the immutable event before delivery, then merges it into the independent rating cache. Routes combine configured defaults, active-user NIP-65 write routes, and verified publication-author read routes. Local Citrine may receive the same event offline; remote work requires validated internet. Acknowledgements/retry metadata are per-route, with exponential delay capped at 15 minutes. Dispatch is process/ViewModel-driven on submission, online-state collection, and Settings retry, not an OS-scheduled worker. Recorded remote failures are not automatically removed on configuration changes. See [offline record](plans/airplane-mode-offline-review-sync.md) and ADRs [0026](decisions/0026-library-card-rating-address-tags.md), [0028](decisions/0028-offline-review-outbox.md), [0035](decisions/0035-cache-first-rating-revisions.md), [0036](decisions/0036-edit-own-book-reviews.md), and [0038](decisions/0038-active-user-relays-for-rating-reads.md).

## Incoming Nostr Book Links

Shared actions locally create stable `nostr:naddr` kind-30040 author/`d` links with an optional validated public `wss://` source hint. Private/local/HTTP hints are excluded. Android `ACTION_SEND` places only URI in `EXTRA_TEXT`, title in `EXTRA_TITLE`; Copy uses the same URI. Shell-owned effects report failures without changing book/reading data. See [ADR 0047](decisions/0047-book-link-sharing.md).

`MainActivity` registers exported `ACTION_VIEW` with `DEFAULT`/`BROWSABLE` for `nostr:`; Android owns chooser/default-app behavior. Strict parsing accepts opaque `nostr:naddr` kind-30040 coordinates within 4,096 characters, preserving exact `d` whitespace. Verified exact relay/HTTP resolution enters ordinary open/render/cache/resume; library cards open details. Invalid/not-found/unavailable outcomes have feedback.

The activity uses `singleTop` and an activity-scoped ViewModel. Initial intent is consumed once across rotation; every warm intent, including repeats, is new. Process recreation resolves the current intent afresh. New links, normal opens, navigation, and connectivity loss cancel/invalidate resolution. Offline resolution uses held/saved/curated/reading metadata or fresh exact-search cache, without remote discovery. Link handling does not auto-save, track, sign, or publish. HTTPS App Links, incoming `ACTION_SEND`, and NIP-89 announcements are not implemented. See [ADR 0054](decisions/0054-incoming-nostr-book-links.md).

## Settings, Appearance, and Help

Settings adapts specialized stores without owning canonical settings/queues; signer/directory coordination stays in `BookshelfViewModel`. Screens cover Reading & Display, Reading progress & privacy, Account & Sync, Discovery Sources, Nostr Relays, Storage & Offline, Tutorials, and About. Sources separate fixed HTTP APIs, ordered chapter relays, bootstrap/NIP-65 routes, and optional local relay.

Paper/Sepia/Night apply app-wide, including Material colors and edge-to-edge system-bar contrast before the first frame/after changes. Reader and preview share font/paragraph mapping; existing preferences default to Serif/Left.

Reader header/menu reuse navigation and unified progress controls. Back stays leading; Contents/Aa/Save wrap trailing. The menu adds a Highlights card, then progress/tracking with a larger gap and stacked full-width actions. One scrolling column is capped at the smaller of 75% of viewport and space above bottom navigation. Track/Reset/Stop/Sync retain state/feedback and at least 48 dp targets; no tracking sheet remains. Shared `BackCloseButton` supplies accessible icon controls. See [menu record](plans/reader-menu-layout.md) and ADRs [0050](decisions/0050-unified-reader-progress-controls.md), [0056](decisions/0056-tutorial-paging-and-shared-navigation.md).

Tutorials contain eight approved offline topics / 22 steps. Stable IDs and saveable pager/scroll state survive rotation. Swipes navigate and dots indicate the active step; there are no Previous/Next buttons. Each final action is centered below the lesson text in the scrollable body, above the dots. Close/Back return to origin. Typed final actions open existing screens with normal lifecycle; account navigation never auto-launches the signer. Tutorials have no durable completion state and remain separate from contextual tips. [Authoring and approved copy](tutorials.md) owns resource details; [ADR 0058](decisions/0058-tutorial-body-actions.md) updates the viewer presentation.

Each step also selects a typed, bundled component illustration between title and body. `TutorialExampleFrame` dispatches to feature examples that reuse production presentation with fictional `TutorialSamples` only: no ViewModels, repositories, network images, cache operations, signer calls, or persisted state. The frame isolates cover-progress composition locals, replaces child semantics with a localized description, excludes pictured controls from keyboard focus, and shields pictured controls from pointer hits without consuming ancestor pager/scroll gestures. Forms are read-only and illustrations have no nested scrolling or modal sheets. Optional local drawables remain supported, exclusively with a description. Shared extracted controls retain production defaults and caller-owned actions. See [ADR 0059](decisions/0059-tutorial-component-illustrations.md).

Contextual tips use stable app-private `OnboardingTipStore` flags initialized synchronously before reader composition. The menu tip records impression; Save/Remove records dismissal on Got it/timeout and leaving composition. Seen tips render only anchors; gestures cannot reopen them. Flags survive books/accounts/restarts/cache clearing. See [ADR 0057](decisions/0057-durable-reader-tip-dismissal.md).

## Persistence, Cache Clearing, and Connectivity

| Store / location | Role |
| --- | --- |
| `filesDir/bookshelf/local-v1.json` | Durable saved membership, tags, summaries |
| Reader `SharedPreferences` | Appearance, local resume/presentation metadata |
| `filesDir/bookshelf/reading-state-v1.json` | Durable guest/account lists/history, privacy, operations, reading outbox |
| `filesDir/bookshelf/review-outbox-v1.json` | Durable signed review delivery |
| Highlight store/outbox | Durable private highlights and signed delivery |
| `filesDir/bookshelf/reader-cache-v1` | Disposable bounded saved-book snapshots |
| `cacheDir/chapter-html` | Disposable rendered fragments |
| `cacheDir/shelf-metadata/v1.json` | Disposable curated summaries, 24-hour freshness |
| `cacheDir/book-ratings/v1/events.json` | Disposable verified rating revisions |
| `cacheDir/book-recommendations/v1` | Disposable ranked recommendations |
| `cacheDir/nostr-profiles/v1` | Disposable verified kind-0 events by pubkey |

Storage & Offline exposes independent HTML, reader-content, rating, and recommendation statistics. **Clear selected caches** confirms selection, clears sequentially, reports individual failures, and refreshes once with controls disabled during the batch. Clearing never deletes saved books, progress/history, highlights, or signed outboxes. Transient confirmations use the dismissible app snackbar, consumed after presentation.

`ValidatedInternetConnectivity` is the shared online authority: transport without validated internet is offline. Default-network callbacks apply only to their emitting network, preventing late Wi-Fi/cellular/VPN events from overwriting newer status. Offline preserves local edits and permitted local delivery without authorizing remote reads. See [ADR 0034](decisions/0034-default-network-connectivity-state.md).

## Backup and Device Transfer

Android 11 and lower exclude shared preferences, databases, and durable `filesDir/bookshelf` from shared cloud/transfer rules. Android 12+ excludes these from cloud backup but permits explicit transfer of selected bookshelf/reader/chapter-source state. Signer session preferences are excluded from both paths; transfer requires reauthorization. See [ADR 0007](decisions/0007-private-cloud-backup-with-explicit-device-transfer.md).
