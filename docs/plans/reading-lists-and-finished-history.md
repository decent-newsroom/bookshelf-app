# Reading lists, finished history, and inline reviews

Status: Implemented. [ADR 0044](../decisions/0044-reading-lists-and-finished-history.md) owns state/privacy/delivery; [ADR 0046](../decisions/0046-coordinated-reader-progress-and-thumbnail-indicators.md) coordinates local resume and section tracking. Current [architecture](../ARCHITECTURE.md#reading-lists-and-finished-history) is the behavior reference.

## Delivered

Explicit tracking and finished history remain separate from automatic device-local resume and saved-book membership. `ReadingStateRepository` stores guest/account partitions, summaries, independent device-only/public preferences, pending operations, and logical signed outbox in `filesDir/bookshelf/reading-state-v1.json`. Cache clearing preserves it.

Public tracking is one kind-16374 replaceable snapshot; completion is an explicit kind-1985 read label. Fetch/rebase the latest snapshot before replacement, preserve unrelated entries/tags, and never treat read failure as empty. Signed IDs survive retries. Where both lists are public, each destination receives the completion label before snapshot removal.

Tracking uses full ordered section totals, including missing bodies/beyond the loaded cap. Genuine transitions advance monotonically with a fixed three-second publication window; Reset/Stop invalidate stale advances and preserve local bookmarks/history. Known complete order gates end cards; arrival alone never finishes. Explicit Finish and the signed-in inline review composer operate independently.

Guest data never silently migrates on login. Public mode requires a signer and selected-entry preview. Device-only changes start no reading-event relay work; permitted public local-Citrine delivery can occur offline. Remote work requires validated internet. Background signing needs permission; scrolling never launches foreground signer UI.

Presentation has evolved: [ADR 0050](../decisions/0050-unified-reader-progress-controls.md) unifies reader controls; [ADR 0051](../decisions/0051-home-reading-history-and-saved-library.md) makes My Books saved-only and places Reading now/Finished on Home; [ADR 0053](../decisions/0053-home-reading-now-actions.md) adds compact tracked cards and coordinate-specific Stop tracking. Earlier Saved/Reading/Finished sub-tabs are superseded.

## Verification checklist

Cover wire examples/malformed events, optional/empty lists, signature/draft checks and replacement ordering; monotonic advances/reset/stop/fixed windows; unknown/missing/500-section boundaries; privacy selection and guest/account isolation; signer rejection/permission/restart; immutable IDs and destination-dependent label-before-removal; failed reads/two-device rebasing; cache clearing and independent membership.

Verify inline reviews/prefill/editing and logout behavior, explicit Finish/reread, local-versus-synced resume, footer offsets, Home list ordering/unresolved metadata, and narrow/theme/accessibility states. [The owner runs builds/tests and device checks](../DEVELOPMENT.md#build-and-test-ownership).

## Deferred

Waiting/promoted slots, undo finished, other people's histories, recursive publication streams, and synchronized in-chapter anchors. Local semantic resume is separately [planned](precise-reader-progress.md).
