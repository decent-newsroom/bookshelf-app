# ADR 0043: Content-only search and cohesive result cards

- Status: Accepted
- Date: 2026-09-27
- Supersedes: ADR 0041's combined-search toggle and prefix precedence while enabled.
- Preserves: ADR 0040's timeout policy and the existing parent-publication lookup.

## Context

Include book contents combines metadata and section channels, allowing metadata hits to dominate the displayed results. Short terms silently omit the section channel. Content match descriptions previously appeared outside the book card, obscuring their association with the publication.

## Decision

Rename the session-only toggle to Search book contents. Disabled searches default to metadata; enabled text searches use CHAPTER_CONTENT and never request metadata matching. Validate 4–160 characters before starting content requests. In this mode metadata prefixes such as title: and author: remain literal text, while an optional content: prefix is removed. Raw exact references retain their dedicated lookup behavior. With the toggle disabled, existing typed prefixes remain available.

Keep toggle changes separate from execution: only Search or keyboard submission starts work. Move the full-text length/latency hint from the input field to the toggle, and adapt the introductory description to the selected mode.

Display content results inside one card with a reusable book header, an internal divider, chapter title, bounded excerpt, and explicit Open matching chapter action. Header taps retain ordinary reader opening; long press retains the book menu. Ordinary metadata results use the compact book card. Choose the layout from result provenance, not the current toggle, because results can belong to an earlier submission. Missing chapter metadata does not invent excerpts or match locations.

Parent lookup, endpoint fallback, result verification, timeout values, cancellation, and search-cache policies remain unchanged. The backend index mapping was updated separately to support parent #a filters; this change does not attempt a client-side workaround for that lookup.

## Consequences

Content mode cannot be diluted by metadata results and rejects undersized terms instead of silently falling back to metadata. Existing scope-aware cache keys separate metadata and content requests. Exact reference lookup remains available, and the internal ALL scope is retained for existing callers/tests, although the toggle no longer selects it.

## Verification

Focused tests cover content-only query routing, metadata-prefix isolation, validation, exact references, and result presentation selection. Tests and builds remain owner-run under AGENTS.md; no Gradle commands are run by the agent.
