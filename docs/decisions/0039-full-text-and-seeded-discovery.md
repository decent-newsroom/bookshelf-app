# ADR 0039: Full-text navigation and seeded discovery

- Status: Accepted
- Recommendation presentation and cache policy superseded by [ADR 0042](0042-inline-book-details-recommendations.md); other decisions remain in force.
- Date: 2026-09-26
- Extends: ADRs 0009 and 0010; recommendation traffic has a separate resilience controller.

## Context

The extended Books API returns signed publication sections for full-text queries and relevance-ranked kind 30040 events for a seed publication. The app already has search fusion, verified parent resolution, reader rendering, and independent rating policies. The new contract has no parent objects, snippets, pagination, similarity reasons, or personalized/language-filtered recommendations. Legacy recommendation endpoint compatibility is unverified.

## Decision

Keep typed search and existing metadata/section fusion. Enforce field-specific contract limits, validate parent membership, reconcile targets with the newest index, and report partial parent-resolution failures. Expose scope controls and a separate explicit chapter-open action. Carry only a transient chapter coordinate across normal load/render/cache; available targets override resume at offset zero for that open. Apply once per reader session, record progress/open time after layout, and fall back to resume with a notice if unavailable.

Use the primary API only for recommendations and require its documented raw-array response. Verify events and reuse the existing publication mapper. Preserve server ranking; filter the seed and saved coordinates locally without sending library IDs by default. Distinguish empty success, 400, seed 404, and unavailable service. Recommendations are an explicit book-details action, separate from ratings and curated shelves.

Use a five-minute, 20-key memory cache keyed by endpoint/seed/limit/exclusions. Cache successful empty results, never protocol/network errors. Refilter against current saved coordinates on display. Existing visible data may be retained with a stale notice after failure, but expired entries cannot serve new requests. Do not persist discovery history or introduce new cache-clearing controls.

Gate remote discovery with validated connectivity, including exact-reference search relays and retries. Give recommendations a separate one-request resilience controller with bounded 503 retry/cooldown. Coalesce identical requests; cancel when the final subscriber leaves. ViewModel request generations prevent late state updates. Connect coroutine cancellation to actual HTTP calls through response reads and stop fallback on cancellation.

## Alternatives and consequences

A persistent recommendation cache would improve offline discovery across restarts but adds storage, privacy, invalidation, and clear-during-refresh behavior. Defer it until needed. A combined similarity/rating feed would mix incomparable rankings; retain separate policies. Automatic recommendations for visible cards would add fan-out and ambiguous seeds; require the user to open More like this.

Legacy fallback remains for existing search paths, but enabling it for recommendations requires a confirmed compatible endpoint. Language filtering, exact passage jumps, and pagination need additional contracts or separately scoped work. Search and recommendations remain bounded discovery, not exhaustive offline indexes. Reader caches, ratings, signed event outboxes, and their clear actions remain independent.

## Verification

Added deterministic API request/status/protocol/cancellation, search parent/revision/offline, recommendation cache/coalescing/retry, and reader/presentation regression tests. Tests/builds are owner-run per AGENTS.md; no Gradle verification was performed by the agent.
