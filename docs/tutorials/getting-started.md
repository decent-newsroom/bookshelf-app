# Getting started

Status: Draft for content review, 2026-10-09. Prepared at the user's request; this copy is not yet approved or connected to the in-app catalog.

## Plan

1. Help a first-time reader find, open, and save one readable book without needing an account.
2. Use short, action-led steps with the labels shown in the current app. Explain automatic resume separately from optional tracking.
3. Explain how to prepare saved books for offline reading and make account connection an optional final step.
4. Review the copy below before adding Android resources, as required by the [authoring guide](../tutorials.md).
5. After review, update the `getting_started` summary and add each title/body to `app/src/main/res/values/tutorial_strings.xml`. Use the ordered IDs below in `TutorialCatalog`; leave both illustration fields null. English is currently the only resource locale.
6. Check resource references and ordering, then have the user run Gradle verification and manually check navigation, rotation, large fonts, TalkBack, and offline access using [ADR 0049](../decisions/0049-topic-tutorials.md). Tracking progress remains a separate unfinished topic, so completing this draft does not make the whole catalog release-ready.

## Proposed topic summary

Find your first book, make reading comfortable, and keep it close at hand.

## Tutorial copy

### 1. Find a book

Step ID: `find_book`

Start on Home and browse the shelves, or tap Search. Enter a title, author, or subject and tap Search to see matching books.

Leave Search book contents off when looking for a book. Turn it on to search words inside chapters instead; use 4 to 160 characters and allow extra time for results.

### 2. Open and read

Step ID: `open_and_read`

Tap a book to open it, then scroll to read. Start with an internet connection so Bookshelf can load the available chapters. Some catalog entries have no readable text.

Tap the reading area to show the menus. Choose Contents and tap an available chapter to jump to it. Use Back to return to Home.

### 3. Make reading comfortable

Step ID: `reading_comfort`

In the reader, tap Aa to open Reader settings. Adjust the text size, line height, and alignment to suit you.

Choose Paper, Sepia, or Night for the appearance you prefer. You can also change these options and choose a font from Settings > Reading & Display. Bookshelf remembers your choices.

### 4. Save your book

Step ID: `save_book`

Show the reader menus and tap Save. Your book appears in My Books under Saved. You can also press and hold a book card and choose Add to My Books.

Saving works without an account. After opening a saved book, look for Continue reading on Home to return to your most recently opened saved book.

### 5. Keep your place

Step ID: `keep_your_place`

Bookshelf saves your reading position automatically on this device. Reopen the book to resume where you left off.

To add it to a reading list, open the reader menu, choose Track progress, then tap Track. The book appears in Reading now on Home and under Reading in My Books. For sharing choices, open Settings > Reading progress & privacy.

### 6. Prepare for offline reading

Step ID: `offline_reading`

Save the book, then open it while connected and let its chapters load. Bookshelf keeps the loaded content of saved books for offline reading. Saving a book alone does not download its text.

Before going offline, check Contents for unavailable chapters and try reopening the book without a connection. Only stored chapters will be readable. If you clear the Offline reading cache in Settings > Storage & Offline, open your saved books online again before relying on them offline.

### 7. Connect an account when you want to

Step ID: `optional_account`

You can browse, read, and save books without signing in. To connect your Nostr identity, have a compatible Android signer available, then open Settings > Account & Sync and tap Connect account. Review the request in your signer.

Connecting enables bookshelf sync and signed reviews. Saving books while connected can ask your signer to share your bookshelf publicly. Check Settings > Reading progress & privacy before sharing reading lists or finished books.

## Integration and review checklist

- Review the topic summary and all seven steps before promoting this draft to app resources.
- Preserve the `getting_started` topic ID and each topic-local step ID above.
- Use `tutorial_getting_started_<step_id>_title` and `tutorial_getting_started_<step_id>_body` for the proposed string resource names. Encode paragraph breaks with `\n\n` when transferring the copy into Android XML.
- Supply title/body resource references only; the existing viewer owns Previous, Next, Done, Close, Back, and position restoration.
- Do not add tutorial actions, persistence, signer calls, or remote content loading.
- Confirm the reader controls and offline/account instructions on a device. Review at large font sizes and with TalkBack; no illustrations are proposed, so no image alt text is needed.
- Keep Tracking progress explanations intact until that separate topic has reviewed content.

## Source checks

The draft was checked against `ui/home/HomeScreen.kt`, `ui/search/SearchScreen.kt`, `ui/reader/ReaderControls.kt`, `ui/reader/ReaderTrackingSheet.kt`, `ui/library/MyBooksScreen.kt`, `ui/books/BookSheets.kt`, `ui/settings/SettingsScreen.kt`, `ui/BookshelfViewModel.kt`, and the saved-book, resume, and tutorial sections of [ARCHITECTURE.md](../ARCHITECTURE.md).

Gradle verification and device/accessibility checks have not been run; the user performs them under AGENTS.md.
