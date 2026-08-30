# Changelog

All notable user-facing changes to Bookshelf are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and versions follow [Semantic Versioning](https://semver.org/).

## v0.1.12

- Add a Home **Continue reading** card for the most recently opened saved book, including durable chapter progress and resume behavior.
- Fix signed local bookshelf directories being rejected before publication because their required publish-only metadata was compared as an editable collection tag.
- Add an explicit, retryable **Sync to relays** action for publishing the current local My Books directory after signer or relay failures, while retaining a separate pull action.

## v0.1.11

- Open saved independently published books from their resolved kind `30040` index, including its chapter relay hints, when Mercury does not mirror that index event.
- Show book-opening errors on the My Books screen instead of silently returning to the saved-books list.
