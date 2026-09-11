# ADR 0027: Resolve reviewer names through the existing profile boundary

- Status: Accepted
- Date: 2026-09-11

## Context

Community-rating events identify their authors by pubkey. Showing that key in
review details is useful only as a fallback; a verified kind-0 profile can
provide the reviewer's human-readable `display_name` or `name`.

The existing profile cache and verifier already protect profile metadata.
However, the active signer's NIP-65 relay routes are session-scoped and must
not be replaced while looking up unrelated public reviewers.

## Decision

When review details load, read the cached kind-0 profile for every distinct
review author before rendering the review list. Refresh those profiles through
the existing `NostrProfileSource` in batches of four and update matching cards
as names arrive. Use `display_name`, then `name`, and retain the compact pubkey
only when no usable profile name is available.

Profile refreshes use the current read-relay set but do not invoke NIP-65
discovery or change the active signer's routing state. A failed profile lookup
is non-fatal and cannot prevent ratings from displaying.

## Consequences

- Review cards show names when verified profile metadata is available, while
  preserving a stable identity fallback.
- Profile cache behavior remains centralized in `NostrProfileRepository`.
- At most four reviewer profile refreshes run at once, keeping the details view
  from creating an unbounded relay burst.
