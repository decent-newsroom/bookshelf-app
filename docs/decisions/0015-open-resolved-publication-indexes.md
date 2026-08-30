# ADR 0015: Open Resolved Publication Indexes Directly

## Status

Accepted

## Context

My Books can resolve a kind `30040` publication index from a verified known
bookshelf relay. An independent publisher's index and its kind `30041` chapters
need not be mirrored by Mercury. Re-fetching the saved index by event ID from
Mercury during reader opening therefore prevents chapter loading before the
publication's declared relay hints can be used.

## Decision

Open a `BookSummary` directly when the user selects it. The summary is the
already mapped, validated publication index and retains its ordered chapter
references and relay hints. Reader opening first queries those hints through
the chapter relay source, then keeps the existing Mercury fallback for chapter
references unresolved by relays.

The event-ID overload remains for callers that have only an event ID; it maps a
Mercury event and then follows the same summary-based path.

## Consequences

Saved books resolved from known relays no longer depend on a separate Mercury
copy of their index. The reader opens the specific resolved index selected by
