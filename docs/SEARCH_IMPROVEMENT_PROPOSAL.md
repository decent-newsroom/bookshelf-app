# Mercury search implementation record

Status: Implemented. The original 2026-08-28 proposal evolved through ADRs [0009](decisions/0009-typed-explainable-mercury-search.md), [0010](decisions/0010-resilient-mercury-search.md), [0039](decisions/0039-full-text-and-seeded-discovery.md), [0040](decisions/0040-full-text-search-timeout.md), [0041](decisions/0041-opt-in-book-content-search.md), and [0043](decisions/0043-content-only-search-and-result-cards.md). Current behavior is in [Architecture: Mercury Search](ARCHITECTURE.md#mercury-search).

## Delivered

- Typed query planning, exact coordinate/ID/NIP-19 routes, verified kinds, bounded excerpts, provenance, deterministic coordinate deduplication and internal rank fusion.
- Metadata-first explicit submission with a session-only **Search book contents** toggle. Enabled means chapter-only search; original combined-search/filter-chip designs are superseded.
- Bounded section-parent resolution and **Open matching chapter** through normal verified loading/rendering, with one-time target precedence over resume.
- Complete/partial/unavailable outcomes, latest-query cancellation, search-only concurrency/retry/cooldown, and complete-only short memory cache.
- Validated-connectivity gating; no query/history/excerpt persistence or full chapter loading during discovery.

## Verification checklist

Use deterministic signed fixtures/fake networking; automated checks must not depend on public services.

- Request fields/limits, prefixes with toggle on/off, four-character section threshold, exact references, and wrong-kind rejection.
- Unrelated/orphan parents, shared sections, newest revisions removing a match, deterministic ranking/provenance, and bounded excerpts.
- Peer/parent failures preserving partial results; successful empty versus unavailable.
- 503 attempt cap, Retry-After, jitter/backoff cancellation, cooldown, cache bounds/expiry, and latest-query-wins state.
- Offline zero remote discovery; matching-target precedence, reordered/missing chapters, cancellation, and rotation without repeated jumps.
- Discovery never fetches/renders whole books; saving retains summaries without query text.

Build/test and device execution remain [owner-run](DEVELOPMENT.md#build-and-test-ownership).

## Deferred and contract limits

Global language selection, exact-phrase/analyzer guidance, passage offsets, and pagination require confirmed backend support. The [historical Mercury 0.2.30 specification](Mercury/swagger.json) described phrase/word behavior but is not a guarantee for the preferred [Decent Newsroom API](references/decent-newsroom-books-api.json). Neither contract promises exhaustive totals or snippets. Client retries cannot resolve sustained server capacity problems.

Recommendations have their own [implementation record](plans/full-text-search-and-recommendations.md), cache, and resilience boundary.