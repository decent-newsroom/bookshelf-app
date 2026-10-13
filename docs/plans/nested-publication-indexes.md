# Nested publication indexes

Status: Implemented under [ADR 0061](../decisions/0061-nested-publication-indexes.md). Owner-run build/tests and device acceptance remain pending. The design and acceptance checklist below record intended behavior, not verification evidence. Index fetching uses bounded sibling prefetch with at most four requests; HTTP helpers retain their exact coordinate filters. Retained index events additionally have an 8 MiB conservative size budget, and completed nodes plus queued child references share the 5,000-entry bound.

## Intended behavior

A kind-30040 index may contain ordered kind-30041 chapter references, kind-30040 index references, or both. Resolve nested indexes recursively and preserve the order of their `a` tags. Each nested index introduces a titled story division, such as Book I, Part Two, or Act III. Do not infer those labels from depth: use the publisher's title.

For example:

```text
Collected stories (30040, reader header)
  Preface (30041)
  Book I (30040, division heading)
    Chapter 1 (30041)
    Act II (30040, division heading)
      Scene 1 (30041)
  Afterword (30041)
```

Contents shows this hierarchy. Reading follows Preface, the Book I break, Chapter 1, the Act II break, Scene 1, and Afterword. Divisions are structural headings, not synthetic chapters or independently opened books. Save, ratings, finished history, sharing, and highlight root tags continue to refer to the opened root publication.

## Existing constraints and affected paths

- `domain/BookModels.kt`: `BookSummary.chapterRefs` and `BookDetail.chapters` are flat. `ChapterReference` has no kind discriminator.
- `data/mercury/MercuryBookRepository.kt`: `extractChapterRefs` drops kind-30040 children; `mapIndexEvent` marks nesting as an unknown stream. `openBook` fetches only direct chapters, capped at 500 bodies. Broadcast also assumes direct chapters.
- `data/mercury/PersistentNostrChapterSource.kt`: the dedicated chapter transport only requests kind 30041. Publication-index reads already have a shared relay source and exact HTTP coordinate lookup.
- `ui/BookshelfViewModel.kt`, `ui/BookshelfApp.kt`, and `ui/books/BookCard.kt`: empty direct chapter references currently mean a library card. An index containing only nested indexes must become openable.
- `ui/reader/ReaderScreen.kt`, `ReaderControls.kt`, and `ReaderProgress.kt`: Contents is flat, and reader row arithmetic assumes one header followed by chapter rows.
- `data/reader/ReaderSettingsStore.kt`, `BookReadingPresentation.kt`, `OfflineBookCache.kt`, `ReaderContentCoordinator.kt`, and `data/reading/ReadingStateRepository.kt`: resume, snapshots, known totals, and tracked positions depend on the flat chapter sequence.

Paths above are relative to `app/src/main/java/eu/decentnewsroom/bookshelf/`.

## Model and ordering decisions

1. Add a serializable typed child reference for kind 30040 or 30041, carrying the exact coordinate, optional relay hint, and optional event ID. Parse both kinds from one pass over `a` tags; preserve interleaving. Preserve exact identifiers, including colons, and existing pubkey normalization. Keep unsupported address kinds outside the reader structure; malformed supported references produce a structure diagnostic rather than silently establishing completeness.
2. Store direct child references separately from resolved leaves. Add backward-compatible defaults to serialized summaries. A summary can identify an expandable publication without knowing its descendant chapter count. Show an unknown count until expansion completes, rather than presenting the direct count as a whole-book total.
3. Add a resolved outline to `BookDetail`: division nodes, chapter leaves, and explicit unresolved/blocked nodes. Nodes carry a stable occurrence key, reference, parent path, depth, title, and resolution state. Index titles use the existing `title`, `T`, then identifier fallback; index content is not rendered as a chapter.
4. Derive a depth-first ordered leaf sequence and reader-entry projection from that outline. Keep `BookDetail.chapters` as the chapter-only projection for rendering and highlight consumers. Expose full resolved leaf metadata separately from the body-loaded prefix. Consumers must use one authoritative resolved sequence for totals and ordinals.
5. Fetch each coordinate once per open, but expand it at each legitimate occurrence. Cycle detection uses the current ancestor path, not a global visited set. Keep existing same-parent duplicate-reference deduplication; shared children under different parents remain visible at both locations. Use occurrence keys based on parent occurrence and child coordinate, not event revision or title, for Compose keys and local resume.

## Loading and failure handling

Introduce a `PublicationOutlineResolver` used by normal opening and full-book broadcast. Keep transport access behind existing adapters; do not create another socket pool.

