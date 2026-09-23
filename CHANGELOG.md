# Changelog

All notable user-facing changes to Bookshelf are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

- Show cached community ratings immediately, refresh them in the background when online, and replace older revisions by the newest `kind:pubkey:d` event in both the list and rating cache. Compact old cached revisions locally in the background at app startup, including offline.
- Normalize pasted `nostr:naddr` references before Search resolves their exact publication coordinates.
- Add a **Delete** action for saved private highlights. Queued or published highlights remain protected because their immutable signed events may still be delivering.
- Keep validated online/offline status stable while Android hands the default network between Wi-Fi, cellular, and VPN transports.

## v0.1.24

- Improve reader resume precision by persisting the in-chapter pixel offset, and make fresh reading progress start at 0% instead of counting the first chapter as complete.
- Move the Reading & Display preview beneath its controls, give it a theme-aware border, and add text alignment to the in-reader controls.
- Give secondary actions a theme-aware filled background so their padded touch targets align visually with adjacent controls.
- Present the Settings About source-code destination as a text link with the repository name.
- Cap the reader's tap-to-reveal-menus hint at one lifetime impression, persist it when shown, and add a **Got it** dismissal control.
- Split the Compose UI into bounded feature, reader, shared-component, and signer-effect packages while keeping BookshelfApp as the composition root.
- Restore the Home feed's scroll position after opening a book and returning to Home.
- Cache successfully opened saved books for offline reading, use cached chapters before remote fallbacks, and present source-neutral offline availability notices in the reader.
- Publish highlights with NIP-22 root tags for their kind `30040` book index and parent tags for the kind `30041` chapter containing the quote.


## v0.1.23

- Treat relay duplicate acknowledgements as successful immutable-highlight delivery so retries can complete.
- Change Settings behavior, so Back gesture from details navigates to main settings list.


## v0.1.22

- Align Settings headers with the main app content and use the edge Back gesture to return Home.
- Improve highlights context menu.


## v0.1.21

- Reorganize Settings into dedicated Reading, Account, Sources, Relays, Storage, and About screens.
- Add persistent reader font and paragraph alignment controls with a live preview.
- Show pending publications, offline state, and safe cache actions alongside the existing account and relay configuration.


## v0.1.20

- Highlights
- Add validated-offline rating reads, durable locally saved review delivery, optional local Citrine publication, and automatic deferred relay synchronization.
- Resolve cached and refreshed Nostr author profiles in community review details, displaying reviewer names when available.


## v0.1.20

- Look up verified reviews for library cards and full books exclusively through interoperable `a`/`A` publication-address tags; publication emits its namespaced `d` convention plus companion `k=30040` and publication-author `p` tags.


## v0.1.19

- Clarify Mercury book-opening APIs by naming the resolved-index operation `openBook`.
- Keep zero-chapter kind `30040` publication indexes discoverable as library cards, label them as full text unavailable, and prevent them from opening the reader.
- Publish and read rating events using each publication index's declared type for the namespaced d target and m tag, defaulting to `book` when unset.


## v0.1.18

- Accept pasted NIP-19 publication `naddr` references in Search, resolve their exact coordinates through secure relay hints and configured read relays, and retain the API fallback.


## v0.1.17

- Expose transient community-rating summaries on book summaries, hydrated through the dedicated ratings repository.
- Add verified R1 book-rating parsing, aggregation, Quartz relay reads, and the Community ratings details flow.
- Route rating publication to configured defaults, the active user's write relays, and the publication author's NIP-65 read relays.
- Accept inclusive normalized rating endpoints `[0, 1]` as an explicit compatibility policy pending R1 clarification.
- Show rating-cache size, event count, and last successful sync in Settings, with an independent safe clear action.


## v0.1.16

- Display the current app version beneath the Settings sections.
- Open books by tapping the card and show My Books, publication details, and configured local-relay actions on long press.
- Show publisher profile information and parsed publication/event metadata in a book details modal.
- Queue and broadcast the original signed book index and all available signed chapters to the configured local relay.
- Replace the deprecated plain-tooltip position provider with the positioned Material 3 tooltip API.


## v0.1.15

- Move bookshelf synchronization actions from Account to the Relays Settings section.
- Confirm when the optional local relay is saved or disabled from Settings.
- Add persistent contextual tooltips for saving books to My Books and revealing reader navigation and settings menus by tapping the reading view.


## v0.1.14

- Make the system Back gesture return every non-Home page to Home, including cancelling a pending book open.
- Reorganize Settings into collapsible Appearance, Account, Relays, and Cache sections, including read-only NIP-65 relay visibility and an optional local Citrine relay.
- Add Compose Material Icons Extended for app iconography.
- Make Sepia the default theme for new reader preferences.


## v0.1.13

- Discover verified NIP-65 user relay lists and route reads and directory publishing to the account's read and write relays.
- Migrate directory, profile, and known-relay publication-index traffic to Quartz's shared Nostr relay client.
- Show relay-specific directory sync outcomes and bounded rejection reasons instead of a generic no-acceptance error.
- Move persistent chapter retrieval to Quartz relay subscriptions, preserving relay hints and Mercury fallback.

## v0.1.12

- Add a Home **Continue reading** card for the most recently opened saved book, including durable chapter progress and resume behavior.
- Fix signed local bookshelf directories being rejected before publication because their required publish-only metadata was compared as an editable collection tag.
- Add an explicit, retryable **Sync to relays** action for publishing the current local My Books directory after signer or relay failures, while retaining a separate pull action.

## v0.1.11

- Open saved independently published books from their resolved kind `30040` index, including its chapter relay hints, when Mercury does not mirror that index event.
- Show book-opening errors on the My Books screen instead of silently returning to the saved-books list.
