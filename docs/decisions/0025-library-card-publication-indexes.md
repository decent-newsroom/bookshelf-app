# ADR 0025: Library-card publication indexes

- Status: Accepted
- Date: 2026-09-11

## Context

Some kind `30040` publication indexes intentionally have no kind `30041`
chapter references. They represent library cards for books under copyright or
not yet ported to Nostr. The previous index mapper rejected every such event,
which made the cards undiscoverable and unavailable for saving or rating.

## Decision

Accept a valid kind `30040` event even when it has zero parsed chapter
references. Expose it as a `BookSummary` with `chapterCount == 0`; the UI
labels it as a library card with full text unavailable. A library card may be
saved and opened through its details and ratings flows, but its reader action
stops before any chapter fetch or rendering work and explains that no full text
is available.

## Consequences

Discovery and exact `naddr` resolution can return library cards without
weakening validation of the index event itself. Reader state and progress
remain reserved for publications that have chapter references.