# ADR 0050: Unified reader position and tracking controls

## Status

Accepted, 2026-10-09. Supersedes ADR 0048's reader tracking presentation and ADR 0049's reader tracking-sheet tutorial entry point, return flow, and conditional long-explanation requirement. Settings privacy and tutorial infrastructure remain unchanged.

## Context

The reader displayed position and tracking in separate areas and exposed tracking both inline and in a dedicated sheet. The sheet repeated explanations and required another navigation step for actions already available in the reader. Transparent action buttons also conflicted with the project's filled secondary-action styling.

## Decision

- Use one reusable compact progress component in the book header and tap-to-show reader menu. It combines the existing position label and one progress bar with tracking state and actions. Remove the dedicated tracking sheet, separate menu shortcut, and Aa settings shortcut.
- Keep current chapter/device-local position as the primary label. When tracked progress differs, show the furthest tracked section as secondary text; backward reading must not relabel that furthest section as the current location. Retain the shared progress resolver's unknown-order, incomplete-content, endpoint, and finished-history rules.
- Show a filled Track progress action when untracked. When tracked, show concise Tracking and On this device/Public status with Reset tracking and Stop tracking using the shared theme-aware `SecondaryButton`. Show Sync where applicable and retain concise unavailable-order, syncing, pending-delivery, and error feedback. Actions wrap at narrow widths or large font sizes and keep accessible labels and at least 48 dp touch targets. Bound the menu height and allow scrolling to keep its actions reachable. Use visible backgrounds for the wrapping Aa theme/alignment controls and the inline Write/Edit review action. The shared secondary button retains its background while disabled, including Sync during synchronization.
- Reuse existing ViewModel callbacks and section observations. Track continues using the latest valid section observation even on footer cards; unknown section ordering keeps Track disabled. Reset and Stop preserve saved reading positions and finished history. Reaching the final chapter does not automatically finish a book.
- Remove long reader explanations and the tracking-sheet tutorial launcher. Tutorial authoring and changes to the catalog, viewer, Settings entry points, or content are outside this task. Existing Settings reading privacy controls remain responsible for sharing policy.

## Consequences

Position and tracking are available together without an extra reader sheet. This presentation change introduces no settings, persistence, signer, relay, or outbox behavior. Device-local resume and monotonic section tracking retain their independent meaning; short state labels keep sharing and delivery visible without duplicating tutorial copy.

## Verification

Statically review component reuse, action callbacks, state labels, button backgrounds, and removed sheet/shortcut references; run scoped whitespace checks. Gradle builds/tests and device verification are run by the user under AGENTS.md. On device, check Paper/Sepia/Night, tracked/untracked and device/public states, unknown order, backward navigation, footer cards and explicit Finish, offline/pending/error delivery, account changes, narrow screens, large text, and TalkBack labels. Settings tutorials and privacy navigation must retain their existing behavior.

## Alternatives

Keeping a shorter dedicated sheet would still split position from tracking and require extra navigation. A second tracking bar would compete with the current-position indicator. Moving all tracking to Settings would remove useful actions from the reading context. Tutorial content can be authored separately without delaying this reader simplification.
