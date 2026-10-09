# ADR 0055: Cover progress badge styling

## Status

Accepted, 2026-10-09. Supersedes the badge stroke, border, and state styling choices in [ADR 0048](0048-progress-visibility-and-tracking-entry-points.md). It does not change badge eligibility, progress calculations, accessibility semantics, or the three suppressed cover surfaces.

## Context

The earlier thick ring, outer border, and separating gap made the small corner badge visually heavy. Zero progress and unknown progress also need distinct visual states, while positive progress should remain legible at thumbnail size.

## Decision

- Keep the shared badge at 26 dp, inset 4 dp from the cover's bottom-right corner, with opaque theme surface backing. Use a 2 dp surface margin from cover artwork; remove the outer border and its border-to-ring gap.
- Draw a 3 dp round stroke with a quieter neutral track at 18% `onSurface`. For known zero progress, place a 4 dp accent dot at 12 o'clock. For positive progress, draw the corresponding arc from -90 degrees with round ends and no extra dot.
- For an unknown fraction, draw the neutral ring and a small muted dash at its center. At complete progress, draw a solid accent circle. Explicitly finished history retains its contrasting check.
- Never-started books have no badge. Keep the badge informational and preserve existing progress calculations, accessibility semantics, eligibility, and suppression on Continue reading, book-opening artwork, and reader-header covers.

## Consequences

The zero, unknown, partial, complete, and explicitly finished states remain distinguishable on small covers without changing progress meaning or introducing a new interaction. The shared badge remains suitable for the smallest existing thumbnail surfaces.

## Verification

Visual review should cover all badge states, light and dark themes, smallest cover thumbnails, and the three suppressed surfaces. No Gradle, build, or device checks were run for this documentation update.

## Alternatives

Keeping the outer border and wide stroke preserved the earlier contrast strategy but made the compact badge too prominent. A numeric label would take more space and imply precision beyond the chapter-based fraction.
