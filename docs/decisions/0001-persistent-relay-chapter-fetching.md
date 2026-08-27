# ADR 0001: Persistent Relay Chapter Fetching

- Status: Accepted
- Date: 2026-08-27

## Context

Mercury exposes publication and chapter events through HTTP, but chapter-heavy book loads can be rate limited. The same kind `30041` content is available from `wss://mercury-relay.imwald.eu` and mirrored by `wss://thecitadel.nostr1.com`. Opening a connection for every individual chapter or request would add handshake cost and unnecessary relay load.

Users also need control over relay availability because mirrors can change independently of the app.

## Decision

Use a dedicated `PersistentNostrChapterSource` behind `MercuryBookRepository`.

- Maintain one persistent WebSocket connection per configured chapter relay.
- Multiplex short-lived Nostr subscriptions over those connections.
- Fetch references both by event ID and by the replaceable-event author/`d` coordinate.
- Accept only kind `30041` events matching the requested IDs or coordinates.
- Merge relay results by coordinate and select the newest event.
- Fall back to Mercury HTTP only for unresolved references.
- Persist an editable, ordered relay list with the Mercury relay and Citadel mirror as defaults.
- Keep chapter-source settings and connections separate from kind `30045` directory synchronization.

## Consequences

Book opening makes fewer Mercury chapter API calls and can succeed when the chapter endpoints are rate limited, provided the publication index was loaded and relays contain the referenced events.

Long-lived sockets consume a small amount of process-wide network state. OkHttp ping frames detect dead connections, and a failed connection is recreated on demand. A settings change is applied when the next chapter fetch reconciles the connection pool, rather than by running a permanent settings observer.

Relay responses are not currently cryptographically verified in this layer; they are filtered against the publication's requested IDs/coordinates, consistent with the existing Mercury event-consumption boundary. Event verification can be introduced as a separate cross-cutting decision if the trust model changes.

HTTP remains necessary for search and publication-index lookup and serves as a compatibility fallback for missing relay content. An empty configured relay list intentionally selects HTTP-only behavior.

## Alternatives Considered

- HTTP-only loading: operationally simpler, but retains the observed rate-limit failure mode.
- One WebSocket per fetch: simpler connection ownership, but repeats TLS/WebSocket handshakes and does not meet the persistent-connection requirement.
- Reuse the directory-sync relay client: rejected because directory publishing/fetching and chapter reading use different relays, event filters, settings, and lifecycle semantics.
- Relay-only loading: rejected because incomplete mirrors would turn missing relay events into missing chapters even when Mercury can supply them.
