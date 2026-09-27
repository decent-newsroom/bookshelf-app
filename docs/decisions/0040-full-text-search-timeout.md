# ADR 0040: Longer full-text search read timeout

- Status: Accepted
- Date: 2026-09-27
- Extends: ADR 0039

## Context

Full-text section search can require substantially more server processing than ordinary metadata lookups. It inherited the shared 20-second HTTP read timeout, which could abandon a slow query before results arrived. Reported empty searches have not been confirmed to result from client timeouts.

## Decision

Use a derived OkHttp client with a 120-second read timeout only for `searchPublicationSections`. Preserve the existing endpoint fallback interceptor and shared connection pool. Both the All scope's section channel and Inside books use this policy. Metadata searches, reverse-parent resolution, recommendations, and book loading keep their existing timeouts.

Keep connection timeouts, retry policy, and coroutine-to-HTTP cancellation unchanged. Dismissal, superseding searches, and connectivity loss can still cancel active work immediately.

## Consequences

Slow full-text queries have more time to complete without making all network operations wait longer. An unresponsive section request can occupy a search slot for longer. The read timeout bounds each stalled read, not the entire search; fallback and retry work can extend total elapsed time. Server-side timeouts and successful empty responses are unaffected.

## Verification

Inspect section-only client selection and existing cancellation/fallback paths. Builds and tests remain owner-run under AGENTS.md; no Gradle verification was performed by the agent.
