# Book Ratings and Suggestions Plan

## Goal

Let readers discover and read community ratings for a book, then surface recently
rated books whose community average is at least four stars. Rating data is
untrusted, relay-delivered Nostr data and must never prevent local book metadata
or chapter reading from working.

## Event contract

The initial supported event shape is R1 kind `34259`. The app retains the
complete verified event and normalizes only the fields defined by R1.

| Normalized field | Source | Notes |
| --- | --- | --- |
| `eventId` | event `id` | Immutable event identity. |
| `bookTargetId` | `d` tag | Required R1 target: parse `books:<kind>:<pubkey>:<identifier>` at the first colon only. |
| `bookCoordinate` | parsed `d` identifier | For the supplied format, this is the kind-`30040` publication coordinate after the `books:` namespace. |
| `entityType` | parsed `d` namespace | Must be `books` for this integration. |
| `declaredEntityType` | `m` tag | Optional R1 metadata; preserve it and require agreement with `books` when present. |
| `reviewerPubkey` | event `pubkey` | Identity of the rating author. |
| `createdAt` | event `created_at` | Used for replacement and recency. |
| `review` | event `content` | Optional public review body. |
| `normalizedRating` | `rating` tag | Normalized decimal string. This app deliberately accepts the inclusive interval `[0, 1]`; see the compatibility note below. |
| `displayStars` | `normalizedRating * 5` | A derived fractional 0–5 presentation value; never persisted as a producer value. |
| `legacyStarTag` | `s` tag | Preserve as non-standard producer metadata; exclude it from aggregates. |
| `relayUrl` | received relay | Provenance only; not part of identity. |

### Inclusive interval compatibility policy

The supplied R1 text says ratings are strictly between 0 and 1, but the app
adopts the inclusive interval `[0, 1]` so a conventional five-star selection can
publish `rating=1.000` and the lowest selection can publish `0.000`. This is an
intentional compatibility policy, not an interpretation of the R1 wording. Keep
it under review in case the R1 author confirms that the boundaries were excluded
for a mathematical or protocol reason. The non-standard `s` tag remains
preserved-only and never affects aggregates.

### Target resolution

1. Read the required `d` tag and split it at its first colon, as specified by
   R1. The prefix is the entity type and every remaining character is the ID.
2. Accept a book rating only when the namespace is `books` and the remaining ID
   parses as a kind-`30040` publication coordinate.
3. Preserve the optional `m` tag. If it is present and does not agree with the
   `books` namespace, retain the event but exclude it from aggregates.
4. Treat `a`, `A`, `e`, `p`, and `k` as supplemental provenance/context only;
   none replaces the R1 `d` target.
5. Reject missing, malformed, or multiply specified conflicting `d` targets
   from aggregates while retaining the raw verified event for troubleshooting.
## Architecture

Introduce a `BookRatingsRepository` with three responsibilities: relay fetch and
subscription, durable/cache-backed event storage, and normalization into
application-facing rating records. It is the sole owner of Nostr event parsing
for ratings. Existing book metadata, chapter rendering, and `openBook` must not
depend on its availability.

Suggested public models:

```kotlin
data class BookRating(
    val eventId: String,
    val bookCoordinate: String,
    val reviewerPubkey: String,
    val createdAt: Instant,
    val starScore: Int?,
    val producerRating: String?,
    val review: String,
    val relayUrl: String?
)

data class BookRatingSummary(
    val averageStars: Double,
    val ratingCount: Int,
    val latestRatingAt: Instant,
    val recentReviews: List<BookRating>
)

data class BookSuggestion(
    val bookCoordinate: String,
    val summary: BookRatingSummary,
    val score: Double
)
```

The repository exposes a per-book summary stream and a suggestion stream. A
screen-specific ViewModel consumes those streams; `BookshelfViewModel` continues
to own opening and rendering books only. The repository reports loading,
stale-cache, and error state independently so the reader stays usable offline.

