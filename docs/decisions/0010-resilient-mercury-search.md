# ADR 0010: Resilient Mercury search

- Status: Accepted
- Date: 2026-08-28

## Context

Mercury intermittently returns HTTP 503 during discovery. Search contains
independent metadata and chapter-section branches, so cancelling the whole
operation on one transient failure discards useful results. Unbounded retries,
parallel requests, and repeated identical queries would also add load while
the service is already constrained.

The resilience policy must apply only to discovery search. Publication and
chapter loading have different lifecycle and fallback behavior and are not
implicitly retried by this decision.

## Decision

Mercury API errors retain HTTP status and parsed Retry-After duration.
MercuryBookRepository runs each planned search call through one process-wide
MercurySearchResilience instance. It permits two concurrent search calls,
retries HTTP 503 at most once with bounded exponential backoff and jitter,
honors Retry-After up to ten seconds, and opens a five-second cooldown after
three consecutive 503 responses. Cancellation propagates through semaphore
waiting and backoff. Other HTTP errors are not retried.

Independent branches are supervised. The repository returns BookSearchOutcome
with COMPLETE, PARTIAL, or UNAVAILABLE status; successful metadata or section
results remain usable when another branch fails. Legacy search methods still
throw when every planned branch is unavailable.

Complete normalized outcomes are cached in process memory for 30 seconds with
a 20-entry ceiling. Partial and unavailable outcomes are never cached, and no
query, excerpt, or history is persisted. BookshelfViewModel cancels the
previous search before submitting a newer query and when search is closed or
the user changes tabs.

## Consequences

Short outages cause fewer blank result screens and less client-generated
request amplification. Users may see a clear partial-result or busy message.
Repeated identical successful searches avoid network work inside the cache
window.

Search filter chips and matched-chapter navigation remain
separate UI work.
