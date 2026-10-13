# ADR 0062: Refresh downloaded books

- Status: Accepted
- Date: 2026-10-13
- Extends: [0030](0030-offline-reader-content-cache.md), [0061](0061-nested-publication-indexes.md)

## Context

A saved publication coordinate can acquire a newer root index, nested indexes, or chapter events. Ordinary opening uses the caller's resolved root revision and can prefer historical event-ID hints. Opening that summary repeatedly therefore does not guarantee a current downloaded copy.

## Decision

Provide an explicit **Refresh downloaded copy** action for saved books. Require validated internet and reuse the cancellable book-opening job and loading screen. Successful refresh opens the result with normal coordinate/occurrence bookmark recovery and user feedback.

Resolve root and nested index coordinates through HTTP, configured index relays, and secure reference relay hints. Consult these routes together and choose the newest matching returned event using the existing repository ordering. Ignore historical event-ID hints on this path. Fetch chapters through coordinate requests to chapter relays and HTTP, comparing returned revisions even when a relay supplied a body. Retain existing trust adapters, expansion limits, and ordinary open/broadcast behavior.

Refresh requires complete structure, all bodies, and no reader truncation. Do not merge stale cache fallback into a successful refresh. Compare the root against the newest caller/cached summary and compare overlapping chapters against cached revisions to reject regressions. Render before atomically saving through `OfflineBookCache`; propagate persistence errors. Keep the previous snapshot on incomplete download or snapshot write failure. Raw content remains the renderer's supported fallback.

Update saved summary metadata under the local bookshelf mutex without changing directory tags, membership, or signer state. Do not restore membership if the book was removed during the request. Bookmarks, ratings, highlights, review outbox, and reading-account records remain independent. Snapshot and saved-summary writes are separate: a metadata-write failure can leave a refreshed offline snapshot with old library metadata, and retry is safe.

## Consequences and validation

- Refresh is user initiated; there is no automatic update polling or update-available badge.
- The app chooses among returned verified revisions; unavailable routes cannot establish global freshness.
- Books beyond the existing 500-body loading cap cannot replace their download through this complete-refresh path.
- Regression coverage exercises changed root/nested/chapter revisions, old event-ID hints, rendered snapshot replacement, incomplete structure, older chapters, offline reopening, and metadata persistence without membership changes.
- Gradle verification is owner-run per repository instructions and has not been run by the agent. Device acceptance: download a book, republish the same coordinates with changed/reordered contents, refresh from its saved-book menu, confirm updated text and recovered bookmark, then reopen offline. Repeat with a missing branch and verify the previous download remains readable.
