# ADR 0026: Associate library-card ratings through address tags

- Status: Accepted
- Date: 2026-09-11

## Context

The initial rating implementation associated kind-34259 rating events only from
their namespaced R1 `d` tag. Existing library-card ratings can instead carry
the kind-30040 publication coordinate in Nostr `a` and `A` address tags, which
left those ratings undiscoverable even though their target was unambiguous.

## Decision

Query rating events only by `a` and `A` for the selected publication. Continue creating the app-specific namespaced `d` tag alongside matching `a` and `A` tags, `k=30040`, and the publication-author `p` tag when publishing, but do not use `d` for review retrieval. Accept a verified rating only when one exact kind-30040 coordinate is present in `a` or `A` address tags. If a typed `d` target is present, preserve the existing requirement that its namespace agrees with an optional `m` tag and references the same address. Multiple different publication coordinates remain invalid.

## Consequences

Library-card and chapter-backed publications share the same review lookup rule. Address tags are the interoperable association mechanism. The typed R1 `d` target is creation-only and cannot affect review retrieval.