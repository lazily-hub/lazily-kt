#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

"$root/gradlew" -p "$root" publishGprPublicationToMavenLocal \
  -Dmaven.repo.local="$tmp/repository"
mkdir -p "$tmp/consumer/src/main/kotlin"
cp "$root/tests/core-artifact-consumer/settings.gradle.kts" "$tmp/consumer/"
cp "$root/tests/core-artifact-consumer/build.gradle.kts" "$tmp/consumer/"
cp "$root/tests/core-artifact-consumer/Main.kt" "$tmp/consumer/src/main/kotlin/"
sed -i "s|__REPOSITORY__|$tmp/repository|" "$tmp/consumer/build.gradle.kts"

"$root/gradlew" -p "$tmp/consumer" run
if grep -R -F "$root" "$tmp/consumer/.gradle" "$tmp/consumer/build" >/dev/null 2>&1; then
  echo "consumer metadata references the source checkout" >&2
  exit 1
fi
