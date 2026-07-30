#!/usr/bin/env bash
# Conformance-coverage guard.
#
# Fails the build when the canonical corpus in ../lazily-spec/conformance/ holds a
# fixture this binding does not actually replay. That is the drift this guard
# exists for: a fixture lands upstream, every binding stays green, and nobody
# learns that one of them is not replaying it.
#
# This binding uses the RUNTIME manifest, not a static grep: every fixture read
# through ConformanceFixtures.read() is recorded and flushed to
# build/conformance-fixtures-loaded.txt on JVM shutdown. A fixture named in a
# comment but hand-transcribed is therefore caught here — a source grep cannot see
# that case at all.
#
# This guard used to be a MIN_FIXTURES floor (117) plus REQUIRED_AREAS. That is
# strictly weaker than per-fixture accounting and it had rotted: 131 fixtures
# replayed against a floor of 117, so roughly 14 replays could have stopped
# running with CI still green, and REQUIRED_AREAS only noticed a whole area going
# dark. The floor is gone. Every canonical fixture must now be OPENED or
# explicitly excused in KNOWN_UNCOVERED, exactly as the other eight bindings
# require.
#
# A missing manifest is missing EVIDENCE and fails. It does not mean "no fixtures
# were read"; it means the suite ran without the recorder attached, and passing in
# that state is the vacuous green this guard exists to prevent.
#
# Usage: scripts/check-conformance-coverage.sh [manifest-path]
set -euo pipefail

SPEC_DIR="${LAZILY_SPEC_CONFORMANCE_DIR:-${LAZILY_SPEC_DIR:-../lazily-spec}/conformance}"
if [ ! -d "$SPEC_DIR" ]; then
  echo "SKIP: canonical corpus not found at $SPEC_DIR (clone the lazily-spec sibling)" >&2
  exit 0
fi

# Fixtures deliberately not replayed by this binding yet. Each entry is a claim
# that someone looked; shrinking this list is the work. Adding to it silently is
# how the guard rots, so keep a reason with any new entry.
#
# NOTE: this ledger is NOT the same thing as EXPECTED_SKIPS in
# ReactiveGraphConformanceTest. That map records fixtures this script counts as
# OPENED — their bytes are read and their ops parsed — whose *replay* then stops
# at a named unsupported op (merge_cell, drain_exhausted). Different stages,
# different failure modes: KNOWN_UNCOVERED answers "did we read it at all", and
# EXPECTED_SKIPS answers "having read it, did we assert on it". EXPECTED_SKIPS is
# already rot-proof via exact set equality in the test; do not merge the two.
KNOWN_UNCOVERED=(
  # No lazily-kt runner drives the reliable-sync outbox-coalescing or
  # lease-eviction scenarios yet. Excused in every other binding for the same
  # reason.
  "reliable-sync/coalesce_bounds_outbox.json"
  "reliable-sync/liveness_lease_eviction.json"
)

MANIFEST="${1:-${LAZILY_CONFORMANCE_MANIFEST:-build/conformance-fixtures-loaded.txt}}"

# Corpus areas lazily-kt expects the canonical checkout to contain. This is a
# CORPUS-shape tripwire, not a coverage check — the per-fixture accounting below
# is what proves replays ran. It is kept because per-fixture enumeration is blind
# in exactly one direction: if the lazily-spec sibling is a stale, partial, or
# half-cloned checkout, whole areas simply vanish from the enumeration and the
# guard passes over a corpus a fraction of its real size. That silent-shrink case
# was the one legitimate job the old MIN_FIXTURES floor did, and asserting area
# names against the corpus does it without a number that rots.
REQUIRED_AREAS=(
  agent-doc
  collections
  coordination
  crdt-tree
  distributed
  familysync
  ingress
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
  stdlib
  temporal
  windowing
)

if [ ! -s "$MANIFEST" ]; then
  echo "FAIL: no conformance manifest at $MANIFEST." >&2
  echo "      Run the suite with LAZILY_CONFORMANCE_MANIFEST set so the recorder" >&2
  echo "      attaches. An absent manifest is missing evidence, not evidence of" >&2
  echo "      absence." >&2
  exit 1
fi
OPENED="$(sort -u "$MANIFEST")"

missing=0

