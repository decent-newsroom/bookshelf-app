# Book thumbnail reading progress

## Status and goal

Implemented, 2026-10-09. Show a corner indicator on every displayed book thumbnail for a book the person has started reading, including unsaved books. The implementation follows [ADR 0046](../decisions/0046-coordinated-reader-progress-and-thumbnail-indicators.md). Unit cases were added and the integration was statically reviewed; Gradle tests/builds and Compose/device visual checks remain for the user, per AGENTS.md.

## Verified behavior before this implementation

- `ReadingProgress.progressFraction` in `data/reader/ReaderModels.kt` is `currentChapterIndex / chapterCount`. `ReaderSettingsStore` clamps persisted indexes to `0..chapterCount - 1`. Consequently, a four-chapter book tops out at 75%, and a one-chapter book stays at 0%, regardless of its pixel offset or reaching the bottom. With very many chapters, rounding can also display 100% before the actual end.
- `ReaderScreen` observes the first visible item index and pixel offset, debounces writes by 500 ms, and flushes the resume position on exit. It does not observe the bottom-of-list condition for percentage calculation. Terminal Finish/review cards deliberately preserve the last chapter's resume offset.
- `finishReading()` writes to the independent reading-state repository, not `ReaderSettingsStore`. Explicitly marking a book as finished therefore does not change the reader's percentage. The tracked-section bars are a separate measure, not the reader's percentage.
- Saved-book opening creates or updates a progress record even before a person scrolls. Record existence and `updatedAtMillis` alone cannot distinguish opened from started.
- `BookCover` in `ui/books/BookCard.kt` renders cover artwork or a monogram without a reading indicator. Cards, carousels, reading lists, details, and Continue reading reuse it. The opening screen renders its artwork separately.
- Existing `ReaderTerminalProgressTest` covers footer resume safety and partial-stream gating, but does not test 100% at the bottom. No Gradle commands were run, in accordance with the repository instructions.

## Recommended appearance

Use a circular indicator in the thumbnail's bottom-right corner, inset by 4 dp: a ring for every non-complete started state, and a solid filled circle for the 100% read state. For partial progress, highlight the corresponding arc; for started state with an unknown fraction, use a static outline ring without a claimed fractional arc. Give the ring an opaque theme surface backing and a contrasting track/highlight, and use a nominal diameter of 22 dp. Keep it inside the cover bounds, including the existing 48 x 68 dp reading-list thumbnails. The badge is informational and does not introduce a new tap target.

| State | Appearance | Accessibility label |
| --- | --- | --- |
| Never started / only opened | No badge | No reading status |
| Started, overall fraction unavailable | Static outline ring, without a fractional arc | Reading started; overall progress unavailable |
| Partial chapter-based progress | Partially highlighted ring | Approximately X% by chapter position |
| Complete content at the reader bottom | Solid filled circle | 100% read; end of book reached |
| Explicit finished history without active rereading | Solid filled circle with contrasting check mark | Marked as finished |

The initial fractional arc is an approximation by chapter position, consistent with the current model; it must not claim text-weighted accuracy. A numeric alternative can use an opaque pill displaying `~42%`, or `100%` only at a verified end. Prefer the ring because it fits small covers and does not imply more precision than the chapter-based source provides. No appearance setting is needed for the first implementation.

## Cooperation between the three progress tracks

The three roles must cooperate without pretending they represent the same measurement. Today local chapter display and physical resume share the `ReadingProgress` record in `ReaderSettingsStore`; explicit section tracking lives in `ReadingStateRepository`. Keep those two persistence boundaries. Derive the thumbnail badge from them rather than introducing another independent progress store.

| Track | Meaning and authority | Update rule |
| --- | --- | --- |
| Local chapter progress | Current chapter position and verified local endpoint; supplies the reader percentage and thumbnail ring | Follows the resolved reader position, including backward navigation; local completion requires actual bottom evidence |
| Nostr section progress | Furthest zero-based section position for an explicitly tracked book in the active guest/account reading cycle | Advance monotonically on section transitions; lower only through explicit Reset; publish only when public sharing is enabled |
| Scroll resume | Last actual chapter and pixel offset on this device | Follows forward/backward navigation; persist coalesced updates and flush on exit; terminal-card offsets never replace chapter offsets |

