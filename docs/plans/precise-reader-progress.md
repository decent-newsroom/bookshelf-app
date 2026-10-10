# Precise reader progress

Status: Physical chapter/pixel resume is implemented under [ADR 0032](../decisions/0032-precise-reader-progress.md). [ADR 0046](../decisions/0046-coordinated-reader-progress-and-thumbnail-indicators.md) adds chapter-coordinate recovery, fingerprints, verified endpoints, and coordinated presentation. Semantic displayed-text anchors and content-weighted fractions remain planned. See [current progress behavior](../ARCHITECTURE.md#saved-books-and-reader-progress).

## Delivered physical location

The existing private reader-preferences record stores a bounded chapter index and nonnegative pixel offset with defaulted legacy decoding. One laid-out observation updates progress/resume; chapter/endpoint changes persist immediately, pixel-only changes coalesce for 500 ms, and disposal flushes the latest actual position. Footer cards preserve real chapter offsets.

Stable coordinates recover reordered chapters; content/order changes invalidate stale endpoint evidence. Explicit navigation targets start at offset zero; ordinary opens prefer local reading evidence before tracked fallback. Opening alone is not started/reread evidence. Approximate chapter fractions cap below 100%; verified complete bottom or explicit finished history can supply completion.

Pixel offsets are layout-specific. Reflow after typography, width, orientation, or renderer changes can move the apparent text position, so physical resume cannot claim semantic precision.

## Planned semantic anchors

Add optional displayed `AnnotatedString` UTF-16 offset and bounded contextual recovery data, using highlights as a conceptual precedent without merging stores. Capture the leading visible text line; after rendering/layout, resolve its semantic position and translate it to an item-relative offset. Fall back to safe physical location, then chapter top if resolution fails.

Only complete readable content with a resolved anchor can support text-weighted whole-book fractions. Missing/truncated chapters or unresolved anchors retain honest chapter-local/approximate presentation. Store metadata only, never chapter text or HTML in progress preferences.

The work stays device-local, backward-compatible, independent of saved membership/caches/outboxes, and follows existing backup policy. Semantic anchors are not a Nostr synchronization feature.

## Acceptance checks

Delivered behavior: legacy zero-offset restore; same-layout resume; reordered/missing/out-of-range chapters; bounded writes/final flush; Contents/highlight/search targets; footer safety; initial 0%; last-chapter entry below 100%; complete versus missing/truncated endpoint; Reset/Stop/account changes preserving local bookmark.

Future semantic slice: reflow-stable leading-line recovery, changed-text contextual resolution, deterministic fallback, bounded anchor metadata, no text-weighted claim for incomplete content, and unchanged cache/backup/Nostr boundaries.

[Builds/tests and device checks are owner-run](../DEVELOPMENT.md#build-and-test-ownership). Update architecture, ADR status, and changelog when the semantic slice is implemented.