## Fetching and cache behavior

* All rating relay communication uses the application-scoped Quartz NostrClient; 
  no parallel relay transport is introduced.
* Query configured relays through Quartz for kind `34259`; request only the window needed for
  the current book detail view or discovery refresh.
* Where relay filtering supports tag filters, filter by the exact `a` coordinate
  for a book. Discovery loads a bounded recent window and paginates/backfills
  deliberately rather than requesting an unbounded global history.
* Cache raw accepted events and normalized projections. Cache keys include event
  id and book coordinate; relay URL is supplemental provenance.
* Ignore malformed/unverifiable events and report their count only through
  diagnostics, never through user-visible error banners.
* Keep rating cache clearing explicit and user-visible, parallel to the existing
  chapter HTML cache clearing behavior. Clearing ratings must not clear books or
  rendered chapter HTML.

## Relay routing and publication

All rating reads and writes use the application-scoped Quartz `NostrClient` and
its existing verified-event boundary. `BookRatingsRepository` must not create a
separate Nostr client, socket adapter, relay pool, or protocol encoder.

The configured default relays form the initial rating pool. They are used for
the first fetch and remain the fallback whenever relay-list discovery is absent,
invalid, or unavailable.

When publishing a signed rating, Quartz sends it to the de-duplicated union of:

* the configured default relays;
* the active user's NIP-65 write relays; and
* the NIP-65 write relays of the pubkey that authored the target kind-`30040`
  publication-index event.

The publication author is determined from the verified publication-index event,
not from an event hint or rating tag. Relay-list events and URLs are validated
through the existing NIP-65 routing rules. Failure to discover either party's
relay list does not prevent publication to the default pool or the other valid
routes. Publish outcomes remain relay-specific, as they are for the existing
book-directory flow.
## Aggregation rules

1. Accept only a resolved R1 `d` target and a valid `rating` decimal in the app compatibility interval `[0, 1]`.
2. De-duplicate by `(bookCoordinate, reviewerPubkey)`, choosing the newest
   `createdAt`; break an exact timestamp tie deterministically by event id.
3. Compute the arithmetic mean from de-duplicated normalized ratings; derive display stars by multiplying the mean by five.
4. Keep normalized-score precision internally; display derived stars to one decimal place.
5. Do not infer ratings from review text or from `rating` until its semantics
   are specified and approved in a superseding ADR.
6. Treat all relay-provided values as public, untrusted input; sanitize review
   text before rendering.

## Suggestions policy (initial)

A candidate enters the "Recently rated highly" feed only when it has:

* a normalized average of at least `0.800` (displayed as `4.0` stars);
* at least `3` de-duplicated ratings; and
* at least one retained rating from the previous `90` days.

Order candidates by a documented score combining a Bayesian-shrunk average,
rating count, and most-recent-rating time. The first release may use a simpler
stable ordering of average descending, count descending, then recency descending
until sufficient real-world data exists to tune the score. Exclude books whose
metadata cannot be resolved locally, rather than showing an incomplete card.

The threshold, recency period, and ranking algorithm must be configuration
constants with tests; they are product policy rather than event parsing rules.

## UI plan

### Book detail

Add a Community ratings summary after canonical book metadata. It shows the
cumulative displayed score (derived average stars) and rating count in a tappable
control. The summary remains present while ratings refresh, with an explicit
loading, stale-cache, error, or empty state that never displaces book content.

Selecting the cumulative score opens a dedicated Community ratings page for that
book. It contains:

* the cumulative displayed score, normalized average, and de-duplicated rating
  count;
* a score distribution/breakdown, with the number and percentage of ratings at
  each displayed star value or bucket;
* the ordering and eligibility rules used for the aggregate, in a concise info
  affordance;
* the full, paginated list of compatible public written reviews, newest first,
  with score, date, and reviewer identity/link only where existing privacy and
  profile UI supports it; and
