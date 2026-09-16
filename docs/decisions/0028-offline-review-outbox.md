# ADR 0028: Persist signed reviews before relay delivery

- Status: Accepted
- Date: 2026-09-16

## Context

Readers can create reviews while the device has no validated internet connection. Direct publication makes a signed review disappear on relay failure and cannot distinguish delivery to the optional local Citrine relay from delivery to remote relays.

## Decision

Persist each signed R1 rating event in an app-private `filesDir/bookshelf` review outbox before relay work. Reuse the same immutable event ID for every retry and merge the event into the separate rating cache so it is visible locally immediately.

Use `ValidatedInternetConnectivity` as the application-wide definition of online. Without validated internet, rating reads use cache only and the dispatcher may attempt the configured local Citrine endpoint. When validated connectivity returns, the dispatcher resolves the normal remote rating routes and publishes only to outstanding remote destinations. It records Citrine and remote acknowledgements independently and retains failed items with bounded exponential retry metadata.

## Consequences

- An unsynced review survives process restart and cache clearing.
- Citrine delivery does not imply remote delivery, and remote relay failure does not prevent delivery to another relay.
- The outbox uses the existing Quartz client and signer/verification boundaries; it does not introduce a parallel relay transport.
- Remote delivery is deferred when offline, while a reachable local Citrine relay remains useful in airplane mode.