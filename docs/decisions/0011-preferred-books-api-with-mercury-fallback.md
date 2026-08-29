# ADR 0011: Preferred Books API with Mercury fallback

- Status: Accepted
- Date: 2026-08-29

## Context

The Decent Newsroom Books API provides the existing Mercury REST contract at
`https://decentnewsroom.com/books/api`, but it is HTTPS-only and does not
offer a compatible WebSocket relay. The existing Mercury endpoint remains a
useful compatibility source during HTTP outages.

## Decision

Use the Decent Newsroom Books API as the first HTTP endpoint and the existing
Mercury API as an ordered fallback. Retry the next endpoint only after a
transport failure or HTTP 5xx response. Treat successful responses, including
empty results and HTTP 4xx failures, as authoritative.

Keep WebSocket relay URLs explicit. API URLs must never be transformed into
relay URLs; the existing Mercury relay remains the explicit relay hint and
chapter relay settings continue to control all chapter WebSocket connections.

## Consequences

Normal requests use the new public Books API without changing the event,
search, verification, or rendering contract. The legacy API can still serve
requests when the preferred API is unavailable, while malformed client input
and valid empty searches do not multiply traffic across both services.