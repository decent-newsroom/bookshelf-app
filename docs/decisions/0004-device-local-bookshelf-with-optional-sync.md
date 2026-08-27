# ADR 0004: Device-Local Bookshelf with Optional Nostr Sync

## Status

Accepted

## Context

My Books previously lived only in process memory and saving was gated on an Android Nostr signer. Sign-out and an empty remote kind `30045` directory cleared local state. This made login part of basic bookshelf use even though its intended role is sharing and synchronization.

The reading and collecting experience must work without an account, network access, signer app, or relay availability. A failed or rejected sharing attempt must not undo a device-local save.

## Decision

`LocalBookshelfStore` is the source of truth for the on-device bookshelf. It stores normalized directory tags and matching `BookSummary` records in an atomically replaced file at `context.filesDir/bookshelf/local-v1.json`. Corrupt storage is treated as an empty, usable bookshelf.

Save and remove actions always commit to local storage. When a signer session is active, the resulting local tags are then offered to the signer and published as kind `30045`; rejection or relay failure leaves the local change intact. Sign-out does not alter My Books.

Remote kind `30045` reads merge valid, deduplicated references with local tags in local-first order. A missing remote directory leaves the device bookshelf unchanged. Resolved remote summaries are combined with existing local summaries so temporary Mercury failures do not discard readable local entries.

Login and signer UI is described as optional sync and sharing rather than a prerequisite for collecting books.

## Consequences

- My Books survives process restarts and works while signed out or offline.
- The app stores bookshelf data as durable user data under `filesDir`, not as evictable cache data.
- Sharing remains explicit because each published update still requires signer approval.
- Merge behavior favors retaining data. Concurrent removals made on another device can reappear during a later merge; conflict-aware tombstones would require a future protocol and storage decision.
