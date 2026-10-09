# ADR 0054: Receive Android Nostr book links

## Status

Accepted, 2026-10-09.

## Context

Bookshelf already creates stable `nostr:naddr` publication links in its shared book actions. A recipient needs Android to offer Bookshelf when another app opens such a link, and the app must resolve it without bypassing signature verification, rendering, resume, or offline rules.

Android intent registration can select the `nostr` scheme but cannot inspect the kind encoded inside a NIP-19 address. The exported activity therefore needs an application-level validation boundary. Incoming links are transient navigation requests, not requests to save, track, sign, or publish a publication.

## Decision

Register `MainActivity` for `ACTION_VIEW` with the `DEFAULT` and `BROWSABLE` categories and `nostr` scheme. Preserve its launcher filter and use `singleTop` to receive warm deliveries through `onNewIntent`. Installing the manifest registers Bookshelf locally; chooser behavior and any previously selected defaults belong to Android.

Accept only the standard opaque `nostr:naddr` URI form carrying a valid kind `30040` publication coordinate, bounded to 4,096 characters. Decode and validate untrusted input before using it for discovery, and show visible feedback for malformed links, unsupported NIP-19 reference types, and non-book kinds. Scheme registration necessarily means Bookshelf may also appear for links it cannot open.

Resolve the exact author and `d` identifier through the existing NIP-19 reference lookup. Reuse its signature verification, matching-coordinate checks, secure relay-hint handling, and HTTP exact-coordinate fallback. Preserve the `d` identifier exactly, including surrounding whitespace, from the NIP-19 decoder through query construction and both HTTP and relay publication mapping. Never open an unrelated discovery result. Show distinct feedback for an unavailable lookup and a publication not found. Feed the resolved summary through normal `openBook`, preserving chapter loading, AsciiDoc rendering and HTML caching, saved reader snapshots, and resume. A publication index without readable chapter references opens details as a library card.

While `ValidatedInternetConnectivity` is offline, resolve only matching metadata already held in saved books, curated shelves, reading-state records, current UI state, or a fresh existing exact-search cache entry. Known summaries use the existing offline reading policy; unknown coordinates produce an offline notice and start no remote discovery. Existing caches and outboxes retain their independent clearing policies.

`MainActivity` owns an activity-scoped `BookshelfViewModel` obtained with `ViewModelProvider` and passes that same instance to `BookshelfApp`. The retained ViewModel consumes the initial `ACTION_VIEW` once, without replaying it across configuration recreation. Every warm `onNewIntent` delivery starts a new request, including the same URI. Process recreation creates a new ViewModel, which resolves the activity's current `ACTION_VIEW` again rather than suppressing recovery with a saved consumption marker or cleared URI.

The ViewModel owns cancellable resolution and opening. A newer link, an ordinary open, Home, tab selection, Search, or book details cancels and invalidates pending work; guarded completion cannot reopen a dismissed reader or replace a newer request. Connectivity loss during link resolution also cancels the request and shows offline feedback.

No new persistent link history or deferred link outbox is introduced. Link handling does not auto-save a book, initiate explicit tracking, request a signature, or publish events; ordinary reader progress and resume behavior still apply. This stage has no incoming `ACTION_SEND` text receiver, HTTPS domain association, or signed NIP-89 announcement. Those remain separate future work.

## Consequences

- Other apps can offer Bookshelf using Android's existing URI-opening flow.
- Stable publication addresses continue to resolve across index revisions.
- Other Nostr apps may appear alongside Bookshelf; a custom scheme is not an exclusive or verified association.
- Invalid or unavailable links show feedback rather than triggering broad searches or opening a different book.
- Cold, warm, repeated, and superseded requests share the same reader/cache boundary.
- Offline opening depends on known metadata and the existing saved-reader snapshot; receiving a link does not make unavailable content downloadable offline.

## Verification

The owner runs Gradle builds and tests under the Windows development setup in [DEVELOPMENT.md](../DEVELOPMENT.md). No Gradle verification is performed by the agent.

Device verification should cover:

1. Cold-start and warm-start opening of a valid kind-30040 link, including the same book twice and a second book while the first is loading or open.
2. Rotation during resolution and after the reader opens, preserving reader position without reapplying the original intent. Deliver another warm link after rotation. Recreate the process with the current `ACTION_VIEW` and verify that the new ViewModel resolves the link again.
3. Back/Home and tab/navigation cancellation during a slow lookup or render, with no delayed reader reopening.
4. A library-card link opening details, and a saved book resuming through the normal reader flow.
5. Offline known saved-book opening from its reader snapshot; unknown coordinates showing an offline notice without remote discovery; connectivity loss during resolution.
6. Malformed Bech32/TLV input, non-book `naddr` kinds, other NIP-19 reference types, `nostr://` variants, empty URIs, extra suffix/query/fragment data, unavailable coordinates, and unrelated returned events.
7. Android chooser behavior with another Nostr app installed, and behavior when a different handler is selected as the default.

Use an actual kind-30040 `naddr` from **Copy book link** in place of `naddr1BOOK_ADDRESS`:

```powershell
adb shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "nostr:naddr1BOOK_ADDRESS" -p eu.decentnewsroom.bookshelf
```

Omit the package constraint to exercise Android's resolution/chooser behavior:

```powershell
adb shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "nostr:naddr1BOOK_ADDRESS"
```

Repeat the command while Bookshelf is foregrounded to verify warm delivery.

Added regression-test source in `NostrBookLinkParserTest` covers exact identifiers and relay hints, case rules, malformed URI forms, length/checksum failures, unsupported kinds/reference types, and blank identifiers. `MercuryBookRepositorySearchTest` adds exact NIP-19 identifier preservation through repository lookup, including Unicode, punctuation, and surrounding whitespace. These tests have not been executed by the agent; lifecycle and cancellation cases above remain device verification for the owner.
