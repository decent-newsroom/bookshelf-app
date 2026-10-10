# Changelog

All notable user-facing changes to Bookshelf are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]


## v0.1.27

- Permanently remember dismissed reader Save/Remove and tap-to-show-menu hints across books and app restarts, including when leaving the reader before the timeout.
- Use consistent icon-only back and close controls across Settings, Tutorials, and the reader.
- Show Continue reading on Home only when the Reading now shelf is empty, avoiding competing reading sections.
- Register Bookshelf for incoming Android `nostr:naddr` book links. Open verified kind-30040 publications through the normal reader and resume flow, show library-card details, and report malformed, unsupported, unavailable, or offline links without automatically saving the book.
- Add Discard changes as a secondary button with a visible background in the end-of-book editor and review sheet, keeping the original rating and review intact; new drafts can also be discarded.
- Populate Tutorials with eight friendly lessons covering reading comfort, book and chapter search, saving and resuming, offline reading, highlights, reviews, tracking, and account connection. Each works offline with two or three steps; the existing tracking-help link opens Track a book.
- Simplify My Books to saved books without sub-tabs. Keep Reading now on Home and show Finished at the bottom as a centered grid of compact covers, with up to seven per row and the most recent completions first.
- Merge reader tracking controls with the position indicator in the book header and tap-to-show menu, with one progress bar, concise tracking and sync status, and theme-aware filled actions. Remove the separate tracking sheet and Aa shortcut while preserving reading-position, reset/stop, and completion behavior.
- Keep reader menu actions reachable with scrolling at large text sizes, wrap the Aa theme/alignment controls, and give those controls and the inline Write/Edit review action visible backgrounds. Preserve a visible background for disabled secondary actions, including Sync during synchronization.
- Document a Getting started tutorial draft and its integration plan for review, covering discovery, reader controls, saved books, resume, offline reading, and optional account connection.
- Add the offline Tutorials catalog and reusable viewer infrastructure, with Getting started and Tracking progress topics ready for user-authored content. Contextual tracking entry points reuse the same topic viewer without changing reading state or contacting relays.
- Make cover reading indicators easier to see with a larger badge, thicker progress ring, opaque backing, and contrasting outer border; hide them on Continue reading thumbnails, book-opening artwork, and reader-header covers.
- Add Track progress to the reader menu and reader settings, with a scrollable sheet for Track, Reset, Stop, and Sync plus current status and bookmark guidance.
- Add a dedicated Reading progress & privacy Settings screen with automatic-resume guidance, current sharing modes, and accessible privacy controls.
- Add Share book and Copy book link actions that create a stable NIP-19 address from the publication's exact author and `d` coordinate, with an optional public source-relay hint.
- Show reading progress in the corner of book covers throughout the app, including unsaved books: a highlighted ring for partial progress and a filled circle at 100% read.
- Report 100% at the bottom of a complete book, preserve chapter offsets through Finish/review cards, and keep incomplete publications from appearing fully read.
- Coordinate local chapter display, scroll resume, and explicit Nostr section tracking; preserve bookmarks through Reset/Stop, prevent stale advances after tracking actions or account changes, and keep rereading independent from finished history.
- Fix the My Books tab-row compilation error by using the Material 3 secondary tab indicator API.
- Refresh pending-highlight counts when background delivery changes, show outstanding relay/chapter acknowledgements and their errors in Settings, and add Retry now to Storage & Offline.
- Complete highlight delivery when a resolved remote destination moves to the configured local relay, and preserve per-event relay failures so successful chapter delivery cannot hide a failed highlight.
- Add explicit reading tracking and finished-book history that work locally without an account, with independent privacy preferences and selected-entry public sharing.
- Show tracked books in Home's Reading now and add Saved, Reading, and Finished views to My Books, independent of saved-library membership.
- Add Track, Reset, Stop, section progress, and delivery feedback in the reader; show explicit Finish and a signed-in inline review card after the book's final section.
- Synchronize public reading lists as kind 16374 snapshots and completed-book labels as kind 1985 events, with durable account-scoped pending work, immutable signed retries, and label-before-removal delivery.
- Keep scrolling publication windows fixed at three seconds, request signer interaction only through explicit actions when background permission is unavailable, and preserve reading state when caches are cleared.


## v0.1.26

- Hide the More like this Retry button when the book edition is not indexed for recommendations.
- Fit the opening cover card to the artwork's proportions and fill it edge to edge, preventing the fallback background from peeking around the image.
- Show a large, generously padded cover while opening a book, with a full-title-and-author fallback when artwork is missing, loading, or unavailable.
- Jump directly to the selected chapter from the table of contents instead of animating through intervening chapters.
- Replace Include book contents with Search book contents: enabled searches chapter text only, validates 4–160 characters, and shows the full-text hint beside the toggle.
- Keep content search matches together in one result card containing the book header, matching chapter, excerpt, and Open matching chapter action.
- Replace separate cache-clear buttons in Settings with selection toggles and one Clear selected caches action, including a combined confirmation and per-cache failure reporting.
- Add Rate and review directly after See details in the book menu, opening the review form with the active reader's cached review ready to edit.
- Add a publication type, chapter count, and Read card above community ratings in book details, while retaining type and chapter count in the metadata.
- Embed More like this below community ratings as a carousel shared with Home, with cached results shown immediately and independent loading, offline, and retry feedback.
- Persist recommendations in a separate bounded 24-hour cache, retain stale results offline or on refresh failure, and expose independent storage statistics and clearing in Settings.
- Simplify Search to all publication metadata by default, with an optional Include book contents toggle instead of scope buttons. Keep the toggle for the current app session and support `content:` for chapter-only searches.
- Give full-text chapter searches a two-minute read timeout instead of 20 seconds so expensive queries have more time to return results.
- Make the stalled-response cancellation regression test observe body-source reads directly instead of relying on HTTP event-listener timing.
- Add All, Title, Author, Subject, and Inside books search controls, visible partial-result notices, and an explicit action to open a matching chapter after normal reader loading.
- Align search validation with the extended Books API, reject unrelated parent-book matches, reconcile chapter hits with current publication revisions, and stop discovery requests while offline.
- Add More like this in book details with verified, server-ranked recommendations, local saved-book filtering, a bounded session cache, and independent offline/error/retry states.
- Cancel discovery HTTP work when dismissed or superseded, including response reads and fallback attempts, and preserve reader resume when a matching chapter is unavailable.
- Search the active user's verified NIP-65 read relays alongside configured relays for community ratings and recent-rating suggestions.
- Show cached community-rating summaries immediately in book details, while publisher and relay refreshes continue independently.
- Include profile aggregator alongside the current read relays, to improve publisher and reviewer profile lookup.


## v0.1.25

- Let signed-in readers edit their own book rating and written review from Community ratings. Prefill the composer from their current review, save the signed revision to the durable review outbox, and show the updated rating from the local cache.
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
