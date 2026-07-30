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
# A second runtime ledger, written the same way, carries per-SCENARIO replay
# accounting (#lzscenariocoverage). "Was the file opened" and "was every scenario
# in it replayed" are different questions, and the first cannot answer the second:
# one scenario is enough to open a file. See the rung-4 block below.
#
# Usage: scripts/check-conformance-coverage.sh [manifest-path] [scenario-ledger-path]
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

# --- rung 4: per-SCENARIO accounting (#lzscenariocoverage) -------------------
#
# KNOWN_UNCOVERED above asks only whether a fixture FILE was opened, and one
# scenario is enough to answer yes. A fixture carrying four scenarios of which a
# runner drives three is therefore green, counted as covered, and proving three
# quarters of what it claims. The assertion-key guards cannot see it either: they
# only bind blocks a runner reaches, so an unreplayed scenario contributes no
# unconsumed key and no unasserted key. Skipping a whole scenario is invisible to
# a guard that only inspects the scenarios you ran.
#
# So the runner writes a second RUNTIME ledger — build/conformance-scenarios-
# replayed.txt, one `fixture<TAB>id<TAB>id-source` line per scenario actually
# replayed (io.github.lazily.ConformanceScenarios) — and the check below compares
# it against the ids the fixtures carry on disk, in both directions.
#
# Ids resolve `id` -> `name` -> positional `#<n>`, identically in every binding.
# The positional fallback exists because collections/mergecell_algebra.json
# carries no scenario identifier at all; it is REPORTED below rather than
# silently accepted, so the corpus gap stays visible and fixable upstream. Adding
# the missing identifiers is a shared-corpus change and does not belong here.
KNOWN_UNREPLAYED_SCENARIOS=()

# excuseScenario <fixture> <scenario-id> <reason>
#
# Declares that this binding does not replay one scenario of a fixture it DOES
# open, and says why. Checked in both directions below, exactly as
# KNOWN_UNCOVERED is: excusing a scenario this same run replayed, or naming an id
# the fixture does not carry, fails as a stale excuse. Prefer implementing the
# scenario — a known-skipped scenario is the work this guard exists to force, and
# an excuse is the fallback for something the binding genuinely cannot express.
excuseScenario() {
  if [ -z "${3:-}" ]; then
    echo "ERROR: excuseScenario('${1:-}', '${2:-}') has no reason — an excuse without" >&2
    echo "       one is an allowlist entry wearing a function call." >&2
    exit 1
  fi
  KNOWN_UNREPLAYED_SCENARIOS+=("$1|$2|$3")
}

# (empty — every scenario in every fixture lazily-kt opens is replayed)

MANIFEST="${1:-${LAZILY_CONFORMANCE_MANIFEST:-build/conformance-fixtures-loaded.txt}}"
SCENARIO_LEDGER="${2:-${LAZILY_CONFORMANCE_SCENARIO_LEDGER:-build/conformance-scenarios-replayed.txt}}"

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

# ===========================================================================
# rung 4 — per-SCENARIO replay accounting (#lzscenariocoverage)
# ===========================================================================
#
# Everything above proves a fixture FILE was opened. This proves every scenario
# inside it was actually replayed. The two ledgers are deliberately separate
# evidence channels written by the same runner: the manifest records reads, the
# scenario ledger records replays, and only the second can see a fixture that was
# opened and then half-driven.

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to enumerate the corpus's scenario ids independently" >&2
  echo "      of the runner. Install jq (it ships on ubuntu-latest runners)." >&2
  exit 1
fi

# A missing ledger is missing EVIDENCE and fails, for the same reason a missing
# manifest does: it means the suite ran without the recorder attached, not that
# there was nothing to record.
if [ ! -s "$SCENARIO_LEDGER" ]; then
  echo "FAIL: no scenario ledger at $SCENARIO_LEDGER." >&2
  echo "      Run the suite with LAZILY_CONFORMANCE_SCENARIO_LEDGER set so" >&2
  echo "      io.github.lazily.ConformanceScenarios attaches its recorder. An absent" >&2
  echo "      ledger is missing evidence, not evidence of absence." >&2
  exit 1
fi
LEDGER="$(sort -u "$SCENARIO_LEDGER")"
LEDGER_KEYS="$(cut -f1,2 <<< "$LEDGER")"
TAB=$'\t'

