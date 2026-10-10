# Offline review synchronization

Status: Durable review storage, validated-connectivity gating, local Citrine delivery, and deferred remote delivery are implemented under [ADR 0028](../decisions/0028-offline-review-outbox.md). [ADR 0034](../decisions/0034-default-network-connectivity-state.md) stabilizes connectivity across default-network handoffs. This record distinguishes implementation from the original proposal's broader scheduling promises.

## Implemented flow

1. Obtain and validate a signed kind-34259 review through the existing signer boundary.
2. Persist the immutable event in `filesDir/bookshelf/review-outbox-v1.json` before relay work, then merge it into the independent rating cache. These are separate writes; a cache failure cannot erase the durable event.
3. Attempt configured local Citrine, including offline. Its acknowledgement is independent of remote delivery.
4. With validated internet, resolve configured defaults, active-user NIP-65 write routes, and verified publication-author read routes; send the same signed ID only to outstanding destinations.
5. Persist per-route results, attempt count, next-retry time, and bounded failure. Exponential retry delay caps at 15 minutes.

`ValidatedInternetConnectivity` is the shared authority. Offline ratings/suggestions read only `BookRatingCache`; saved reader snapshots and cached profiles support their separate offline paths. Local relay reachability does not authorize remote work. Outboxes survive cache clearing.

## Scheduling and completion limits

Current retries are process/ViewModel-driven: submission, online-state collection (including initial online state), and Settings' forced batch retry call the dispatcher. Retry timestamps persist eligibility but do not schedule execution themselves. No WorkManager/OS-scheduled review worker or dedicated retry-one UI is implemented.

Completion requires resolved remote routes, no pending/failed recorded remote entries, and no pending/failed Citrine state. The dispatcher adds current remote routes to the stored map; it does not prune removed destinations. Consequently, removing a failed relay does not automatically unblock completion. A failed Citrine state also remains recorded when the local URL is cleared. Completed events are not automatically replayed to newly configured routes.

OS-managed background scheduling and explicit route-removal/reconciliation semantics remain follow-ups requiring a lifecycle/persistence decision. They were proposed originally and must not be presented as current guarantees.

## Verification checklist

- Airplane-mode cache hits remain readable; offline misses are explicit and start no remote rating work.
- Reachable/unreachable local Citrine, process restart, and reconnect preserve the original signed event ID.
- Successful peers are recorded independently of failed peers; manual retry respects accepted routes.
- Duplicate connectivity callbacks, authentication/signer failures, and default-network handoffs do not lose or resign reviews.
- Cache clearing retains the outbox; cache-write failure after enqueue leaves the signed event durable.
- Exercise route additions/removals and local-relay disablement against the limits above; do not assume removal completes old work.
- Distinguish saved locally, local delivery, remote pending/failure, and successful completion in Settings.

[Owner-run verification](../DEVELOPMENT.md#build-and-test-ownership) remains separate from static review. Current storage/ownership is in [Architecture](../ARCHITECTURE.md#book-ratings).