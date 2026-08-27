# ADR 0006: Bound Untrusted Content Resource Use

## Status

Accepted — 2026-08-27

## Context

Mercury responses, relay frames, Nostr event fields, search text, configured
relay URLs, AsciiDoc source, rendered HTML, and the chapter cache are influenced
by remote or user-provided input. Without application-level limits, a validly
signed author event or compromised upstream service could consume excessive
heap, parser CPU, connections, or cache storage.

## Decision

Apply limits before expensive work and at each persistence boundary:

- Mercury response bodies are limited to 8 MiB and relay messages to 2 MB
  before JSON decoding.
- Verified events allow at most 1,000,000 UTF-8 content bytes, 1,000 tags,
  20 elements per tag, and 4,096 UTF-8 bytes per tag element.
- Searches are limited to 256 characters. Publication mapping stops after 500
  unique chapter references.
- Chapter settings accept at most eight encrypted `wss://` relays with bounded
  URL and total input lengths.
- AsciiDoc sources larger than 2 MiB are not rendered. Rendered HTML entries are
  limited to 4 MiB, and the cache is pruned by last access to at most 1,000
  entries and 64 MiB. Writes use temporary files and atomic replacement where
  supported, with cleanup on failure.

## Consequences

Oversized input is rejected or falls back to raw chapter text rather than
entering expensive parsing or unbounded persistence. Limits are deliberately
conservative and may need measured adjustment for legitimate unusually large
books. Opening a book still renders its accepted chapters eagerly; moving to
on-demand rendering or enforcing a parser execution-time budget remains a
possible defense-in-depth improvement.