1. Start from the already-resolved root's ordered child references. Do not require a Mercury mirror or refresh the root midway through opening.
2. Resolve each frontier of kind-30040 coordinates through the existing publication-index relay source, then exact Mercury lookup for unresolved references. Carry validated child relay hints through the adapter, subject to existing URL policies and relay caps. Use exact author/`#d` filters, never broad author scans.
3. Verify every event and check kind, author, identifier, and optional ID association. A mismatched ID hint must never inject a different coordinate. Retain existing exact-ID-first, coordinate-fallback behavior; choose coordinate revisions with deterministic timestamp/event-ID ordering. Freeze the selected revision per coordinate for the duration of this open.
4. Assemble the outline in tag order regardless of response order. Resolve all bounded index metadata before assigning final leaf ordinals. Then load chapter bodies using the existing chapter source and exact-coordinate HTTP fallback, preserving successful branches and unavailable chapter placeholders. Rethrow coroutine cancellation instead of swallowing it in broad `runCatching` blocks.
5. Maintain distinct structure-completeness, body-availability, and truncation states. A missing chapter body leaves a known ordinal. A missing index leaves an unknown subtree, so no whole-book total or public tracking ordinal can be claimed. Other resolved branches remain readable.
6. Apply named, injectable limits. Proposed initial defaults: depth 16 below the root, 256 distinct index events including the root, 5,000 expanded outline nodes including repeated occurrences, four concurrent requests, and a 60-second overall outline-resolution deadline. Preserve the existing 500 chapter-body occurrence cap and existing event/HTTP byte limits. Bound frontier queues and cached decoded references as well as completed output. Report cycle, depth, node-budget, and timeout diagnostics at the affected branch.

Only the successfully bounded, fully expanded outline establishes the full leaf count. A body cap may retain complete outline metadata but must prevent terminal reader actions, as today. Discovery, cards, and recommendation mapping parse direct references only; they must not recursively fetch a book merely to display it.

## Contents and reader presentation

- Contents displays a single lazy list projected from the tree: stronger division headings and indented descendants. Keep it expanded initially; collapsible groups are deferred. Cap visual indentation for deep trees while retaining depth in accessibility semantics. Highlight the current chapter and its ancestor divisions.
- Selecting a division scrolls to its story-break heading; selecting a chapter scrolls to its body. Missing divisions remain visible with an unavailable explanation; fully resolved empty divisions show their title without inventing chapters.
- Insert a dedicated division entry before each nested index's descendants in the reader. Use generous vertical space, a divider, and prominent centered title; successive depths use a bounded type scale. Avoid forced screen-height blank pages. Consecutive and empty divisions remain distinct. The root keeps its existing book header.
- Replace `chapterIndex + 1` arithmetic with explicit entry-to-leaf and leaf-to-entry maps shared by Contents, highlights, search targets, initial positioning, and scroll observation. Header, division, diagnostic, and footer entries never count as chapters.
- Keep chapter AsciiDoc rendering and HTML caching unchanged. Division headings are native Compose elements and follow reader theme and typography preferences.

## Resume, progress, and publication identity

- Depth-first kind-30041 occurrences define zero-based reading positions and full totals. Divisions do not advance tracking. Public reading-list events keep their existing root coordinate and leaf section-ID fields; do not add an unapproved protocol extension.
- Add optional local entry/occurrence identity for precise division and repeated-chapter resume. Keep existing chapter-coordinate/index fields as compatibility fallbacks. Restore the same occurrence when possible, then the first matching chapter coordinate, then existing bounded fallback. A division bookmark restores that heading without claiming entry into its first chapter. Division-only documents remain navigable with chapter tracking disabled.
- Include resolved leaf order and structure in publication fingerprints; retain chapter content fingerprints. Reordering or replacing a nested index invalidates incompatible endpoint/offset evidence while preserving coordinate recovery. A public tracked position can be attributed only after the full leaf order is known; use existing monotonic tracking and reset rules.
- Unknown subtrees disable whole-book percentages, new public tracking/advances, verified 100%, and terminal actions. Missing chapter bodies retain the known total but cannot establish a verified complete endpoint. Local resume remains available in readable branches.
- Highlights keep the opened root as `A`/`K`/`P` and the actual kind-30041 leaf as `a`/`k`/`p`. Nested indexes do not become highlight parents. Existing coordinate-only search/highlight targets select the first matching leaf occurrence deterministically.

## Offline snapshots and local broadcast

