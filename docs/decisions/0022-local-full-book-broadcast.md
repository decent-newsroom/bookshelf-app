# ADR 0022: Explicit full-book broadcast to a local relay

- Status: Accepted
- Date: 2026-09-02

## Context

A configured relay running on the device can be useful for keeping publication content locally available. A publication summary is not sufficient to reconstruct Nostr events because doing so would invalidate the publisher's signatures.

## Decision

A long-press book action is shown only when a local relay is configured. On request, Bookshelf re-fetches the original verified kind `30040` index and every available verified referenced kind `30041` chapter. It queues the index first and chapters in index order, then publishes each original signed event to exactly the configured local relay through the shared Quartz transport.

Missing chapter events are omitted and reported. Bookshelf never synthesizes, re-signs, or changes publisher events. This broadcast is separate from account directory sync and NIP-65 routing.

## Consequences

The action can populate Citrine or another local relay with a complete book when all source events remain available. Partial source availability produces a transparent partial broadcast. Relay acknowledgements are counted per event.