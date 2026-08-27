# ADR 0003: Cache Nostr Profile Metadata on Login

## Status

Accepted

## Context

The signer login result identifies an account by pubkey and signer package, but it does not provide the human-readable name needed for personalized Home and Settings copy. Nostr kind `0` metadata is replaceable and may be unavailable temporarily when relays cannot be reached.

Profile lookup is related to identity presentation, not to the kind `30045` bookshelf directory's validation and publication rules. A profile failure must not turn a valid signer login into a failed login or prevent collection synchronization.

## Decision

When a signer session becomes active, the ViewModel first exposes any cached kind `0` event for that pubkey and then performs a best-effort refresh against the existing identity/directory relay set. The relay client queries kind `0` by author, accepts only matching events, and chooses the newest `created_at` result across relays.

The complete event is atomically cached per pubkey under `context.cacheDir/nostr-profiles/v1`. Presentation parses `display_name`, with `displayName` compatibility and `name` fallback, normalizes whitespace, and bounds displayed names to 80 characters. Cached data is scoped to the active pubkey before it enters UI state.

Profile loading remains a separate repository from `BookshelfRelaySync`. Its failures are non-fatal and preserve both the signer session and any previously cached name.

## Consequences

- Home can greet a known account immediately after cached data is read, while Settings can identify the logged-in account by name.
- Restored signer sessions also refresh profile metadata without requiring another signer interaction.
- Profile metadata is cache data and may be evicted by Android; the pubkey remains the UI fallback until a profile is available.
- Kind `0` lookup adds a best-effort relay request when a session becomes active, independently of directory synchronization.
