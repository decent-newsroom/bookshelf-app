# ADR 0061: Nested publication indexes

- Status: Accepted
- Date: 2026-10-13
- Extends: 0022, 0025, 0030, 0032, 0046

## Context

Publication indexes may mix kind-30040 indexes and kind-30041 chapters in their ordered address tags. Ignoring nested indexes loses Books, Parts, Acts, and their descendants; it also mistakes nested-only publications for library cards. Reader row positions, flat snapshots, and chapter totals previously assumed direct chapters only.

## Decision

Retain typed direct children in summaries and resolve a bounded outline when opening a publication. The outline is a serializable preorder tree representation with parent occurrence keys, depth, resolution diagnostics, and zero-based leaf ordinals. Preserve publisher sibling order and deduplicate identical references within one parent. Shared subtrees expand at each parent occurrence while event retrieval is deduplicated by coordinate. Detect cycles on the ancestor path, allowing legitimate shared subtrees.

Reuse verified publication-index relay/HTTP adapters and the existing chapter transport. Resolve exact coordinates and ID hints; never accept an event for a different kind, author, or identifier. Freeze a selected revision for each coordinate during an open. Bound expansion to depth 16 below the root, 256 index coordinates including the root, 5,000 outline entries/queued references, an 8 MiB conservative retained-index estimate, four concurrent sibling requests, and a 60-second index-resolution budget. Chapter bodies retain the 500-occurrence cap. Cancellation must reach upstream operations. Failed branches remain explicit and readable peer branches survive.

Only a fully resolved structure establishes whole-book leaf totals and tracking ordinals. Body availability is independent of structural completeness. Missing indexes, cycles, and capped expansion disable whole-book tracking/percentages; missing bodies and the body cap prevent verified completion and terminal reader actions. Discovery parses direct references without recursive fetching. A publication with nested children is openable; only a publication without supported children is a library card.

Contents projects the hierarchy into one expanded, indented list. Native Compose division entries create titled story breaks before descendants, using the publisher's index title. Explicit row/leaf maps replace chapter-plus-header arithmetic. Chapters alone count toward progress; division/header/diagnostic/footer rows cannot advance tracking. Occurrence keys distinguish repeated leaves and division bookmarks, while coordinate/index fallbacks retain legacy resume. Structure and revision fingerprints invalidate incompatible endpoints and physical offsets.

The root remains the publication identity for saved membership, ratings, sharing, tracked/finished state, and highlight root tags. Highlights still identify the actual kind-30041 chapter parent. Public reading-list events retain their existing format, with depth-first leaf positions and actual section IDs; occurrence-specific entry bookmarks remain local.

Version 2 reader snapshots store the outline and occurrence identities alongside chapter bodies/HTML, without signed source events. Decode version 1 flat snapshots with their existing completeness safeguards. A complete fresh outline is authoritative; removed children must not be restored from caches. Matching unresolved divisions may use stale cached subtrees for reading without establishing current totals or tracking ordinals. Preserve the prior usable offline snapshot when a partial refresh would discard more readable content than it supplies. Existing independent cache-clearing boundaries remain unchanged.

Local broadcast resolves the original signed root's actual descendant structure, queues parents before descendants in publisher preorder, and deduplicates signed event IDs. Missing or capped branches produce explicit partial feedback. Offline snapshots cannot synthesize signed events.

## Consequences and supersession

- ADR 0022's direct-index/chapter broadcast becomes recursive signed-event broadcast.
- ADR 0025's library-card predicate becomes absence of supported direct children, rather than absence of direct chapters.
- ADR 0030's flat offline snapshot gains versioned outline storage and stale-subtree reconciliation.
- ADRs 0032/0046 retain chapter-only progress and add local entry occurrence bookmarks plus resolved-structure fingerprints.
- Transitive reverse-parent discovery from full-text hits, collapsible groups, division body rendering, and per-division saved/ratings identity remain outside this change.

## Verification

Unit tests cover parsing/order, repeated occurrences and cycles, bounded/failed expansion, reader row mapping, local bookmark recovery, snapshot migration/reconciliation, and incomplete-structure tracking restrictions. Builds, tests, and device acceptance are owner-run under [Development](../DEVELOPMENT.md#build-and-test-ownership); static review is not evidence of a passing Android build.
