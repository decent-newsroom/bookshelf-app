# Reader menu layout

Status: Implemented, 2026-10-10. Source/static verification only; owner builds, tests, and device acceptance remain pending. This is Compose presentation work, preserving [ADR 0050](../decisions/0050-unified-reader-progress-controls.md)'s unified progress behavior and [ADR 0056](../decisions/0056-tutorial-paging-and-shared-navigation.md)'s shared navigation.

## Layout and ownership

| Card | Contents |
| --- | --- |
| Navigation | Leading Back; trailing Contents, Aa, Save/Remove, wrapping with end alignment |
| Highlights | Full-width existing Highlights action with trailing chevron |
| Reading progress | Single bar, current/furthest position, tracking/sharing feedback, stacked full-width actions |

Navigation-to-Highlights gap is 12 dp; gap above progress is 24 dp. Cards retain reader theme, 14 dp internal padding, 16 dp corners, and 12 dp outer margins. The header reuses navigation/progress with a 24 dp gap and preserves the Save/Remove onboarding anchor; it has no social card.

`ReaderControls.kt` shares navigation/card wrappers; `ReaderProgressControls.kt` owns progress contents and stacked actions. `ReaderScreen.kt` supplies existing callbacks/progress slot and measures bottom navigation. One scrolling column is capped at the smaller of 75% of viewport or available space above navigation, without duplicate system insets.

Back uses accessible `BackCloseButton`; other actions have theme-aware filled treatment. Labels may wrap, with at least 48 dp targets. Track/Reset/Stop/Sync keep existing state rules, disabled backgrounds, unavailable-order guidance, and delivery feedback. Reset/Stop preserve bookmarks/history; reaching the end still requires explicit Finish. Comments/Labels and other social entries remain deferred.

## Owner acceptance checklist

- Normal phone: leading Back, end-aligned navigation, separate Highlights card, larger gap above progress.
- 320 dp width, 200% font scale, increased display size, and short landscape: all labels, tracking actions, and errors remain reachable. Check Save and longer Remove states.
- Paper/Sepia/Night: card contrast, progress, filled/disabled actions. TalkBack order matches visual order and reaches individual controls.
- Untracked/tracked, guest/signed-in, device/public, unknown order, syncing/pending/error: visibility and callbacks retain behavior.
- Contents/Aa/Save/Highlights, highlight jumps, Reset/Stop, footer position, explicit Finish, and durable onboarding all work without resetting chapter scroll.
- Touches/scrolling on cards, gaps, and exposed content preserve expected reader/menu gestures.

No Gradle or device verification was performed during implementation. Follow [owner-run verification](../DEVELOPMENT.md#build-and-test-ownership); do not add implementation-mirroring spacing tests or dependencies for this layout.