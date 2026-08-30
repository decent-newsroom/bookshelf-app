# ADR 0013: My Books publication relay lookup

- Status: Accepted
- Date: 2026-08-30

## Context

My Books is a kind `30045` directory of publication references. The referenced
kind `30040` publication indexes may be published outside Gutenberg and may
not be present in either configured HTTP API.

## Decision

Resolve My Books references through the existing HTTP APIs and the known
bookshelf relay list. For each valid kind `30040` coordinate, request the
exact author and `#d` tag from every known relay. Verify returned Nostr events
against that kind, author, and identifier before mapping them, cap concurrent
coordinate requests at eight, and merge API and relay results by coordinate,
selecting the newest event with ID as a deterministic tie-breaker.

Keep this behavior exclusive to My Books. Curated Home shelves and search
retain their existing HTTP-only publication lookup behavior.

## Consequences

My Books can display publications from independent sources when their
publication events are available on the configured known relays. API or relay
failure alone does not discard results returned by the other source.

