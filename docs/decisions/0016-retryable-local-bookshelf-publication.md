# ADR 0016: Retryable Local Bookshelf Publication

## Status

Accepted

## Context

Saving or removing a book writes the local bookshelf first and then offers the
resulting kind `30045` directory to an Android Nostr signer. If signing is
rejected or no relay accepts the signed event, that one-shot request is gone.
The existing Settings action only fetched a directory from relays, so users
could not actively publish their retained local state without making another
book change.

## Decision

Keep the device-local bookshelf as the source of truth and add a separate
Settings action that signs and publishes its complete current directory. The
action is available whenever a signer session is active and no signer, pull,
or publish operation is already running. A failed relay state labels the same
action as a retry. Every invocation still requires the external signer to
approve a freshly created event; no signed event or retry queue is persisted.

Keep **Sync from relays** as a distinct, local-first merge operation. A manual
publish does not fetch or merge remote summaries because it sends the current
local directory unchanged.

## Consequences

- Users can resend their local collection after a transient relay failure or
  rejected signer request without changing saved books.
- The app does not retain a signed event, so retries use a fresh timestamp and
  require explicit signer approval.
- Pull and push remain explicit, avoiding an unintended remote merge when the
  user only wants to publish local state.