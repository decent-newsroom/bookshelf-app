# ADR 0007: Private Cloud Backup with Explicit Device Transfer

## Status

Accepted

## Context

Bookshelf persists a Nostr signer session identifier, reader preferences and
reading progress, chapter relay settings, and the local saved-book collection.
These values can connect a device identity to reading interests. The previous
Android 12+ rules included every database and shared-preference file, while
Android 11 and lower had no explicit `fullBackupContent` rules at all.

Android 12 introduced separate cloud-backup and device-transfer rule sets, but
older platform versions have one legacy ruleset for both operations. The app
must keep cloud backup from exporting identity or reading metadata while still
making a deliberate, understandable device-migration choice.

## Decision

For Android 11 and lower, exclude all databases, shared preferences, and the
durable `filesDir/bookshelf` directory in `full_backup_content.xml`. This is a
privacy-first compromise: legacy Android cannot distinguish cloud backup from
device-to-device transfer, so these versions do not transfer the local shelf
automatically.

For Android 12 and newer, exclude the same data classes from cloud backup. The
device-transfer rules explicitly include `bookshelf/local-v1.json`,
`bookshelf_reader.xml`, and `bookshelf_chapter_sources.xml`. Device-to-device
transfer is an OS-mediated, user-controlled migration, so retaining the shelf,
reading position, display preferences, and custom relay settings preserves the
reader experience. The `nostr_signer_session.xml` file is intentionally absent;
signer authorization must be re-established on the destination device.

The manifest references both rule files. Future durable stores must be added to
the cloud exclusions and considered separately for device transfer before they
are shipped.

## Consequences

- Cloud backup providers receive no signer session, reading history, relay
  settings, bookshelf, or database data.
- Android 12+ users retain a useful, user-initiated device migration while
  reauthorizing the external signer.
- Android 11 and lower users may need to rediscover or resave books after a
  migration; this is the deliberate privacy tradeoff required by the legacy
  backup API.
- The app stores no Nostr private key, and no backup rule can export one.

## References

- [Android backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices#exclude-data)
- [Android data extraction rules](https://developer.android.com/about/versions/12/backup-restore)
