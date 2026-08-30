# ADR 0014: Publication image and chapter relay hints

- Status: Accepted
- Date: 2026-08-30

## Context

Kind 30040 publication indexes can represent complete books that are not part
of the Project Gutenberg corpus. These indexes may contain a publisher-hosted
cover in an `image` tag and `wss://` relay hints in their kind 30041 chapter
`a` tags. Restricting covers to Gutenberg hosts hides valid independent
artwork, and querying only the configured relay list can leave their chapters
unavailable despite an explicit source hint.

## Decision

Use a valid publication `image` tag before attempting inferred Project
Gutenberg artwork. A cover URL must remain absolute HTTPS, include a host, and
not include user-info.

For a chapter fetch, append valid relay hints from the selected chapter `a`
tags to the configured chapter-relay list. Normalize and deduplicate the
combined list, preserve configured relays first, and cap it at eight entries.
Ignore malformed hints. When the saved configured list is empty, use the
built-in chapter relay defaults before adding any hints.

This supersedes the automatic-cover host allowlist portion of ADR 0008 and
the empty-list HTTP-only portion of ADR 0001; their other decisions remain
unchanged.

## Consequences

Independent publications can show their declared cover art and locate chapters
on their declared relays. Opening or displaying an independent publication can
now contact a valid publisher cover host and a valid hinted relay. URL
validation, event verification, and connection limits constrain that behavior.
