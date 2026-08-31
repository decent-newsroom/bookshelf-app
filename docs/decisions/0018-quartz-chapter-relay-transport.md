# ADR 0018: Quartz chapter relay subscriptions

## Status

Accepted

Supersedes the socket-implementation portion of ADR 0001. ADR 0001 remains
accepted for chapter relay selection, event matching, and Mercury fallback.

## Context

`PersistentNostrChapterSource` previously owned OkHttp WebSockets, manually
encoded NIP-01 `REQ` and `CLOSE` frames, decoded relay messages, and maintained
per-relay subscription bookkeeping. Directory, profile, and publication-index
traffic already use Quartz for those protocol responsibilities.

Chapter reads still require a separate client boundary: their editable relay
list, publication relay hints, kind `30041` filters, short read deadline, and
Mercury fallback do not match bookshelf-directory synchronization. Sharing the
directory client would merge unrelated relay policy and lifecycle state.

## Decision

`PersistentNostrChapterSource` owns a dedicated process-scoped Quartz
`NostrClient` with Quartz's OkHttp socket adapter. A chapter fetch builds the
same two filter families as before—event IDs, and author plus `d` tag
coordinates—and subscribes them to the selected configured and hinted relays.
Quartz owns relay pooling, reconnection/backoff, NIP-01 command encoding, and
subscription `CLOSE` delivery through `unsubscribe`.

A fetch completes after every selected relay reaches `EOSE` or fails, or after
its existing eight-second deadline. It preserves verified matching events from
successful peers, selects the newest event per chapter coordinate, and falls
back to Mercury only for unresolved chapters. All-relay connection failure is
reported as a chapter-relay failure; timeout returns the verified partial
snapshot for the existing fallback path.

Chapter traffic uses a separate Quartz client from directory/profile traffic.
The distinct relay configuration and read-only policy are preserved; no Amber
or NIP-42 signer interaction is added to chapter reads.

## Consequences

- The app no longer hand-builds Nostr WebSocket frames for chapter retrieval.
- Quartz supplies subscription multiplexing, connection pooling, and retry
  behavior while chapter URL hints and timeout semantics stay app-owned.
- `NostrEventVerifier` remains the trust boundary before a relay event can
  become chapter content.
- Idle chapter relay connections are reconciled by Quartz from active
  subscriptions instead of being retained by application-owned WebSocket maps.