# ADR 0023: Normalize public book-rating events separately from book content

* Status: Proposed
* Date: 2026-09-07

## Context

R1 defines kind `34259` ratings. The required `d` tag is a namespaced target ID;
parsing splits only at the first colon. Its required `rating` tag is a normalized
decimal string strictly between 0 and 1. `m` is optional descriptive metadata,
and event content is an optional review.

The supplied book-rating event uses `d=books:30040:<pubkey>:<identifier>`, so
`books` is the target namespace and the remainder is the publication coordinate.
It also contains `rating=1.000` and `s=5`. The former is outside R1's strict
range and the latter is not defined by R1, so neither permits a standards-based
five-star aggregate for that event.

The app must remain able to load local/canonical book details and render cached
chapters when relays are unavailable. It also needs a safe basis for a discovery
feed of books recently averaging four or more displayed stars.
## Decision

1. Add a dedicated `BookRatingsRepository`; it owns kind-`34259` event parsing,
   caching, and aggregate queries. All rating relay communication uses the
   application-scoped Quartz `NostrClient` and existing verified-event boundary;
   no parallel Nostr client, socket adapter, relay pool, or protocol encoder is
   introduced. It does not change the book-detail or chapter-rendering ownership
   boundaries.
2. Associate a rating with a book only through a required R1 `d` tag whose
   namespace is `books` and whose remaining ID is a valid kind-`30040`
   publication coordinate. Preserve `m`, `a`, `A`, `e`, `p`, and `k` as context;
   none replaces the `d` target.
3. Preserve raw events and producer fields. Only an R1-valid `rating` strictly
   between 0 and 1 contributes to aggregates. Display stars are derived by
   multiplying that normalized value by five. The non-standard `s` tag is never
   used for aggregation.
4. For each book, use only the newest valid rating per reviewer pubkey when
   computing public aggregates. Resolve equal timestamps deterministically by
   event id.
5. The first suggestion feed includes only books with normalized mean `>= 0.800`
   (displayed as at least 4.0 stars), at least three de-duplicated ratings, and a
   rating in the previous 90 days. Ranking policy remains configuration,
   separate from event normalization.
6. Relay errors, malformed events, and stale caches are non-fatal. Rating cache
   clearing is a separate, explicit Settings action and never clears book or
   chapter-HTML caches.
7. The configured default relays are the initial rating read/write pool. A signed
   rating is also published through Quartz to the active user's valid NIP-65
   write relays and to the valid NIP-65 write relays of the verified pubkey that
   authored the target kind-`30040` publication-index event. Missing or invalid
   relay lists fall back to the default pool and never block the other routes.
## Consequences

* The UI can display a consistent community summary and recent reviews without
  coupling reading to relay availability.
* The app does not lose information if the event convention evolves, because raw
  values are retained.
* The initial approach may omit ratings from producers that use a different
  scoring tag. That is preferable to producing misleading averages.
* A global suggestion feed needs bounded queries, cache expiry, and relay/privacy
  policy; it must not issue unbounded historical subscriptions.
* A future authoritative definition of `rating`, a different event kind, or a
  new multi-rating policy requires a superseding ADR and migration/aggregation
  tests.

## Alternatives considered

### Treat the non-standard `s` tag as the score

Rejected: the representative event's `1.000` conflicts with `s=5`, and its
meaning is not established.

### Average every event from the same author

Rejected: it lets edits or repeated submissions overweight one reviewer and
makes results dependent on relay history completeness.

### Put rating queries in `BookshelfViewModel`

Rejected: it combines event transport/cache lifecycle with book opening and
chapter rendering, weakening offline behavior and testability.

### Require a rating count of one

Rejected for discovery: a single recent five-star event is too easy to game and
does not represent a useful community average. Individual book pages may still
show it.

## Relay-routing consequence

Publishing uses the default pool plus the active user's and verified publication author's NIP-65 read routes, while remaining available when discovery is incomplete.

## Follow-up

Implement only after confirming the event producer contract and relay policy.
At implementation time, add fixtures for the supplied event, update architecture documentation, and record the feature in the changelog.

## Compatibility note

The supplied R1 text says the normalized score is strictly between zero and one. The app deliberately accepts the inclusive [0, 1] interval to support one- and five-star endpoint selections. Reassess this policy if the R1 author confirms the excluded boundaries were intentional.
