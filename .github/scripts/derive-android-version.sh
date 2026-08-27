#!/usr/bin/env bash
set -euo pipefail

tag=${1:-}

if [[ ! $tag =~ ^v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "Release tag must use vMAJOR.MINOR.PATCH (for example, v0.1.7)." >&2
  exit 1
fi

major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}

if (( minor > 999 || patch > 999 )); then
  echo "Release tag minor and patch components must not exceed 999." >&2
  exit 1
fi

version_code=$((major * 1000000 + minor * 1000 + patch))
if (( version_code < 1 || version_code > 2100000000 )); then
  echo "Derived Android versionCode must be between 1 and 2100000000." >&2
  exit 1
fi

printf 'version_name=%s.%s.%s\n' "$major" "$minor" "$patch"
printf 'version_code=%s\n' "$version_code"
