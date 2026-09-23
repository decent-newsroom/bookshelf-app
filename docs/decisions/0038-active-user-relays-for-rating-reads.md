# ADR 0038: Include active-user read relays in community rating lookups

- Status: Accepted
- Date: 2026-09-23
- Extends: ADR 0019's verified NIP-65 routing and ADR 0035's cache-first rating reads
- Supersedes: ADR 0023's configured-only rating read pool

## Context

Community ratings and recent-rating suggestions previously queried the configured relay set: the built-in defaults and optional local relay. A signed-in reader may advertise other read relays in a verified NIP-65 kind `10002` event. Reviews available there can be missing from the per-book rating list and recent-rating discovery even though the app already trusts and uses that account-specific read set for directory and profile traffic.

## Decision

Use the existing application-scoped Quartz relay client read-relay set for both exact per-book kind `34259` rating lookups and recent-rating discovery. It combines configured relays with the active signer's verified, normalized, bounded NIP-65 read relays, with deduplication. With no active signer or no discovered list, configured relays remain the pool. Keep `wss://profiles.nostr1.com` dedicated to kind-0 profile lookup; it is not a rating relay. Continue to verify returned rating events and apply the existing selection, cache, and aggregation rules.

The validated-internet gate remains: offline rating reads and suggestions use `BookRatingCache` only and start no remote rating relay work. Rating publication and the independent chapter relay pool are unchanged.

## Consequences

- A signed-in reader can find reviews on their advertised read relays in both book details and recent-rating discovery.
- More relays may receive rating queries while the reader is online. Existing normalization and limits bound the added relay set.
- Cache-first display and offline behavior remain available when signer discovery or remote relays fail.
