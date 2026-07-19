#!/usr/bin/env bash
# Positive assertion that canonical conformance fixtures ACTUALLY RAN (#lzspecconf).
#
# An absence guard ("is ../lazily-spec/conformance present?") cannot catch
# shadowing: a replay that is refactored away, renamed, filtered out by a test
# selector, or short-circuited still leaves the fixture directory present and CI
# green. lazily-go closes this by grepping `go test` output to assert the replay
# emitted RUN lines and no SKIPs; the Gradle equivalent is a manifest — every
# fixture read through ConformanceFixtures.read() is recorded and flushed to
# build/conformance-fixtures-loaded.txt on JVM shutdown.
#
# This script asserts that manifest is non-trivial and covers every area the
# binding is expected to replay. Deleting a replay now fails CI loudly.
#
# Usage: scripts/check-conformance-coverage.sh [manifest-path]

set -euo pipefail

manifest="${1:-build/conformance-fixtures-loaded.txt}"

# Minimum total fixtures replayed. Raise this when areas are added; never lower
# it to make a red build green — a drop means a replay stopped running.
# 96 before reactive-graph, +1 for the one reactive-graph fixture lazily-kt can
# replay today (transitive_invalidation_reaches_depth). The other 8 are skipped
# for named unsupported ops and are deliberately NOT counted — the manifest
# means "actually replayed", so raise this as those ops land.
MIN_FIXTURES="${MIN_FIXTURES:-97}"

# Areas lazily-kt replays. message-passing and receipts are listed explicitly
# because they were the areas that silently skipped for the entire life of the
# bundled-fixture fallback. reactive-graph is listed because lazily-kt replayed
# NONE of it until ReactiveGraphConformanceTest existed — lazily-rs was the only
# binding executing that corpus, which is how an invalidation-cascade defect
# shipped undetected in lazily-dart and lazily-go against a fixture that was
# already on disk.
REQUIRED_AREAS=(
  agent-doc
  collections
  coordination
  crdt-tree
  distributed
  familysync
  lossless-tree
  materialization
  membership
  message-passing
  presence
  rateshape
  reactive-graph
  receipts
  reliable-sync
  resilience
  service
  signaling
  statechart
  temporal
  windowing
)

if [[ ! -f "$manifest" ]]; then
  echo "::error::no conformance manifest at '$manifest' — the fixture replays did not run at all" >&2
  exit 1
fi

count="$(grep -c . "$manifest" || true)"
if (( count < MIN_FIXTURES )); then
  echo "::error::only $count conformance fixtures replayed, expected >= $MIN_FIXTURES." >&2
  echo "A replay was removed, renamed, or short-circuited. Do not lower MIN_FIXTURES to fix this." >&2
  exit 1
fi

missing=0
for area in "${REQUIRED_AREAS[@]}"; do
  if ! grep -q "^${area}/" "$manifest"; then
    echo "::error::no fixtures replayed from conformance area '$area' — that suite is silently not running" >&2
    missing=1
  fi
done

# Root-level IPC fixtures (snapshot_*.json / delta_*.json / arena_blob.json).
if ! grep -qE '^(snapshot_|delta_|arena_blob)' "$manifest"; then
  echo "::error::no root-level IPC fixtures replayed" >&2
  missing=1
fi

if (( missing != 0 )); then
  exit 1
fi

echo "conformance coverage OK: $count canonical fixtures replayed across ${#REQUIRED_AREAS[@]} areas"
