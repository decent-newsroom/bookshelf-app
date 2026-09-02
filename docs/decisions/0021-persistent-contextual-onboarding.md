# ADR 0021: Persist contextual onboarding tips by stable identifier

## Status

Accepted

## Context

Bookshelf needs short explanations for compact controls. A single global onboarding-complete flag would prevent future UI changes from introducing their own discoverable tip.

## Decision

`OnboardingTipStore` persists only the stable identifiers of dismissed contextual tooltips in app-private `SharedPreferences`. Each tip is independently eligible and is marked seen after the tooltip closes, whether it was followed or skipped.

The first tips explain Save/Remove and the reader's tap-to-reveal menus.

## Consequences

Future UI affordances can add new identifiers without replaying existing tips. The stored state contains no book, chapter, account, or reading-content data and follows the app's existing preferences backup policy.
