# ADR 0009: Typed and explainable Mercury search

- Status: Accepted
- Date: 2026-08-28

## Context

Free-text discovery previously sent speculative requests for several
publication fields and returned only BookSummary, so users could not tell
why a result matched. Section search results are already verified Nostr
events and can provide a bounded explanation without loading a book.

## Decision

Represent search input with BookSearchQuery and SearchScope. Ordinary
all-scope searches use one publication q request plus an eligible section
request. Structured scopes select one publication field, while exact
publication/chapter coordinates resolve through author-plus-#d filters.
MercuryApiClient enforces expected kinds at the response boundary.

Return transient BookSearchResult values containing the BookSummary,
merged MatchProvenance, optional chapter coordinate/title, and a
maximum-320-character excerpt from the verified section event. Merge channels
using reciprocal-rank fusion (k=60) that preserves each Mercury response's
ordering. Saved books continue to persist only BookSummary.

## Consequences

Search uses fewer requests and can explain chapter matches without fetching
or rendering complete chapters. The current UI displays the provenance,
chapter title, and excerpt. Retries, caching, latest-query cancellation,
filter chips, and matched-chapter navigation remain follow-up work.
