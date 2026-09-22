# ADR 0030: Cache reader content for saved books offline

- Status: Accepted
- Date: 2026-09-22

## Context

Saved-book membership survives restart, but it contains only publication metadata. Existing rendered chapter HTML lives in the disposable cache directory and has no durable index from a saved book to its reader content. Opening a saved book offline consequently starts relay and Mercury work, then presents unavailable placeholders even when usable chapter content was previously rendered.

## Decision

Persist an independent, versioned reader-content snapshot for each saved book under `filesDir/bookshelf/reader-cache-v1`. A snapshot contains the saved book identity, chapter ordering and metadata, raw chapter fallback content, and rendered HTML; it intentionally excludes the original signed source event. Use atomic replacement, schema validation, per-snapshot safety limits, and bounded retention. Corrupt entries are cache misses.

Use `ValidatedInternetConnectivity` as the application-wide gate for reader remote work. Offline opening of a saved book reads only its exact snapshot and never starts relay or Mercury work. Online opening uses cached readable chapters as a fallback for partial remote results, then updates the snapshot after rendering. Saving a currently open book stores its reader detail; removing a saved book intentionally retains its snapshot so saving it again can restore offline reading without another download.

The reader shows cached available chapters and expresses missing content once at reader level with source-neutral language. Contents preserves unavailable chapter positions but does not open placeholder chapter bodies.

Reader-content clearing is explicit and user-visible. It never removes saved-book membership, reading progress, highlights, review or highlight outboxes, or ratings data.

## Consequences

- Saved books are readable offline to the extent their content has previously been cached.
- Existing HTML-only cache files cannot be retroactively turned into reader snapshots; a saved book must open successfully once after this feature is installed.
- Reader caching is separate from chapter rendering, remote retrieval, and saved-book membership, keeping each lifecycle and clear action explainable.