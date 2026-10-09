# ADR 0051: Reading activity on Home and saved books in My Books

## Status

Accepted, 2026-10-09. Supersedes ADR 0044's My Books Saved/Reading/Finished presentation only.

## Context

Saved-library membership is independent from tracking and completion. My Books sub-tabs mixed these roles, while Reading now already had a place on Home. Completed books need a compact history view without duplicating full book cards.

## Decision

- My Books shows the saved library directly, with its existing count, empty state, and actions, without sub-tabs.
- Keep all tracked books in Home's Reading now, independent of saved membership.
- Place Finished after Home's discovery shelves. Hide the section when history is empty. Use lazy rows of 56 x 80 dp covers within Home's existing scroll container, with 12 dp horizontal gaps, responsive columns capped at seven, and centered rows, including incomplete rows.
- Order by descending `finishedAt`, then coordinate ascending as a stable tie-breaker. Fill rows left to right, then downward; the newest completion occupies the first position in the first row.
- Tap opens a resolved book and long-press offers its existing actions. Unresolved entries retain a placeholder and explicit metadata-resolution action. Cover labels and touch targets remain accessible.
- Derive this presentation from the existing active reading-state partition. Saved membership, finished timestamps, device/account ownership, sharing preferences, relay routes, caches, and outbox behavior retain their current boundaries.

## Consequences

My Books has one clear purpose, while Home presents active and completed reading separately. Finished history may grow, so lazy rows avoid composing every cover at once. Centering an incomplete row can place its first cover inward from the grid edge; the latest completion still comes first in reading order.

## Verification

Review callback wiring, empty-state behavior, descending ordering and tie-breaking, lazy row keys, and guidance text statically. Gradle builds and tests are run by the user under AGENTS.md. Device checks should cover narrow and wide widths, incomplete rows, missing artwork or metadata, TalkBack, long-press actions, account switches, and returning from the reader to Home's saved scroll position.

## Alternatives

Keeping the sub-tabs duplicates Home's active-reading view and blurs saved membership with reading activity. A full-card finished list takes more vertical space. A nested scrolling grid complicates Home's scroll behavior; rows in the existing feed keep one scrolling surface.
