# ADR 0031: Cap the reader onboarding hint and provide explicit dismissal

## Status

Accepted

## Context

ADR 0021 persisted a contextual tip only after its tooltip closed. The reader's persistent tooltip closes after a timeout, so leaving the reader before that point let the same instruction return on every visit. It also had no direct dismissal action.

## Decision

The reader menu hint has one lifetime impression. Its stable identifier is persisted as soon as it is presented, while its local UI remains visible until the person selects **Got it** or the existing timeout closes it. All contextual tooltips expose the same **Got it** action.

## Consequences

The change continues to use the app-private stable identifiers in `OnboardingTipStore`; no reading or account data is added. A person who leaves before the timeout will not see the reader hint again. New tips remain independently eligible through their own identifiers.