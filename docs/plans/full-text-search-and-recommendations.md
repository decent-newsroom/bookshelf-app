# Full-text search and recommendations

Status: Implemented. The original 2026-09-26 integration uses the supplied [Decent Newsroom Books API 1.0.0 contract](../references/decent-newsroom-books-api.json). Build/test and device verification remain owner-run. Current behavior is in [search](../ARCHITECTURE.md#mercury-search) and [recommendations](../ARCHITECTURE.md#book-recommendations).

## Current decisions

- [ADR 0039](../decisions/0039-full-text-and-seeded-discovery.md): verified section-parent reconciliation, explicit chapter targets, separate seeded recommendation transport/resilience.
- [ADR 0040](../decisions/0040-full-text-search-timeout.md): 120-second section read timeout only.
- [ADR 0043](../decisions/0043-content-only-search-and-result-cards.md): metadata default, session-only contents toggle selecting chapter-only search, cohesive match cards; supersedes ADR 0041's combined mode and the original scope buttons.
- [ADR 0042](../decisions/0042-inline-book-details-recommendations.md): inline details carousel and independent persistent 24-hour cache; supersedes ADR 0039's separate sheet/five-minute memory cache.

## Contract boundaries

| POST endpoint under /books/api | Constraints | Result |
| --- | --- | --- |
| /publications/search | q/title/author/subject/d ≤160; language ≤32; identifier ≤512; limit 1–100, default 25 | Kind-30040 indexes |
| /publications/sections/search | q 4–160; limit 1–100, default 25 | Kind-30041 sections |
| /publications/recommendations | 64-hex seed_event_id; ≤100 exclude_ids before deduplication; limit 1–50, default 10 | Raw ranked index array |
| /events/filter | Limit 1–100; case-sensitive single-letter tag keys | Exact lookups / reverse #a parents |

The configured base ends in `/books`; client paths append `/api/...` once. Validate before networking, preserve exact-reference routing, and accept only bounded signature/kind-verified events. Search may use the legacy fallback; recommendations are primary-only. Seed 404 differs from empty success, validation, and service failure.

Search parent indexes must reference returned sections and retain the target after revision reconciliation. Matched navigation resolves coordinates after normal load/render/cache, applies once, then falls back safely if unavailable. Excerpts/queries remain transient; no full book is loaded during discovery.

Recommendations preserve server order through the shared mapper/deduplication. Seed/saved coordinates are filtered locally on each display; no library upload or refill loop. Cache successful verified responses before filtering, including empty lists. Persistent keys include endpoint, seed, limit, and canonical explicit exclusions; limits are 20 entries/8 MiB total/1 MiB each. Stale cache stays usable offline/after failure, and clear generations prevent old work repopulating it.

Separate one-request resilience/coalescing and subscriber cancellation keep recommendations independent of search/book opening. Validated connectivity, navigation/seed cancellation, and generation guards prevent stale updates. Opening details triggers the carousel; merely displaying cards does not.

## Verification checklist

Use signed fixtures, deterministic clocks, and fake networking:

- Final URLs/JSON; text 3/4 and 159/160/161, language 32/33, identifier 512/513; recommendation limits 1/50/51 and exclusions 100/101 before deduplication.
- Wrong signatures/kinds, malformed/raw-array response, seed 404, short/empty success, and primary-only recommendation routing.
- Unrelated/shared/orphan parents, duplicate revisions, removed targets, partial parent failures, and generic analyzed-hit wording.
- Toggle/prefix routing, partial warnings, offline zero HTTP/relay discovery, target precedence/rotation/missing chapters, and cancelled opens.
- Rank, local filtering after save changes, cache TTL/eviction/corruption/clear races, coalescing/last-subscriber cancellation, retry/cooldown isolation, and body-read cancellation.
- Details states, seed races, retry/dismissal, library cards, ordinary recommended-book opening, themes/large text/TalkBack.

Tests cover API/repository/cache/reader-target boundaries; static review is not proof of a passing build or device acceptance. Follow [owner-run verification](../DEVELOPMENT.md#build-and-test-ownership). Public-service smoke tests require explicit invocation and must not be unit-test dependencies.

## Deferred and API questions

Home recommendation/rating-suggestion rows, local full-text indexing, exact passage highlighting/offset jumps, and global language selection remain follow-ups. Confirm analyzer/phrase and multi-field semantics before advertising them. Snippets/offsets, pagination/totals, coordinate exclusions, reasons, and language filters need API support. Enable legacy recommendation fallback only after its capability/precedence are verified.