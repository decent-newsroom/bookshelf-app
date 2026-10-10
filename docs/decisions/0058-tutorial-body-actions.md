# ADR 0058: Tutorial actions in the lesson body

## Status

Accepted, 2026-10-10. Supersedes ADR 0056's Previous/Next controls and final-action placement. Its navigation, stable-ID, offline-content, and state-restoration decisions remain accepted.

## Context

The user requested removal of Previous/Next buttons and a final CTA centered below the tutorial text and above the page dots.

## Decision

- Use the existing horizontal pager for step navigation and retain the page-position dots. Remove the Previous/Next button row.
- Place the final destination action after the final step's text, centered horizontally inside its scrollable body. Keep the dots below the pager. Long lessons and large text can scroll to reveal the action.
- Preserve destination callbacks, close/system Back behavior, stable step IDs, and saveable pager and per-step scroll state. No persistence or network behavior changes.

## Verification

Inspect the viewer for removal of the button row and final-step-only placement of the centered action. The user performs Gradle and device checks per AGENTS.md: verify swipes and TalkBack paging, dots, action placement and destinations, large text scrolling, and rotation restoration. Builds and device checks were not performed by the agent.
