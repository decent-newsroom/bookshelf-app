# ADR 0049: Offline topic tutorials for reusable explanations

## Status

Accepted, 2026-10-09. Extends ADR 0029's dedicated Settings boundary. Once the user supplies and reviews Tracking progress content, it conditionally supersedes ADR 0048's requirement to repeat the long tracking explanations in the sheet; tracking behavior and status/privacy information remain unchanged.

[ADR 0050](0050-unified-reader-progress-controls.md) supersedes the reader tracking-sheet tutorial entry point, return-to-sheet lifecycle, and requirement to retain long reader explanations until tutorial content is supplied. The offline catalog, reusable viewer, Settings entry points, and user-authored content requirement remain accepted; tutorial authoring is separate work.

## Context

Reading progress and privacy has important explanations, and the reader's tracking controls need the same guidance in context. Duplicating explanatory copy across Settings and the reader risks drift. The existing onboarding system serves brief, dismissible tips with persistent seen state; it is not a suitable content model for a navigable tutorial.

## Decision

- Provide a native, offline Tutorials catalog with the stable topics **Getting started** and **Tracking progress**. Topic IDs and localized string resources supply topic and step text. Steps may optionally include a local drawable and its alt text. The catalog currently contains empty step lists pending the user's content; the app must not ship with the unfinished catalog.
- Use one reusable viewer for Settings and contextual entry points. It displays one step per page, preserves step and scroll position across rotation, and supports Previous, Next, Done, Close, and system Back.
- List Tutorials in Settings immediately above About. Reading progress & privacy and the reader's tracking sheet open the same Tracking progress topic. Closing it returns to the exact originating screen or sheet.
- In the reader, show the viewer in a full-screen dialog over the retained reader composition. Close the tracking sheet while the tutorial is shown, then reopen it when the viewer closes.
- Tutorial presentation is explanatory only. It does not mutate reading progress or tracking, access the signer, create outbox work, or make network requests.
- Preserve the current long explanations in the tracking sheet while Tracking progress has no steps. Once it has content, the presentation can condense those explanations. Keep status, errors, and privacy confirmations visible in both presentations.
- Extend ADR 0029's Settings boundary with the catalog and viewer. This decision conditionally supersedes ADR 0048's repeated-long-explanation presentation only after the user supplies and reviews Tracking progress content; it does not change tracking operations or privacy/status behavior.
- Keep tutorials independent from ADR 0021's persistent contextual tooltip identifiers and ADR 0031's one-impression reader hint behavior. Tutorials introduce no seen-tip persistence.

## Consequences

Settings and reader entry points share a single offline explanation source, and future user-authored copy can be localized and reviewed independently of UI behavior. Until the user supplies and reviews content for both topics, this is infrastructure rather than a complete user-facing tutorial feature and must not be represented as release-ready tutorial content. Existing tracking explanations remain available during this phase.

## Verification

Review the catalog for exactly the two stable topics and confirm it contains no agent-authored step text or outline. Statically inspect that topic and optional image resources use localized text and that image alt text is available. Manually check that Settings places Tutorials above About; viewer navigation and Done/Close/system Back work; step and scroll position survive rotation; and both contextual links open Tracking progress and return to their exact origin. In the reader, check that the viewer covers the retained reader, temporarily closes the tracking sheet, and restores it on close. Verify that empty Tracking progress content keeps the existing explanations and that populated content selects the concise presentation while status, errors, and privacy confirmations stay visible. Confirm tutorial navigation produces no reading-state change, signer call, outbox entry, or network request. Source-level checks may be performed by the user; Gradle builds, tests, and device checks are run by the user as directed by AGENTS.md.

## Alternatives

Duplicating long explanations in each entry point would create inconsistent guidance. Reusing contextual tooltips would constrain tutorials to transient tips and couple them to persistent seen state. Loading tutorial content remotely would add connectivity and lifecycle behavior to information that should work offline.
