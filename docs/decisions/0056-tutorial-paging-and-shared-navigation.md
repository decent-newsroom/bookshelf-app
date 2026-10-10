# ADR 0056: Tutorial paging, destination actions, and shared navigation controls

## Status

Accepted, 2026-10-09. Extends ADR 0049 and ADR 0052, superseding their button-based viewer presentation and explanatory-only exit restriction with swipes and explicit final destination actions. Their approved-copy, stable-ID, offline-content, and persistence boundaries remain accepted. ADR 0050's integrated reader controls remain accepted.

## Context

The user requested lightbulb tutorial icons, dots for page position, forward/back swipes, and an action on each tutorial's final page that opens the relevant part of the app. Settings and reader controls also need one reusable icon-only back/close style.

## Decision

- Use a lightbulb in the tutorial viewer header and Settings Tutorials entry. Render steps in a Compose `HorizontalPager` with active-page dots. Retain Previous/Next buttons as accessible alternatives to swiping.
- Preserve stable step IDs, saveable page selection, and per-step scroll restoration. Keep bundled content offline and independent of contextual onboarding seen-state persistence.
- Give each topic a localized final-page action and typed `TutorialDestination`. Start reading, highlights, and reviews open Home with Start reading so the user can choose a book. Search opens Search with Search now. Save and resume opens My Books. Offline reading opens Storage & Offline Settings. Tracking opens Reading progress & privacy Settings. Account connection opens Account & Sync Settings without automatically invoking the signer.
- Close and system Back retain the originating Settings path. A final action deliberately exits to its selected destination. Tutorial paging performs no reading-state mutation, signer access, outbox work, or network requests; destination screens retain their ordinary behavior when entered.
- Provide a shared `BackCloseButton` for back and close actions: a 48 dp tonal icon control, an auto-mirrored back arrow or close icon, and a localized accessible description. Reuse it in Settings index/subsection headers, tutorial exits, reader headers, menus, and sheet close controls. Callers retain their existing navigation and dismissal callbacks.
- Add no durable tutorial state, navigation store, network dependency, or new persistence format.

## Consequences

Tutorials support direct manipulation while remaining usable without gestures. Final actions bridge the explanation to an existing screen without choosing a book or changing account/reading state on the user's behalf. Shared controls keep touch targets, icon styling, and descriptions consistent while each screen owns its navigation semantics.

## Verification

Inspect catalog coverage and destination mapping for all eight topics, localized action and control descriptions, and preservation of step IDs and per-page scroll state. Verify that swiping only changes presentation and that account navigation does not invoke signer connection.

The user runs Gradle and device checks per AGENTS.md. On device, verify forward/back swipes, dots, Previous/Next, final actions, close/system Back from both catalog and contextual origins, rotation on a scrolled step, large fonts, TalkBack, and offline tutorial access. Check shared controls throughout Settings and reader UI without changing dismissal or reader progress behavior. Builds and device checks were not performed by the agent.

## Alternatives

Gesture-only paging would omit an accessible explicit navigation alternative. A generic final Done action would not connect lessons to the described activity. Repeating icon styling at each call site would allow touch targets and visual treatment to diverge. Automatically opening a particular book or signer from a tutorial would add effects beyond navigation.