### One observation, coordinated projections

1. Produce one positioned reader observation containing book coordinate, publication/order identity, real chapter coordinate/index, pixel offset, verified bottom state, and navigation origin. Distinguish initial restoration, ordinary scrolling, explicit Contents/highlight/search navigation, and footer-only movement. Do not manufacture a chapter transition from a footer card.
2. Route it through a coordinator in the existing reader/ViewModel boundary. Update local chapter/started/endpoint/cycle presentation immediately and save its associated resume location together in one local record. Keep the actual latest pixel observation in the reader and coalesce pixel-only application-state/persistence updates by the existing 500 ms policy; chapter and endpoint changes must not depend on a quiet scrolling period. Maintain an exit flush of the latest observation.
3. Feed real chapter transitions to the existing tracked-section advancement path immediately, only when that book is already tracked. A verified complete endpoint also establishes the final real section index even if a short final chapter never becomes the first visible item. Preserve the fixed three-second publication window and durable operations/outbox. Pixel motion and other local percentage changes never create separate Nostr events or implicitly opt into tracking.
4. Sequence observations and explicit Track/Reset/Stop/Finish intents per book. Carry reader-session and account identity plus a tracking-cycle/generation guard so an old debounced observation, queued advance, or background callback cannot undo a later Reset, recreate stopped tracking, or modify the new account. Persist the latest local chapter/resume pair even when its Nostr projection is ineligible.
5. Treat local projection and reading-state persistence as separate writes, not a cross-store transaction. A reading-state write/signing/delivery failure must not block local progress or resume. Retain retry/error state in the existing reading repository and never roll back local progress to an acknowledged relay position.

### Resume and presentation precedence

- Opening precedence is a resolved explicit navigation target, then a valid device-local reading chapter/offset, then the active account's tracked section at offset zero, then the beginning. A merely opened zero-position record is not reading evidence and does not suppress tracked fallback; an unresolved explicit target also falls through to this hierarchy. A newer or further remote snapshot must not move an already-open reader or overwrite an existing local bookmark. Remote fallback is resolved against the current full section order and positioned before creating a local resume record; failure falls back safely and does not establish an endpoint.
- Match chapter coordinates/event IDs against the publication's full ordered section references before comparing or projecting positions. Missing bodies keep their ordinals; the 500-section loaded prefix is never the full denominator. Reordering or revisions invalidate stale endpoint evidence and require safe position resolution rather than copying an old numeric index into a different order.
- Use one resolved presentation model for local reader percentage, Continue reading, and thumbnails. Prefer current local chapter/end evidence; use account tracking only when local reading evidence is absent. Preserve the source in the model: tracked fallback uses furthest-section labels and accessibility text without prefixing an unrelated local chapter. Keep tracking-specific controls explicitly labeled as section position/furthest section, rather than presenting their monotonic index as the same current-position percentage.
- Keep kind 16374's wire position zero-based and bounded to `0..total - 1`. At the verified bottom, local presentation becomes 100% and any active tracked entry reaches at most the final section index. Do not send `position = total` as a completion sentinel. Another device receiving that final-section index can infer a last-section location, not exact bottom completion. An explicit kind 1985 finished label remains the interoperable completion signal.
- Local chapter and scroll position can move backward while Nostr's furthest section stays ahead. This is an intentional semantic difference: for example, returning to chapter 2 after reaching chapter 5 resumes chapter 2 and shows its local progress, while section tracking retains chapter 5. Do not take a maximum across these sources to choose the bookmark or local ring.

### Explicit actions and completion

