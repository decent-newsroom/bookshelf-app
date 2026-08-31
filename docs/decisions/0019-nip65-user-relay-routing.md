# ADR 0019: Verified NIP-65 user relay routing

## Status

Accepted

## Context

Bookshelf initially read and published directory metadata only through its
configured application relays. Users who publish their content to a personal
relay set could therefore have a valid signed kind `30045` directory that the
app did not find or did not send to the relays they use for writes.

NIP-65 defines a replaceable kind `10002` relay list. Its `r` tags identify a
relay and optionally mark it as `read` or `write`; an unmarked relay supports
both roles. The event is untrusted until its NIP-01 ID and signature are
verified for the active account.

## Decision

On account sign-in, `NostrRelayClient` fetches the latest kind `10002` event
for the active pubkey through the configured bootstrap relays. Directory and
profile reads also lazily retry that discovery when no list has been loaded
for their pubkey. Only a `NostrEventVerifier`-accepted event may alter relay
selection.

The app accepts only normalized `wss://` `r` tags with an absent/empty marker,
`read`, or `write`. Unmarked entries are added to both roles; read and write
entries are added only to their corresponding role. Invalid URLs, cleartext
URLs, unsupported role markers, and entries after twelve distinct relays per
role are ignored. Configured bootstrap relays remain in both target sets as
fallbacks.

Directory, profile, and publication-index fetches use the read set. Directory
publishing uses the write set and reports every selected relay outcome. The
list is memory-only, belongs only to the active signer pubkey, and is cleared
on sign-out. No private key, unsigned event payload, or user relay list is
persisted.

## Consequences

- Users can publish directories to their own advertised write relays and read
  them from their advertised read relays without changing app defaults.
- Bootstrap-relay outages leave the configured relay behavior available rather
  than blocking sign-in or sync.
- NIP-65 affects only Bookshelf metadata routing. Chapter relay settings and
  their independently selected relays remain unchanged.
- The bounded, verified, secure-WebSocket-only policy limits a remote event's
  ability to expand the app's network surface.