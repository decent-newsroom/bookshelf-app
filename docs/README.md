# Engineering documentation

Start with the current guides; use ADRs for rationale and implementation records for acceptance checks and deferred work.

| Guide | Purpose |
| --- | --- |
| [Architecture](ARCHITECTURE.md) | Current boundaries, data flows, persistence, and invariants |
| [Development](DEVELOPMENT.md) | Setup, owner-run verification, and releases |
| [Decision index](decisions/README.md) | All ADRs, including superseded decisions |
| [Tutorials and authoring](tutorials.md) | Approved lesson copy, resources, and release checks |
| [Highlight threading](HIGHLIGHT_THREADING.md) | NIP-22 book-root and chapter-parent tags |

## Implementation records and remaining work

These records summarize implemented slices; they do not establish that builds or device acceptance checks passed.

| Record | Status / remaining work |
| --- | --- |
| [Mercury search](SEARCH_IMPROVEMENT_PROPOSAL.md) | Implemented; global language selection and analyzer guarantees deferred |
| [Quartz migration](QUARTZ_RELAY_CLIENT_MIGRATION.md) | Directory/profile and chapter transports implemented |
| [Ratings and suggestions](plans/book-ratings-and-suggestions.md) | Ratings implemented; suggestion policy exists without a production discovery UI |
| [Offline review synchronization](plans/airplane-mode-offline-review-sync.md) | Durable delivery implemented; scheduling and route reconciliation limits recorded |
| [Precise reader progress](plans/precise-reader-progress.md) | Physical resume and chapter recovery implemented; semantic anchors and text-weighted progress planned |
| [Reader menu](plans/reader-menu-layout.md) | Implemented; owner build/device verification pending |
| [Cover progress](plans/book-thumbnail-reading-progress.md) | Implemented; approximate intermediate percentages remain |
| [Reading lists and finished history](plans/reading-lists-and-finished-history.md) | Implemented; deferred extensions listed |
| [Full-text search and recommendations](plans/full-text-search-and-recommendations.md) | Implemented, including persistent recommendation cache |

## References and historical evidence

- [R1 rating format](references/R1-ratings.md): supplied protocol text; the app's inclusive score policy is documented separately.
- [Decent Newsroom Books API](references/decent-newsroom-books-api.json): supplied preferred-API contract.
- [Mercury API](Mercury/swagger.json): historical fallback contract.
- [Security audit, 2026-08-27](SECURITY_AUDIT_2026-08-27.md): dated baseline and remediation evidence, not a current security assessment.
- [Changelog](../CHANGELOG.md): release history. [Tickets](../TICKETS.md): owner-maintained backlog, which may include ideas or older behavior.

## Maintenance

Describe implemented behavior in `ARCHITECTURE.md`. Update it and add or supersede an ADR when changing boundaries, persistence, networking, lifecycle, or important invariants. Label proposals explicitly and record implemented features/fixes under `CHANGELOG.md`'s Unreleased section.

Keep operational commands in `DEVELOPMENT.md`, rationale in ADRs, and approved copy under `tutorials/`. Link instead of repeating them. Retain historical ADRs and audit evidence with clear status/supersession notes. Documentation-only consolidation does not require a new architectural decision.