# ADR 0063: Exclude contained indexes from recommendations

- Status: Accepted
- Date: 2026-10-13
- Extends: [0042](0042-inline-book-details-recommendations.md), [0061](0061-nested-publication-indexes.md)

## Context

More like this can return an index already directly contained in the displayed publication. Event-ID filtering would still allow another revision of that contained index, and filtering only new responses would leave existing cache entries inconsistent.

## Decision

Derive excluded coordinates from the seed's direct kind-30040 child references. Filter recommendations by coordinate before applying the caller's result limit, preserving the remaining server order. Apply the rule to fresh results and all cached returns, including stale, offline, and refresh-failure fallback. Keep existing seed and saved-book display filtering.

Retain raw verified ranked summaries in the recommendation cache and shared in-flight results, applying the contained-index rule separately for each caller's seed. Preserve request coalescing, network interfaces, cache keys, and cache schema. Existing cache entries therefore acquire the rule when read without a migration.

Do not resolve deeper descendants or treat kind-30041 chapter references as contained publications. Do not make extra requests or refill shortened results; a short or empty result uses existing presentation states.

## Verification

Repository regression coverage checks direct children and alternate revisions, remaining server order, chapter references, unlisted deeper descendants, fresh and cached results, offline and refresh-failure fallback, and entirely excluded results. Gradle tests and builds remain owner-run under repository instructions.
