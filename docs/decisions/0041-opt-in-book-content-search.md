# ADR 0041: Opt-in book content search

- Status: Accepted
- Date: 2026-09-27
- Supersedes: ADR 0039's visible search scope controls only.
- Preserves: ADR 0040's full-text read timeout.

## Context

The All, Title, Author, Subject, and Inside books buttons make the search interface busy. Ordinary searches should cover all publication metadata, while expensive chapter-content searches should be a deliberate choice.

## Decision

Expose one search field and an **Include book contents** toggle, initially off. The default selects the internal `METADATA` scope and sends only the metadata `q` request. Enabling the toggle selects `ALL`, combining metadata with the section channel when the term meets the existing four-character minimum. Keep internal structured scopes for typed queries, with recognized prefixes overriding the toggle. Add `content:` as an explicit chapter-only query.

Retain the toggle across navigation in `BookshelfViewModel` state only. Do not persist a preference or query history. A new ViewModel starts with content search disabled. Toggle changes apply on the next explicit Search button or keyboard submission; they neither launch a search nor replace current results.

Preserve partial-result notices, matched-chapter excerpts, and the explicit Open matching chapter action. Keep exact-reference routing, validated-connectivity gating, cancellation, fusion, and cache policies. The section channel retains its 120-second read timeout, including fallback endpoints; metadata and other operations retain their existing timeouts.

## Consequences

The default search makes fewer requests and the interface has fewer controls. Readers opt into slower chapter searches without losing metadata results. Advanced field searches remain available through prefixes. Session-only state remembers the choice during navigation without making expensive searches the default on later app sessions.

## Verification

Review metadata-only versus combined query routing, explicit prefix precedence, and Compose/ViewModel submission behavior. Builds and tests remain owner-run under AGENTS.md; no Gradle verification was performed by the agent.
