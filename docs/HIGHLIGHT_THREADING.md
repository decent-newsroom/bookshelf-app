# Highlight Threading

Highlights use the NIP-22 root/parent convention to make the book index the
stable discovery scope while retaining the chapter that contains the selected
quote as the direct context.

## Direct highlights

A direct highlight of a chapter in a book has these thread-reference tags:

```json
[
  ["A", "30040:<book-index-pubkey>:<book-index-d-tag>"],
  ["K", "30040"],
  ["P", "<book-index-pubkey>"],

  ["a", "30041:<chapter-pubkey>:<chapter-d-tag>"],
  ["k", "30041"],
  ["p", "<chapter-pubkey>"]
]
```

Uppercase tags are the **root scope**:

- `A` identifies the addressable book index (kind `30040`).
- `K` records the root event kind (`30040`).
- `P` identifies the book-index author.

Lowercase tags are the **immediate parent**:

- `a` identifies the addressable chapter containing the quote (kind `30041`).
- `k` records the parent event kind (`30041`).
- `p` identifies the chapter author.

The tag keys are case-sensitive.  In particular, `A` is not an alternate form
of `a`: it groups highlights under their book index, while `a` retains their
chapter-level location. The root and parent authors may be the same pubkey;
both `P` and `p` remain meaningful and should be included.

## Replies to highlights

For a reply to an existing highlight, preserve `A`/`K`/`P` for the `30040`
book index. Change the lowercase parent reference to the highlight being
replied to (normally an `e` tag for its event ID, `k` of `1111`, and `p` for
its author), rather than pointing back to the chapter.

This convention follows the root/parent distinction in [NIP-22 Comment](https://github.com/nostr-protocol/nips/blob/master/22.md).
