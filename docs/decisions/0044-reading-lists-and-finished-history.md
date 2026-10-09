# ADR 0044: Private reading state with explicit public synchronization

- Status: Accepted
- Date: 2026-10-08
- Preserves: ADR 0015's device-local resume location and the independent saved-book directory.

## Context

A resume location answers where this device should reopen a book. It does not express an explicit reading list or a completed-book history. Sharing either list requires signed public events, while guests and readers who prefer privacy need a durable local experience. Kind 16374 replaces the entire account list, so publishing an isolated book can erase books tracked by another device. An explicit finished label must reach a relay before the corresponding list removal.

## Decision

Own reading state in an application-scoped `ReadingStateRepository`, separate from saved-book membership, reader preferences, disposable caches, and the review/highlight outboxes. Persist guest/account state, unsigned operations, and a logical reading outbox together in the versioned atomic `filesDir/bookshelf/reading-state-v1.json` file, with cached publication summaries for offline lists. Signing in never assigns guest entries to the account. Independent reading-list and finished-history privacy preferences default to device-only.

Public tracking uses one kind 16374 replaceable snapshot with empty content, edition `a` tags and `book` tuples containing edition coordinate, zero-based section position, complete section total, optional section event ID, and update timestamp. Public completion uses kind 1985 with namespace `ugc`, label `read`, and the edition `a` tag. Both use `client=Bookshelf`. Verify all remote and signer-returned events at the existing Nostr trust boundary, including exact signer-draft matching. Choose the newest public snapshot by NIP-01 timestamp and ID ordering; never union historical snapshots.

Persist pending operations before signing and immutable signed events before sending. Refresh the newest available public list before applying local operations and creating a replacement; a failed read cannot stand in for an empty list. Preserve unrelated books and unfamiliar tags. A dedicated reading outbox keeps exact signed event IDs, account ownership, relay results, and label-before-removal dependencies across retries. Remote routes use configured relays and the active account's NIP-65 routes only when validated internet is available. Explicitly public signed events may reach configured local Citrine offline. Device-only actions perform no reading-event relay work.

Section transitions update the tracked maximum immediately and schedule publication three seconds after the first advance in a burst; further transitions do not postpone the window. Reset is the only action that reduces tracked position. Stop removes tracking without changing finished history. Reaching the last section does not finish a book. Retain complete publication section positions before the 500-section body-loading cap, including missing bodies, and hide book-end actions for a truncated reader.

NIP-55 content-provider signing is used only with signer permission. Scrolling never launches foreground signer UI. Changes remain pending when background permission is absent; an explicit Sync action offers foreground signing through the serialized app signer bridge. Account changes cancel stale signer work.

Settings previews existing private entries before public sharing and uploads only selected entries. Subsequent actions follow that list's preference. Returning to device-only pauses undelivered work and retains already public data; it does not retract previously published events. Finished history and public tracking remain independently configurable.

## Presentation

Reader metadata exposes Track, Reset, Stop, section progress, and delivery feedback. Two end-of-book cards offer explicit Finish and, for signed-in readers, the existing review form inline. Review publication remains independent of completion and retains the review outbox. Home shows all tracked books in Reading now; My Books offers Saved, Reading, and Finished views. Finished books stay out of ordinary Continue reading until explicit rereading, while completion history remains intact. A device-local resume position wins over a synchronized section position.

## Consequences and limits

Legacy offline snapshots with 500 sections and no explicit stream-known field may be prefixes from the old silent cap. They remain readable but hide tracking/end claims until refreshed. New snapshots persist the flag explicitly; shorter legacy snapshots remain compatible.

Reading and completion survive cache clearing and do not require saved-library membership. Compact completed/superseded progress deliveries while retaining the newest snapshot as the synchronization baseline, pending events, prerequisite labels, and the latest label for each edition. A newly added removal destination receives its prerequisite label even when the label's previous routes completed. Whole-list synchronization can merge pending local operations against a fetched snapshot, but simultaneous disconnected writers still follow Nostr last-writer replacement behavior. All tracked books are active; waiting slots, undo finished, another person's history, recursive publication streams, and synchronized in-chapter anchors remain follow-ups.

## Verification

Focused tests cover event parsing/building, malformed and optional fields, snapshot ordering, monotonic advances and reset/removal rebasing, fixed publication windows, privacy/account boundaries, durable delivery identity, and completion dependencies. UI scenarios cover missing chapters, the body-loading cap, inline review account changes, immediate list updates, and resume precedence. Gradle verification remains owner-run under `AGENTS.md`; agents must not run it.
