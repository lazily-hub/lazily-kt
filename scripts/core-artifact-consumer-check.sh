#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/consumer/src/main/kotlin"
cp "$root/tests/core-artifact-consumer/settings.gradle.kts" "$tmp/consumer/"
cp "$root/tests/core-artifact-consumer/build.gradle.kts" "$tmp/consumer/"
cp "$root/tests/core-artifact-consumer/Main.kt" "$tmp/consumer/src/main/kotlin/"

if [ -n "${LAZILY_CORE_ARTIFACT_VERSION:-}" ]; then
  : "${GITHUB_ACTOR:?GITHUB_ACTOR is required to read the published GitHub Package}"
  : "${GITHUB_TOKEN:?GITHUB_TOKEN is required to read the published GitHub Package}"
  repository="https://maven.pkg.github.com/lazily-hub/lazily-kt"
  version="$LAZILY_CORE_ARTIFACT_VERSION"
else
  "$root/gradlew" -p "$root" publishGprPublicationToMavenLocal \
    -Dmaven.repo.local="$tmp/repository"
  repository="$tmp/repository"
  version="0.46.0"
fi
sed -i "s|__REPOSITORY__|$repository|" "$tmp/consumer/build.gradle.kts"
sed -i "s|__VERSION__|$version|" "$tmp/consumer/build.gradle.kts"

"$root/gradlew" -p "$tmp/consumer" run
if grep -R -F "$root" "$tmp/consumer/.gradle" "$tmp/consumer/build" >/dev/null 2>&1; then
  echo "consumer metadata references the source checkout" >&2
  exit 1
fi
