# ADR 0052: Approved focused tutorial content

## Status

Accepted, 2026-10-09. Extends ADR 0049's catalog and supersedes its two-topic, empty-content restriction and requirement for user-authored copy with user-approved copy. ADR 0050's integrated reader controls remain accepted.

[ADR 0056](0056-tutorial-paging-and-shared-navigation.md) extends the viewer with swipe paging, dots, shared icon controls, and explicit final actions to existing app screens. The approved copy, stable IDs, offline-content, and persistence boundaries remain accepted; paging itself retains the no-effects boundary.

## Context

The offline tutorial viewer had two empty topics awaiting content review. The user requested shorter tutorials, combined book and chapter search, combined the first reading experience with reading comfort, and asked for inviting copy that includes highlights and reviews. The user approved the resulting eight tutorials and authorized integration.

## Decision

- Bundle eight topics in this order: Start reading (`getting_started`), Search (`search`), Save a book and return to it (`save_and_resume`), Read offline (`offline_reading`), Keep the lines you love (`highlights`), Share your take on a book (`reviews`), Track a book (`tracking_progress`), and Connect your account (`connect_account`).
- Retain the two original topic IDs and enum names, including `TutorialTopic.TrackingProgress` for the existing Settings help link. Each topic has two or three stable, topic-local steps, for 22 steps in total.
- Keep the approved Markdown copy under `docs/tutorials/` and its runtime copy in Android string resources. Topic titles, summaries, and all step text use resource references, with paragraph breaks preserved. No illustrations are needed for this version.
- Reuse the existing catalog and viewer for Settings entry points, paging, rotation restoration, and exit behavior. Tutorials remain explanatory and offline, without reading-state changes, signer access, outbox work, network requests, or seen-tip persistence.
- Populated Track a book content activates the existing concise Reading progress & privacy Settings presentation. Sharing controls, delivery status, errors, and privacy confirmations remain independent of tutorial content. Do not restore the reader tracking sheet or its tutorial launcher.
- Require user review for future copy additions and substantive revisions. The friendly tone must retain exact action labels and clear private/public sharing semantics.

## Consequences

The catalog now contains approved content rather than empty placeholders. Readers can choose a small lesson for the task at hand, including the enjoyable activities of highlighting and reviewing. Markdown and resource copy must stay synchronized. Approval and source checks do not replace build or device verification before release.

## Verification

Statically check the eight topic IDs, catalog coverage and order, unique step IDs, 22 non-empty title/body pairs, Android/XML escaping, and exact agreement with approved Markdown. Confirm the Settings tracking link retains its target and operational privacy/status UI remains outside the tutorial-content condition.

The user runs Gradle and device checks under AGENTS.md. On device, check all eight topics offline; large font sizes and TalkBack; Previous, Next, Done, Close, and system Back; step and scroll restoration through rotation; and returning from Track a book to the exact Settings origin. Tutorial navigation must not change reading progress, open the signer, enqueue signed events, or contact relays.

## Alternatives

A single long introduction would make readers page through unrelated tasks. Separate book and chapter search lessons would split the modes of the same Search control. A separate reading-comfort lesson would interrupt the first reading experience. Remote tutorial content would introduce unnecessary connectivity and lifecycle behavior.