* an empty state that distinguishes no ratings from ratings with no written
  opinion.

The page includes an **Add review** action. For a signed-in reader it opens a
composer with a required star selector and an optional opinion field. The action
is unavailable until a Nostr signer session exists; in that case it explains the
requirement and offers the existing sign-in path rather than collecting a review
that cannot be published.

On submit, the composer creates a kind-`34259` event with the verified book's
R1 `d=books:<publication-coordinate>` target, optional `m=books`, and optional
text content. It publishes through the existing Quartz routing policy: configured
default relays, the reader's NIP-65 write relays, and the publication author's
NIP-65 write relays. Submission UI reports relay-specific outcomes and keeps the
composer text recoverable after rejection or partial failure.

The star selector maps one through five stars to normalized ratings `0.2`,
`0.4`, `0.6`, `0.8`, and `1.0`. The app compatibility policy accepts the
inclusive `[0, 1]` interval specifically to permit the five-star endpoint. This
must be revisited if R1's excluded-boundary wording is confirmed as intentional.
### Discovery

Add a "Recently rated highly" destination or bookshelf section. Each card shows
cover/title/author, average stars, count, and latest-rating date. A refresh is
explicit and cancellable. Explain the 4+ average and minimum-count policy near
the list or in an info affordance.

### Settings

Show rating cache size/entry count, last successful sync, configured relays, and
a dedicated "Clear rating cache" action with confirmation. This action must be
safe offline and must not affect chapter HTML caching.

## Delivery phases

1. **Foundation:** add event adapter, parser fixtures using the supplied sample,
   repository interfaces, cache schema, and invalid-event tests.
2. **Per-book ratings:** implement coordinate-filtered loading, de-duplication,
   summary calculation, and the book-detail section.
3. **Discovery:** implement bounded recent fetch, candidate aggregation,
   thresholding, ranking, and the suggestions UI.
4. **Operations:** settings/diagnostics/cache clearing, relay failure behavior,
   accessibility checks, and metrics only if the app already has an approved
   privacy-preserving telemetry mechanism.

## Test plan

* Parse the supplied event, preserve `rating=1.000` and `s=5`, and include its normalized endpoint score under the app compatibility policy.
* Reject invalid normalized ratings, missing/conflicting `d` targets, malformed coordinates, and
  future/invalid timestamps according to existing event-validation policy.
* Verify newest-per-reviewer replacement and deterministic ties.
* Verify summary averages, display rounding, 4.0 boundary, three-rating
  boundary, and 90-day boundary.
* Verify raw content is sanitized before UI rendering.
* Verify offline/stale cache behavior and dedicated rating-cache clearing.
* Add repository and ViewModel tests; exercise UI semantics and screenshot tests
  if those conventions already exist in the app.

* Verify the initial default-relay pool and publishing to the default, active-user, and verified publication-author relay routes, including missing or invalid NIP-65 lists.

* Verify the tappable book-detail summary opens the rating page with correct
  aggregate values, distribution, written-review paging, and empty states.
* Verify unsigned readers are sent to the existing sign-in path and cannot lose
  an entered opinion; verify a signed review uses the R1 `d` target and Quartz
  publish routes.
* Verify star-selection conversion at every boundary, especially the five-star
  endpoint, and prevent publication until the R1 `(0, 1)` conflict is resolved.
## Open questions before implementation

1. What R1-compatible normalized value represents each 1–5 star selection,
   particularly five stars when the literal R1 range excludes `1.000`?
2. Should the app accept the `book` namespace as an alias for the supplied
   `books` namespace, or require exact target matching?
3. Should a rating author be able to deliberately keep multiple ratings for a
   book, or is latest-per-author the intended replacement rule?
4. What trust, mute, block, and profile-identity policies should affect public
   aggregates and review display?
5. Is global discovery allowed to fetch broadly from relays by default, or must
   it be opt-in because of bandwidth/privacy considerations?