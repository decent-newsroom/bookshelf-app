# ADR 0037: Add a dedicated relay for kind-0 profile lookup

- Status: Accepted
- Date: 2026-09-23
- Extends: ADR 0003's profile cache and ADR 0027's reviewer-profile resolution

## Context

The app looks up verified kind-0 metadata for the active signer, book publishers, and review authors. It first reads the cached profile, then refreshes from the current read relays. Those relays are selected for directory and other Nostr traffic and may not hold a requested profile, leaving only a pubkey fallback despite an available profile on a profile-focused relay.

## Decision

Append `wss://profiles.nostr1.com` to the current read-relay set for kind-0 profile lookups. Keep the existing read relays in that lookup and continue to verify profile events before caching them by pubkey. Do not add the dedicated relay to the shared default read/write routes, active signer's NIP-65 routes, directory synchronization, publication-index lookup, ratings, or chapter fetching.

## Consequences

- Profile refresh has another source for publisher, reviewer, and active-signer names while cached metadata remains available immediately and on lookup failure.
- The dedicated relay receives kind-0 lookup requests. Its failure cannot block other read relays or directory synchronization.
- Relay routing for non-profile operations remains unchanged.
