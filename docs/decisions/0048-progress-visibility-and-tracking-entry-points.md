# ADR 0048: Progress visibility and tracking entry points

## Status

Accepted, 2026-10-09. Extends ADR 0046's indicator presentation and ADR 0029's Settings UI boundary. Supersedes ADR 0046's inclusion of opening artwork and the earlier thumbnail plan's inclusion of Continue reading and reader-header covers.

Reader tracking presentation is superseded by [ADR 0050](0050-unified-reader-progress-controls.md): the header and tap-to-show menu now share compact progress controls, replacing the tracking sheet, Aa shortcut, and long reader explanations. Cover presentation, Settings privacy controls, and tracking behavior remain accepted.

Badge styling is superseded by [ADR 0055](0055-cover-progress-badge-styling.md). The 26 dp size, 4 dp corner inset, opaque theme backing, informational behavior, progress semantics, and three suppressed surfaces remain accepted; the outer border, border-to-ring gap, and 4 dp stroke do not.

## Context

The 22 dp cover badge's roughly 2.3 dp progress stroke was hard to distinguish against artwork. Explicit tracking was only exposed in scrolling reader metadata, while the Aa sheet contained appearance controls and Settings buried reading privacy under Reading & Display. Automatic local resume and optional reading-list tracking were difficult to distinguish.

## Decision

- Render eligible badges at 26 dp with a 4 dp progress stroke, opaque theme backing, a contrasting 1.5 dp outer border, and a 1 dp separating gap. This styling is superseded by ADR 0055; the accepted size, backing, state semantics, and informational behavior remain.
- Hide the cover badge in Continue reading, book-opening artwork, and the reader header. Continue reading text and reader progress bars/labels remain. Suppression is local to the rendering call site; it never removes or alters the shared reading presentation or stored progress.
- Add Track progress to the reader's tap-to-show menu and a shortcut near the top of its scrollable Aa settings sheet. Both open the same scrollable tracking sheet. Transitioning from appearance settings closes that sheet and the overlay menu before exposing tracking.
- Reuse existing ReadingTrackingControls and ViewModel actions for Track, Reset, Stop, and Sync, including unknown-stream disabling and public-delivery status. Action rows wrap at large text sizes. Track uses the latest observation's tracking section (including the final section at a verified endpoint), falling back to its resume chapter and then current local progress, so header/footer indexes cannot manufacture a section.
- Explain automatic device-local resume separately from explicit furthest-section tracking, and explain that Reset/Stop preserve the bookmark and finished history. Show current progress, new-entry sharing policy, pending synchronization, and errors.
- Give Reading progress & privacy its own Settings index entry and screen. Move existing independent reading-list and finished-history privacy controls there without changing their guarded selected-entry preview, account ownership, or synchronization callbacks. Label each privacy switch as one full-row switch with a minimum 48 dp touch target.

## Consequences

Progress tracking is discoverable mid-book and in Settings. Existing stores, signed events, outboxes, connectivity gates, reading-state partitions, reset/stop semantics, and finished-history rules remain the canonical sources of behavior. No new setting or persistent record is introduced.

## Verification

Implementation is reviewed statically and scoped whitespace checks are run. Gradle builds/tests and device checks belong to the user, per AGENTS.md. On device, verify small covers and busy artwork across Paper/Sepia/Night; unknown/partial/completed/finished states; all three suppressed cover surfaces with progress text/bar retained; Track/Reset/Stop/Sync mid-book and after terminal cards; signed-out/offline behavior; sharing previews and account changes; scrolling sheets and wrapping actions at large text sizes; and TalkBack labels.

## Alternatives

A thicker ring alone would still blend into arbitrary cover colors. Hiding badges globally would lose useful shelf/list status. A global tracking switch would conflate automatic local resume with independently opted-in reading-list tracking. Duplicating privacy logic in the reader would introduce another account-sensitive publishing flow; the existing Settings flow remains responsible for changing sharing policy.
