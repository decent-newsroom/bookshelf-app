# ADR 0020: Settings Relay Configuration

## Status

Accepted

## Context

Settings must distinguish fixed discovery endpoints, editable chapter-publication relays, account-provided NIP-65 relays, and an optional relay that runs locally on the phone.

## Decision

The Settings screen uses collapsible Appearance, Account, Relays, and Cache sections. The Relays section presents the search API defaults and signed NIP-65 read/write lists as read-only. Chapter-publication relays retain their existing editable store.

An optional local `ws://` or `wss://` relay URL is persisted independently. It is combined with the fixed directory bootstrap relays for directory, profile, and publication-index traffic, but it does not modify chapter-source routing. Clearing the field removes it.

## Consequences

Users can inspect the configured and discovered routing without accidentally editing protocol-owned lists. A local Citrine relay becomes usable immediately while the default bootstrap relays remain available as fallbacks.
