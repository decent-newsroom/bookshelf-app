# ADR 0042: Inline book details recommendations and independent persistent cache

- Status: Accepted
- Date: 2026-09-27
- Supersedes: ADR 0039's separate recommendation sheet and five-minute memory-only cache policy.

## Context

Book details should offer reading and related publications without a second discovery sheet. Reopening the same book should reuse recommendation results across process restarts, while cache clearing must remain independent of signed reviews and reader data.

## Decision

Place a publication type/chapter count/Read card before community ratings, retaining type and chapter count in metadata. Read uses the existing reader loading, rendering, and resume boundary. Publications with no chapter references cannot invoke Read. Place More like this between ratings and metadata, using the same horizontal carousel as Home. Tapping a readable recommendation opens the reader; a library card opens details. Long press opens its book menu.

Opening details initiates only that seed's recommendation load, independently of rating and publisher reads. Render cached summaries first. Keep primary-only verified API responses, server ranking, current saved-coordinate filtering, request coalescing, bounded retries, connectivity gating, and cancellation/generation guards. Visible cards alone do not initiate discovery. Seed changes, dismissal, and reader/navigation transitions cancel obsolete work.

Persist ranked summaries and fetch timestamps in `cacheDir/book-recommendations/v1`; store no chapter bodies. Keys include endpoint, seed event ID, limit, and normalized explicit exclusions. Freshness is 24 hours. Keep at most 20 entries and 8 MiB total, with 1 MiB per entry. Use atomic replacement, tolerate malformed entries, cache successful empty responses, and never cache failed responses. Expired entries remain available offline or after a failed refresh; they are refreshed only with validated connectivity.

Expose a separate Book recommendations count/size and clear action in Storage & Offline. Clearing removes memory and disk entries and advances a repository generation, preventing pre-clear requests from writing results back or becoming subscribers for later requests. Other cache slots, saved books, progress, highlights, and signed outboxes are unaffected.

Add Rate and review directly after See details. The composer reads the active signer's cached effective review without routing through the ratings list. Prefill is cancellable and guarded by book/account identity, and editing is disabled until it finishes. Existing review edits retain the exact normalized score and replaceable target; signed-event persistence and delivery are unchanged.

## Consequences

Recommendations now persist device-local discovery metadata until eviction, manual clearing, or Android cache reclamation. A 24-hour freshness window reduces repeated API work at the cost of less frequent ranking updates. Stale results remain explicitly marked when freshness cannot be restored. Loading one details seed avoids background requests for every carousel item.

## Verification

Focused tests cover persistence, expiry, successful empty results, offline/failure fallback, cache bounds/corruption, clear-during-refresh behavior, and cached review prefill. Builds and tests remain owner-run under AGENTS.md; no Gradle verification is performed by the agent.
