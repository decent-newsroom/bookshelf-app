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

## Windows Gradle Home

Gradle 9.x launches its daemon with an instrumentation-agent path. On Windows
profiles with non-ASCII characters, that path can be misencoded before the JVM
opens the agent jar. Use ASCII-only paths for both the project and Gradle user home during local builds and
Android Studio syncs.

On this machine, `C:\bookshelf-bundle` is a directory junction to this repo, and
`C:\gradle-home` is a directory junction to the real `%USERPROFILE%\.gradle`.
Open `C:\bookshelf-bundle\android` in Android Studio, then restart Android
Studio after changing `GRADLE_USER_HOME`. If Android Studio still uses the old
Gradle cache path, set **Gradle user home** to `C:\gradle-home` in the IDE's
Gradle settings.

If Android Studio still reports the old SDK path, set **Android SDK Location** to `C:\Android\Sdk` in **Settings > Appearance & Behavior > System Settings > Android SDK**.


