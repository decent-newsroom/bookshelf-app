# ADR 0045: Observe and explain pending highlight delivery

## Status

Accepted

## Context

A highlight can be visible in another Nostr client while its outbox entry remains pending: every resolved relay must acknowledge both the highlight and the exact cited chapter. Settings previously read the count only during statistics refresh and did not display the outstanding destinations. A successful chapter report could also clear the entry's last error after highlight publication failed. Separately, moving the only resolved remote route into the local relay slot left no remote slot, making the old completion predicate permanently false even after both events were accepted.

## Decision

Preserve the all-destination, two-event acknowledgement policy. Complete a resolved entry when there is at least one current destination and all current local/remote pairs are acknowledged. Local delivery before initial remote discovery remains incomplete.

Expose an in-process outbox revision after successful durable writes. Settings observes that signal and rereads authoritative pending entries independently of disposable cache statistics. Show the outstanding relay and whether the highlight or cited chapter still needs acknowledgement in both Account & Sync and Storage & Offline. Reuse the existing forced retry action in Storage.

Persist optional failure reasons separately for each event in each relay pair, retaining the v1 file and defaulting absent fields to null. Clear only the acknowledged event's error; preserve other outstanding errors. Older entries retain their entry-level failure as a diagnostic fallback until delivery completes.

## Consequences

- Published elsewhere and fully delivered remain distinct states; failed or unavailable destinations still keep work pending.
- Background completion updates Settings without navigation, cache clearing, or manual statistics refresh.
- Existing signed event IDs, queued payloads, private-highlight links, and cache independence are preserved.
- Settings exposes relay rejection reasons, including cases requiring relay-policy or routing changes rather than another identical retry.
- This change does not infer acknowledgement from another client, discard failed destinations, or treat an unrelated newer chapter revision as delivery of the cited event.

## Alternatives considered

Completing after one relay accepts a highlight would hide unfinished replication and chapter delivery. Removing the entry through cache clearing would lose durable work. Periodic full cache-statistics refresh would couple delivery status to unrelated storage work without explaining the blockers.

## Verification

Regression coverage exercises multi-relay partial delivery, persisted per-event failures, duplicate acknowledgements on immutable retries, retained signed IDs, completion after a remote destination moves local, and explanatory details for a rejected chapter and legacy entry-level errors. The repository owner runs Gradle tests and builds per `AGENTS.md`.
