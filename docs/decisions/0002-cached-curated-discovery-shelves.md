# ADR 0002: Cached Curated Discovery Shelves

- Status: Accepted
- Date: 2026-08-27

## Context

The bookshelf Home experience needs predictable, categorized suggestions while keeping search secondary and avoiding chapter-heavy network work before a reader opens a book. Editorial lists are supplied as NIP-19 `naddr` values that identify replaceable kind `30040` publication indexes.

## Decision

Use a checked-in catalog of shelf IDs, shelf titles, and ordered naddrs. Decode and validate each naddr, then resolve its exact publication coordinate through Mercury with an author plus `#d` filter. Do not duplicate titles or authors in editorial configuration. Preserve catalog ordering and allow the same publication in multiple shelves.

Cache only `BookSummary` publication-index metadata in `context.cacheDir/shelf-metadata/v1.json` with a 24-hour freshness window. Show stale cache entries while refreshing and retain them when refresh fails. Write cache updates through a temporary file and replacement. Shelf loading must not access chapter events; the existing book-open path remains the sole chapter loader and renderer.

Make Home the default tab, render shelves as horizontal cover/title/author carousels, and expose Search from a Home action rather than a bottom-navigation tab.

## Consequences

Warm launches avoid repeated shelf metadata requests, and temporary Mercury outages do not blank previously loaded shelves. Editorial updates require an app release. Missing or invalid naddrs are omitted without failing other shelves. Exact coordinate lookup avoids the limited author-only result window and replaceable-event ambiguity.