- Version the reader snapshot schema to store the outline, leaf order, occurrence keys, completeness/diagnostics, and rendered bodies together. Validate depth, counts, kinds, paths, and byte limits on decode. Continue excluding original signed events from snapshots.
- Read old snapshots as a flat outline with existing completeness rules. Never infer previously discarded nested structure from a legacy direct-chapter prefix; refresh it online. Continue reading old flat books offline.
- When fresh structure is complete, it is authoritative: reuse cached bodies only for leaves still referenced by it. When a fresh subtree is unresolved, a matching cached subtree may supply readable fallback, explicitly marked stale; it cannot certify the current total or public ordinals. Retain the previous usable snapshot if the new result would discard cached readable content without replacement. Never graft children onto an explicitly removed branch.
- Keep offline opens entirely snapshot-based. Existing reader-content clearing removes the hierarchical snapshot; HTML clearing removes only chapter HTML. Neither touches membership, progress, highlights, ratings, or signed outboxes.
- Broadcast re-fetches original signed root, nested indexes, and chapters through the same bounded resolver. Publish root first, then referenced events in depth-first order with indexes before descendants; deduplicate delivery by signed event ID. Report unresolved branches and caps without claiming a complete book was sent. Signed cached source events remain an in-memory concern, never reconstructed from offline rendering data.

## Implementation sequence

1. **References and outline:** add typed children, pure parsing/expansion/projection models, diagnostics and limits. Keep flat fixtures equivalent. Update library-card/openability predicates and unknown-count presentation across cards, saved books, recommendations, and incoming links. A root with valid nested children is expandable; unresolved children are a loading failure, not proof of a library card.
2. **Recursive loading:** implement the resolver, publication hint routing, bounded batching, verification and cancellation; integrate it into `openBook`. Replace the test that treats all nesting as unsupported with assertions for resolved and incomplete structures.
3. **Reader and progress:** implement hierarchical Contents, division entries, navigation maps, occurrence-aware local resume, fingerprints, and resolved-sequence tracking/presentation consumers together. Check zero-leaf and repeated-leaf behavior before enabling the new reader path.
4. **Snapshots and broadcast:** implement migration, safe partial refresh, offline preservation, and recursive signed-event broadcast. Retain existing independent cache controls.
5. **Documentation and acceptance:** update `ARCHITECTURE.md`, add an accepted ADR covering recursive loading, structure limits, snapshot migration, and leaf-position semantics, and qualify/supersede the affected parts of ADRs 0022, 0025, 0030, 0032, and 0046. Add an Unreleased changelog entry when behavior is implemented. Keep this plan's status accurate.

## Verification and acceptance

Add meaningful unit/contract tests for:

- Flat-only, nested-only, mixed/interleaved, three-level, empty-division, and different-author trees; exact sibling ordering independent of network response order.
- Shared subtrees, repeated leaves, same-parent duplicate tags, self-cycle and ancestor-cycle; occurrence identity and one fetch per coordinate.
- Invalid coordinates/signatures, wrong kind/author/identifier/ID, competing revisions, relay-only indexes, relay hints, exact HTTP fallback, branch failures, deadlines/limits, and cancellation with subscription cleanup.
- Known structure with missing bodies versus unknown subtrees; full metadata beyond 500 bodies; no false totals, tracking advancement, terminal actions, or 100%.
- Entry/leaf mapping, Contents division/chapter targets, highlight/search targets, division-only books, occurrence-aware resume, old coordinate-only resume, reordering, changed content, and footer offset preservation.
- New and legacy offline snapshots, corrupt/oversized/deep snapshots, zero offline network calls, cached branch fallback, authoritative removal, and independent cache clearing.
- Broadcast includes original nested index events before descendants, deduplicates signed IDs, preserves signatures, and reports partial results.

Owner device acceptance: open a mixed novel with Books/Parts and a drama with Acts/Scenes; inspect Contents indentation and story breaks at large fonts in every theme; navigate, rotate, reopen offline, refresh a changed nested index, and confirm tracking/highlights still identify root publication and leaf chapter correctly. Confirm ordinary flat books and true library cards retain their behavior.

Builds/tests are owner-run under [Development](../DEVELOPMENT.md#build-and-test-ownership). No Gradle verification was run by the implementing agents.

## Deferred scope

Collapsible Contents groups, per-division ratings/saving, division AsciiDoc bodies, lazy chapter streaming, and transitive reverse-parent discovery for full-text search are separate work. Existing direct-parent search remains supported; opening a known root can navigate to any resolved descendant leaf. Search cannot promise to discover an outermost collection from a deeply nested leaf until bounded reverse traversal and multiple-parent selection have their own design.
