# Security Audit — 2026-08-27

## Executive summary

This static audit originally found **one high-severity, three medium-severity, and two low-severity findings**. The most important issue was systemic: events received from Nostr relays and Mercury were decoded and structurally filtered without verifying their NIP-01 event IDs and Schnorr signatures.

The app otherwise has a relatively small Android attack surface. It requests only `INTERNET`, has no WebView or JavaScript bridge, uses Android-private storage, uses normal platform TLS validation for its default HTTPS/WSS endpoints, keeps Nostr private keys in the external signer, bounds directory and loaded-chapter counts, and performs several useful signer-result and tag-shape checks.

The remediation described below was implemented in the working tree after the baseline audit. A full Gradle verification run is still required on a host where the Gradle client can establish its local loopback connection.

## Remediation status — 2026-08-27

| ID | Status | Implemented control / remaining risk |
| --- | --- | --- |
| SEC-01 | Implemented; build verification pending | A shared Quartz-backed verifier now checks canonical IDs, Schnorr signatures, event bounds, future timestamps, request context, signer results, cached profiles, Mercury responses, and both relay paths before use. Focused valid/forged/future/context tests were added. |
| SEC-02 | Substantially mitigated | HTTP bodies, relay messages, event fields, searches, chapter references, relay settings, renderer inputs/outputs, and cache growth are bounded. Cache writes are atomic and pruned. Eager accepted-chapter rendering and parser execution-time budgets remain defense-in-depth work. |
| SEC-03 | Implemented | API 26–30 and API 31+ rules exclude signer and reading data from cloud backup; API 31+ device transfer explicitly preserves only the non-identity reader state selected by ADR 0007. |
| SEC-04 | Partially mitigated | The Gradle distribution and wrapper JAR are checksum-pinned, GitHub Actions use immutable SHAs, and build/test runs read-only without signing secrets. Dependency-verification metadata and a signing job that never evaluates project build logic remain outstanding. |
| SEC-05 | Implemented | Chapter navigation accepts absolute HTTPS links only and shows the normalized host in a confirmation dialog before external navigation. |
| SEC-06 | Substantially mitigated | Automatic remote covers are restricted to an explicit HTTPS Gutenberg allowlist with monogram fallback. Redirect enforcement and response/dimension limits remain defense-in-depth work. |

The detailed findings below retain the original audit evidence and recommendations as a baseline record. See ADRs 0005–0008 and `ARCHITECTURE.md` for the implemented policy.

### Remediation validation

- A daemon-free Kotlin compiler check passed for every changed non-Compose production file and all focused tests.
- 33 focused JUnit tests passed, covering canonical hashing/signatures, forged and context-mismatched events, profile caching, relay settings, Mercury mapping/search, renderer/cache quotas, link navigation, and cover policy.
- `.github/scripts/verify-security-config.sh` passed, including backup-rule assertions, immutable action references, and Gradle distribution/wrapper checksums.
- The prescribed full Gradle unit-test/assembly run remains blocked on this host before project configuration because Gradle cannot establish its internal loopback connection. This is an environment verification gap, not a passing build result.

## Scope and method

- Audited revision: `1cfd1e3121e7dde273a8aa795c513b096438c521` on `main`.
- Audit date: 2026-08-27.
- Reviewed all production Kotlin sources, the manifest and backup rules, Gradle configuration and direct dependency declarations, the release workflow, tests, architecture notes, and ADRs.
- Traced external data from Mercury HTTP, Nostr WebSockets, the Android external signer, chapter links, and cover URLs through validation, rendering, caching, persistence, and publication.
- Used qualitative severities based on exploitability and confidentiality, integrity, and availability impact.
- No application code or configuration was changed. No dynamic device test, traffic interception, APK reverse engineering, malicious-relay integration test, or exhaustive transitive-dependency CVE scan was performed. Dependency versions were inspected, but “no known vulnerabilities” should not be inferred from this review.

## Findings overview

