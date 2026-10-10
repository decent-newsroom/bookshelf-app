# ADR 0060: User completion of pending publications

## Status

Accepted. Extends ADRs 0028 and 0045 with explicit user completion; automatic delivery criteria remain unchanged.

## Context

A user may be satisfied with a highlight or review's current distribution even while unavailable destinations keep its delivery pending. Retrying indefinitely and retaining failure messages no longer serves that user's intent.

## Decision

Add a secondary **Clear pending** action beside **Retry now** in Settings > Storage & Offline. Explain that it accepts current distribution as delivered and stops retries for pending highlights and reviews. It is separate from disposable cache clearing and does not affect reading-list publications.

Persist an optional `completedByUserAtMillis` on each incomplete outbox entry. This terminal marker makes the entry complete regardless of unresolved or failed routes. Clear its entry-level and highlight/chapter failure messages. Retain signed events, original IDs, local highlight links, attempt history, and actual relay acknowledgement states. Do not invent relay acknowledgements. Existing files default to no marker and keep their current automatic completion semantics.

Serialize each queue's completion through its dispatcher mutex so an active publication finishes first. Reject subsequent updates to user-completed entries, including stale delivery updates. Re-enqueuing the same signed event does not restart delivery. New signed events remain independently pending. Persist each queue atomically using its existing file writer; completion across the two queues is independent and reports failures individually. Unreadable review files must throw without replacing the file, rather than silently loading an empty queue.

Emit revisions only after durable writes and observe both outboxes for pending counts. Reader highlight/review status also reflects user completion. This action requires no signer, connectivity, or new relay work.

## Consequences

The pending counts and error details stay cleared after restart and automatic retry. User completion means distribution was accepted by the user; it is distinct from proof of storage at every relay. Stored acknowledgement history remains available. A running delivery can delay completion until it releases the dispatcher mutex.

## Verification

Regression tests cover persisted completion with unresolved routes, cleared pair/entry failures, retained signed events and associations, stale update protection, repeated enqueue/delivery suppression, new reviews remaining pending, and corrupt review files remaining untouched. Gradle verification is owner-run per `AGENTS.md`; tests have not been executed by the agent.
