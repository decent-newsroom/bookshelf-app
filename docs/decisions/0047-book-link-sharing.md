# ADR 0047: Share stable publication links from book actions

## Status

Accepted, 2026-10-09.

## Context

Readers need a direct way to pass a publication to another person or app. A book's kind `30040` index is replaceable, so a link to a particular event revision would become stale as the publisher updates the index. Sharing also should not initiate network activity, request a signature, or create local records.

Android owns the external share chooser and clipboard feedback. Keeping those effects in the UI shell follows the app's boundary for platform interactions, while the view model remains responsible for dismissing the shared actions menu.

## Decision

The shared `BookActionsSheet` provides **Share book** and **Copy book link**. Both generate the same `nostr:naddr1` URI locally for kind `30040`, using the book's exact author public key and `d` coordinate. This address identifies the publication across index revisions.

An optional source-relay hint may be included only for an eligible `wss://` DNS hostname. Single-label names, recognized local/private suffixes (including names ending in a DNS dot), all IP literals, credentials, fragments, and HTTP(S) URLs are omitted. This is a conservative syntax policy; no DNS lookup or relay discovery runs during sharing. The identifier and relay hint must each fit NIP-19's 255-byte UTF-8 TLV limit; an overlong hint is omitted, while an invalid or overlong publication identity cannot be shared. Link generation does not fetch, sign, persist, or otherwise mutate data.

Share uses Android `ACTION_SEND`, MIME type `text/plain`, and puts only the exact URI in `EXTRA_TEXT`. The book title is supplied as `EXTRA_TITLE` metadata. Copy writes the exact same URI to the clipboard. Android 13+ displays its system copy notification; older Android versions receive app feedback.

The `ui/shell` layer owns launching the chooser and clipboard operations. The view model only dismisses the menu. Invalid identity, chooser launch failures, and clipboard failures produce visible feedback. Canceling the share chooser does not change book or reading data.

## Consequences

- Links continue to resolve to the publication as its index is revised.
- Sharing and copying work without network access or signer availability.
- Consumers receive a plain-text NIP-19 address, with a title available as share metadata.
- No app-owned share history, clipboard record, or retry state is introduced.
- Relay hints improve discovery when safe public metadata is available, while private or insecure sources are not disclosed.

## Verification

Focused unit-test source covers exact identifier round trips (including Unicode, colons, and whitespace), stable index identity across event revisions, UTF-8 length boundaries, invalid coordinates, and relay-hint exclusion. Gradle builds and tests are run by the owner on this Windows setup, per `AGENTS.md`.

Device verification should confirm both actions from Home, Search, My Books, and recommendations; URI-only clipboard and share-target text; offline and library-card behavior; cancel/return behavior; and copy feedback on Android versions before and after 13. The action sheet scrolls so the added actions remain accessible on shorter screens.
