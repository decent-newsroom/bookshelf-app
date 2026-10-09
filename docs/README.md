# Bookshelf Engineering Notes

This directory records architectural context and implementation decisions that should survive individual tasks and code changes.

## Start Here

- [`ARCHITECTURE.md`](ARCHITECTURE.md) describes the current application boundaries, data flows, invariants, and operational notes.
- [`DEVELOPMENT.md`](DEVELOPMENT.md) covers local development, verification, and release mechanics.
- [`SEARCH_IMPROVEMENT_PROPOSAL.md`](SEARCH_IMPROVEMENT_PROPOSAL.md) records the Mercury search and 503-resilience refactor, with implemented and follow-up slices called out.
- [`QUARTZ_RELAY_CLIENT_MIGRATION.md`](QUARTZ_RELAY_CLIENT_MIGRATION.md) records the implemented Quartz migration for directory/profile relay transport.
- [`HIGHLIGHT_THREADING.md`](HIGHLIGHT_THREADING.md) defines the NIP-22 root and parent tags used to associate highlights with book indexes and quoted chapters.
- [plans/book-ratings-and-suggestions.md](plans/book-ratings-and-suggestions.md) records the proposed rating-event ingestion and high-rated-book discovery plan.
- [plans/airplane-mode-offline-review-sync.md](plans/airplane-mode-offline-review-sync.md) records the implemented offline cache policy, local Citrine review delivery, and deferred remote-relay synchronization design.
- [plans/precise-reader-progress.md](plans/precise-reader-progress.md) records the proposed staged upgrade from chapter-level reader progress to durable in-chapter positions.
- [plans/book-thumbnail-reading-progress.md](plans/book-thumbnail-reading-progress.md) records implemented endpoint detection, shared corner indicators, and cooperation between local chapter progress, Nostr section tracking, and scroll resume.
- [plans/reading-lists-and-finished-history.md](plans/reading-lists-and-finished-history.md) records independent local/public reading lists, completed-book labels, durable synchronization, and inline reader reviews.
- [plans/full-text-search-and-recommendations.md](plans/full-text-search-and-recommendations.md) records the implemented full-text search and seed-based recommendations integration, with deferred follow-ups.
- [references/R1-ratings.md](references/R1-ratings.md) preserves the supplied R1 rating-event format for implementation reference.
- [`decisions/`](decisions/) contains architecture decision records (ADRs) explaining why consequential choices were made.
- ADR 0033 records private-highlight deletion and preserves the durable outbox boundary for signed events.
- ADR 0034 records default-network callback handling that keeps validated connectivity stable across transport handoffs.
- ADR 0035 records immediate cached-rating display, background refresh, and newest-only rating revisions.
- ADR 0036 records how an active signer edits their own rating while preserving its replaceable target and durable delivery.
- ADR 0037 records the dedicated profile lookup relay added to current read routes for kind-0 metadata only.
- ADR 0038 records active-user NIP-65 read-relay routing for community rating lookups and recent-rating discovery.
- ADR 0039 records full-text chapter navigation, primary-only seeded recommendations, and independent discovery lifecycle/cache policies.
- ADR 0040 records the longer read timeout scoped to full-text section searches.
- [ADR 0041](decisions/0041-opt-in-book-content-search.md) records metadata-first search and the session-only Include book contents toggle.
- [ADR 0042](decisions/0042-inline-book-details-recommendations.md) records the details carousel, independent persistent recommendation cache, reading card, and direct review shortcut.
- [ADR 0043](decisions/0043-content-only-search-and-result-cards.md) records exclusive content search and cohesive chapter-match cards.
- [ADR 0044](decisions/0044-reading-lists-and-finished-history.md) records guest/account reading-state separation, explicit public sharing, replaceable snapshots, and ordered completion delivery.
- [ADR 0045](decisions/0045-observable-highlight-delivery-status.md) records live pending-highlight counts, per-event relay failure details, and completion when a resolved remote route becomes local.
- [ADR 0046](decisions/0046-coordinated-reader-progress-and-thumbnail-indicators.md) records coordinated local chapter/scroll progress, independent section tracking, and ring/filled cover indicators.
- [ADR 0047](decisions/0047-book-link-sharing.md) records local NIP-19 book-link creation, public-only relay hints, and OS share/clipboard effects.
- [ADR 0048](decisions/0048-progress-visibility-and-tracking-entry-points.md) records accessible cover badges, the three cover exceptions, and reader/Settings tracking entry points.
- [ADR 0049](decisions/0049-topic-tutorials.md) records the offline topic-tutorial catalog, reusable step viewer, contextual tracking explanations, and separation from persistent onboarding tips.
- [ADR 0050](decisions/0050-unified-reader-progress-controls.md) records compact tracking controls integrated into the reader position indicator and supersedes the separate reader tracking sheet and its tutorial return flow.
- [ADR 0051](decisions/0051-home-reading-history-and-saved-library.md) records saved-only My Books and Home's centered, newest-first finished-cover grid.
- [ADR 0052](decisions/0052-approved-focused-tutorials.md) records the eight approved, populated tutorial topics and preserved offline viewer and contextual Settings boundaries.
- [ADR 0053](decisions/0053-home-reading-now-actions.md) records compact Reading now cards, single-tap shared actions, and coordinate-specific Stop tracking.
- [Tutorials and authoring](tutorials.md) indexes the approved lesson copy and explains resource maintenance, catalog integration, and release verification.
- ADR 0009 records the accepted typed, explainable Mercury search boundary; ADR 0010 records search-only 503 resilience, partial outcomes, cancellation, and caching; ADRs 0017 and 0018 record the Quartz relay transport boundaries; ADR 0019 records NIP-65 user relay routing; ADR 0020 records Settings relay configuration; ADR 0021 records persistent contextual onboarding; ADR 0027 records reviewer-profile resolution without mutating active-user routing; ADR 0028 records offline review delivery; ADR 0029 supersedes ADR 0020 with the dedicated settings boundary; ADR 0031 caps and makes reader onboarding dismissible; ADR 0032 proposes durable in-chapter reader positions.

## Keeping These Notes Current

Update `ARCHITECTURE.md` when a change alters a component boundary, persistent data, network flow, lifecycle, or important invariant. Add or supersede an ADR when a decision has meaningful alternatives or tradeoffs that a future maintainer might otherwise revisit without context.

Documentation should describe the implemented state. Keep speculative ideas clearly labeled as follow-up work.
