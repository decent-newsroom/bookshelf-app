# ADR 0008: Untrusted Content Navigation and Cover Privacy

## Status

Accepted — 2026-08-27

## Context

Chapter bodies and publication metadata arrive from remote Mercury and Nostr
sources. Chapter HTML can contain links, and cover URLs can cause Coil to make
an automatic request while a search result, shelf, or saved book is visible.
Arbitrary link schemes can activate unintended external applications, while
author-controlled image hosts can observe that a user viewed a book listing.

## Decision

Treat both values as untrusted content at their UI/network boundaries:

- Chapter links must be absolute HTTPS URIs with a parsed host and no user-info.
  The reader displays the normalized host and requires an explicit user
  confirmation before handing the URI to the platform. Other schemes,
  cleartext HTTP, malformed, relative, and deceptive user-info forms are
  rejected without external navigation.
- Automatically loaded cover art must be HTTPS and use one of the explicit
  trusted Gutenberg hosts: `gutenberg.org`, `www.gutenberg.org`,
  `images.gutenberg.org`, or `aleph.gutenberg.org`. Values outside this list
  are discarded and the existing monogram remains visible. Generated
  Gutenberg cover URLs remain supported.

No Android permissions or arbitrary network destinations are added. This is a
content-policy boundary; it does not claim that a trusted host's content is
safe or that redirects from a trusted host are independently verified.

## Consequences

Users get a visible trust decision before leaving the reader, and ordinary
book browsing no longer automatically contacts arbitrary author-controlled
cover hosts. Some publishers' custom cover images will no longer load until a
future privacy-preserving proxy or separately reviewed allowlist is provided.
The monogram fallback keeps cards usable when a cover is rejected.
