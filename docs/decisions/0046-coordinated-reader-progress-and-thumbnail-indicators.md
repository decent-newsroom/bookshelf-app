# ADR 0046: Coordinate reader progress and shared thumbnail indicators

## Status

Accepted, 2026-10-09. Extends ADR 0032's local position model and ADR 0044's independent explicit tracking/history model. Semantic text anchors remain deferred.

## Context

Local chapter display, the furthest section in kind 16374 tracking, and the last physical scroll position have different meanings. The existing chapter-index fraction cannot reach 100% through valid persisted indexes, and saved-book opening alone creates resume metadata. A corner badge must distinguish started/completed books without inventing reading activity, moving bookmarks to remote positions, or treating entry into the last section as completion.

## Decision

- Keep the existing two stores. Local chapter presentation and pixel resume share one private `ReadingProgress` record; explicit guest/account tracking and finished history remain in the reading-state repository/outbox. Thumbnail presentation is derived, not a third persisted progress record.
- Observe the positioned list once. Publish and persist chapter/start/end/cycle transitions immediately, retain the latest physical position in the reader, coalesce pixel-only application-state updates and writes for 500 ms, and flush the actual latest location on exit. Header/footer indexes cannot manufacture a tracked section or overwrite real chapter offsets. Explicit navigation counts as activity only after the location changes; layout reflow alone does not start a reread.
- Add backward-compatible metadata for started/end state, the full section denominator, chapter coordinate, summary/order and loaded-content fingerprints, and real activity/cycle evidence. Normalize existing locations on every open without treating opening/recency as rereading. Content/order changes invalidate stale endpoints.
- Establish local 100% only at a laid-out bottom with known complete order, all bodies available, and no truncation, or through explicit finished history without an active reread. The tracked wire position stays zero-based and bounded to `total - 1`; a complete endpoint may advance that final section but cannot create a completion sentinel, label, or removal. Finish remains an explicit action with its existing relay dependencies.
- Serialize tracking intents and invalidate automatic advances queued before Track/Reset/Stop/Finish or account changes. Reset retains the observed viewport baseline so unchanged pixels do not undo it. Local persistence never waits for signing/delivery and is never rolled back to relay acknowledgements.
- Restore explicit targets first, then valid local chapter/offset, then a resolved account tracked section at offset zero, then the beginning. A remote refresh cannot move an active reader. Local display can move backward while explicit tracking remains a maximum.
- Use one pure presentation resolver for reader percentage, Continue reading, and every cover. Local reading evidence wins over synced fallback; incomplete local content keeps an unknown fraction. Finished history wins until a new reading cycle. Explicitly finishing a reread clears its local activity/cycle evidence without moving its bookmark or replacing original finished history.
- Render a bottom-right ring for non-complete started books, highlight the approximate chapter fraction when known, and use a solid filled circle at 100%. Explicit finished history adds a check. No badge appears for merely opened books. Incomplete numeric display caps at 99%.
- Supply the derived map at the Compose root. Shared covers and standalone opening artwork consume it without preference access, book rendering, or relay traffic. Account overlays are guarded against signer/repository handoffs; device-local resume remains device-wide. Cache clearing preserves all metadata and signed pending events.

## Consequences

The three tracks cooperate without conflating current location, furthest tracked section, and exact bookmark. A different device receiving a final-section index can restore that section but cannot infer exact bottom completion; kind 1985 remains the interoperable explicit completion signal. Intermediate percentages remain approximate by chapter position. The filled completion state is visually distinct from a nearly full ring, including small covers and unknown progress.

Tests cover pure endpoint gates, physical footer safety, transition persistence metadata, account filtering, local/synced/finished precedence, changed editions, rereading, and stale automatic advances. Gradle execution and Compose/device visual verification are performed by the user on this Windows setup, as required by AGENTS.md.

## Alternatives

Using the maximum of local and remote indexes for all displays would move bookmarks and hide backward reading. Treating the final index as complete would produce false 100% and require an incompatible wire sentinel. A separately persisted thumbnail percentage would introduce another source of truth. Exact text-weighted percentages need semantic anchors and remain a follow-up.
