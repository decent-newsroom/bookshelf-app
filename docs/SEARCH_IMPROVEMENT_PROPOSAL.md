# Mercury Search Improvement Proposal

- Status: Partially implemented; reliability and UI follow-ups remain
- Date: 2026-08-28
- API reference: [Mercury/swagger.json](Mercury/swagger.json), Mercury Index-Relay 0.2.30

## Purpose

Improve search relevance and explainability while reducing avoidable Mercury API load and making searches useful during transient 503 Service Unavailable responses.

The typed query/result-model slice is implemented. ARCHITECTURE.md remains the source of truth for current behavior; see ADR 0009. Reliability, filters, and matched-chapter navigation below remain follow-up planning.

## Current Behavior

MercuryBookRepository.search builds a SearchPlan from raw text. An ordinary query may fan out into concurrent publication requests for q, title, author, identifier, d, subject, and language, plus section search. Matching kind 30041 sections are resolved back to kind 30040 publication indexes through bounded #a filters.

The repository deduplicates and ranks publications but returns only BookSummary, losing whether a result matched metadata, a chapter title, or chapter content. Advanced prefixes such as author: and language: exist but are not clearly exposed in the UI.

The parallel work uses a regular coroutineScope. One failed request, including one 503, can cancel the complete search even if another endpoint returned valid results. Superseded and repeated queries are not cancelled, cached, or globally concurrency-limited.

## Mercury Capabilities and Limits

Mercury 0.2.30 provides:

- POST /api/publications/search for q and structured title, author, language, subject, d, and identifier fields, with a result limit of 1-100.
- POST /api/publications/sections/search for kind 30041 title and body search. It supports case-insensitive matching, hyphen/space equivalence, AND matching for eligible unquoted words, and ranked or required exact phrases.
- POST /api/events/filter for bounded NIP-01 filtering, including #a and #d.

The specification has no cursor, offset, total count, highlights, or snippets. Search must remain a bounded result set. The app may derive a short bounded excerpt only from a verified matching section event already returned by search. True pagination needs a future Mercury API extension.

## Proposed Design

### 1. Typed Query Planning

Implemented in the first search-model slice: typed BookSearchQuery/SearchScope,
single metadata q plus eligible section request, exact coordinate routing,
expected-kind checks, deterministic merging, provenance, and bounded verified
section excerpts. Retries, cancellation, caching, and UI filter chips remain
unimplemented.

Replace repository-specific raw-text heuristics with a typed request representing:

- Free text and quoted exact phrases.
- Scope: all, metadata, title, author, subject, identifier, or chapter content.
- Optional language filtering.
- Exact event IDs and kind 30040 or 30041 coordinates.

Keep existing prefixes for advanced users, but expose common scopes as Compose filter chips. Handle malformed prefixes predictably instead of silently broadening them.

Before sending several structured fields together, confirm and test how Mercury combines them. Do not infer AND or OR behavior solely from the schema.

### 2. Lower Request Fan-Out

For ordinary free text, issue at most:

1. One metadata request using q.
2. One section request when the normalized term is at least four characters.
3. Bounded #a resolution batches only when returned section hits must be mapped to publications.

Do not send speculative title, author, slug, subject, identifier, and language requests for every query. Use structured fields only when explicitly selected or when the input is an unambiguous exact identifier.

Resolve exact publication coordinates with the existing author-plus-#d lookup instead of fetching an author's broad result window and filtering locally.

### 3. Explainable Results and Ranking

Return a transient BookSearchResult containing:

- The BookSummary.
- Match provenance: title, author, subject, identifier, chapter title, or chapter body.
- Best matching chapter coordinate and title when applicable.
- A resource-bounded excerpt derived from the verified matching section.
- Rank information used to merge result channels.

Preserve Mercury ordering inside each response. Merge metadata and section channels with deterministic rank fusion rather than unrelated absolute score constants. Deduplicate by publication coordinate, retain the newest valid replaceable event, and combine its match provenance.

