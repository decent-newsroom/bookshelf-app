# Precise Reader Progress Plan

## Goal

Replace chapter-step reader progress with a durable location that can resume a person within a chapter and present progress without treating every chapter as the same amount of reading. The work remains entirely device-local and preserves Continue reading for saved books.

## Current behavior and gap

Before Phase 1, `ReaderScreen` observed only `firstVisibleItemIndex`; `ReaderSettingsStore` persisted that chapter index, the chapter count, and a timestamp. Restore therefore started at that chapter's top.

Scrolling through a long chapter therefore writes no new position. The current fraction is `(currentChapterIndex + 1) / chapterCount`, so entering chapter one is non-zero and entering the final chapter is 100% even when its content is unread. It also assumes every chapter has equal reading length.

## Scope and non-goals

In scope:

* Durable in-chapter resume for saved books and the existing Continue reading card.
* Honest chapter-local progress and a content-weighted book fraction only when complete readable content permits it.
* Backward-compatible decoding of existing `SharedPreferences` progress JSON.
* Unit and Compose-level coverage for persistence, restoration, and content changes.

Out of scope:

* Nostr events, relay traffic, account state, cloud synchronization, chapter caches, reader-content snapshots, highlights, or cache-clearing controls.
* Treating unavailable or truncated content as a known portion of a whole-book percentage.

## Proposed staged design

### Phase 1: durable physical location (implemented)

Extend the per-book `ReadingProgress` record with an in-chapter location:

* `currentChapterIndex`: a bounded index retained from existing progress records.
* `chapterScrollOffsetPx`: the non-negative `LazyListState.firstVisibleItemScrollOffset` within the chapter item.
* The existing `chapterCount` and `updatedAtMillis`, which retain their Continue reading roles.

Observe chapter item and scroll offset together with `snapshotFlow`. Coalesce writes with `distinctUntilChanged` and a 0.5–1 second debounce or sample interval, then persist the final observed location when the reader leaves composition. Do not write on every pixel of a drag.

On restore, clamp the stored chapter index against the loaded `BookDetail` and supply it with the stored offset to `rememberLazyListState`. Contents and highlight navigation create a zero-offset location. Invalid, negative, stale, or unavailable locations safely use the chapter top.

A pixel offset is an immediate fidelity improvement, not a semantic bookmark. It is exact only for the same rendered content and layout geometry; typography, width, orientation, font availability, and renderer changes can reflow text. Phase 1 must not claim an exact whole-book percentage.

### Phase 2: coordinate and semantic anchors (planned)

Add a stable chapter coordinate and optional semantic anchor after Phase 1: UTF-16 offset in the displayed `AnnotatedString`, and short contextual text or a content fingerprint. Highlights already use UTF-16 offsets plus contextual anchors and provide the recovery precedent.

Capture the leading visible text line as the semantic offset. On restore, resolve it against newly rendered displayed text, wait for text layout, and convert its line to an item-relative scroll offset. If resolution fails, use the Phase 1 pixel offset, then the chapter top. Persist neither raw chapter text nor rendered HTML in progress preferences.

With a resolved semantic anchor and a complete locally available detail, calculate overall progress from displayed-text character weights instead of chapter count. If chapters are missing, truncated, or the anchor is unresolved, retain chapter-local state and omit any claimed whole-book percentage.

## User experience

Always identify the current chapter, for example `Chapter 3 of 12`. A fresh position is 0%, not one completed chapter. In Phase 1, show a chapter-local indicator only when current-item geometry is available; Phase 2 may show a whole-book percentage only with a resolved anchor and complete readable content.

Continue reading still selects the most recently opened saved book by `updatedAtMillis`; the card need not expose the offset. Its tap continues through cache-aware `openBook` before the reader resolves location.

## Persistence and lifecycle invariants

* Progress remains in the existing app-private reader preferences and follows the existing backup policy.
* New serializable fields have defaults, so old JSON restores to a chapter-top location; unknown future fields remain ignored.
* Progress remains independent of saved-book membership and all cache-clearing actions.
* It stores metadata and anchors only—never bodies, HTML, signed events, or account data.
* Deleted, changed, or unavailable chapters fall back safely and never produce an out-of-range scroll. Coordinate-aware reordering is planned for Phase 2.

## Implementation slices

1. Add pure location normalization and resolution helpers, defaulted progress fields, and legacy JSON tests. (Implemented in Phase 1.)
2. Change reader/store callbacks from chapter indexes to locations while retaining `recordBookOpened` timestamp behavior. (Implemented in Phase 1.)
3. Observe and restore list offsets, bound write frequency, flush the final location, and correct chapter-entry percentage semantics. (Implemented in Phase 1.)
4. Add semantic anchors, contextual recovery, and text-weighted progress for complete content. (Phase 2, planned.)
5. Update this plan and architecture from planned to implemented, accept or revise ADR 0032, and add an Unreleased changelog entry when a user-visible slice ships. (Completed for Phase 1; Phase 2 remains.)

## Acceptance tests

* Legacy index-only JSON restores at offset zero and remains eligible for Continue reading.
* Unchanged layout restores the saved chapter and pixel offset.
* Typography, width, orientation, and changed content never crash; unresolved locations have a predictable fallback.
* Reordered, missing, invalid, and out-of-range chapter locations clamp safely.
* Rapid scrolling produces bounded preference writes, and leaving the reader retains the final location.
* Contents and highlight jumps store offset zero.
* Fresh reading is 0%; entering the last chapter alone is not 100%.
* Whole-book progress appears only for complete readable content with a resolved semantic anchor.
