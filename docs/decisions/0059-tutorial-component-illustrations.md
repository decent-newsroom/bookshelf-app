# ADR 0059: Bundled tutorial component illustrations

## Status

Accepted, 2026-10-10. Extends ADR 0049's optional drawable illustrations and ADR 0052's approved offline catalog. ADR 0058's paging, restoration, and final-action presentation remain accepted.

## Context

The user requested graphics made from actual app components, approved a plan covering all eight existing tutorials, and requested implementation with supervised sub-agents. Small Compose excerpts can show the controls and highlight appearance readers will encounter while remaining consistent with production styling.

## Decision

- Map all 22 existing steps to a typed `TutorialExample`; enum entries own localized descriptions. Retain optional local drawables with required alt text, and reject simultaneous drawable/component illustration assignments.
- Render examples between title and approved body copy, in a consistent labelled frame that grows inside the existing page scroll. Preserve stable IDs, copy, navigation, restoration, and final destination behavior.
- Reuse production presentation components, extracting small inline controls where full screens or sheets own unnecessary scrolling/lifecycle behavior. Keep sample assembly in `ui/tutorials`; shared presentation stays in its feature package. Production wrappers retain their callbacks and default editability.
- Supply deterministic fictional books, chapters, highlights, reviews, and progress. Null image URLs prevent image loading; an empty cover-progress composition local prevents examples from reading actual reading state. Themes use the existing MaterialTheme without invoking application theme effects.
- Make examples illustrations only. Replace child accessibility semantics with the localized example description, exclude pictured controls from keyboard focus, use read-only fields, and place a transparent pointer-input sibling above pictured controls. The sibling owns the child hit path but does not consume events, preserving ancestor scroll/pager gesture handling.
- Do not construct services, ViewModels, stores, signers, network work, durable state, or publication queues in examples. Do not wire illustrated actions to production callbacks. Only the existing final destination action leaves the tutorial and uses a real screen's normal lifecycle.

## Consequences

Tutorials now demonstrate actual controls and one highlighted passage without storing screenshots or maintaining a parallel visual design. Shared presentations must continue to support both real feature wrappers and local examples. Some examples, particularly settings and review forms, are tall and scroll with the lesson. Interactive practice is outside this change.

## Verification

Catalog unit tests cover all 22 unique mappings, description references, and exclusive drawable/component rules. Static source/resource checks verify XML, resource resolution, fixture consistency, effect boundaries, and production defaults. These checks do not establish compilation or device behavior.

The owner runs Gradle and device verification under AGENTS.md. Check all topics offline, Paper/Sepia/Night, narrow layouts and large fonts, TalkBack descriptions without pictured action targets, keyboard traversal, swipe/scroll gestures starting over examples, rotation/scroll restoration, Close/Back, and final destinations. Confirm examples cannot modify books, preferences, highlights, caches, reading progress, or outboxes. Build and device checks remain pending.
