# Full-text search and book recommendations

- Status: Implemented in the working tree; owner-run build/test verification pending.
- Date: 2026-09-26
- Contract: supplied Decent Newsroom Books API Swagger 2.0, version 1.0.0, base path `/books/api`.
- Scope: native Android integration. No backend implementation, local full-text index, or new AI service.

## Approach and evidence

Finish the existing full-text search experience, then add a separate, explicitly seeded **More like this** feature. Search already uses the publication and section endpoints; its main gaps are contract alignment, correctness, scope controls, and chapter navigation. Recommendations are a new integration.

Two subagent reviews covered search/navigation and recommendations/ratings. The find-skills workflow identified the [Android Mobile Design skill](https://raw.githubusercontent.com/wshobson/agents/main/plugins/ui-design/skills/mobile-android-design/SKILL.md); its [Compose guidance](https://raw.githubusercontent.com/wshobson/agents/main/plugins/ui-design/skills/mobile-android-design/references/details.md) informs accessible controls, state hoisting, lazy lists, and theme reuse. Existing project conventions take precedence over generic examples. No additional skill was installed.

## Implementation record

All five delivery slices below are implemented. The publication mapper is reused by exposing the existing repository mapping function internally, rather than creating a duplicate mapper. Validation, verified parent reconciliation, scope controls, explicit chapter navigation, memory-only recommendations, and lifecycle cancellation are wired into the app. See ADR 0039 for the accepted boundaries.

Added API, repository, cache/coalescing, reader-target, and presentation regression tests. Static cross-review was completed by the delegated agents; Gradle tests/builds and device checks remain owner-run. The delivery-slice text below preserves the original design and acceptance checklist; it is not a claim that device acceptance checks have been executed.

## Existing behavior to preserve

- `MercuryApiClient` already implements publication/section search, exact lookup, signature/expected-kind verification, and parent lookup using `POST /events/filter` with `#a`.
- `MercuryBookRepository` already merges metadata and section channels with reciprocal-rank fusion, coordinate deduplication, bounded excerpts, partial outcomes, and complete-only memory caching (30 seconds, 20 entries).
- `MercurySearchResilience` already limits search concurrency to two, retries 503 once with bounded backoff/Retry-After, and applies a short cooldown.
- `SearchScreen` already displays matching chapter titles and excerpts. It lacks scope controls and drops the chapter target on open. The ViewModel suppresses partial warnings when results exist.
- `readerContent.open` loads/renders/caches before exposing reader content. Saved offline snapshots and ordinary resume behavior remain authoritative.
- `BookSuggestionPolicy` and `BookRatingsRepository.recentlyHighlyRated()` exist but have no production UI caller. Home currently shows curated shelves and Continue Reading.

## Contract boundaries

| Endpoint under `/books/api` | Request constraints | Result |
| --- | --- | --- |
| `POST /publications/search` | q/title/author/subject/d <=160 characters; language <=32; identifier <=512; limit 1-100, default 25 | kind 30040 indexes |
| `POST /publications/sections/search` | required q 4-160 characters; limit 1-100, default 25 | kind 30041 sections |
| `POST /publications/recommendations` | required 64-hex seed_event_id; <=100 supplied exclude_ids before deduplication; limit 1-50, default 10 | relevance-ranked, coordinate-deduplicated kind 30040 indexes |
| `POST /events/filter` | required limit 1-100; case-sensitive single-letter tag keys | use kind 30040 and `#a` for section parents |

The configured base is already `https://decentnewsroom.com/books`, and the client appends `/api/...`. Test the final URL; do not duplicate `/api`.

This specification does not promise search analyzer/phrase semantics, match fields, snippets, scores, cursors, offsets, or totals. Do not transfer the older Mercury analyzer guarantees into new UI copy without checking the new backend. Recommendations use subject similarity and exact author matches, with no language filter or personalization. Short and empty results are valid.

## Slice 1: Contract and transport foundations

Files: `data/mercury/MercuryApiClient.kt`, `BookSearchModels.kt`, `MercuryBookRepository.kt`, `AppGraph.kt`, and contract tests.

1. During implementation, preserve the supplied specification as `docs/references/decent-newsroom-books-api.json` and use it for request fixtures. Retain the older Mercury reference for fallback compatibility.
2. Replace generic 256-character validation with shared field-specific rules. Show actionable errors before networking; never silently drop an invalid field and broaden a query. Keep exact ID/coordinate/NIP-19 routing separate from text limits. Retain the existing two-character metadata UI minimum as a product rule; Inside books requires four.
3. Add a serializable recommendations request and client method. Normalize valid IDs to lowercase, reject invalid IDs/counts/limits, and validate exclusion count before deduplication. Serialize only documented fields.
4. Give recommendations a primary-only endpoint policy initially: legacy recommendation support is unverified. Preserve search fallback behavior. An unsupported legacy 404 must not mask a primary 503.
5. Connect coroutine cancellation to OkHttp Call cancellation in touched request paths, propagate CancellationException, and stop fallback/retry after cancellation. Review current blocking execute/catch-all handling; cancelling UI jobs alone must not leave unnecessary HTTP work running.
6. Preserve bounded response reads and event verification. Use typed, bounded error handling for 400, seed-not-found 404, 503, other network/protocol failures, and successful empty lists. Avoid displaying arbitrary server error text directly.

Acceptance: fixture tests prove final URLs/JSON, field boundaries, signature/kind rejection, error distinctions, and cancellation. Recommendations never reach the legacy endpoint.

## Slice 2: Full-text resolution correctness

Files: `MercuryBookRepository.kt`, `BookSearchModels.kt`, `MercuryBookRepositorySearchTest.kt`.

1. Retain All = one metadata request plus one eligible section request; Inside books = section only. Resolve parents only for returned section coordinates, in bounded `#a` batches. Never fetch/render complete chapters during discovery.
2. Require each returned parent to reference a requested matched chapter. Remove the current fallback that assigns unrelated returned parents a section rank.
3. Deduplicate section revisions deterministically while retaining channel rank. Deduplicate publications by coordinate and keep the newest valid index. Reconcile chapter targets against that final index so a removed chapter cannot remain actionable.
4. Treat section retrieval plus parent resolution as one usable channel. Failed parent resolution is not successful empty search. Preserve successful metadata/parent batches as partial results; report unavailable when no usable channel succeeds. Successful orphan lookup may yield no book results without claiming no section matched.
5. Keep bounded plain-text excerpts from verified returned sections only. Retain legitimate analyzed hits even without a literal local match; use generic text-match provenance instead of inventing precise match locations.
6. Preserve RRF, complete-only caching, query privacy, and result bounds. Do not imply exhaustive results: section, parent, and final-result caps can omit matches.

Acceptance: tests cover unrelated parents, shared chapters, duplicate revisions, removed chapter refs, orphan hits, and parent failures with/without metadata results.

## Slice 3: Search UI and matching-chapter navigation

Files: `ui/search/SearchScreen.kt`, `ui/BookshelfViewModel.kt`, `ui/BookshelfApp.kt`, `ui/reader/ReaderScreen.kt`, and a transient reader-open target model.

1. Hoist selected scope into UI state. Add All, Title, Author, Subject, and Inside books controls. Keep explicit Search/IME submission, not requests per keystroke. Recognized advanced prefixes override the selected scope for that submitted query; exact references retain existing routing.
2. Retain title/excerpt presentation, add honest provenance, and show partial warnings alongside results. Distinguish validation, loading, empty, partial, offline, and unavailable. Use accessible selected-state labels, 48dp touch targets, stable keys, and existing theme.
3. Add **Open matching chapter**, carrying `ReaderOpenTarget(chapterCoordinate)`. Ordinary book opening still resumes. Library cards keep details/save/rating behavior without opening the reader.
4. Resolve the coordinate after normal load/render/cache completion. A valid explicit target overrides resume for that open at offset zero. Apply once per open-request token; recomposition must not repeat the jump.
5. Match by coordinate rather than stale position. Missing/unavailable target: show a notice and use ordinary resume behavior. Do not write progress before successful positioning. Closing/superseding an open clears the transient target.
6. Gate discovery requests with `ValidatedInternetConnectivity`, before HTTP or exact-reference relay work. Offline show existing visible or still-valid memory-cached results with an offline notice; otherwise explain that discovery needs internet. Opening cached hits follows current saved-book offline rules. This is not offline full-text indexing.
7. Defer a global language selector because sections cannot enforce it. Existing metadata language syntax stays metadata-scoped; never imply it filters section results.

Acceptance: tests cover selected request scope, partial warnings, explicit-target precedence, reordered/missing chapters, cancelled opens, and zero remote discovery requests offline. Query text/excerpts stay transient and are not added to persistent history or SavedState.

## Slice 4: Recommendation repository and lifecycle

Files: new `data/discovery/BookRecommendationRepository.kt`, recommendation models/cache, shared publication mapper extracted from private `MercuryBookRepository.mapIndexEvent`, and `AppGraph.kt`.

1. Extract/reuse the existing publication mapper, with regression coverage for search, curated books, and library cards. Avoid duplicate metadata interpretation.
2. Use the displayed book's event ID and coordinate as seed. Preserve server rank; do not apply search RRF, rating rankings, invented scores, or per-item explanations. Defensively deduplicate coordinates while retaining first returned rank and selecting a deterministic valid revision.
3. Filter the seed and current saved-book coordinates locally, including other revisions. Default to no library IDs in exclude_ids: local filtering avoids sending the saved library and accepts shorter results. The client supports explicit exclusions without automatically uploading library data. Never loop to fill a short list.
4. Proposed initial cache: process-memory only, 20 request keys, five-minute TTL, default request limit 10. Key by endpoint, seed event ID, limit, and canonical explicit exclusions. Cache verified mapped results before saved-coordinate filtering; reapply current filtering when displayed. Cache successful empty results, never errors. Keep already-visible data marked stale after refresh failure; expired entries cannot serve a new request.
5. Gate all HTTP on validated internet. Offline use available in-session data only; app restart has no recommendation cache. No disk schema, Storage controls, reading-history persistence, or outbox interaction in this version.
6. Use a separate recommendation resilience policy/instance: one active recommendation request, at most one 503 retry, bounded Retry-After/backoff, and cooldown. Reuse tested mechanisms behind an explicit boundary if useful; do not silently broaden search's shared cooldown or book-opening retries. Do not retry 400/404 or use discovery-relay fallbacks.
7. Coalesce identical in-flight requests. Cancel on seed changes, dismissal, or loss of validated connectivity; guard UI updates with seed/request-generation tokens. Recommendations must not block reader/details loading.

Acceptance: tests cover rank, IDs/coordinates, filtering after saved-state changes, empty/short responses, cache identity/TTL/eviction, seed 404, offline zero HTTP, stale-response races, and retry/cancellation bounds.

## Slice 5: More like this UI and documentation

Files: book-details components in `ui/books`, `BookshelfViewModel.kt`, `BookshelfApp.kt`, and a bounded recommendation list component.

1. Add a user-triggered **More like this** action in book details, opening a list labeled with the seed title. Do not request recommendations for every visible card.
2. Give the surface independent loading, results, empty, offline, seed-unavailable, and retry states. A 404 means that event is not indexed; never silently change to a different seed revision. Empty success is not failure.
3. Reuse cards/save/details actions and normal `openBook`. Failures must not hide details, ratings, curated shelves, or readable content.
4. Keep rating suggestions separate and unchanged; do not label similarity results personalized or highly rated. Offline rating reads/suggestions still use only `BookRatingCache`. Signed-review delivery and all outboxes stay independent.
5. When implementing, update `ARCHITECTURE.md`, the search proposal's implemented/follow-up status, and `CHANGELOG.md` Unreleased for every feature/fix. Add or supersede ADRs for endpoint capabilities, recommendation cache/resilience/connectivity, and transient reader-target precedence. Do not describe proposals as current behavior.

Acceptance: UI/manual checks cover accessibility, large text, themes, A-to-B seed races, dismissal while loading, retry, library cards, save changes, and recommended-book opening online/offline.

## Delivery and verification

Implement slice 1 first. Search slices 2-3 and recommendation slices 4-5 can then proceed independently after agreeing shared client/mapper interfaces and ownership of ViewModel edits. Each slice should remain independently reviewable.

Extend `BookSearchModelsTest`, `MercuryBookRepositorySearchTest`, `MercurySearchResilienceTest`, reader progress tests, and `ReaderContentCoordinatorOfflineTest`; add focused API, recommendation, and UI-state tests. Use signed fixtures, fake networking, and deterministic clocks. Include text boundaries 3/4 and 159/160/161, language 32/33, identifier 512/513, recommendation limits 1/50/51, exclusions 100/101 before deduplication, invalid signatures, malformed bodies, and cancellation during HTTP/backoff.

The owner runs Gradle tests/builds using `docs/DEVELOPMENT.md`; the agent must not execute Gradle verification. A later explicitly requested live smoke test should check a content-only match, parent resolution, an indexed seed, and valid empty recommendations. Unit tests must not depend on the public service.

## Deferred work and API questions

- Persistent offline recommendations, a Home recommendation row, and a separate Recently rated highly row follow after the details-based feature is validated.
- Local full-text indexing, exact passage highlighting, and character-offset jumps are separate projects; this plan navigates to the chapter.
- Confirm new-backend analyzer/phrase semantics and multi-field combination rules before advertising advanced search behavior.
- Parent coordinates, snippets/match offsets, pagination/totals, language filters, coordinate exclusions, and recommendation reasons would improve a future API. They are not prerequisites for this bounded first version.
- Enable legacy recommendation fallback only after capability and precedence are verified.

## Repository scope

Implementation updates the scoped Android code, regression tests, API reference, architecture, ADR 0039, and Unreleased changelog. Unrelated pre-existing `TICKETS.md` and `AGENTS.md` work is preserved.
