# ADR 0053: Compact Reading now cards and direct tracking actions

## Status

Accepted, 2026-10-09. Extends ADR 0051's Home Reading now presentation and ADR 0050's tracking entry points.

## Context

Home's tracked-book carousel repeated detailed section progress and delivery status. Its book actions required a long press, and stopping tracking required opening the reader first.

## Decision

- Show Reading now as compact cards with a cover, title, and accessible three-dot action button in one row. Remove the detailed furthest-section label, progress bar, and delivery-status text from these Home cards. Retain the existing cover progress badge and reader tracking feedback.
- A single tap on the action button opens the same shared book actions sheet as long press. Tapping the card opens the book. Preserve metadata-resolution fallback for unresolved entries.
- For a book tracked in the active account partition, place Stop tracking before the other actions in the shared sheet, regardless of which surface opened it. Untracked books retain the existing menu.
- Stop the explicitly chosen coordinate through the existing coordinated reading action boundary, including pending-advance invalidation, serialization, and signer/account checks. Dismiss the sheet on selection. Reader Stop tracking uses the same path.
- Preserve device-local reading position, saved-library membership, finished history, and existing reading-state persistence, privacy, and synchronization behavior.

## Consequences

Home cards prioritize book identity and make actions discoverable. Detailed tracking feedback remains available in the reader. The shared sheet consistently exposes stopping for tracked books without requiring reader navigation or introducing a second menu.

## Verification

Statically review callback wiring, action order, active-account visibility, coordinate targeting, and retained coordination guards. Gradle verification remains owned by the user under AGENTS.md. Device checks should cover single-tap actions versus card opening, long press, narrow screens and large fonts, TalkBack, unresolved metadata, stopping with and without an open reader, and guest/account switching. Confirm stopping removes only the selected tracked entry and retains its bookmark, saved membership, and finished history.
