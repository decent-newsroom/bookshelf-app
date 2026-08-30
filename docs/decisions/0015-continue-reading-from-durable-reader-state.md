# ADR 0015: Continue reading from durable reader state

- Status: Accepted
- Date: 2026-08-30

## Context

Saved-book summaries already persist locally, and reader progress persists by
book coordinate. Home did not join those two sources, so readers had to find a
book in My Books after restarting the app. Updating progress only on a chapter
change also could not reliably identify the book most recently reopened at the
same chapter.

## Decision

When a saved book finishes loading for the reader, update its existing chapter
position and a durable last-opened timestamp in `ReaderSettingsStore`. Do not
record this for unsaved books.

Home derives one Continue reading entry by joining saved `BookSummary` values
with persisted progress by coordinate and choosing the newest timestamp. Its
tap target uses the existing `openBook` flow; the reader then uses the stored
chapter index. The card stores no book details, chapter bodies, or rendered
HTML.

## Consequences

- Continue reading survives app process restart without relying on cache files
  or network availability for selecting the book.
- The card can only represent books whose summaries are still in My Books;
  removing a book removes it from Home immediately while leaving its stale
  progress harmlessly unavailable.
- Loading remains necessary before the reader can show chapter content, so