| Action | Local chapter presentation / scroll resume | Explicit section tracking / history |
| --- | --- | --- |
| Track reading | Preserve current chapter/offset; qualify as started | Add at the positioned chapter using the full total; begin an explicit tracking cycle |
| Reset tracking | Preserve current location and local endpoint; do not jump the reader | Reset tracked position to zero; reject earlier queued advances; wait for a subsequent genuine chapter transition rather than immediately replaying the unchanged viewport |
| Stop tracking | Preserve local started state, progress, and bookmark; badge can remain visible | Remove tracking and its eligible public snapshot entry; preserve finished history |
| Reach complete book bottom | Save 100% presentation plus the last chapter's valid resume offset | Advance only to the valid final section index if tracked; no automatic finished label/removal |
| Mark as finished | Display completion consistently; preserve bookmark; local bottom evidence remains distinct from explicit completion | Save finished history and remove active tracking, retaining existing label-before-removal delivery dependencies |
| Start rereading | Preserve history; follow the new reader position and clear current endpoint when moving away from it | A new explicit Track/Reset cycle can advance independently of the previous completed cycle |

Opening a finished book or refreshing `updatedAtMillis` alone is not rereading. Use a new explicit tracking cycle, or actual navigation away from the completed position followed by new reading activity, to establish an active reread. Track that distinction as local metadata alongside the existing progress record; a last-opened timestamp is insufficient. Finished history wins for completed presentation until a new reading cycle is established. Both the reader percentage and thumbnail must use this same rule; tracking's zero-based wire value remains independent.

Account changes replace only account/guest tracked and finished projections and invalidate pending account-scoped work. Existing device-local progress/resume remains local as it does today. Cache clearing must retain all three tracks and pending signed events. Offline progress/resume updates remain immediate; remote work stays gated by validated connectivity, with configured local Citrine delivery governed by the existing public-event policy.

## Implemented slices

### 1. Correct and persist the reader endpoint

- Add defaulted local metadata to `ReadingProgress`: a started flag, a current-position end flag, reading-cycle evidence distinct from last-opened time, and sufficient publication identity/order metadata to invalidate an end flag when the edition's content changes. Keep the existing index and pixel offset for resume; never encode completion as an out-of-range chapter index. Apply the coordination contract above before wiring the badge.
- Observe bottom state alongside the existing resume observation, after initial positioning and a non-empty layout. A valid end requires known section order, a positive full section count, every ordered section loaded and available, no truncation, and `!listState.canScrollForward`. The bottom of a loaded prefix, a missing-section placeholder, an empty layout, and a failed load cannot establish completion.
- Terminal cards may satisfy the bottom condition but must continue to preserve the last real chapter's offset. Save the endpoint separately, including when a person exits before the debounce expires. A short complete book that fits entirely in the viewport can reach the end after its initial layout.
- Define end as current reader position: moving backward clears the end flag, and reaching the end again restores it. Explicit finished history remains durable and independent. Reopening preserves a saved end until the positioned layout establishes otherwise; changing chapter order/content invalidates stale endpoint evidence.
- Return 1.0 for a verified current endpoint or explicit finished history without an active reread, retaining the distinction in the presentation model/accessibility label. Cap incomplete numeric display at 99% so rounding never reports 100% early. Keep a fresh location at 0%. Use the same display helper for the reader controls, Continue reading, and badges; retain separate labeled section semantics for tracking controls.
- Reaching the bottom records completion locally and may advance an already tracked book to its final valid section through the normal tracking path. It must not automatically mark the book finished, sign a Nostr finished label, remove a tracked entry, or trigger a review publication. The existing explicit Finish action remains separate.

### 2. Define started state and one badge resolver