# Resolve a fixture's scenario ids straight from the corpus on disk: `id`, else
# `name`, else the 0-based positional index spelled `#<n>`. This must stay
# independent of the runner — the whole point is that the corpus, not the
# binding, says what there was to replay.
scenario_ids() {
  jq -r '
    if (type == "object") and ((.scenarios | type) == "array")
    then (.scenarios | to_entries[] | ((.value.id // .value.name // ("#" + (.key | tostring))) | tostring))
    else empty end
  ' "$SPEC_DIR/$1"
}

sc_total=0
sc_replayed=0
sc_excused=0
sc_fixtures=0
while IFS= read -r fixture; do
  [ -n "$fixture" ] || continue
  ids="$(scenario_ids "$fixture")"
  [ -n "$ids" ] || continue
  sc_fixtures=$((sc_fixtures + 1))
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    sc_total=$((sc_total + 1))
    if grep -qxF "$fixture$TAB$id" <<< "$LEDGER_KEYS"; then
      sc_replayed=$((sc_replayed + 1))
      continue
    fi
    hit=0
    for entry in "${KNOWN_UNREPLAYED_SCENARIOS[@]:-}"; do
      [ -n "$entry" ] || continue
      if [ "${entry%%|*}" = "$fixture" ]; then
        rest="${entry#*|}"
        if [ "${rest%%|*}" = "$id" ]; then hit=1; break; fi
      fi
    done
    if [ "$hit" -eq 1 ]; then
      sc_excused=$((sc_excused + 1))
    else
      echo "ERROR: '$fixture' scenario '$id' was NOT replayed." >&2
      echo "       The fixture was opened, so every guard above it reports green while" >&2
      echo "       this scenario proves nothing: an unreplayed scenario contributes no" >&2
      echo "       unconsumed key and no unasserted key, so the assertion-key guards" >&2
      echo "       cannot see it either. Replay it, or excuseScenario it with a reason." >&2
      missing=$((missing + 1))
    fi
  done <<< "$ids"
done <<< "$OPENED"

# The scenario ledger guards itself, exactly as the manifest does above: every
# recorded entry must name a fixture in the corpus AND an id that fixture really
# carries. A ledger entry the corpus does not recognise means the id resolution
# drifted from the shared order, and coverage computed from it is fiction.
while IFS= read -r line; do
  [ -n "$line" ] || continue
  lf="${line%%$TAB*}"
  lrest="${line#*$TAB}"
  lid="${lrest%%$TAB*}"
  if [ ! -f "$SPEC_DIR/$lf" ]; then
    echo "ERROR: scenario ledger records '$lf', which names no file in $SPEC_DIR." >&2
    missing=$((missing + 1))
    continue
  fi
  if ! grep -qxF "$lid" <<< "$(scenario_ids "$lf")"; then
    echo "ERROR: scenario ledger records '$lf' scenario '$lid', which the fixture does" >&2
    echo "       not carry. The runner's id resolution has drifted from the shared" >&2
    echo "       'id -> name -> #<n>' order; the ledger cannot be compared to the corpus." >&2
    missing=$((missing + 1))
  fi
done <<< "$LEDGER"

# Both directions, same rule as KNOWN_UNCOVERED: a scenario excuse for something
# this run DID replay, or for an id the fixture does not carry, is stale. Stale
# excuses understate coverage and silently disarm the guard for that scenario.
for entry in "${KNOWN_UNREPLAYED_SCENARIOS[@]:-}"; do
  [ -n "$entry" ] || continue
  ef="${entry%%|*}"
  erest="${entry#*|}"
  eid="${erest%%|*}"
  ereason="${erest#*|}"
  if [ ! -f "$SPEC_DIR/$ef" ]; then
    echo "ERROR: excuseScenario names '$ef', which is not in the canonical corpus." >&2
    missing=$((missing + 1))
    continue
  fi
  if ! grep -qxF "$ef" <<< "$OPENED"; then
    echo "ERROR: excuseScenario names '$ef', a fixture the suite never opens. A whole" >&2
    echo "       unopened fixture belongs in KNOWN_UNCOVERED, not here — as written the" >&2
    echo "       excuse hides nothing and rots in a second place." >&2
    missing=$((missing + 1))
    continue
  fi
  if ! grep -qxF "$eid" <<< "$(scenario_ids "$ef")"; then
    echo "ERROR: excuseScenario '$ef' / '$eid' ($ereason) names a scenario the fixture" >&2
    echo "       does not carry. The excuse is stale — the corpus renamed or removed it." >&2
    missing=$((missing + 1))
    continue
  fi
  if grep -qxF "$ef$TAB$eid" <<< "$LEDGER_KEYS"; then
    echo "ERROR: excuseScenario '$ef' / '$eid' ($ereason) is stale — the suite DID replay" >&2
    echo "       that scenario. Delete the excuse. Leaving it there understates this" >&2
    echo "       binding's coverage and disarms the guard the day the replay stops." >&2
    missing=$((missing + 1))
  fi
done

# The positional fallback is REPORTED, never silently accepted: it marks a
# fixture whose scenarios carry no identifier, which makes every id in it
# order-dependent. Fixing that is a shared-corpus change and belongs upstream, so
# what this guard owes is visibility.
positional_lines="$(awk -F'\t' '$3 == "positional" { print "         " $1 " [" $2 "]" }' <<< "$LEDGER")"
if [ -n "$positional_lines" ]; then
  echo "NOTE: $(wc -l <<< "$positional_lines") scenario(s) resolved by POSITIONAL index —" \
       "the fixture carries neither \`id\` nor \`name\`, so these ids are order-dependent:"
  echo "$positional_lines"
fi

if [ "$missing" -gt 0 ]; then
  echo "conformance coverage FAILED: $missing problem(s)" >&2
  exit 1
fi

echo "conformance coverage OK: $covered/$total canonical fixtures OPENED by the suite" \
     "($excused_count of ${#KNOWN_UNCOVERED[@]} KNOWN_UNCOVERED entries applied;" \
     "runtime manifest — these bytes were really read)"
echo "scenario coverage OK: $sc_replayed/$sc_total scenarios across $sc_fixtures opened" \
     "scenario-bearing fixtures were REPLAYED ($sc_excused excused of" \
     "${#KNOWN_UNREPLAYED_SCENARIOS[@]} excuseScenario entries; runtime ledger —" \
     "these scenarios really ran)"
