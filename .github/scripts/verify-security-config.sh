#!/usr/bin/env bash
set -euo pipefail

manifest=app/src/main/AndroidManifest.xml
legacy=app/src/main/res/xml/full_backup_content.xml
modern=app/src/main/res/xml/data_extraction_rules.xml
workflow=.github/workflows/release.yml
wrapper=gradle/wrapper/gradle-wrapper.properties
wrapper_jar=gradle/wrapper/gradle-wrapper.jar

grep -Fq 'android:fullBackupContent="@xml/full_backup_content"' "$manifest"
grep -Fq 'android:dataExtractionRules="@xml/data_extraction_rules"' "$manifest"
grep -Fq '<exclude domain="sharedpref" path="." />' "$legacy"
grep -Fq '<exclude domain="file" path="bookshelf" />' "$legacy"
grep -Fq '<exclude domain="sharedpref" path="." />' "$modern"
grep -Fq '<exclude domain="file" path="bookshelf" />' "$modern"
grep -Fq '<include domain="file" path="bookshelf/local-v1.json" />' "$modern"
grep -Fq '<include domain="sharedpref" path="bookshelf_reader.xml" />' "$modern"
grep -Fq '<include domain="sharedpref" path="bookshelf_chapter_sources.xml" />' "$modern"
grep -Fq 'distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a' "$wrapper"
test "$(sha256sum "$wrapper_jar" | cut -d' ' -f1)" = \
  7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d

# Reject mutable action refs and workflow-wide write access. Every action ref
# should be a full 40-character commit SHA, with a human-readable tag comment.
if grep -Eq '^[[:space:]]+uses: [^ ]+@(v|main|master|[0-9a-f]{7,39})([[:space:]]|$)' "$workflow"; then
  echo 'Mutable or short GitHub Actions reference found.' >&2
  exit 1
fi
grep -Fq 'contents: read' "$workflow"
grep -Fq 'contents: write' "$workflow"

echo 'Security configuration checks passed.'
