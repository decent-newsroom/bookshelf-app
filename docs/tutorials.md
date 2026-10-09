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

Add ordered `TutorialStep` entries with stable, topic-local IDs and resource references. `TutorialStep.illustrationRes` is optional and accepts a local drawable; when used, set `descriptionRes` to localized, meaningful alt text. Leave both fields null when there is no illustration, as in the current catalog. Review catalog expansions as architectural decisions and document the accepted changes.

The viewer owns paging, scroll restoration, navigation, and exit behavior. Content updates supply reviewed resources and catalog references without tutorial-specific persistence, remote loading, or reading actions. The approved Track a book content now selects the existing concise presentation in Reading progress & privacy Settings; status, errors, sharing controls, and privacy confirmations remain available. The reader uses the integrated progress controls from ADR 0050 and has no separate tracking-sheet tutorial launcher.

## Release verification

Before release, confirm every listed topic has approved, non-empty content, topic IDs are unique, step IDs are unique within each topic, all resource references resolve, and Markdown and resource copy agree. Check paragraph breaks and any image alt text. Manually verify the rendered steps at large font sizes and with TalkBack, navigation and rotation restoration, offline access, and the contextual Settings return path. See [ADR 0052](decisions/0052-approved-focused-tutorials.md), which extends [ADR 0049](decisions/0049-topic-tutorials.md). Gradle verification and device checks are performed by the user per AGENTS.md.
