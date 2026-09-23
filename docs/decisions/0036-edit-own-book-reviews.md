# ADR 0036: Edit the active signer's book review as a replacement rating event

- Status: Accepted
- Date: 2026-09-23
- Extends: ADR 0028's durable review outbox and ADR 0035's rating revision selection

## Context

A kind `34259` rating is a parameterized replaceable event. The app already selects the newest revision for a `(kind, pubkey, d-tag)` tuple, but the rating composer only starts a new rating. A reviewer needs to change their own stars or opinion without creating a second visible review or losing an edit when delivery is deferred.

## Decision

1. Identify the active signer's selected rating by pubkey in Community ratings. Offer editing for that rating even when its opinion is blank. Prefill the composer with the selected stars and opinion. An opinion-only edit retains the exact normalized rating even when its displayed stars are rounded; deliberately selecting a whole star changes that rating. Account changes must not allow one signer to edit another signer's rating.
2. Submit an edit as a new signed event for the same publication and namespaced `d` target. Use current wall-clock seconds at submission. If that is the same second as the selected revision, wait until the next second before requesting a signature; if the selected revision is further ahead of the clock, show a clock error rather than inventing a future timestamp. Keep the original rating until a new signed event has been accepted locally; cancelling leaves it unchanged.
3. Before persistence, verify the signer's returned event and require its author, target, timestamp, rating, opinion, and other draft fields to match the pending request. Persist the immutable signed event in the review outbox first, then merge it into the rating cache and refresh the visible review. Delivery reuses the existing offline-capable local Citrine and connectivity-gated remote relay dispatcher. An older pending event remains an independent outbox item with its original event ID; its later delivery cannot displace a newer cached revision.

## Consequences

- The reviewer sees their updated rating locally after durable acceptance, even while offline, with one selected revision in the visible list.
- Signing cancellation, account changes, or a mismatched signer response cannot overwrite the prior review.
- Cache clearing remains separate from pending signed revisions in the durable outbox.
