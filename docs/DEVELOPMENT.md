# Development Notes

See the [documentation index](README.md) and [architecture](ARCHITECTURE.md) for component ownership, rendering, cache safety, and network/lifecycle invariants. This native Android project is independent of the Symfony bundle, which remains a reference for Mercury/directory rules.

## Local Development

1. Open this repository in Android Studio.
2. Sync Gradle with JDK 17 or newer.
3. Run the app and verify Mercury search, book opening, reader rendering, and `My Books`.
4. When a Nostr signer is available, verify sign-in and kind `30045` directory sync.

Keep dependency versions pinned in `gradle/libs.versions.toml`. Rendering uses Android-compatible `asciidoc-kmp`; do not substitute desktop/JRuby-backed AsciidoctorJ, whose JVM tests can pass while Android initialization fails.

## Verification

### Build and test ownership

The project owner runs builds/tests and reports results. Agents must not execute Gradle verification commands on this machine, per `AGENTS.md`. Documentation/static checks do not establish a passing Android build or device acceptance.

The generated daemon JVM points at a broken cached JetBrains JDK 25, and test workers can fail with the default non-ASCII Gradle home. Use Android Studio's JBR and an ASCII Gradle home outside the repo, then stop the daemon:

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

Move Unreleased changelog entries into the release section before tagging. Create a release by pushing a tag, for example:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Release tags must use `vMAJOR.MINOR.PATCH`. The workflow removes the leading
`v` for Android's `versionName` and derives the monotonically increasing
`versionCode` as `MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`. Minor and patch
components are therefore limited to `999`; for example, `v0.1.7` produces
`versionName` `0.1.7` and `versionCode` `1007`. Local builds retain the default
development metadata (`0.1.0`, code `1`) unless `appVersionName` and
`appVersionCode` Gradle properties are supplied.

Before publishing, the release job reads the final APK manifest and fails if
its version does not match the tag-derived values.

The [release workflow](../.github/workflows/release.yml) pins actions to immutable SHAs. The [Gradle wrapper configuration](../gradle/wrapper/gradle-wrapper.properties) pins the distribution checksum; update it from the official Gradle checksum reference when upgrading. The [security verifier](../.github/scripts/verify-security-config.sh) also checks the wrapper JAR checksum and CI/backup policy. Keep these files aligned when changing release tooling.
