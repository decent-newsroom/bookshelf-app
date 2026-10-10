# Architecture decision records

Use [Architecture](../ARCHITECTURE.md) for current behavior. ADRs retain the context, alternatives, and consequences of decisions; partial supersession changes only the named portion. Status notes below are navigation aids; each record contains the detailed scope.

Two existing records share number 0015. Their descriptive filenames remain stable to preserve links; cite the title/filename when distinguishing them. New ADRs continue after the highest assigned number.

| Record | Status / later changes |
| --- | --- |
| [ADR 0001: Persistent Relay Chapter Fetching](0001-persistent-relay-chapter-fetching.md) | Partly superseded by 0018; relay selection/fallback retained |
| [ADR 0002: Cached Curated Discovery Shelves](0002-cached-curated-discovery-shelves.md) | Accepted |
| [ADR 0003: Cache Nostr Profile Metadata on Login](0003-cache-nostr-profile-on-login.md) | Accepted |
| [ADR 0004: Device-Local Bookshelf with Optional Nostr Sync](0004-device-local-bookshelf-with-optional-sync.md) | Accepted |
| [ADR 0005: Verify NIP-01 events at every external boundary](0005-verified-nostr-event-boundary.md) | Accepted |
| [ADR 0006: Bound Untrusted Content Resource Use](0006-bound-untrusted-content-resource-use.md) | Accepted |
| [ADR 0007: Private Cloud Backup with Explicit Device Transfer](0007-private-cloud-backup-with-explicit-device-transfer.md) | Accepted |
| [ADR 0008: Untrusted Content Navigation and Cover Privacy](0008-untrusted-content-navigation-and-cover-privacy.md) | Accepted; publication image policy extended by 0014 |
| [ADR 0009: Typed and explainable Mercury search](0009-typed-explainable-mercury-search.md) | Accepted |
| [ADR 0010: Resilient Mercury search](0010-resilient-mercury-search.md) | Accepted |
| [ADR 0011: Preferred Books API with Mercury fallback](0011-preferred-books-api-with-mercury-fallback.md) | Accepted |
| [ADR 0012: Signer-neutral bounded NIP-42 relay authentication](0012-nip42-relay-authentication.md) | Transport superseded by 0017; signer boundary retained |
| [ADR 0013: My Books publication relay lookup](0013-my-books-publication-relay-lookup.md) | Accepted |
| [ADR 0014: Publication image and chapter relay hints](0014-publication-image-and-chapter-relay-hints.md) | Accepted |
| [ADR 0015: Continue reading from durable reader state](0015-continue-reading-from-durable-reader-state.md) | Accepted; progress extended by 0032/0046, Home refined by 0053 |
| [ADR 0015: Open Resolved Publication Indexes Directly](0015-open-resolved-publication-indexes.md) | Accepted |
| [ADR 0016: Retryable Local Bookshelf Publication](0016-retryable-local-bookshelf-publication.md) | Accepted |
| [ADR 0017: Quartz directory relay transport and explainable publication](0017-quartz-directory-relay-transport.md) | Accepted |
| [ADR 0018: Quartz chapter relay subscriptions](0018-quartz-chapter-relay-transport.md) | Accepted |
| [ADR 0019: Verified NIP-65 user relay routing](0019-nip65-user-relay-routing.md) | Accepted |
| [ADR 0020: Settings Relay Configuration](0020-settings-relay-configuration.md) | Superseded by 0029 |
| [ADR 0021: Persist contextual onboarding tips by stable identifier](0021-persistent-contextual-onboarding.md) | Accepted; refined by 0031/0057 |
| [ADR 0022: Explicit full-book broadcast to a local relay](0022-local-full-book-broadcast.md) | Accepted |
| [ADR 0023: Normalize public book-rating events separately from book content](0023-book-rating-events-and-suggestions.md) | Historical proposal; see 0026/0028/0035/0038 and compatibility note |
| [ADR 0024: Naddr search relay lookup](0024-naddr-search-relay-lookup.md) | Accepted |
| [ADR 0025: Library-card publication indexes](0025-library-card-publication-indexes.md) | Accepted |
| [ADR 0026: Associate library-card ratings through address tags](0026-library-card-rating-address-tags.md) | Accepted |
| [ADR 0027: Resolve reviewer names through the existing profile boundary](0027-reviewer-profile-resolution.md) | Accepted |
| [ADR 0028: Persist signed reviews before relay delivery](0028-offline-review-outbox.md) | Accepted; user completion added by 0060 |
| [ADR 0029: Dedicated Settings Feature](0029-dedicated-settings-feature.md) | Accepted |
| [ADR 0030: Cache reader content for saved books offline](0030-offline-reader-content-cache.md) | Accepted |
| [ADR 0031: Cap the reader onboarding hint and provide explicit dismissal](0031-capped-dismissible-contextual-onboarding.md) | Accepted |
| [ADR 0032: Persist a stable in-chapter reader location](0032-precise-reader-progress.md) | Physical resume accepted; extended by 0046; semantic anchors planned |
| [ADR 0033: Allow deletion only for private highlights](0033-private-highlight-deletion.md) | Accepted |
| [ADR 0034: Bind connectivity updates to the reported default network](0034-default-network-connectivity-state.md) | Accepted |
| [ADR 0035: Show cached ratings first and retain only the latest revision](0035-cache-first-rating-revisions.md) | Accepted |
| [ADR 0036: Edit the active signer's book review as a replacement rating event](0036-edit-own-book-reviews.md) | Accepted |
| [ADR 0037: Add a dedicated relay for kind-0 profile lookup](0037-dedicated-profile-lookup-relay.md) | Accepted |
| [ADR 0038: Include active-user read relays in community rating lookups](0038-active-user-relays-for-rating-reads.md) | Accepted |
| [ADR 0039: Full-text navigation and seeded discovery](0039-full-text-and-seeded-discovery.md) | Partly superseded by 0041/0042/0043 |
| [ADR 0040: Longer full-text search read timeout](0040-full-text-search-timeout.md) | Accepted |
| [ADR 0041: Opt-in book content search](0041-opt-in-book-content-search.md) | Toggle/results superseded by 0043 |
| [ADR 0042: Inline book details recommendations and independent persistent cache](0042-inline-book-details-recommendations.md) | Accepted |
| [ADR 0043: Content-only search and cohesive result cards](0043-content-only-search-and-result-cards.md) | Accepted |
| [ADR 0044: Private reading state with explicit public synchronization](0044-reading-lists-and-finished-history.md) | State accepted; presentation superseded by 0051 |
| [ADR 0045: Observe and explain pending highlight delivery](0045-observable-highlight-delivery-status.md) | Accepted; user completion added by 0060 |
| [ADR 0046: Coordinate reader progress and shared thumbnail indicators](0046-coordinated-reader-progress-and-thumbnail-indicators.md) | Coordination accepted; presentation updated by 0048/0055 |
| [ADR 0047: Share stable publication links from book actions](0047-book-link-sharing.md) | Accepted |
| [ADR 0048: Progress visibility and tracking entry points](0048-progress-visibility-and-tracking-entry-points.md) | Reader UI superseded by 0050; styling by 0055 |
| [ADR 0049: Offline topic tutorials for reusable explanations](0049-topic-tutorials.md) | Catalog accepted; presentation/content updated by 0050/0052/0056 |
| [ADR 0050: Unified reader position and tracking controls](0050-unified-reader-progress-controls.md) | Accepted |
| [ADR 0051: Reading activity on Home and saved books in My Books](0051-home-reading-history-and-saved-library.md) | Accepted |
| [ADR 0052: Approved focused tutorial content](0052-approved-focused-tutorials.md) | Approved copy accepted; viewer extended by 0056 |
| [ADR 0053: Compact Reading now cards and direct tracking actions](0053-home-reading-now-actions.md) | Accepted |
| [ADR 0054: Receive Android Nostr book links](0054-incoming-nostr-book-links.md) | Accepted |
| [ADR 0055: Cover progress badge styling](0055-cover-progress-badge-styling.md) | Accepted |
| [ADR 0056: Tutorial paging, destination actions, and shared navigation controls](0056-tutorial-paging-and-shared-navigation.md) | Navigation accepted; paging controls/action placement superseded by 0058 |
| [ADR 0057: Record reader tip dismissal independently of tooltip suspension](0057-durable-reader-tip-dismissal.md) | Accepted |
| [ADR 0058: Tutorial actions in the lesson body](0058-tutorial-body-actions.md) | Accepted; supersedes 0056's paging controls/action placement |
| [ADR 0059: Bundled tutorial component illustrations](0059-tutorial-component-illustrations.md) | Accepted; extends 0049/0052 with local Compose examples |
| [ADR 0060: User completion of pending publications](0060-user-completion-of-pending-publications.md) | Accepted; extends 0028/0045 |

Update supersession links when revising a decision. Proposed/deferred slices are not implementation claims, and acceptance checklists are not evidence that runtime verification passed.
