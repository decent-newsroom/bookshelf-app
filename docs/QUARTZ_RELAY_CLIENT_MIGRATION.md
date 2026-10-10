# Quartz relay migration record

Status: Implemented. [ADR 0017](decisions/0017-quartz-directory-relay-transport.md) replaced directory/profile/index transport; [ADR 0018](decisions/0018-quartz-chapter-relay-transport.md) subsequently migrated chapters. [ADR 0019](decisions/0019-nip65-user-relay-routing.md) added verified user routing. See [current transport ownership and defaults](ARCHITECTURE.md#relay-transport-and-identity).

## Delivered boundary

Quartz owns sockets, pooling, subscription/protocol encoding, reconnects, and publish confirmations. The process-scoped directory client serves directory/profile/index and rating operations; chapters retain their own client/settings/lifecycle. The application-facing `NostrRelayClient` adapter preserves verified-event mapping and local-first bookshelf behavior.

The external Android signer remains authoritative. Unsolicited AUTH never prompts; an auth-required operation can request one validated signature and one retry. Session changes cancel stale requests. Quartz protocol values are not trusted application events: `NostrEventVerifier` and exact request/draft checks still apply.

Structured per-relay reports preserve signed event ID, acceptance, bounded rejection reason, authentication/transport/protocol failure, and timeout. Safe logs omit event contents and signer payloads. Saving remains local-first; Sync to relays retries publication and Sync from relays merges independently.

## Verification checklist

Use controllable relay fixtures for automated checks:

- Accepted/rejected OK, bounded reasons, malformed frames, DNS/TLS/socket failure, early close, and acknowledgement timeout.
- Successful peers with failing peers, all-relay failure, and a connection deadline that is not prematurely truncated.
- Unsolicited AUTH, auth-required operation, accepted/rejected auth, malformed/missing challenge, signer rejection/timeout, repeated auth-required, and account changes.
- Strict event conversion, signature/context checks, exact kind/author/d filters, and immutable signed payloads.
- Subscription multiplexing, EOSE/CLOSE cleanup, cancellation/reconnect behavior, and preserved independent chapter routing.
- Settings full/partial/failure copy and retry behavior without losing local books.

Manual signer/relay checks should use an explicitly approved test account, then verify a fresh install can pull the directory. Builds/tests and device checks are [owner-run](DEVELOPMENT.md#build-and-test-ownership); this checklist is not evidence of runtime completion.

## Limits

This migration does not change event kinds or give relay acceptance the meaning of cross-relay replication. Durable review/highlight/reading outboxes are separate later features, not guarantees supplied by Quartz transport itself.