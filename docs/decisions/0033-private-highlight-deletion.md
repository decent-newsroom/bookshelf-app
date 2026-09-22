# ADR 0033: Allow deletion only for private highlights

## Status

Accepted

## Context

Reader highlights are first stored as app-private records. Publishing asks an Android signer to create an immutable NIP-84 event, then preserves that signed event in the durable highlight outbox until each required relay route acknowledges it. A reader needs to remove a highlight saved only on the device, but removing a record that has already been signed or queued would not retract the immutable event and could obscure pending delivery.

## Decision

Expose a **Delete** action only for highlights without a published event ID or linked highlight-outbox entry. Deletion atomically removes the requested record from the private highlight store. The view model repeats the publication and outbox checks before removal so stale UI cannot delete a queued or published record.

## Consequences

* Users can remove unwanted private highlights from the reader highlights sheet.
* Signed, queued, and published highlights remain visible and continue using the durable delivery path.
* This is local record deletion, not a Nostr event deletion or retraction mechanism.

## Alternatives considered

### Delete every local highlight

Rejected because removing a local record cannot cancel, retract, or make an already signed immutable Nostr event private again.

### Publish a Nostr deletion event

Deferred. It would be a separate interoperable protocol feature with signer, relay, and recipient semantics beyond local private-highlight deletion.