| ID | Severity | Finding | Primary impact |
| --- | --- | --- | --- |
| SEC-01 | High | Nostr events are accepted without ID or signature verification | Content, identity, and saved-shelf integrity |
| SEC-02 | Medium | Remote responses, event fields, rendering work, and chapter cache growth are insufficiently bounded | Memory, CPU, and disk denial of service |
| SEC-03 | Medium | Backup rules do not cover Android 11 and lower and include reading/identity preferences | Reading-history and identity privacy |
| SEC-04 | Medium | Release supply-chain integrity controls are incomplete | Release key, token, and artifact integrity |
| SEC-05 | Low | Untrusted chapter links are made directly clickable without URI policy | Phishing and unintended external-app activation |
| SEC-06 | Low | Author-controlled cover URLs are fetched automatically | Reading/search-interest tracking |

## Detailed findings

### SEC-01 — Nostr events are accepted without ID or signature verification

**Severity:** High  
**Category:** CWE-345, Insufficient Verification of Data Authenticity

#### Evidence

`NostrEvent` is a passive serialization model; it contains `id`, `pubkey`, and `sig`, but defines no authenticity invariant ([`NostrEvent.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/domain/NostrEvent.kt#L6)).

The directory/profile relay client decodes an event and accepts it when its claimed `kind`, `pubkey`, and directory `d` tag match the request. It then selects the greatest claimed `createdAt`; it does not recompute `id` or verify `sig` ([`NostrRelayClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/NostrRelayClient.kt#L106), [`NostrRelayClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/NostrRelayClient.kt#L290)).

The persistent chapter client similarly accepts a decoded event when its claimed ID or claimed author/`d` coordinate matches a requested reference, and prefers the greatest claimed timestamp ([`PersistentNostrChapterSource.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/PersistentNostrChapterSource.kt#L98), [`PersistentNostrChapterSource.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/PersistentNostrChapterSource.kt#L185)). The implementation ADR explicitly records that relay events are not cryptographically verified ([ADR 0001](decisions/0001-persistent-relay-chapter-fetching.md#L31)).

Mercury HTTP responses are read and deserialized directly into the same unverified model ([`MercuryApiClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryApiClient.kt#L221), [`MercuryApiClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryApiClient.kt#L238)). Repository mapping checks the event kind and a few non-blank fields but not canonical hex lengths, event-ID correctness, signatures, or exact response/request binding ([`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L221), [`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L302)).

Unverified remote directory tags are normalized, resolved through Mercury, merged into local state, and persisted ([`BookshelfViewModel.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfViewModel.kt#L525), [`BookshelfViewModel.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfViewModel.kt#L569)). Rendered unverified chapter bodies are also cached on disk ([`ChapterHtmlCache.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterHtmlCache.kt#L47)).

The external-signer result receives useful account and collection-tag binding checks, but the returned event's `id` and `sig` are still not verified before publication ([`BookshelfRelaySync.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/BookshelfRelaySync.kt#L136), [`BookshelfViewModel.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfViewModel.kt#L380)).

NIP-01 defines the event ID as the SHA-256 hash of the canonical serialized event and `sig` as a Schnorr signature over that ID. Those properties, not a relay's filtering, establish author and content authenticity: [NIP-01 events and signatures](https://github.com/nostr-protocol/nips/blob/master/01.md#events-and-signatures).

#### Exploit scenarios

1. A malicious default relay returns a forged kind `30041` event with the expected author/`d` coordinate, arbitrary AsciiDoc content, and a far-future timestamp. It wins the cross-relay merge, is rendered, displayed, and cached even though its signature is empty or invalid.
2. A directory relay returns a forged kind `30045` event claiming the signed-in pubkey. The app can merge up to 500 attacker-chosen references into the durable local bookshelf. The local-first merge prevents deletion, but does not prevent injection.
3. A relay returns forged kind `0` metadata for the active pubkey, causing an attacker-selected display name to be cached and shown as the account identity.
4. A compromised Mercury endpoint can replace publication metadata or chapter events because HTTP response decoding has the same missing verification gate.

#### Recommendation

Create one mandatory verification boundary that every event crosses before feature code can inspect, compare, cache, render, merge, or publish it. Prefer a well-reviewed secp256k1/Nostr implementation already available through Quartz rather than implementing Schnorr verification manually.

The boundary should, at minimum:

1. Validate required fields and canonical bounds: 64-character lowercase hex `id`/`pubkey`, 128-character lowercase hex `sig`, legal kind/timestamp values, and valid string tag arrays.
2. Canonically serialize `[0, pubkey, created_at, kind, tags, content]`, recompute SHA-256, and require equality with `id`.
3. Verify `sig` over `id` using `pubkey`.
4. Apply contextual binding after cryptographic verification: requested event ID, kind, author, `d` coordinate, and subscription ID.
5. Compare `created_at` only among verified, context-matching events. Add a deterministic ID tie-breaker and a reasonable future-timestamp policy.
6. Return a distinct `VerifiedNostrEvent` type so unverified `NostrEvent` values cannot accidentally reach repositories, caches, or UI.
7. Verify signer-returned events and require equality with all security-relevant draft fields, including kind, pubkey, tags, content, and a defensible timestamp window.

Add malicious-relay tests for invalid signatures, mismatched hashes, spoofed pubkeys, wrong subscription IDs, wrong requested event IDs, far-future timestamps, and a forged newer event competing with a valid older one.

### SEC-02 — Remote input and cache resource use are insufficiently bounded

**Severity:** Medium  
**Category:** CWE-400 / CWE-770, Uncontrolled Resource Consumption

#### Evidence

Mercury response bodies are materialized with `response.body.string()` and then parsed as complete JSON documents without a byte limit ([`MercuryApiClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryApiClient.kt#L100), [`MercuryApiClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryApiClient.kt#L221)). WebSocket messages are also decoded as complete arrays with no application-level frame or event-size policy ([`PersistentNostrChapterSource.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/PersistentNostrChapterSource.kt#L98), [`NostrRelayClient.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/NostrRelayClient.kt#L106)).

Publication mapping walks all remote tags and creates all chapter references before `getBook` later truncates the list to 500. Individual tag strings, identifiers, metadata values, event content, and search input have no meaningful byte/character ceiling ([`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L114), [`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L265)).

Opening a book renders every available chapter before exposing the book to the UI ([`BookshelfViewModel.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfViewModel.kt#L169), [`ChapterHtmlCache.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterHtmlCache.kt#L31)). The AsciiDoc parser is given the complete attacker-controlled source ([`ChapterRenderer.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterRenderer.kt#L28)). Every rendered fragment is written to `cacheDir/chapter-html`; the cache has statistics and manual clearing, but no size quota, entry cap, age policy, or LRU eviction ([`ChapterHtmlCache.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterHtmlCache.kt#L36), [`ChapterHtmlCache.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterHtmlCache.kt#L58)).

The editable relay list is deduplicated but has no count or total-length limit, and a fetch creates/retains one connection per configured URL ([`ChapterSourceSettingsStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/ChapterSourceSettingsStore.kt#L41), [`PersistentNostrChapterSource.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/PersistentNostrChapterSource.kt#L73)). This portion is user-triggered rather than remotely triggered, but it increases self-denial-of-service risk.

#### Impact

A book author, malicious relay, or compromised Mercury service can make book opening consume excessive heap, parser CPU, or cache storage. The likely outcomes are UI stalls, process termination, repeated failure on reopening content, or storage pressure affecting the app/device. Exploitation requires the content to be returned and, for chapter rendering, normally requires the user to open the book.

#### Recommendation

- Reject HTTP bodies and WebSocket event messages over explicit byte limits before deserialization; stream where practical.
- Define one event policy with maximum content bytes, tag count, tag arity, per-tag element length, identifier length, metadata length/list counts, and acceptable timestamp range.
- Stop collecting chapter references once the supported limit is reached instead of allocating the complete remote list first.
- Bound search text and configured relay count/URL length.
- Render incrementally or on demand, and isolate parsing with cancellation and a defensible time/work budget.
- Give the chapter cache a total-byte quota and entry/age eviction policy. Use atomic temporary-file replacement and remove partial files on failure.
- Add boundary tests at the limit and just above it, plus a stress test for pathological AsciiDoc input.

### SEC-03 — Backup rules expose reading and identity metadata and omit legacy rules

**Severity:** Medium  
**Category:** CWE-200, Exposure of Sensitive Information

#### Evidence

The application enables backup and specifies only Android 12+ `dataExtractionRules` ([`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml#L14)). The rules include every database and every shared-preference file for both cloud backup and device transfer ([`data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml#L2)).

Shared preferences contain the active Nostr pubkey and signer package ([`NostrSignerSessionStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/NostrSignerSessionStore.kt#L5)), reading progress keyed by book coordinate ([`ReaderSettingsStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/reader/ReaderSettingsStore.kt#L25)), and configured chapter relays ([`ChapterSourceSettingsStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/ChapterSourceSettingsStore.kt#L10)). These are intentionally included on Android 12+.

The app supports API 26, but the manifest does not declare `android:fullBackupContent` for Android 11 and lower ([`app/build.gradle.kts`](../app/build.gradle.kts#L42)). Android's guidance states that apps using `dataExtractionRules` should still supply a separate `fullBackupContent` rules file for Android 11 and lower. Without it, the platform's default backup set can also include `filesDir/bookshelf/local-v1.json`, which stores the saved-book collection and metadata ([`LocalBookshelfStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/bookshelf/LocalBookshelfStore.kt#L24)). See [Android backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices#exclude-data).

#### Impact

Reading progress and the book collection can reveal sensitive interests. The signer pubkey connects that activity to a public identity. Data may enter the user's backup provider or be restored/transferred to another device. On Android 11 and lower, the absence of legacy rules broadens the backed-up set beyond the explicit Android 12+ selection.

No Nostr private key is stored by this app, so this finding does not expose signing keys.

#### Recommendation

Make a documented product/privacy decision for each persisted data class, then encode the same decision for both platform generations:

- Add a legacy `full-backup-content` XML file and reference it with `android:fullBackupContent`.
- Consider excluding signer session metadata so a restored install must re-establish signer authorization.
- Consider excluding reading progress and local bookshelf data from cloud backup, or require client-side/end-to-end encryption where platform rules support it.
- Distinguish cloud backup from device-to-device transfer if the desired privacy tradeoff differs.
- Add manifest/rules tests or release checks that assert the intended files and preferences are included/excluded on API 30 and API 31+.

### SEC-04 — Release supply-chain integrity controls are incomplete

**Severity:** Medium  
**Category:** CWE-494, Download of Code Without Integrity Check

#### Evidence

The Gradle wrapper uses HTTPS and a fixed version, but `gradle-wrapper.properties` does not set `distributionSha256Sum` ([`gradle-wrapper.properties`](../gradle/wrapper/gradle-wrapper.properties#L1)). Gradle recommends always pinning the distribution checksum and validating the wrapper JAR on upgrade: [Gradle security best practices](https://docs.gradle.org/current/userguide/best_practices_security.html#validate_the_gradle_distribution_sha_256_checksum).

There is no checked-in Gradle dependency-verification metadata or dependency lock state. Direct versions are pinned in the version catalog, which prevents accidental dynamic-version drift, but downloaded plugin and library artifacts are not pinned to reviewed checksums. Gradle describes dependency, repository, build-tool, poisoned-cache, and CI compromise risks and supports checksum/signature verification: [Securing Gradle builds](https://docs.gradle.org/current/userguide/security.html).

The release workflow references `actions/checkout@v4` and `actions/setup-java@v4` by mutable major tags rather than immutable commit SHAs ([`release.yml`](../.github/workflows/release.yml#L18)). Its job-wide token has `contents: write` ([`release.yml`](../.github/workflows/release.yml#L8)). The signing keystore and passwords are restored before Gradle executes all tests and build logic ([`release.yml`](../.github/workflows/release.yml#L56), [`release.yml`](../.github/workflows/release.yml#L74)). A compromised action, build plugin, dependency/plugin-resolution path, wrapper, or build script running in that job can therefore target both signing material and the release token.

#### Impact

This is not a direct runtime vulnerability. It increases the blast radius of a CI/build supply-chain compromise: theft of signing credentials or the GitHub token, or publication of a tampered APK/AAB under a trusted release identity.

#### Recommendation

- Add the official `distributionSha256Sum` and validate `gradle-wrapper.jar` in CI and on every wrapper upgrade.
- Generate and review Gradle dependency-verification metadata, preferring checksum/signature verification with a controlled update process; consider dependency locking for transitive resolution stability.
- Pin third-party GitHub Actions to immutable commit SHAs and use an update bot/process to keep them current.
- Reduce default job permissions and grant `contents: write` only to the release-publishing step/job.
- Separate build/test from signing/publishing. Pass only reviewed artifacts and checksums into a minimal signing job, and expose signing secrets only there.
- Add automated dependency/advisory scanning and a documented triage policy. The direct version catalog alone is not an SCA control.

### SEC-05 — Untrusted chapter links are opened without a URI policy

**Severity:** Low  
**Category:** Untrusted external navigation / phishing hardening

#### Evidence

Chapter content is remote AsciiDoc. The renderer emits HTML fragments ([`ChapterRenderer.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterRenderer.kt#L28)), and the reader passes the fragment to `AnnotatedString.fromHtml` with link styling but without a `LinkInteractionListener` or URI validation ([`BookshelfApp.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfApp.kt#L1121)). Compose documents that HTML links are clickable and that a `LinkAnnotation.Url` without a custom listener is opened through the platform `UriHandler`: [Compose HTML links](https://developer.android.com/develop/ui/compose/text/style-text#display-html) and [`LinkAnnotation.Url`](https://developer.android.com/reference/kotlin/androidx/compose/ui/text/LinkAnnotation.Url).

Because SEC-01 allows forged chapter content, a malicious relay does not even need the referenced author's signing key to place a link in a chapter.

#### Impact

The user must click the link, and content is not executed in an in-app WebView, which substantially limits impact. However, attacker-controlled schemes/hosts can trigger external apps or convincing phishing pages without an app-provided destination preview or trust decision.

#### Recommendation

Provide a `LinkInteractionListener` that parses the destination before opening it. Allow only `https` by default (optionally a consciously supported subset such as `mailto`), reject malformed and dangerous/custom schemes, and show the full host in a confirmation sheet before leaving the reader. Add tests for `javascript:`, `content:`, `file:`, `intent:`, `nostrsigner:`, cleartext `http:`, deceptive hostnames, and valid HTTPS links.

### SEC-06 — Author-controlled cover URLs are fetched automatically

**Severity:** Low  
**Category:** CWE-359, Exposure of Private Personal Information

#### Evidence

Publication metadata accepts any syntactically valid HTTP or HTTPS cover host ([`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L236), [`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L406)). Visible book cards pass that URL directly to Coil `AsyncImage`, which performs a network request automatically ([`BookshelfApp.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfApp.kt#L1155)). The app includes Coil's OkHttp network loader specifically to support these requests ([`app/build.gradle.kts`](../app/build.gradle.kts#L108)).

#### Impact

An author can use a unique cover URL to learn that an IP/device displayed a particular search result, curated shelf entry, or saved book, along with request time and ordinary HTTP metadata. This can reveal reading interests even when the user never opens the book. SEC-01 also lets a compromised content source inject such tracking URLs without the author's signature.

The current manifest does not opt into cleartext traffic, so modern Android's default network-security policy provides some protection against `http://` loads. The application should not rely on that implicit behavior as its URL policy.

#### Recommendation

Prefer a privacy-preserving image proxy or a trusted-host policy. If arbitrary decentralized cover hosts are a product requirement, consider explicit remote-image consent, a setting to disable remote covers, stripping request-identifying headers where feasible, strict download/dimension limits, and clear privacy disclosure. Enforce HTTPS in application code and add tests for loopback, link-local, private-network, redirect, and oversized-image destinations.

## Positive controls observed

- Only the `INTERNET` permission is requested, and package visibility is narrowly scoped to the signer scheme ([`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml#L4)).
- The only exported component is the launcher activity; it declares no external data/deep-link filter ([`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml#L22)).
- No WebView, JavaScript interface, custom trust manager, permissive hostname verifier, dynamic code loading, external-storage persistence, content provider, broadcast receiver, or service was found.
- Default Mercury and relay endpoints use HTTPS/WSS and the shared OkHttp client uses normal platform certificate/hostname validation with finite connect/read/subscription timeouts ([`AppGraph.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/AppGraph.kt#L22)).
- Nostr private-key operations remain in the external signer; the app stores only a validated public key and signer package name ([`AndroidExternalSigner.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/AndroidExternalSigner.kt#L15), [`NostrSignerSessionStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/NostrSignerSessionStore.kt#L9)).
- Signer-returned directory events are checked for kind, active account, empty content, and unchanged normalized collection tags before publication ([`BookshelfRelaySync.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/BookshelfRelaySync.kt#L136), [`BookshelfViewModel.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/ui/BookshelfViewModel.kt#L395)).
- Directory and book-loading flows cap user-visible items/chapters at 500, and local saves occur before signer/network operations ([`BookshelfDirectoryRules.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/bookshelf/BookshelfDirectoryRules.kt#L8), [`MercuryBookRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/mercury/MercuryBookRepository.kt#L544)).
- Local bookshelf and metadata/profile cache replacement is atomic where supported, with a replace fallback ([`LocalBookshelfStore.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/bookshelf/LocalBookshelfStore.kt#L111), [`NostrProfileRepository.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/nostr/NostrProfileRepository.kt#L80)).
- Chapter HTML and profile/shelf metadata are stored under `cacheDir`; chapter-cache clearing is scoped to its fixed cache subdirectory and remains explicit/user-visible ([`ChapterHtmlCache.kt`](../app/src/main/java/eu/decentnewsroom/bookshelf/data/rendering/ChapterHtmlCache.kt#L16)).
- Release builds are explicitly non-debuggable and require a configured release keystore ([`app/build.gradle.kts`](../app/build.gradle.kts#L63), [`app/build.gradle.kts`](../app/build.gradle.kts#L84)).
- Keystores and local signing properties are ignored by Git, and no committed production secret or private Nostr key was found ([`.gitignore`](../.gitignore#L9)).

## Remediation order

1. **Before relying on Nostr authenticity:** implement the shared verified-event boundary in SEC-01 and route every relay, Mercury, cache, and signer event through it.
2. **Next release hardening:** add the input/cache limits in SEC-02 and align API 26–30/API 31+ backup policy in SEC-03.
3. **Release pipeline:** pin wrapper/dependencies/actions and isolate signing as described in SEC-04.
4. **Content privacy and navigation:** add link policy/confirmation and a documented remote-cover privacy model for SEC-05 and SEC-06.
5. Add regression tests that exercise these controls with forged, malformed, oversized, future-dated, cross-subscription, redirecting, and custom-scheme inputs.

## Residual-risk note

Even after cryptographic event verification, signed public content remains untrusted for availability and user-safety purposes. A valid author can publish oversized content, tracking images, or phishing links. Signature verification proves provenance and integrity; it does not replace size limits, safe rendering/navigation, privacy controls, or moderation policy.
