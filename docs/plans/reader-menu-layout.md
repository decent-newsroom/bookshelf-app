# Reader menu layout

Status: Implemented, 2026-10-10. Source/static verification only; owner builds, tests, and device acceptance checks remain pending.

## Goal

Make the reader controls read as a small menu with clear groups. Back belongs at the left of a navigation card; Contents, Aa, and Save/Remove belong at the right. Highlights belongs in a separate middle card that can grow to include comments, labels, and other social actions. Reading progress and tracking belong together in their own card, with more whitespace above it.

## Previous layout

- `ReaderControls.kt`: `ReaderControlsMenu` places Back, Contents, Aa, Save/Remove, and Highlights in one wrapping `FlowRow`, followed by progress controls, all inside one `Surface`.
- `ReaderHeader` repeats the first four actions and the progress controls above the book information. Its Save/Remove action anchors an existing onboarding tooltip.
- `ReaderScreen.kt` builds one shared `ReaderProgressControls` slot for both locations. The overlay is capped at 75% of the viewport height; its contents scroll. Bottom app navigation is separate.
- `ReaderProgressControls.kt` combines current position, one progress bar, tracking state, actions, and delivery feedback. [ADR 0050](../decisions/0050-unified-reader-progress-controls.md) requires position and tracking to remain together.

## Implemented layout

```text
  +---------------------------------------+
  | Back            Contents   Aa   Save  |  Navigation card
  +---------------------------------------+
                     12 dp
  +---------------------------------------+
  | Highlights                         >  |  Social menu card
  +---------------------------------------+

                     24 dp

  +---------------------------------------+
  | Reading progress                      |  Progress / tracking card
  | =================-------------------  |
  | Current chapter / position            |
  | Tracking / sharing status             |
  | [ Track progress                   ]  |  When untracked
  |                                       |
  | [ Reset tracking                   ]  |  When tracked
  | [ Stop tracking                    ]  |  When tracked
  | [ Sync                             ]  |  When applicable
  | Delivery feedback when relevant       |
  +---------------------------------------+
```

The sketch shows alternative action states together for explanation; the UI retains its current conditional visibility.

### Navigation card

- Keep the existing shared `BackCloseButton` and its accessible, auto-mirrored arrow. Pin it to the leading edge and top-align it when other actions wrap.
- Put Contents, Aa, and Save/Remove in a weighted trailing group, with end alignment on every wrapped line and a minimum 12 dp gap from Back. Preserve this action order.
- Use a consistent, theme-aware filled secondary treatment for the trailing actions so Save does not visually dominate navigation. Preserve the existing labels and Aa's accessible name, "Reader settings".
- Retain at least 48 dp touch targets, allowing height to grow with text. At small widths or large font scales, wrap only the trailing group; allow labels to wrap if an individual item cannot fit. Do not shrink text, truncate action labels, or introduce horizontal scrolling.
- Preserve the Save/Remove onboarding anchor and dismissal behavior in the header. Extract a shared action-card composable with a composable Save-action slot so the tooltip stays owned by the existing header.

### Social menu card

- Render Highlights as a full-width clickable menu row with a clear text label, minimum 48 dp target, button semantics, and a trailing chevron. Its existing callback opens the existing highlights sheet.
- Use a simple column of menu rows. Future Comments, Labels, and other social destinations can append rows with subtle dividers inside the same card.
- Include only Highlights in this change. Future entries do not require placeholders, new badges, counters, state, or navigation infrastructure now.
- Use the card itself as the row's visible background rather than placing another pill button inside it. Keep each future action independently accessible.

### Progress and tracking card

- Add a compact "Reading progress" heading and wrap the existing shared progress contents in one card. Preserve the single progress bar, primary local-position label, secondary furthest-tracked label, and sharing status.
- Replace the tracking action `FlowRow` with a vertical group of full-width controls: primary Track progress when untracked; secondary Reset tracking and Stop tracking when tracked; secondary Sync when currently applicable. Preserve theme-aware filled backgrounds, disabled-state visibility, and minimum 48 dp targets.
- Keep unavailable section-order guidance, public delivery status, syncing text, pending counts, and errors visible within the same card. Long status text must wrap.
- Keep current visibility and enablement rules and the existing callbacks. Reset and Stop preserve bookmarks and finished history; Track uses the latest valid section observation; reaching the end still requires explicit Finish.

### Card spacing and reader header

