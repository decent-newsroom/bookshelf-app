# ADR 0029: Dedicated Settings Feature

## Status

Accepted

## Context

The root Compose app and `BookshelfViewModel` accumulated settings presentation and store mutation alongside reading and bookshelf coordination. Settings mixed editable chapter sources, fixed search APIs, account relays, and caches in one collapsible form.

## Decision

Settings has its own index, section screens, and settings-specific ViewModel. The ViewModel observes and updates the existing specialized stores; no global settings store or persistence migration is introduced. Reader typography is persisted in `ReaderSettingsStore` and shared by the reader and preview. Account and directory signing continue through the existing root coordination and Android signer bridge because saving books also depends on them.

Discovery Sources owns the ordered chapter relay list and displays fixed HTTP search services separately. Built-in chapter relays may be removed; the last relay cannot be removed and Restore defaults writes the built-in list. Nostr Relays presents fixed bootstrap and account NIP-65 routes read-only, with only the independent local relay editable. Settings uses validated connectivity and the existing outboxes for pending counts and retries. Storage only clears disposable chapter HTML and rating caches.

## Consequences

Settings state stays small and local preferences appear from their synchronous store values on first render. Reader and Settings controls remain synchronized. Clearing caches preserves saved-book membership, reading progress, highlights, and signed pending events. The About screen provides version and source-code access; dependency licenses are deferred.