Search must not fetch every chapter or render chapter content. BookshelfViewModel.openBook remains the full chapter-loading and rendering boundary.

### 4. 503 and Transient-Failure Resilience

Reducing fan-out is the primary load reduction. The first implementation slice should also:

- Supervise independent metadata and section branches so one failure does not discard successful results from the other.
- Cancel superseded work and allow only the latest submitted query to update UI state.
- Apply a small process-wide concurrency limit to Mercury search calls.
- Keep a short-lived, size-bounded in-memory cache for identical normalized queries; do not persist search history.
- Retry read-only search requests on 503 with a low attempt cap, exponential backoff, and jitter.
- Honor a valid Retry-After header.
- Never retry validation failures or non-transient 4xx responses.
- Apply a brief cooldown after repeated 503s to avoid a request storm.
- Show a non-blocking partial-result message when one branch succeeds, reserving total failure for searches with no usable result.

Retries must be cancellation-aware and must not outlive the active query. These changes reduce client-contributed load and visible failures but cannot fix sustained Mercury server capacity problems.

### 5. Search UI and Navigation

Expose All, Title, Author, Subject, and Inside books scopes plus an optional language selector. Explain quoted exact phrases and exact Nostr references without requiring advanced syntax.

Content hits should show the matching chapter title and bounded excerpt. Opening one should use the normal verified book-open path and then select or scroll to the matching chapter coordinate. Never derive a navigation target from untrusted HTML or a remote URL.

Distinguish complete results, partial results, Mercury busy/retrying, total unavailability, and a successful empty result.

## Delivery Slices

### Slice 1: Reliability and Request Reduction

- Typed query planning and reduced fan-out.
- Exact coordinate filtering and expected-kind checks at the Mercury boundary.
- Latest-query cancellation, supervised partial results, bounded 503 retry/backoff, concurrency limiting, cooldown, and in-memory caching.
- Existing result-card UI retained initially.

### Slice 2: Explainable Results and Filters

- BookSearchResult, provenance, bounded excerpts, and deterministic rank fusion.
- Search scope chips, language filtering, exact-phrase help, and partial-result messages.

### Slice 3: Matched-Chapter Navigation

- Carry an optional chapter coordinate through book opening.
- Select or scroll after loading and rendering.
- Continue persisting only BookSummary when saving a result, never query text or excerpts.

## Verification

Add deterministic tests for:

- Free-text and structured request bodies, quoted phrases, and the four-character section threshold.
- Exact event/coordinate routing and wrong-kind rejection.
- Deduplication, newest-event selection, provenance merging, and ranking.
- Partial results when either branch returns 503.
- Retry-After, attempt caps, cancellation during backoff, cooldown, and no retry for non-transient errors.
- Superseded queries, cache bounds/expiry, and latest-query-wins state.
- The invariant that discovery search never starts full chapter fetching or rendering.
- ViewModel state transitions and Compose filters, messages, excerpts, and matched-chapter navigation.

A live Mercury smoke test may be an explicit non-default check; unit tests must not depend on the public service.

## Acceptance Criteria

- A normal eligible query makes no more than two initial search requests.
- Section-to-publication resolution happens only for returned section hits.
- Identical queries inside the cache window do not duplicate network work.
- One endpoint 503 can still yield visible partial results when the other succeeds.
- Retries are capped, jittered, cancellation-aware, and respect Retry-After.
- Superseded searches cannot overwrite newer results.
- Common scopes do not require prefix syntax.
- Content hits identify a chapter and show only a bounded excerpt.
- Opening a hit uses the existing verified loading/rendering path.
- The UI does not imply unsupported pagination or a complete total count.

## Non-Goals

- Relay-side text search.
- Fetching or indexing entire books during discovery.
- Persisting queries, history, or excerpts.
- Infinite scrolling without a server cursor or offset.
- Treating client retries as a substitute for server capacity or observability work.
