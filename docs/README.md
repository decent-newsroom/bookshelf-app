# Bookshelf Engineering Notes

This directory records architectural context and implementation decisions that should survive individual tasks and code changes.

## Start Here

- [`ARCHITECTURE.md`](ARCHITECTURE.md) describes the current application boundaries, data flows, invariants, and operational notes.
- [`DEVELOPMENT.md`](DEVELOPMENT.md) covers local development, verification, and release mechanics.
- [`SEARCH_IMPROVEMENT_PROPOSAL.md`](SEARCH_IMPROVEMENT_PROPOSAL.md) records the Mercury search and 503-resilience refactor, with implemented and follow-up slices called out.
- [`decisions/`](decisions/) contains architecture decision records (ADRs) explaining why consequential choices were made.
- ADR 0009 records the accepted typed, explainable Mercury search boundary.

## Keeping These Notes Current

Update `ARCHITECTURE.md` when a change alters a component boundary, persistent data, network flow, lifecycle, or important invariant. Add or supersede an ADR when a decision has meaningful alternatives or tradeoffs that a future maintainer might otherwise revisit without context.

Documentation should describe the implemented state. Keep speculative ideas clearly labeled as follow-up work.
