# Reading lists, finished history, and inline reviews

This implementation record accompanies [ADR 0044](../decisions/0044-reading-lists-and-finished-history.md). The approved feature keeps device-local resume, explicit section tracking, and explicit finished history separate.

The proposed [thumbnail progress plan](book-thumbnail-reading-progress.md#cooperation-between-the-three-progress-tracks) defines follow-up coordination between local chapter presentation, Nostr section tracking, and physical scroll resume, including endpoint display, action sequencing, and shared presentation rules. Those coordination changes remain planned.

## User behavior

- Track reading under reader metadata; show zero-based position over the complete section total, Reset tracking, Stop tracking, and delivery status. Opening a book only saves its existing resume position.
- Advance immediately on visible section transitions, without reducing position during backward scrolling. Use a fixed three-second publication window after the first advance. Last-section arrival never finishes automatically.
- Show Finish and a signed-in inline review form after the actual final section. Finishing provides inline confirmation and removes active tracking while retaining completion history. Review submission uses the shared cached-review/editing/signing/outbox path independently.
- Home's Reading now includes all tracked books, regardless of saved membership. My Books switches between Saved, Reading, and Finished, with latest completions first. An explicit reread restores Continue reading eligibility without erasing finished history.
- Existing device-local chapter/offset resume takes precedence; a synchronized tracked section is the fallback when this device has no resume location.

## State and interoperability

`AppGraph` owns `ReadingStateRepository`. The versioned atomic `filesDir/bookshelf/reading-state-v1.json` file stores guest/account partitions, privacy preferences, cached summaries, pending unsigned operations, and the logical reading outbox's signed delivery records together. These are independent of saved-book membership, reader preferences, all cache clearing, and other outboxes.

Kind 16374 publishes one complete account reading snapshot with empty content, matching edition `a` tags, `client=Bookshelf`, and `book` tuples: coordinate, zero-based position, complete total, optional section ID, update timestamp. An empty snapshot removes all public tracking. Kind 1985 publishes an empty-content completed-book label with `L=ugc`, `l=read,ugc`, edition `a`, and `client=Bookshelf`. Verify signatures and exact signer payloads before accepting either.

Preserve full ordered section metadata before the existing 500-section loading cap. Missing chapter bodies keep their ordinal positions. Unknown ordering/total disables tracking with a loading hint; a loaded prefix does not count as the book end.

## Privacy and delivery

Independent Keep reading list on this device only and Keep finished books on this device only preferences default on. Public mode needs the active Android signer. Settings presents a selectable preview before sharing existing private entries, leaves unselected entries private, and warns that returning to device-only does not retract already published records. Guest entries never silently migrate to an account.

Keep pending operations durable before signing and signed IDs immutable across relay retries. The reading outbox owns per-relay delivery and dependencies. When both lists are public, a relay must acknowledge the finished label before the replacement snapshot removes that book. Private finished history with public tracking sends only the removal; private tracking removes locally.

Refresh on foreground, account activation, validated-connectivity recovery, and explicit Sync. Fetch the latest verified 16374 before rebasing pending operations and publishing, preserving unrelated books/unfamiliar tags. A read failure is not an empty snapshot. Do not union older snapshots. Simultaneous disconnected updates retain Nostr last-writer behavior.

Use configured and active-user NIP-65 routes; gate remote work on `ValidatedInternetConnectivity`. Explicit public signed events may reach configured local Citrine offline. Device-only operations start no reading-event relay work. NIP-55 background signing requires permission; when unavailable, retain pending work and offer explicit Sync without opening signer UI during scrolling. Foreground signing is serialized and account guarded.

## Acceptance and follow-ups

Cover wire examples, malformed events, optional fields, empty lists, signature/payload verification and replaceable ordering; monotonic section updates, reset/stop, fixed windows, footer indexing, missing bodies and the 500-section boundary; independent privacy, selection, guest/account isolation, signer rejection, restart and preserved IDs; failed reads, two-device rebasing and label-before-removal delivery. Verify inline reviews remain in the reader and disappear on logout, cached editing survives, and lists update without saved membership.

Builds and tests remain user-run using the Windows commands in `AGENTS.md`; no agent executes Gradle. Deferred: waiting/promoted slots, undo finished, other people's histories, recursive streams, and synchronized in-chapter anchors.