- Mark local started state after a meaningful positive chapter offset, advancing beyond the first chapter, or a verified endpoint. Explicit Track reading also qualifies, including position zero. A saved-book open at the first chapter's top alone does not qualify.
- Decode legacy JSON with defaults. Infer started state from a positive chapter index or pixel offset, and from applicable tracked/finished state. An old zero-position record is ambiguous: hide its badge until new reading activity or explicit tracking establishes started state.
- Build a pure, coordinate-keyed UI resolver from `BookshelfUiState.readingProgress` plus the active account/guest `ReadingState`. Do not read preferences or fetch books inside `BookCover`.
- Precedence: active rereading overrides older finished history; otherwise explicit finished history supplies a completed presentation in both reader and badge. For an active read, use local chapter evidence first, then tracked section position if local evidence is absent. Follow the explicit reading-cycle rules above rather than deriving rereading from the last-opened timestamp; normalize milliseconds and Nostr seconds where timestamps must be compared.
- Use the full ordered section total when available. The existing resume count may describe a capped loaded prefix; never turn that prefix into a whole-book percentage. For unknown/missing/truncated content without sufficient complete metadata, show the started marker. Synced tracked positions can yield an explicitly approximate section fraction below 100%; a tracked position alone is not evidence of a verified local endpoint.
- Local resume remains device-wide as today. Tracked and finished overlays follow the current guest/account state; switching accounts must not retain the previous account's overlays. Reset/Stop tracking keeps its existing behavior and does not erase independent local resume evidence or finished history.

### 3. Render the reusable cover badge everywhere

- Add an optional resolved badge parameter to `BookCover`, rendered after the artwork/monogram in its existing clipped Box. Apply progress semantics and a single useful status label without duplicate TalkBack announcements.
- Thread the coordinate-keyed resolved state through `BookshelfApp`, `HomeScreen`, `MyBooksScreen`, `SearchScreen`, book details/recommendations, and their shared card/carousel components. Cover both metadata and content-search results, Continue reading, Reading/Finished cards, and the reader's cover. Unsaved books remain eligible when they have reading evidence.
- Include the separately rendered `BookOpeningScreen` artwork using the same badge composable when state is known. Books with unavailable summary artwork can keep their existing reading-list text status until a thumbnail can be rendered.
- UI changes must react to progress flow updates immediately after leaving the reader and after account changes. Displaying thumbnails must not open/render chapter content or start any relay work.

### 4. Validation and documentation

- Unit coverage: valid endpoint versus first/last chapter entry; one-chapter and short books; unknown/empty/missing/truncated streams; rounding below 100%; backward movement; content/order changes; legacy JSON; exit before debounce; terminal cards retaining chapter pixels.
- Resolver coverage: opened-only, started within chapter one, unsaved books, tracked at zero, finished, rereading, local-versus-synced precedence, reset/stop, and account switching.
- Coordinator coverage: rapid chapter transitions update local chapter presentation and tracked sections before pixel debounce; exiting flushes matching chapter/offset/end evidence; backward navigation changes local progress/resume while tracking remains monotonic; late observations cannot undo Reset/Stop/Finish or cross accounts; a newer remote snapshot never moves an open reader or replaces its bookmark; remote fallback creates local state only after positioning; reading-state persistence/signing failures preserve local progress.
- End-to-end scenarios: chapter 5 to chapter 2 then reopen; Reset while still in chapter 5; a new account with a different tracked section and an existing local bookmark; offline bottom then reopen; explicit Finish and reread with all three projections; final tracked index remains `total - 1` while verified local completion displays 100%.
- Compose/device checks: bottom detection after layout/restoration; footer behavior signed in and signed out; consistent badges across all surfaces; cover-image failure/monogram fallback; smallest covers; light/dark themes; large fonts; TalkBack; offline reopen and cache clearing retaining metadata.
- Appearance acceptance: every visible non-complete indicator remains a ring; exactly 100% read uses a solid fill rather than a fully highlighted hollow ring. Beginning an active reread returns the indicator to its appropriate ring state, while never-started books keep no badge.
- The user runs builds/tests. Do not execute Gradle verification as part of this plan or its implementation on this machine.
- Architecture, ADR 0046, and Unreleased changelog entries document the implemented persistence/lifecycle rules. Validation scenarios above describe the coverage added and the remaining user-run build/device checks.

## Follow-up: precise intermediate percentages

The semantic displayed-text anchors and text-weighted fractions in Phase 2 of [precise-reader-progress.md](precise-reader-progress.md) remain a separate follow-up. They can replace the approximate fractional input without changing the shared badge interface. Raw pixel offsets must not be converted to an exact global percentage.
