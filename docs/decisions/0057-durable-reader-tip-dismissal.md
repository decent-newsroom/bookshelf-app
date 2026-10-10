# ADR 0057: Record reader tip dismissal independently of tooltip suspension

## Status

Accepted; clarifies ADRs 0021 and 0031.

## Context

The Save/Remove tooltip recorded its seen flag only after Material's persistent `TooltipState.show()` returned normally. Dismissing a persistent tooltip can leave that operation suspended, and leaving composition cancels it. The callback could therefore be skipped, allowing the hint to return for another book. Tooltip anchor gestures could also reopen help independently of its eligibility flag.

## Decision

Record dismissal directly on **Got it** and timeout, and in the display operation's `finally` block when the anchor leaves composition or display ends. Repeated callbacks are safe because `OnboardingTipStore.markSeen` is idempotent. Use the latest callback across recompositions. Render only anchor content for ineligible tips and disable tooltip input gestures.

Initialize the ViewModel's seen-tip state from the store's current value before collecting subsequent changes. Keep the existing stable preference keys and app-wide SharedPreferences storage; existing seen flags need no migration. Preserve the reader menu tip's immediate impression recording.

## Consequences

Dismissal survives opening different books and restarting the app. Leaving early also consumes a presented hint. No book-specific or account-specific flags, cache dependencies, network work, or new reset controls are introduced. Clearing app data still resets preferences normally.

## Verification

On a fresh installation, dismiss the menu hint and Save/Remove hint with **Got it**, open another book, and restart the app: neither hint should recur. Repeat with timeout and early reader exit on fresh app data. Long-pressing Save after dismissal should not reopen the tooltip. Builds and device verification are run by the user under the repository's Windows development policy.
