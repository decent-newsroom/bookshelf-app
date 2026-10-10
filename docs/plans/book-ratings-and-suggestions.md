# Book ratings and suggestions

Status: Ratings, cache-first display, review creation/editing, and durable delivery are implemented. `BookSuggestionPolicy` and `BookRatingsRepository.recentlyHighlyRated()` exist without a production discovery UI caller. This replaces the original proposal's stale d-only lookup and publisher-write-route descriptions. See [Architecture: Book Ratings](../ARCHITECTURE.md#book-ratings).

## Current contract

- Kind 34259 events cross the shared verifier before parsing. One exact kind-30040 coordinate in `a`/`A` associates a book, including library cards. Queries use address tags, not `d`.
- Publishing retains a namespaced `d=<type>:<coordinate>` alongside matching address tags, `k=30040`, and publisher `p`. Typed d targets and optional m must agree with the address/namespace. Stars derive from normalized rating × 5; nonstandard s is preserved only.
- The app deliberately accepts inclusive `[0,1]`, differing from [supplied R1 text](../references/R1-ratings.md). Revisit if upstream confirms that excluding endpoints is intentional.
- Cache revision identity is `(kind, pubkey, d)`; aggregates retain newest per reviewer with deterministic ties. Opinion-only edits preserve exact score/target.
- Reads use configured and active-user NIP-65 read routes. Publication adds active-user write and verified publication-author **read** routes. The profile-only relay does not join rating reads.
- Offline reads/suggestions use only `BookRatingCache`. Signed reviews persist before delivery; local Citrine can receive them offline, with remote retries gated by validated internet. Cache clearing preserves the outbox.

Rationale: ADRs [0023](../decisions/0023-book-rating-events-and-suggestions.md) (historical proposal), [0026](../decisions/0026-library-card-rating-address-tags.md), [0028](../decisions/0028-offline-review-outbox.md), [0035](../decisions/0035-cache-first-rating-revisions.md), [0036](../decisions/0036-edit-own-book-reviews.md), and [0038](../decisions/0038-active-user-relays-for-rating-reads.md).

## Suggestion policy and follow-ups

The existing policy requires mean normalized rating ≥ 0.800 (4 stars), at least three effective ratings, and one retained rating within 90 days. It orders by mean, count, and recency. This is rating-based discovery, independent of the seeded More like this API.

A production suggestions surface, metadata resolution, explicit bounded refresh/paging, trust/mute/block policy, and any Bayesian ranking remain follow-ups. Do not describe these as delivered or fetch unbounded global history.

## Verification checklist

Check score boundaries/precision and malformed/conflicting targets; newest revision and reviewer deduplication; cache startup compaction/concurrent merges; 4-star/three-review/90-day policy boundaries; cache-first/offline zero relay reads; profile failures; active-user route isolation; composer prefill/cancellation/account changes; opinion-only edits and clock handling; durable immutable retries; cache clearing preserving pending reviews.

Fixtures and device checks should distinguish no ratings, no written opinions, stale cache, partial delivery, and unavailable refresh. [Builds/tests are owner-run](../DEVELOPMENT.md#build-and-test-ownership).
