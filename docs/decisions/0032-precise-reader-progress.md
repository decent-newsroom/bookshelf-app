# ADR 0032: Persist a stable in-chapter reader location

## Status

Accepted — Phase 1 implemented; coordinate/semantic anchoring remains planned

## Context

ADR 0015 introduced durable chapter-level progress so Home can offer Continue reading. The reader is a continuous Compose `LazyColumn`, but stored state contains only a chapter index. It cannot restore a position inside a chapter, and its chapter-count fraction treats all chapters as equal and counts the active chapter as completed.

The app renders chapters into Compose `AnnotatedString` values and stores highlights using UTF-16 offsets with contextual anchors. Reader location must remain local metadata: it must not duplicate chapter bodies, participate in Nostr publication, or become coupled to reader-content and HTML cache clearing.

## Decision

Adopt a staged location model keyed by the publication coordinate:

1. Persist a clamped chapter index and a non-negative item scroll offset in pixels. This Phase 1 slice is implemented. A stable chapter coordinate for reorder-resistant recovery remains planned.
2. Persist new fields with serialization defaults in the existing private reader-preferences JSON. Existing index-only records therefore resume at the chapter top without a data migration or loss.
3. Coalesce scrolling updates and flush the latest observed location when the reader leaves composition. A progress write remains local reader state and continues to update the last-opened timestamp used by Continue reading. This Phase 1 lifecycle behavior is implemented.
4. Treat pixel offsets as layout-specific resume data. They improve same-layout resume but do not establish an exact global reading fraction.
5. Follow with an optional semantic displayed-text anchor—UTF-16 offset plus contextual recovery data—before exposing a content-weighted whole-book percentage. This remains Phase 2. If the detail is incomplete, truncated, or the anchor cannot resolve, show chapter-local state instead.

The fresh-state progress invariant is also implemented: a new reading location reports 0%, and entering the first chapter does not count it as completed. Phase 1 does not claim a precise whole-book percentage.

## Consequences

* Resume precision improves without changing network, Nostr, account, cache, or backup boundaries.
* The Phase 1 index and offset remain safe for legacy and unavailable content; a stable chapter coordinate is deferred for reorder-resistant recovery.
* Progress writes become more frequent than chapter transitions, requiring coalescing and a final lifecycle flush.
* Pixel locations can move relative to text after typography or viewport changes. Semantic anchoring requires rendered text and layout work, but is the durable route to reflow-stable resume and honest whole-book progress.
* Highlight anchors are a reusable conceptual precedent, not a reason to merge reader-progress and highlight persistence.

## Alternatives considered

### Keep chapter-only progress

This is simple and durable but loses the reader's place in long chapters and has inaccurate percentage semantics.

### Persist only a pixel offset and retain the existing global fraction

This improves resume cheaply but leaves percentage accuracy unchanged. The staged model allows it only as an initial resume mechanism.

### Persist a percentage of total scroll range

`LazyColumn` does not reliably know every chapter's measured height at once, and total pixel height changes with typography and viewport. It is neither a stable bookmark nor a trustworthy book fraction.

### Persist a semantic text offset immediately

This is the strongest long-term representation, but needs visible-line capture, contextual recovery after revisions, and post-layout restoration. Delivering physical positions first limits risk while preserving the migration path.
