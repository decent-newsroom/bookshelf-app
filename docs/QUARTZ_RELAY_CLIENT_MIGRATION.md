# Quartz Relay Client Migration Specification

## Status

Implemented in the current development version. This document records the
migration requirements, decisions, and verification checklist.

## Problem

The directory/profile relay path hand-builds NIP-01 WebSocket messages in
`NostrRelayClient`. It opens a new socket for every one-shot operation and
reduces relay rejection reasons, socket failures, close codes, and timeouts to
a Boolean. Consequently, a signed directory that is rejected, times out, or
cannot connect produces the same user-visible result: “No relay accepted the
directory update.”

Quartz `1.13.1` is already a project dependency for Nostr event hashing,
signature verification, and NIP-19 decoding. Its relay client provides an
OkHttp socket adapter, a shared `NostrClient`, NIP-42 coordination, and
one-shot publish-confirmation helpers. The migration will make Quartz the
directory/profile relay transport rather than maintaining a second relay state
machine in the app.

## Scope

Replace the transport implementation behind the directory-sync and profile
paths currently using `NostrRelayClient`:

- publish kind `30045` bookshelf directories;
- fetch the latest kind `30045` directory and kind `0` profile metadata; and
- resolve exact kind `30040` publication indexes from the known bookshelf
  relays.

Chapter transport was initially out of scope; it is now Quartz-backed under
ADR 0018 while retaining its distinct relay list and request policy.
The configured default directory relays remain unchanged:

- `wss://relay.decentnewsroom.com`
- `wss://thecitadel.nostr1.com`
- `wss://pipe.imwald.eu`

User relay-list discovery is also out of scope.

## Required Design

### Shared client and lifecycle

`AppGraph` constructs one application-scoped Quartz `NostrClient` with the
existing configured `OkHttpClient` through Quartz’s OkHttp WebSocket adapter.
The client is owned by a new directory-relay transport boundary, not by a
Composable or a `ViewModel`.

The boundary is process-scoped. One-shot operations use Quartz’s supported
`fetchAll` and `publishAndCollectResults` helpers instead of manually opening
a WebSocket and waiting for an `OK` frame.

### External signing and NIP-42

The app continues to own the user-facing Android signer interaction. Private
keys must never enter Bookshelf, Quartz, or the relay transport.

- The existing Activity-result request ID, selected signer package, and
  signed-directory validation remain the authority for the initial kind
  `30045` signature.
- Before a signed event is submitted to Quartz, retain the current checks for
  the expected account, kind, timestamp, content, tags, canonical event ID,
  and Schnorr signature.
- Use a Quartz connection-listener adapter which creates the existing pending
  Amber signing request only after an auth-required publication result and
  waits for its Activity result. Do not install Quartz's stock
  `RelayAuthenticator`: it treats unsolicited `AUTH` as interactive, which
  would regress the lazy signer-prompt rule.
- An unsolicited `AUTH` challenge is stored only. It must not prompt Amber.
  Authentication is requested only after the protected operation is rejected
  as auth-required, then retried once after accepted authentication.
- A session change, signer rejection, malformed challenge, failed auth event,
  or second auth-required result terminates that relay attempt. It cannot loop
  or produce a duplicate successful directory publication.

### Trust boundary and event mapping

Quartz protocol objects are transport values, not trusted application data.
Keep `NostrEventVerifier` as the single ingress boundary for relay events and
signed events returned by Amber. Map between the app’s `NostrEvent` and the
Quartz event type in one dedicated adapter; do not spread conversion code
across repositories or UI code.

Inbound directory, profile, and publication-index events retain their current
request context checks: kind, author, `d` tag where applicable, requested
coordinate/event ID, bounded fields, canonical ID, and Schnorr signature.
Newest-event selection remains deterministic by `created_at`, then event ID.

### Per-relay outcomes

Replace the Boolean-only publication result with a structured report. Each
attempt must include the normalized relay URL and exactly one terminal outcome:

- accepted;
- rejected, with the relay’s NIP-01 `OK` reason when supplied;
- authentication required, rejected, or unavailable;
- connection/transport failure;
- protocol failure; or
- timeout.

The report must retain the signed directory event ID and derive accepted and
attempted counts from its outcomes. It must not log or expose private keys,
unsigned signer payloads, signatures beyond the already-public event ID, or
unbounded relay payloads. Bound and sanitize relay-provided reasons before
showing or logging them.

The normal publish deadline must be at least the configured socket connection
deadline plus a bounded acknowledgement interval; it must not be shorter than
the WebSocket connection timeout. Auth approval keeps its separate bounded
deadline.

### UI and observability

On success, continue showing the existing accepted/attempted summary. On
partial success or failure, show a concise result that identifies each relay
and its category/reason, for example:

`Shared with 1/3 relays. Citadel: accepted; Pipe: timed out; Decent Newsroom:
rejected (rate limited).`

Emit structured Android logs for every terminal relay outcome, tagged with the
relay URL, event ID, operation, category, elapsed time, close code where
available, and bounded reason. Do not write event content, full tags, or
signer payloads to logs. The Settings retry action remains available after any
unsuccessful report.

## Migration Steps

1. Confirm the exact Quartz `1.13.1` APIs against the resolved dependency and
   add a small adapter layer around `NostrClient`, `fetchAll`,
   `publishAndCollectResults`, relay filters, and the lazy auth listener.
2. Share that client between directory, profile, and publication-index
   operations in `AppGraph`.
3. Retain `NostrRelayClient` as the application-facing adapter, preserving the
   local-first behavior of `BookshelfRelaySync` and `NostrProfileRepository`.
4. Bridge the lazy Quartz listener to the existing external-signer
   Activity-result flow without replacing its request-ID matching and
   cancellation guarantees.
5. Replace the Boolean-only `PublishReport` with a structured report and
   update Settings/UI messages and safe logs.
6. Record the boundary in `ARCHITECTURE.md`, ADR 0017, and the changelog.


## Acceptance Criteria

- A successful kind `30045` publish reports the accepting relay URLs and the
  signed event ID.
- An `OK false` response preserves a bounded rejection reason for that relay.
- A DNS/TLS/WebSocket failure, close before acknowledgement, and timeout are
  distinguishable in the report and logs.
- A slow initial connection is not prematurely classified as a five-second
  publish timeout.
- A public relay sending an unsolicited `AUTH` does not launch Amber.
- An auth-required relay prompts Amber once, sends a validated kind `22242`
  event, and retries the protected publish once after accepted auth.
- A rejected or malformed Amber result never reaches Quartz publishing.
- Relay-returned events still fail closed unless `NostrEventVerifier` accepts
  them with their request context.
- Local My Books data remains committed before publish and remains untouched
  by relay failure; **Sync to relays** remains retryable and **Sync from
  relays** remains a separate merge operation.
- Chapter fetching remains behaviorally unchanged.

## Test Matrix

Use a controllable WebSocket relay fixture rather than production relays for
automated tests. Cover:

- accepted `OK`, rejected `OK` with reason, malformed protocol frame, socket
  failure, early close, and acknowledgement timeout;
- one accepted plus failed/rejected peers, and all peers failing;
- unsolicited `AUTH`, auth-required publish, accepted/rejected auth `OK`,
  malformed/missing challenge, signer rejection, timeout, and repeated
  auth-required response;
- strict event conversion and verification at both ingress and Amber return;
- exact kind/author/`d`-tag filters for directory and publication-index reads;
- application lifecycle shutdown without leaked client jobs or sockets; and
- UI copy for full success, partial success, and no acceptance.

Before release, perform a manual publish using Amber to each configured relay,
record the per-relay outcomes without publishing test events to a user account,
and verify a fresh install can pull the accepted directory.

## Non-goals

- Changing the event kind, directory tags, or local persistence format.
- Storing private keys or a reusable signed-event queue.
- Treating a relay’s reported acceptance as proof that another relay has
  replicated the event.
- Moving chapter relay transport to Quartz in this version.