- Replace the overlay's one outer surface with a single scrollable column of independent surfaces. Retain 12 dp outer margins; start with 14 dp internal padding, 16 dp corner radii, and restrained elevation. Use `ReaderColors.controls` and the existing reader palette across Paper, Sepia, and Night.
- Use 12 dp between navigation and social cards and 24 dp between social and tracking cards. The gap above tracking must be outside the card, exposing the reader background.
- Keep one scroll container for the whole stack, without nested scrolling in individual cards. `ReaderScreen.kt` measures the bottom navigation height through `onSizeChanged` and caps the stack at the smaller of 75% of the viewport and the available height above bottom navigation. The app shell already applies system-bar padding, so no additional system insets are added. Short-landscape reachability still requires owner device verification.
- Reuse the navigation card and progress-card treatment in `ReaderHeader`, with a 24 dp gap between them. Preserve book artwork, metadata, warnings, ordering, and tooltip behavior. Highlights remains an overlay entry; this plan adds no social card to the header.

## Implementation record

1. `ReaderControls.kt` shares the navigation card and reader-themed card wrapper between the header and overlay, retaining callback and tooltip ownership.
2. The overlay composes navigation, the Highlights menu card, a larger spacer, and the progress card. Highlights retains its existing callback; Comments, Labels, and other future social entries remain deferred.
3. `ReaderProgressControls.kt` adds the heading and stacked full-width action group, retaining progress calculations and state-dependent feedback. The caller wraps it once per location.
4. `ReaderScreen.kt` continues to supply the shared progress slot, existing sheet/state callbacks, and menu visibility gestures, retaining content-list keys and position observation. It measures bottom navigation and caps the menu at the smaller of 75% of the viewport and the height available above navigation, without adding system-bar padding.
5. `CHANGELOG.md`, `docs/README.md`, and `docs/ARCHITECTURE.md` describe the new presentation. ADR 0050's unified progress/tracking decision and ADR 0056's shared Back/Close control remain applicable; no new architectural decision is introduced by the layout.

## Verification status

Verification during implementation is limited to source/static review and scoped whitespace checks. No Gradle commands were executed, as required by `AGENTS.md`. Builds, unit tests, and all runtime checks below remain for the owner, including narrow/large-text and short-landscape layout, theme contrast, TalkBack order, touch/scroll behavior, state-specific actions, and onboarding behavior. The layout has not been visually verified on a device or emulator.

## Verification and acceptance

- At ordinary phone size, the first card shows Back at the left and Contents, Aa, and Save/Remove at the right, with a visible flexible gap. Highlights is a menu row in its own card; tracking is a distinct card with visibly more space above it.
- At 320 dp width and 200% font scale, and with increased system display size, all labels and actions remain readable and reachable. Back stays separate; trailing actions wrap with end alignment. Check the longer Remove state as well as Save.
- In short landscape windows, the whole menu scrolls within its bound. Tracking actions and error text remain reachable above bottom navigation. Touches on card backgrounds, actions, gaps, and exposed chapter content must preserve expected menu/reading gestures without accidental chapter selection or progress movement.
- Verify Paper, Sepia, and Night for card/background contrast, text, progress, filled actions, and disabled Sync. TalkBack follows visual order, announces Back and Reader settings, and reaches every individual action; do not merge whole-card semantics.
- Verify untracked/tracked, guest/signed-in, device-only/public, unknown section order, syncing, pending, and error states. Confirm action visibility and callbacks match the existing behavior.
- Verify Contents chapter jumps, Aa settings, Save/Remove, Highlights opening and highlight navigation, existing Reset/Stop behavior, footer-card position, explicit Finish and its confirmation, and durable onboarding dismissal. Confirm overlay interactions do not reset the underlying chapter scroll position.
- Inspect the scoped diff for changes to callbacks, content-list identity, progress observation, persistence, networking, or outboxes. This work should remain a Compose presentation change; existing progress and tracking unit tests continue to cover the retained rules. Do not add implementation-mirroring unit tests for spacing or new test dependencies just for this layout.
- Builds, tests, and device verification are run by the owner under `AGENTS.md` and [Development Notes](../DEVELOPMENT.md#build-and-test-ownership). The agent may perform static review and scoped whitespace checks; it must not execute Gradle verification commands.

## Review basis

Two supervised subagent reviews examined the previous Compose structure and the proposed menu hierarchy during planning. Their recommendations were reconciled around one shared navigation card, an extensible social list, and the existing unified progress/tracking component. Implementation was delegated and supervised with separate work on the tracking actions, documentation, and source review. The main tradeoff is greater menu height from stacked tracking actions; the existing bounded single scroll container provides scrolling while reducing the scanning required by the previous button rows. Runtime reachability remains part of owner verification.
