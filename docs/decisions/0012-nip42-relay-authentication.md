# ADR 0012: Signer-neutral bounded NIP-42 relay authentication

## Status

Accepted

## Context

Some Nostr relays require NIP-42 authentication before accepting an event or
serving a subscription. The relay transport must handle `AUTH` challenges
without moving private-key operations into the app's WebSocket client. A
challenge may arrive proactively, or a request may be closed/rejected with an
`auth-required` reason.

## Decision

`NostrRelayAuthenticator` is an injectable, signer-neutral boundary. It
receives a canonical `NostrAuthEventDraft` and returns a signed event; it never
receives or exposes private key material. `NostrAuthEventValidator` requires
kind 22242, empty content, exactly the ordered `relay` and `challenge` tags,
the configured public key, a valid NIP-01 id/signature, and a bounded event
timestamp.

`NostrRelayClient` recognizes `AUTH` and stores a valid challenge without
authenticating proactively; some relays advertise AUTH on public connections.
Only an `auth-required` response starts one validated auth event per
connection, after which the original publish or subscription is retried at
most once after an accepted auth response. A missing authenticator, malformed
challenge, auth rejection, or missing challenge fails safely and does not
invent context or loop. The `ExternalSignerNostrRelayAuthenticator` bridges
the Activity callback through one serialized pending request, cancels it on
session changes, and gives active auth signing a bounded 90-second approval
window. The callback remains outside the relay client's private-key boundary.

## Consequences

- Relay communications can authenticate with external, hardware, or test
  signers while preserving the existing event verification boundary.
- Authenticated retry cannot create an uncontrolled publish loop or duplicate
  an already accepted event.
- Public relay reads continue to work with no authenticator configured.
- Other signer adapters can implement the same provider boundary without
  changing relay transport or moving private-key operations into the app.
