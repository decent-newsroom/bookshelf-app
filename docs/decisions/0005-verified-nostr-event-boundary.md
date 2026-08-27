# ADR 0005: Verify NIP-01 events at every external boundary

## Status

Accepted

## Context

Mercury responses, relay messages, profile cache entries, and signer responses all carry Nostr events. Relay filtering and claimed created_at values do not authenticate those events. Accepting an unverified newer event could replace valid content, profile metadata, or directory state.

## Decision

All decoded Nostr events pass through NostrEventVerifier before feature code can inspect, compare, cache, render, merge, or publish them. The verifier enforces lowercase NIP-01 hex fields, bounded kinds/timestamps/tags/content, canonical ID recomputation using Quartz, Schnorr signature verification using Quartz, and optional request/author/kind/d-tag context. Newest-event selection uses created_at followed by event ID as a deterministic tie-breaker, and events more than 15 minutes in the future are rejected.

Mercury and relay paths additionally bind responses to requested IDs, authors, kinds, d-tags, and subscription IDs. Profile cache reads and writes and signer-returned directory events use the same boundary. Directory signer responses are also compared with the exact draft fields before publication.

## Consequences

Forged or malformed events are discarded before persistence or UI mapping, and a forged future event cannot win newest-event selection. Quartz remains responsible for secp256k1/Schnorr operations and canonical event hashing. The existing NostrEvent serialization model remains the wire format; VerifiedNostrEvent marks values produced by the verification boundary for new code. Signature verification may add CPU cost to large responses, bounded by the verifier event limits.
