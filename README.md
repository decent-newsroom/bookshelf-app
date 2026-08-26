# Bookshelf Android

Native Android scaffold for the Mercury-backed Nostr bookshelf.

This app is intentionally separate from the Symfony bundle. The bundle remains
the reference implementation for the Mercury and directory rules while the
Android project grows into a standalone reader.

## Shape

- `app/src/main/java/.../domain` contains Nostr and bookshelf data models.
- `data/mercury` ports the Mercury REST and book-mapping boundary.
- `data/bookshelf` owns local-first `My Books` directory rules.
- `data/nostr` is the Quartz-facing sync boundary.
- `ui` contains the initial Compose shell.


## Reader Comfort

The reader now keeps local reading preferences and per-book chapter progress in
`SharedPreferences`. The first reader pass includes font size, line height,
paper/sepia/night themes, and a progress indicator based on the first visible
chapter.

## First Milestone

1. Open `android/` in Android Studio.
2. Sync Gradle with JDK 17+.
3. Run the app and verify Mercury search works.
4. Replace `QuartzBookshelfRelaySync` with real Quartz calls:
   - login with `NostrSignerExternal` for NIP-55;
   - fetch the latest kind `30045` event for the active pubkey;
   - sign and publish replacement kind `30045` events.

## Build Notes

This scaffold does not include a Gradle wrapper yet. Generate one from inside
`android/` when Gradle is available:

```bash
gradle wrapper --gradle-version 9.4.1
```

The dependency versions are pinned on purpose. Avoid dynamic versions for the
Android Gradle Plugin, Kotlin, Compose, Quartz, or OkHttp.

## Tag Releases

GitHub Actions builds signed APK and AAB release artifacts when a tag is
pushed, then attaches them to a GitHub release for that tag.

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
