# Airplane Mode and Offline Review Synchronization Plan

## Goal

Keep the bookshelf useful without validated internet connectivity. While offline, the app reads only persisted data, lets a signed-in reader create a review safely, sends the review to the configured local Citrine relay when reachable, and delivers the same signed event to configured remote relays once validated internet connectivity returns.

This is an offline-first review and relay-transport change. It does not alter the R1 rating-event contract, Chapter HTML cache semantics, or existing relay routing except where a durable outbox defers remote publication.

## Connectivity and read policy

Introduce one application-scoped connectivity source that reports validated internet availability from Android network callbacks. A connected transport without validation is offline. Repositories and background work consume this source; screens must not make independent connectivity decisions.

With no validated internet:

* Do not start Mercury HTTP, remote chapter relay requests, review/profile refreshes, or remote review publication.
* Return only persisted/cache-backed books, chapters, metadata, profiles, and ratings. A cache miss is an explicit offline-unavailable result, not an empty result.
* Preserve the existing reader preference for `BookChapter.renderedHtml`, with raw AsciiDoc as fallback.
* Permit a configured local Citrine endpoint (for example, loopback) to receive a review if it is reachable. Citrine reachability never re-enables remote reads or implies internet access.

The UI shows an app-level offline indicator and distinguishes an empty review list from a list unavailable for refresh while offline.

## Durable review outbox

Add an app-private durable review-outbox store, separate from all cache-clearing facilities. It survives process death and restart; chapter HTML or rating-cache clearing must never remove unsynced reviews.

Review submission sequence:

1. Build and obtain the signed R1 kind-`34259` event once through the existing signer boundary. Its event ID, serialized signed event, target coordinate, review projection, and creation time are immutable.
2. Atomically persist the reader-visible local review projection and outbox record before attempting a relay operation. The review is immediately visible as saved locally, including in airplane mode.
3. If Citrine is configured, publish that exact signed event immediately without waiting for validated internet. Its acknowledgement records Citrine-only delivery, never remote completion.
4. Retain unsuccessful Citrine and remote deliveries for retry. Retries always use the original event, preventing duplicate review events after reconnects or restarts.

Each outbox record stores the signed event, immutable event ID, local creation time, last attempt and bounded failure detail, next retry time, Citrine delivery state, and remote delivery state keyed by relay URL. Event ID is the idempotency key.

## Publishing and synchronization

Reuse the application-scoped Quartz `NostrClient` and existing signer/authentication boundaries. Do not add another relay client, socket pool, or protocol encoder.

The dispatcher has two paths:

* **Immediate Citrine delivery:** attempt the configured optional local relay after the local transaction, including while offline. If unavailable, retain retry metadata; failure never loses the review or blocks later remote delivery.
* **Remote delivery:** on validated connectivity, schedule durable Android background work with a network constraint. Publish each pending event to the enabled remote destinations derived from the current review routing policy: configured defaults, active-user NIP-65 write relays, and verified publication-author NIP-65 routes. Exclude Citrine from remote fan-out because it is tracked independently.

Record acknowledgement, timeout, transport/authentication/protocol failure, and rejection independently per relay. Retry eligible failures with exponential backoff across restarts. One failed relay must not block publication to successful peers.

An item completes only when Citrine is disabled or acknowledged and every currently enabled remote relay has acknowledged. Disabling/removing a relay removes it from completion requirements. Relays added after completion do not receive historic events; newly enabled relays are included only for still-pending items. Settings provides retry-one and retry-all controls.

## User experience and settings

Expose aggregate review delivery state in the review UI:

* **Saved locally** after the durable commit.
* **Published to Citrine** after local acknowledgement while remote delivery remains pending.
* **Syncing** while the dispatcher has eligible delivery work.
* **Needs retry** after a retryable failure.

Settings shows the pending-review count, last sync attempt, concise failure diagnostics, and retry controls. Per-relay details remain in Settings diagnostics, not the reader flow. Cache controls explicitly state that unsynced reviews are retained.

## Documentation required on implementation

When implemented, update `docs/ARCHITECTURE.md` with the connectivity boundary, cache-only invariant, outbox ownership, and separate Citrine/remote paths. Add the next sequential ADR for the durable outbox, immutable event identity, retry/completion policy, and local-Citrine exception. Add an Unreleased `CHANGELOG.md` entry for offline reads, queued reviews, Citrine publication, and automatic remote synchronization.

## Acceptance tests

* In airplane mode, cached books, rendered chapters, ratings, and profiles remain readable; a cache miss is explicitly unavailable and starts no remote request.
* An offline review with reachable Citrine is saved first, delivered once to Citrine, and remains pending for remotes.
* An offline review with unreachable Citrine survives restart and publishes later without a duplicate.
* Returning validated internet starts background fan-out to all enabled remote routes, records relay outcomes independently, and retries unfinished work with backoff.
* A failed remote relay does not block successful relays; relay removal unblocks completion; newly added relays receive only still-pending events.
* Duplicate network callbacks, manual retry, signer rejection, NIP-42 authentication failure, and restart preserve the original event ID.
* Chapter/rating cache clears retain unsynced reviews and Settings accurately reports pending/failed deliveries.