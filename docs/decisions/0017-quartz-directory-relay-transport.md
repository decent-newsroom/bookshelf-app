# ADR 0017: Quartz directory relay transport and explainable publication

## Status

Accepted

Supersedes the relay-transport portion of ADR 0012. ADR 0012 remains the
source of the external-signing and event-validation requirements.

## Context

The first directory relay client constructed NIP-01 messages directly with
OkHttp. It opened a socket per operation and reduced every relay result to a
Boolean, concealing whether a relay rejected an event, timed out, disconnected,
or could not be contacted. The five-second operation timeout could also expire
before the configured WebSocket connection timeout.

Quartz is already the app's Nostr crypto dependency and provides a pooled
`NostrClient`, OkHttp WebSocket adapter, validated relay URL normalization,
multi-relay fetch helpers, and per-relay publication confirmations.

Quartz's stock `RelayAuthenticator` deliberately treats an `AUTH` message as
an interactive authentication opportunity. Bookshelf must not do that: public
relays may advertise a challenge, and an unsolicited challenge must not launch
Amber. Its auth-required retry behavior also targets `CLOSED` request failures,
whereas directory publication receives `OK false` failures.

## Decision

Directory, profile, and known-relay publication-index operations use one
application-scoped Quartz `NostrClient`. Quartz owns the WebSocket pool,
subscriptions, NIP-01 command encoding, reconnects, and `OK` collection.
`NostrRelayClient` remains the application-facing adapter and maps only
verified app events to and from Quartz event values.

Directory publishing uses Quartz `publishAndCollectResults` with a 15-second
confirmation deadline. Every configured relay gets a terminal outcome:
accepted, rejected, authentication-required/failed, transport failure,
protocol failure, or timeout. Relay-provided reasons are whitespace-normalized
and length-bounded before being surfaced or logged. The UI reports failed
relay URLs and reasons; logs contain only operation, public event ID, relay,
category, and bounded reason.

A small Quartz connection-listener adapter retains the app's lazy NIP-42
policy. It remembers an `AUTH` challenge without prompting. Only a directory
event `OK false` auth-required response creates a validated kind `22242` draft
through the existing Android signer bridge. An accepted auth result retries
that relay's directory event once. Session changes, invalid signer results,
missing challenges, rejected auth, and a second auth-required result fail the
relay attempt without looping.

## Consequences

- A 5-second custom socket timeout no longer determines directory publication.
- The relay transport is shared and no longer hand-implements NIP-01 frames.
- The app keeps its strict signer-returned and relay-event verification
  boundary; Quartz parsing never makes an event trusted application data.
- Existing default relays remain unchanged; ADR 0018 moves the separate chapter client to Quartz.
- The app-specific lazy auth adapter is retained because Quartz's general
  interactive authentication policy is intentionally different.
