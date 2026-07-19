#!/usr/bin/env bash
# Fail when a bundled conformance fixture has drifted from canonical lazily-spec.
#
# lazily-kt bundles its own copy of the lazily-spec conformance fixtures under
# src/test/resources/conformance/ because CI has no lazily-spec sibling checkout
# (see docs in lazily-spec/docs/conformance.md). That bundling means a fixture
# the spec has since changed keeps replaying its stale local copy and stays
# green — drift is invisible. This check closes that gap: point it at a
# lazily-spec checkout and it byte-compares every bundled fixture.
#
# Usage: scripts/check-fixture-sync.sh [path-to-lazily-spec]
#        (defaults to ../lazily-spec, the local sibling layout)

set -euo pipefail

spec_root="${1:-$(dirname "$0")/../../lazily-spec}"
bundled_root="$(dirname "$0")/../src/test/resources/conformance"

if [[ ! -d "$spec_root/conformance" ]]; then
  echo "error: no lazily-spec conformance dir at '$spec_root/conformance'" >&2
  exit 2
fi

drift=0
count=0
while IFS= read -r rel; do
  count=$((count + 1))
  canonical="$spec_root/conformance/$rel"
  if [[ ! -f "$canonical" ]]; then
    echo "DRIFT (not in spec): $rel" >&2
    drift=1
    continue
  fi
  if ! diff -q "$bundled_root/$rel" "$canonical" >/dev/null; then
    echo "DRIFT (differs from spec): $rel" >&2
    diff -u "$bundled_root/$rel" "$canonical" | head -40 >&2 || true
    drift=1
  fi
done < <(cd "$bundled_root" && find . -name '*.json' -printf '%P\n' | sort)

if [[ $drift -ne 0 ]]; then
  cat >&2 <<'EOF'

Bundled conformance fixtures are out of sync with canonical lazily-spec.
Refresh them with:

  cp -r ../lazily-spec/conformance/. src/test/resources/conformance/

then re-run the test suite: a refreshed fixture may add frames or cases the
binding does not yet handle.
EOF
  exit 1
fi

echo "conformance fixtures in sync with lazily-spec ($count files)"
