# Book thumbnail reading progress

Status: Implemented, 2026-10-09. Coordination follows [ADR 0046](../decisions/0046-coordinated-reader-progress-and-thumbnail-indicators.md); visibility follows [ADR 0048](../decisions/0048-progress-visibility-and-tracking-entry-points.md); current styling follows [ADR 0055](../decisions/0055-cover-progress-badge-styling.md). Build/device verification is owner-run.

## Cooperation between the three progress tracks

| Track | Authority | Update behavior |
| --- | --- | --- |
| Local chapter presentation | Current device position/verified bottom | Follows forward/backward reading |
| Explicit section tracking | Furthest section in active guest/account cycle | Monotonic advances; lower only through Reset |
| Physical resume | Actual chapter and pixel offset on this device | Coalesced pixels/final flush; footer offsets never replace chapter offsets |

Local presentation and resume share `ReaderSettingsStore`; explicit tracking/history live in `ReadingStateRepository`. There is no separate badge store. One positioned observation supplies both projections; session/account/generation guards prevent stale advances from undoing Reset/Stop/Finish. Reading-state persistence/signing failure must not block local resume.

Opening precedence is explicit resolved target, local reading location, tracked section, beginning. Merely opened zero-position records do not block tracked fallback. Remote snapshots never move an open reader. Full section order survives the 500-body cap, missing bodies retain ordinals, and content/order revisions invalidate stale endpoints.

Verified complete bottom displays 100% locally; tracked wire position remains at most `total - 1`. Explicit Finish remains separate. Backward navigation can clear the local endpoint while leaving furthest tracking ahead. Finished history wins until genuine rereading/new tracking cycle; reopening alone is insufficient.

## Current indicator

Shared `BookReadingPresentation` resolves reader labels and eligible badges without content loading/relay work in thumbnails. Local evidence wins over tracking, including unknown local fraction for incomplete content. Approximate fractions never round to 100%.

| State | Badge / accessible meaning |
| --- | --- |
| Never started / opened only | No badge |
| Started, unknown fraction | Neutral ring/dash; progress unavailable |
| Known zero | Neutral ring/accent dot; approximately 0% |
| Partial | Round-ended accent arc; approximately X% |
| Verified complete bottom | Solid circle; end reached |
| Explicit finished history | Solid circle/check; marked finished |

Badge diameter is 26 dp, inset 4 dp bottom-right, with opaque theme backing, 2 dp artwork margin, 3 dp stroke, neutral track at 18% onSurface, and no outer border. Zero uses a 4 dp dot at 12 o'clock; arcs start there. It adds no tap target. Continue reading, opening artwork, and reader-header covers suppress it. Unsaved books remain eligible. Account changes replace only account overlays; device-local resume survives.

Reset/Stop preserve local bookmark/progress and history. Finish preserves bookmark and clears active tracking. Cache clearing retains every progress track and signed event.

## Verification checklist and follow-up

Check one/short chapters, first/last chapter entry versus real bottom, unknown/missing/truncated order, rounding, backward motion, changed content/order, legacy JSON, exit before debounce, and footer offsets. Exercise local-versus-synced precedence, tracked zero, explicit finish/reread, Reset in an unchanged viewport, Stop, account switching, offline reopen, and failed reading-state writes.

On device verify smallest covers/monogram fallback, themes, large text, TalkBack, three suppressed surfaces, action sequencing, and uniform reader/badge state. Added model coverage/static review is not a runtime result; [the owner runs verification](../DEVELOPMENT.md#build-and-test-ownership).

[Semantic anchors/text-weighted intermediate percentages](precise-reader-progress.md) remain separate work and can replace the approximate fraction without changing badge ownership.