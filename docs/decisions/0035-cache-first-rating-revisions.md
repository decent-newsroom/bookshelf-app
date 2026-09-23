# ADR 0035: Show cached ratings first and retain only the latest revision

- Status: Accepted
- Date: 2026-09-23
- Supersedes: ADR 0023's rating cache and presentation behavior; preserves its aggregate and relay boundaries

## Context

Rating events of kind `34259` are parameterized replaceable events. A reviewer can revise a rating while keeping its `d` tag, and different relays may return several versions in any order. Waiting for relay completion before displaying a rating hides events already in the app cache and makes the details screen appear empty during slow or failed requests. Retaining every revision in the cache can also make an older review reappear.

## Decision

1. The rating details flow reads `BookRatingCache` first and presents its selected events immediately. When `ValidatedInternetConnectivity` reports online, it starts a relay refresh in the background and publishes a new visible list only if the selected event set changes. Relay errors keep the cached list visible. Offline rating reads and suggestions use only the cache and start no remote rating relay work.
2. Select one event per `(kind, pubkey, d-tag)` tuple before caching, listing, or aggregating. Prefer larger `created_at`; for equal timestamps, use event ID as a deterministic tie break. Duplicate or older relay results do not replace the winner. A newer revision overwrites the tuple's prior cached event, so the cache stores only the last known winner. At app startup, trigger a local background cache read to compact legacy cache files, including offline, without waiting for the ratings screen or contacting relays. Keep first-read compaction for any subsequent cache access, and serialize cache instances with a process-wide mutex so repository and outbox merges cannot restore older revisions.
3. Preserve ADR 0023's separate aggregate rule: after selecting revision winners, a book aggregate uses the newest valid rating per reviewer. Keep signed review outbox persistence and retry state independent of rating cache clearing, as required by ADR 0028.

## Consequences

- A previously seen review appears without relay latency, including when offline.
- The list and cache converge on the same revision winner despite relay result order and duplicate delivery. Legacy stale revisions are removed from the on-disk cache after startup compaction.
- A new rating or newer revision can update an open list; an unchanged refresh does not cause a list update.
- Explicitly clearing the disposable rating cache removes locally cached reviews, but does not remove signed reviews waiting in the durable outbox.

