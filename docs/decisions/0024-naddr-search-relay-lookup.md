# ADR 0024: Naddr search relay lookup

- Status: Accepted
- Date: 2026-09-11

## Context

A publication `naddr` is a NIP-19 address for a replaceable event and can
carry relay hints. Search previously recognized only raw publication
coordinates, so pasting an `naddr` did not resolve the publication through its
advertised relays.

## Decision

Treat a valid kind `30040` `naddr` pasted into Search as an exact publication
coordinate. Query its secure `wss://` relay hints alongside the configured
read relays, cap the combined relay set at eight, and accept only a
signature-verified kind `30040` event whose author and `d` tag match the
encoded coordinate. Run the existing HTTP exact-coordinate request in
parallel as a fallback. Relay transport failures remain isolated from the
HTTP search branch; a successful source still returns its result.

This supersedes ADR 0013 only for the stated Search `naddr` exception. My
Books continues to resolve ordinary saved references through known relays.

## Consequences

Users can share or paste publication addresses without first converting them
to raw coordinates. Untrusted relay hints cannot broaden an event match, use
cleartext transport, or create unbounded relay work. Ordinary free-text and
coordinate searches retain their existing request plans.