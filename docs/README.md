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
- [references/R1-ratings.md](references/R1-ratings.md) preserves the supplied R1 rating-event format for implementation reference.
- [`decisions/`](decisions/) contains architecture decision records (ADRs) explaining why consequential choices were made.
- ADR 0033 records private-highlight deletion and preserves the durable outbox boundary for signed events.
- ADR 0034 records default-network callback handling that keeps validated connectivity stable across transport handoffs.
- ADR 0035 records immediate cached-rating display, background refresh, and newest-only rating revisions.
- ADR 0036 records how an active signer edits their own rating while preserving its replaceable target and durable delivery.
- ADR 0009 records the accepted typed, explainable Mercury search boundary; ADR 0010 records search-only 503 resilience, partial outcomes, cancellation, and caching; ADRs 0017 and 0018 record the Quartz relay transport boundaries; ADR 0019 records NIP-65 user relay routing; ADR 0020 records Settings relay configuration; ADR 0021 records persistent contextual onboarding; ADR 0027 records reviewer-profile resolution without mutating active-user routing; ADR 0028 records offline review delivery; ADR 0029 supersedes ADR 0020 with the dedicated settings boundary; ADR 0031 caps and makes reader onboarding dismissible; ADR 0032 proposes durable in-chapter reader positions.

## Keeping These Notes Current

Update `ARCHITECTURE.md` when a change alters a component boundary, persistent data, network flow, lifecycle, or important invariant. Add or supersede an ADR when a decision has meaningful alternatives or tradeoffs that a future maintainer might otherwise revisit without context.

Documentation should describe the implemented state. Keep speculative ideas clearly labeled as follow-up work.