for area in "${REQUIRED_AREAS[@]}"; do
  if [ -z "$(find "$SPEC_DIR/$area" -name '*.json' -print -quit 2>/dev/null)" ]; then
    echo "ERROR: canonical corpus has no fixtures under area '$area'." >&2
    echo "       The lazily-spec checkout at $SPEC_DIR is stale or partial; coverage" >&2
    echo "       computed against it would silently understate the real corpus." >&2
    missing=$((missing + 1))
  fi
done
if [ -z "$(find "$SPEC_DIR" -maxdepth 1 -name '*.json' -print -quit 2>/dev/null)" ]; then
  echo "ERROR: canonical corpus has no root-level IPC fixtures (snapshot_*/delta_*/arena_blob)." >&2
  missing=$((missing + 1))
fi

total=0
covered=0
excused_count=0
while IFS= read -r fixture; do
  total=$((total + 1))
  # Here-string, NOT a pipe. With `set -o pipefail`, `printf ... | grep -q` reports
  # FAILURE when grep matches: grep -q exits immediately on the first hit, printf
  # takes SIGPIPE writing the rest, and pipefail surfaces printf's death as the
  # pipeline's status. The check then inverts — every covered fixture is reported
  # missing.
  if grep -qxF "$fixture" <<< "$OPENED"; then
    covered=$((covered + 1))
    continue
  fi
  excused=0
  for known in "${KNOWN_UNCOVERED[@]:-}"; do
    if [ "$known" = "$fixture" ]; then excused=1; break; fi
  done
  if [ "$excused" -eq 1 ]; then
    excused_count=$((excused_count + 1))
  else
    echo "ERROR: canonical fixture '$fixture' was NOT opened by the suite." >&2
    echo "       A runner may still name it in source while no longer reading it —" >&2
    echo "       that is the drift this manifest exists to catch. Replay it, or add" >&2
    echo "       it to KNOWN_UNCOVERED with a reason." >&2
    missing=$((missing + 1))
  fi
done < <(cd "$SPEC_DIR" && find . -name '*.json' | sed 's|^\./||' | sort)

# The evidence channel guards itself. Every recorded id must resolve against the
# corpus root; otherwise the manifest was truncated or interleaved in transit,
# and coverage computed from it cannot be trusted.
while IFS= read -r id; do
  [ -n "$id" ] || continue
  if [ ! -f "$SPEC_DIR/$id" ]; then
    echo "ERROR: manifest records '$id', which names no file in $SPEC_DIR." >&2
    echo "       The recorder is dropping or interleaving writes; coverage computed" >&2
    echo "       from this manifest cannot be trusted." >&2
    missing=$((missing + 1))
  fi
done <<< "$OPENED"

# A stale allowlist is its own drift, in two directions.
#
# 1. An entry naming a fixture that no longer exists means the corpus moved and
#    nobody updated the excuse.
# 2. An entry naming a fixture the suite DOES open is a stale excuse: the gap it
#    claims was closed, and the excuse outlived it. That rot understates coverage,
#    which is the direction nobody files a bug about — you do not report missing
#    coverage you have been told you lack — and it buries the real gaps in noise.
#    Worse, a stale excuse silently disarms the guard for that fixture: the day
#    the replay really does stop running, the excuse absorbs it.
#
# The open test below uses the SAME `grep -qxF ... <<< "$OPENED"` comparison as the
# covered-check above, deliberately: if the two ever disagreed, a fixture could be
# both counted as covered and excused as uncovered in one run.
for known in "${KNOWN_UNCOVERED[@]:-}"; do
  if [ ! -f "$SPEC_DIR/$known" ]; then
    echo "ERROR: KNOWN_UNCOVERED lists '$known', which is not in the canonical corpus." >&2
    missing=$((missing + 1))
    continue
  fi
  if grep -qxF "$known" <<< "$OPENED"; then
    echo "ERROR: KNOWN_UNCOVERED lists '$known', but the suite DID open it." >&2
    echo "       The excuse is stale — the gap it claims no longer exists. Delete" >&2
    echo "       this entry from KNOWN_UNCOVERED. Leaving it there understates this" >&2
    echo "       binding's coverage and disarms the guard for that fixture." >&2
    missing=$((missing + 1))
  fi
done

if [ "$missing" -gt 0 ]; then
  echo "conformance coverage FAILED: $missing problem(s)" >&2
  exit 1
fi

echo "conformance coverage OK: $covered/$total canonical fixtures OPENED by the suite" \
     "($excused_count of ${#KNOWN_UNCOVERED[@]} KNOWN_UNCOVERED entries applied;" \
     "runtime manifest — these bytes were really read)"
