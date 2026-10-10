# Tutorials and authoring

The Tutorials feature supplies an offline catalog and a reusable viewer. The user approved the eight tutorials on 2026-10-09; all 22 steps are integrated into the app. Readers can choose a short lesson for the task they need. Start reading is the suggested starting point; account connection is optional.

## Approved copy

| Tutorial | Outcome | Steps | Topic ID |
| --- | --- | --- | --- |
| [Start reading](tutorials/getting-started.md) | Open a book, navigate chapters, and adjust reading comfort | 3 | `getting_started` |
| [Search](tutorials/search.md) | Find books or chapter text and open a match | 3 | `search` |
| [Save a book and return to it](tutorials/save-and-resume.md) | Keep a book and resume reading | 2 | `save_and_resume` |
| [Read offline](tutorials/offline-reading.md) | Prepare and check stored chapters | 3 | `offline_reading` |
| [Keep the lines you love](tutorials/highlights.md) | Save, revisit, and optionally publish highlights | 3 | `highlights` |
| [Share your take on a book](tutorials/reviews.md) | Rate, review, and update your opinion | 3 | `reviews` |
| [Track a book](tutorials/tracking-progress.md) | Start, understand, and stop tracking | 3 | `tracking_progress` |
| [Connect your account](tutorials/connect-account.md) | Connect a signer and understand sharing | 2 | `connect_account` |

## Authoring

The catalog API is in `ui/tutorials/TutorialCatalog.kt`. `TutorialTopic` owns each stable ID and its `titleRes` and `summaryRes`. Preserve IDs when changing displayed titles: Start reading retains `getting_started` and Track a book retains `tracking_progress`. Enum order defines the catalog order shown above. The Settings tracking-help link continues to select `TutorialTopic.TrackingProgress`.

For additions or revisions, draft the copy for user review before integrating it. Keep each tutorial focused on one task, with a friendly invitation and exact action labels. Update its Markdown source, localized topic title/summary strings, and step title/body strings in `res/values/tutorial_strings.xml` (and corresponding locale files where available) together. English is currently the only resource locale. Use `tutorial_<topic_id>_<step_id>_title` and `tutorial_<topic_id>_<step_id>_body` for step resources; encode paragraph breaks as `\n\n` and escape Android string literals and XML characters.

Add ordered `TutorialStep` entries with stable, topic-local IDs and resource references. All 22 current steps select a bundled `TutorialExample`. Each enum entry owns a localized description resource; the shared frame announces it as an illustration and hides pictured action semantics. `TutorialStep.illustrationRes` remains available for local drawables with a required `descriptionRes`. A step cannot select both a drawable and a component example. Review catalog expansions as architectural decisions and document the accepted changes.

Component illustrations appear between the title and lesson body in a labelled, rounded frame. Feature composables receive deterministic fictional data from `TutorialSamples`, with no live account, repository, cache, or delivery state. Cover URLs are null and cover progress composition locals are isolated. Text fields are read-only; a transparent hit-test shield blocks pictured controls while leaving ancestor swipe/scroll gestures unconsumed, and inherited focus properties exclude pictured controls from keyboard traversal. Keep examples free of modal sheets and independent scroll containers. Reuse small production presentation pieces rather than duplicating their appearance or invoking full screens. See [ADR 0059](decisions/0059-tutorial-component-illustrations.md) and the [step mapping](plans/tutorial-component-illustrations.md).

The viewer owns paging, scroll restoration, navigation, and exit behavior. Its lightbulb header and the Settings Tutorials entry share the tutorial visual identity. Horizontal swipes move between steps; page dots indicate the active step. There are no Previous/Next buttons. The final-page action is centered below the lesson text inside the scrollable body, above the dots. Stable step IDs and saveable per-step scroll positions preserve the lesson position through rotation. The shared icon-only close control and system Back return to the originating screen.

Each topic supplies a localized final-page action and a typed `TutorialDestination`. The action intentionally leaves the tutorial and opens an existing app screen; paging itself does not change reading state or start network work. Opening a destination uses that screen's normal lifecycle. Account navigation opens Account & Sync without launching the signer.

| Tutorial | Final action | Destination |
| --- | --- | --- |
| Start reading | Start reading | Home, to choose a book |
| Search | Search now | Search |
| Save a book and return to it | Open My Books | My Books |
| Read offline | Check offline storage | Settings: Storage & Offline |
| Keep the lines you love | Start reading | Home, to choose a book |
| Share your take on a book | Start reading | Home, to choose a book |
| Track a book | Open reading progress | Settings: Reading progress & privacy |
| Connect your account | Connect your account | Settings: Account & Sync |

Content updates supply reviewed resources and catalog references without tutorial-specific persistence or remote loading. The approved Track a book content selects the existing concise presentation in Reading progress & privacy Settings; status, errors, sharing controls, and privacy confirmations remain available. The reader uses the integrated progress controls from ADR 0050 and has no separate tracking-sheet tutorial launcher.

## Release verification

Before release, confirm every listed topic has approved, non-empty content, topic IDs are unique, step IDs are unique within each topic, all resource references resolve, and Markdown and resource copy agree. Check paragraph breaks and any image alt text. Manually verify the rendered steps at large font sizes and with TalkBack, swipe navigation, dots, per-step scroll and rotation restoration, offline access, each centered final action's placement and destination, and the contextual Settings return path. Check icon control descriptions and touch targets across Settings and reader screens. See [ADR 0056](decisions/0056-tutorial-paging-and-shared-navigation.md) for navigation behavior, [ADR 0058](decisions/0058-tutorial-body-actions.md) for the updated presentation, and [ADR 0052](decisions/0052-approved-focused-tutorials.md) for approved copy. Gradle verification and device checks are performed by the user per AGENTS.md.
