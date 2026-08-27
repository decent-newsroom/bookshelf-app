# Development Notes

This file keeps the developer-facing notes for Bookshelf. The README is reserved for the project introduction.

## Project Shape

Bookshelf is a native Android app for the Mercury-backed Nostr bookshelf. It is intentionally separate from the Symfony bundle: the bundle remains the reference implementation for Mercury and directory rules while this Android project grows into a standalone reader.

- `app/src/main/java/.../domain` contains Nostr and bookshelf data models.
- `app/src/main/java/.../data/mercury` owns the Mercury REST and book-mapping boundary.
- `app/src/main/java/.../data/bookshelf` owns local-first `My Books` directory rules.
- `app/src/main/java/.../data/nostr` owns the Nostr relay and signer sync boundary.
- `app/src/main/java/.../ui` contains the Compose app shell and reader.

## Reader Rendering

Chapter event contents are expected to be AsciiDoc. `AsciidoctorChapterRenderer` renders chapter HTML, and `ChapterHtmlCache` stores rendered output under `context.cacheDir/chapter-html`.

The renderer uses the Android-compatible Kotlin Multiplatform `asciidoc-kmp` parser and produces body-only, CSS-free HTML fragments. The reader converts those fragments to Compose `AnnotatedString` values so rich text and links stay native to Compose and do not intercept reader gestures through an embedded view. Do not replace it with the JRuby-backed AsciidoctorJ artifact: AsciidoctorJ's desktop JVM tests can pass while its runtime initialization fails on Android, causing the reader to fall back to raw chapter text.

`BookshelfViewModel.openBook` renders and caches the loaded `BookDetail` before exposing it to the reader. `BookChapter.renderedHtml` is the preferred UI content, with raw `content` as a fallback.

Settings exposes chapter cache stats and `clearChapterHtmlCache()`. Keep cache clearing user-visible and safe.

## Local Development

1. Open this repository in Android Studio.
2. Sync Gradle with JDK 17 or newer.
3. Run the app and verify Mercury search, book opening, reader rendering, and `My Books`.
4. When a Nostr signer is available, verify sign-in and kind `30045` directory sync.

The dependency versions are pinned on purpose. Avoid dynamic versions for the Android Gradle Plugin, Kotlin, Compose, Quartz, AsciidoctorJ, OkHttp, or related runtime libraries.

## Verification

On this Windows machine, use Android Studio's JBR and an ASCII Gradle user home outside the repo:

```powershell
cmd /c .\gradlew.bat --gradle-user-home C:\Users\Public\Android\gradle-user-home-bookshelf --no-configuration-cache "-Dorg.gradle.java.home=C:\Program Files\Android\Android Studio\jbr" :app:testDebugUnitTest :app:assembleDebug
cmd /c .\gradlew.bat --gradle-user-home C:\Users\Public\Android\gradle-user-home-bookshelf --stop
```

Do not commit temporary Gradle user homes or generated build/cache output.

## Release Tags

GitHub Actions runs unit tests in a read-only `build-test` job, then builds signed APK and AAB release artifacts in the dependent signing/release job when a tag is pushed. Only that release job receives signing secrets and `contents: write`; the build/test job has `contents: read` and cannot publish. The signing job publishes checksums alongside the artifacts in the GitHub release.

Configure these repository secrets before pushing a release tag:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: base64-encoded release keystore.
- `ANDROID_RELEASE_STORE_PASSWORD`: keystore password.
- `ANDROID_RELEASE_KEY_ALIAS`: signing key alias.
- `ANDROID_RELEASE_KEY_PASSWORD`: signing key password.

On PowerShell, encode the keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystores\bookshelf-release.jks"))
```

Create a release by pushing a tag, for example:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release workflow pins its actions to immutable commit SHAs. The Gradle
wrapper also pins the official Gradle 9.7.1 binary distribution checksum; when
upgrading Gradle, obtain the new value from [Gradle's official checksum
reference](https://gradle.org/release-checksums/) and update
`gradle-wrapper.properties` in the same change. The pinned action revisions are
documented by the upstream [checkout v4.4.0 commit](https://github.com/actions/checkout/commit/11d5960a326750d5838078e36cf38b85af677262)
and [setup-java v4 commit](https://github.com/actions/setup-java/commit/cf277c60eb25467037889841efdb72551f06f6c3).
The same verifier checks the checked-in wrapper JAR checksum before CI builds.
