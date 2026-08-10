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
# that state is the vacuous green this guard exists to prevent. A missing CORPUS
# is the same claim one level up: it is a skip on a local checkout without the
# sibling, and a hard failure under CI (#lzvacuousrun). And because every rung
# here is a negative check over a population, the run must also prove the
# population was non-empty before it may print OK — see the positive-evidence
# block near the bottom.
#
# A second runtime ledger, written the same way, carries per-SCENARIO replay
# accounting (#lzscenariocoverage). "Was the file opened" and "was every scenario
# in it replayed" are different questions, and the first cannot answer the second:
# one scenario is enough to open a file. See the rung-4 block below.
#
# Usage: scripts/check-conformance-coverage.sh [manifest-path] [scenario-ledger-path]
set -euo pipefail

SPEC_DIR="${LAZILY_SPEC_CONFORMANCE_DIR:-${LAZILY_SPEC_DIR:-../lazily-spec}/conformance}"

# A missing corpus is a legitimate LOCAL state (no sibling checkout) and an
# illegitimate CI state (#lzvacuousrun). Every rung below reasons about fixtures
# the corpus lists and the run OPENED, so an absent corpus makes all of them
# vacuously true: zero fixtures means zero uncovered fixtures, zero stale
# excuses, and zero unreplayed scenarios. Exiting 0 there reports conformance OK
# having examined nothing, which is exactly the false claim this guard exists to
# prevent. Under CI that is missing EVIDENCE — a wrong checkout — not evidence of
# absence, so it is a hard failure. Locally it stays a skip, because a
# contributor without the sibling is not making a claim about coverage at all.
if [ ! -d "$SPEC_DIR" ]; then
  if [ -n "${CI:-}" ]; then
    echo "ERROR: canonical corpus not found at $SPEC_DIR, and CI is set." >&2
    echo "       Under CI this is missing EVIDENCE, not evidence of absence: the" >&2
    echo "       checkout is wrong, not the corpus. Exiting 0 here would report" >&2
    echo "       conformance OK having examined zero fixtures (#lzvacuousrun)." >&2
    exit 1
  fi
  echo "SKIP: canonical corpus not found at $SPEC_DIR (clone the lazily-spec sibling)" >&2
  echo "      Local checkout only — this is a hard failure under CI." >&2
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
  # Register CRDTs (LWW / MV / PnCounter + the CellCrdt projection bit) are
  # implemented here, but this binding has no canonical replay for the new
  # registers corpus yet; the Registers coverage row is `~` until it does.
  "collections/registers_convergence.json"
  # Reactive egress is currently Rust-only; Kotlin has no egress replay runner.
  "egress/egress_generation_fence.json"
  "egress/egress_inflight_window.json"
  "egress/egress_ordered_ack.json"
  "egress/egress_retry_budget.json"
  # codec/frame_roundtrip_msgpack.json was excused here while lazily-kt spoke
  # only the `json` half of the frame-codec obligation. It is now REPLAYED
  # (#lzmsgpackseven, MsgpackCodec.kt + CodecConformanceTest.kt), so the entry
  # is gone rather than kept as a stale excuse — this script fails an excuse
  # for a fixture the same run opened, which is exactly what should happen to
  # a gap that has been closed.
  #
  # No lazily-kt runner drives the reliable-sync outbox-coalescing or
  # lease-eviction scenarios yet. Excused in every other binding for the same
  # reason.
  "reliable-sync/coalesce_bounds_outbox.json"
  "reliable-sync/liveness_lease_eviction.json"
  # The canonical journal-decoder trace has no Kotlin replay runner yet.
  "reliable-sync/outbox_journal_decode.json"
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
# Ids resolve `id`, else `name`, identically in every binding. There is no
# positional fallback (#lzspecscenarioids) -- it used to exist because
# collections/mergecell_algebra.json
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
  # `codec` was ABSENT here while CodecConformanceTest.kt replayed
  # frame_roundtrip_json.json every run: the replay existed, and nothing required
  # it, so deleting that runner would have left this guard green and the
  # coverage matrix still claiming the reference codec. Found by lazily-spec's
  # coverage-claim guard (#coveragejsonscores); the same hole cost lazily-cpp
  # two undetected codec defects.
  codec
  collections
  coordination
  crdt-tree
  distributed
  familysync
ingress
ipc
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
if [ "$area" = "ipc" ]; then
area_fixture="$(find "$SPEC_DIR" -maxdepth 1 -type f \
  \( -name 'arena_blob.json' -o -name 'snapshot_*.json' -o -name 'delta_*.json' \) \
  -print -quit 2>/dev/null)"
else
area_fixture="$(find "$SPEC_DIR/$area" -name '*.json' -print -quit 2>/dev/null)"
fi
if [ -z "$area_fixture" ]; then
echo "ERROR: canonical corpus has no fixtures under area '$area'." >&2
echo "       The lazily-spec checkout at $SPEC_DIR is stale or partial; coverage" >&2
echo "       computed against it would silently understate the real corpus." >&2
missing=$((missing + 1))
fi
done

total=0
covered=0
excused_count=0
# Areas witnessed by a fixture that is BOTH listed by the corpus and recorded as
# opened. Accumulated here rather than derived from the manifest's own strings so
# the positive-evidence block at the bottom cannot be satisfied by paths that name
# nothing on disk.
COVERED_AREA_LIST=""
while IFS= read -r fixture; do
  total=$((total + 1))
  # Here-string, NOT a pipe. With `set -o pipefail`, `printf ... | grep -q` reports
  # FAILURE when grep matches: grep -q exits immediately on the first hit, printf
  # takes SIGPIPE writing the rest, and pipefail surfaces printf's death as the
  # pipeline's status. The check then inverts — every covered fixture is reported
  # missing.
  if grep -qxF "$fixture" <<< "$OPENED"; then
    covered=$((covered + 1))
case "$fixture" in
*/*) COVERED_AREA_LIST+="${fixture%%/*}"$'\n' ;;
arena_blob.json|snapshot_*.json|delta_*.json) COVERED_AREA_LIST+='ipc'$'\n' ;;
*) COVERED_AREA_LIST+='(unknown-root)'$'\n' ;;
esac
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
# `name`. There is no positional fallback (#lzspecscenarioids): an id derived
# from a POSITION silently rebinds to a different scenario when the corpus array
# is reordered, so an unidentified scenario is reported rather than given an
# invented id. This must stay independent of the runner — the whole point is that
# the corpus, not the binding, says what there was to replay.
scenario_ids() {
  # A manifest or ledger entry naming a file this corpus does not hold is
  # already reported by the self-guards above, so resolve it to "no scenarios"
  # rather than letting jq die on the open. Under `set -e` that death aborts the
  # whole script with status 2 part-way through the diagnosis: the summary gate
  # never runs, every problem queued behind it is lost, and — the reason it
  # matters here — the positive-evidence block at the bottom becomes unreachable
  # in exactly the empty/half-cloned-corpus case it exists to catch.
  [ -f "$SPEC_DIR/$1" ] || return 0
  jq -r '
    def identifier: if type == "string" and (gsub("\\s"; "") != "") then . else null end;
    if (type == "object") and ((.scenarios | type) == "array")
    then (
      .scenarios
      | to_entries[]
      | ((.value.id? | identifier) // (.value.name? | identifier) // "!UNIDENTIFIED!\(.key)")
    )
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
    # An unidentified scenario is a corpus defect, not an id to invent
    # (#lzspecscenarioids). Booking it by POSITION would silently rebind that
    # ledger entry to a different scenario on any corpus reorder.
    case "$id" in
      '!UNIDENTIFIED!'*)
        echo "ERROR: '$fixture' scenario at index ${id#!UNIDENTIFIED!} carries neither" >&2
        echo "       \`id\` nor \`name\`. The ledger would record it by POSITION, which" >&2
        echo "       silently rebinds on a corpus reorder. Give it a stable id upstream" >&2
        echo "       in lazily-spec (#lzspecscenarioids)." >&2
        missing=$((missing + 1))
        continue
        ;;
    esac
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
    echo "       'id -> name' order; the ledger cannot be compared to the corpus." >&2
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

# ===========================================================================
# Positive evidence — the examined population itself (#lzvacuousrun)
# ===========================================================================
#
# Every rung above is a NEGATIVE check: it walks a population and reports the
# problems it finds. All of them are vacuously satisfied by an empty population.
# Zero fixtures in the corpus means zero uncovered fixtures; zero fixtures in the
# manifest means zero stale excuses and zero unreplayed scenarios. The loops
# cannot tell "nothing is wrong" from "nothing was examined", and neither can the
# OK line they license.
#
# This block is deliberately NOT a reinstated MIN_FIXTURES floor. That floor was
# removed for good reason (see the header): it was a number that rots, and 131
# replays against a floor of 117 meant fourteen replays could stop running with
# CI green. Per-fixture accounting replaced it and is strictly stronger — for
# everything except magnitude. So the magnitude assertion here is stated in the
# same AREA vocabulary this guard already uses, and mostly without numbers:
#
#   1. The REQUIRED_AREAS list must be non-empty. An area tripwire with no areas
#      passes over any corpus at all, so the guard that protects the enumeration
#      needs its own emptiness check.
#   2. The corpus enumeration and the opened set must both be non-empty.
#   3. Every REQUIRED_AREA must contribute at least one OPENED fixture. The loop
#      near the top asserts the area exists in the CORPUS; this asserts the RUN
#      actually read something from it. That turns the area list from a
#      corpus-shape tripwire into positive evidence about the examined
#      population, and it is the assertion that cannot rot into a stale number:
#      it is derived from the area names, which are also what the corpus check
#      already uses.
#   4. One calibrated floor on the count of distinct opened areas, so a corpus
#      that loses areas outright (a partial checkout that also drops them from
#      REQUIRED_AREAS) still cannot slip through at a fraction of its real size.
#
# Do not lower MIN_OPENED_AREAS to fix a red run — a drop here means the corpus
# or the recorder shrank, which is the finding, not the obstacle.

if [ "${#REQUIRED_AREAS[@]}" -eq 0 ]; then
  echo "ERROR: REQUIRED_AREAS is empty — the corpus-shape tripwire asserts nothing." >&2
  echo "       An area guard with no areas is green over any corpus, including none." >&2
  missing=$((missing + 1))
fi
if [ "$total" -eq 0 ]; then
  echo "ERROR: the corpus at $SPEC_DIR enumerated ZERO fixtures." >&2
  echo "       Every per-fixture check above is vacuously green over an empty" >&2
  echo "       population (#lzvacuousrun)." >&2
  missing=$((missing + 1))
fi
if [ "$covered" -eq 0 ]; then
  echo "ERROR: the suite OPENED zero canonical fixtures." >&2
  echo "       The manifest exists but records nothing this corpus recognises, so" >&2
  echo "       coverage, allowlist-rot and scenario accounting all compared nothing." >&2
  missing=$((missing + 1))
fi

# Distinct top-level areas witnessed by a fixture the corpus lists AND the run
# opened. Root-level fixtures (snapshot_*/delta_*/arena_blob) form the explicit
# `ipc` area. An unknown future root fixture is deliberately not credited to it.
OPENED_AREAS="$(sort -u <<< "$COVERED_AREA_LIST" | grep . || true)"
opened_area_count=0
[ -n "$OPENED_AREAS" ] && opened_area_count="$(grep -c . <<< "$OPENED_AREAS")"

for area in "${REQUIRED_AREAS[@]}"; do
  if ! grep -qxF "$area" <<< "$OPENED_AREAS"; then
    echo "ERROR: area '$area' is required, but the suite OPENED no fixture in it." >&2
    echo "       The corpus check above only proves the area EXISTS. This proves the" >&2
    echo "       run examined it — without that, an area can go entirely dark while" >&2
    echo "       every negative check stays green over the fixtures it never saw." >&2
    missing=$((missing + 1))
  fi
done

# This floor tracks WHAT THE RUN ACTUALLY OPENS, exactly — no margin, no slack.
# Pinned 2026-08-09 at 26 distinct opened areas, re-derived from a full
# `./gradlew test --no-daemon --rerun-tasks` plus this guard on the commit CI run
# 31343632650 was green over (that run reported the same 142/150 fixtures,
# 153/153 scenarios and 18/18 assertion blocks, so it examined the same corpus).
#
# It previously sat at 22 against 26, on the theory that slack makes an upstream
# area rename a one-line corpus edit rather than a forced number change. That
# reasoning does not hold: a rename ALSO fails the REQUIRED_AREAS loop directly
# above, so the list has to be edited either way — the slack bought nothing and
# let four areas go entirely dark with this guard still green. Do not restore it,
# and do not raise this floor "by however many areas a change adds" while leaving
# an old margin in place; that convention is what let the fixture and scenario
# floors in the sibling bindings rot to 40 replays behind reality
# (#lzscenariofloordrift). Set it to the count the `area coverage OK` line below
# reports from a COMPLETED CI run, which examines the published corpus rather
# than a working tree (#lzspecpushbeforebindings).
#
# Note the number is necessarily >= the 25 REQUIRED_AREAS, since each of those
# must contribute an opened fixture; the extra is an area the run opens without
# requiring. Do not lower this to fix a red run — the shrink is the finding.
MIN_OPENED_AREAS="${MIN_OPENED_AREAS:-26}"
if [ "$opened_area_count" -lt "$MIN_OPENED_AREAS" ]; then
  echo "ERROR: the suite OPENED fixtures in only $opened_area_count corpus area(s)," >&2
  echo "       expected >= $MIN_OPENED_AREAS. The corpus is a partial checkout, or the" >&2
  echo "       recorder detached part-way through the run. Do not lower" >&2
  echo "       MIN_OPENED_AREAS to fix this — the shrink is the finding." >&2
  missing=$((missing + 1))
fi

# The scenario rung walks the scenarios of OPENED fixtures, so it is vacuous in
# exactly the same way one level down: no opened scenario-bearing fixture means
# no scenario to find unreplayed.
if [ "$sc_fixtures" -eq 0 ] || [ "$sc_total" -eq 0 ]; then
  echo "ERROR: ZERO scenarios were enumerated across the opened fixtures." >&2
  echo "       The per-scenario rung is vacuously green over an empty population." >&2
  missing=$((missing + 1))
fi

# --- rung 0: was every fixture-level `assertions` block BOUND to a tracker? ---
#
# Every other rung here is scoped to a block a runner ALREADY OPENED. The unread
# check, the unasserted check and the prose ledger all live inside AssertionKeys,
# so a fixture-level `assertions` block that no runner ever constructs an
# AssertionKeys over reports NOTHING: its keys are not unread, because nothing was
# reading them. lazily-dart found two such blocks in its own suite — eight silent
# keys, including the invariant that a forwarded `from` is the server's registered
# peer id and never a client-supplied one. No grep finds that; the evidence is the
# absence of a call, so it has to be recorded at runtime like every other rung
# here (#lznullformblind).
#
# ConformanceFixtures inventories the block when it reads the fixture, and
# AssertionKeys books it bound when it is constructed over it — matched by
# CONTENT, not by the `where` label a runner picks for itself.
BLOCK_LEDGER="${3:-${LAZILY_CONFORMANCE_ASSERTION_BLOCK_LEDGER:-build/conformance-assertion-blocks.txt}}"
if [ ! -f "$BLOCK_LEDGER" ]; then
  echo "ERROR: assertion-block ledger not found at $BLOCK_LEDGER." >&2
  echo "       That is missing EVIDENCE, not evidence of absence: the suite ran" >&2
  echo "       without the rung-0 recorder attached (#lzvacuousrun)." >&2
  missing=$((missing + 1))
else
  blocks_total=$(grep -c . "$BLOCK_LEDGER" || true)
  unbound=$(awk -F'\t' '$2 == "UNBOUND" { print $1 }' "$BLOCK_LEDGER")
  if [ -n "$unbound" ]; then
    echo "ERROR: fixture-level \`assertions\` block(s) that NO runner bound to a tracker:" >&2
    echo "$unbound" | sed 's/^/         /' >&2
    echo "       Their keys are silent — not unread, because nothing reads them —" >&2
    echo "       so every other rung passes over them (#lznullformblind). Bind the" >&2
    echo "       block with AssertionKeys and assert its keys." >&2
    missing=$((missing + 1))
  fi
  if [ "$blocks_total" -eq 0 ]; then
    echo "ERROR: ZERO fixture-level \`assertions\` blocks were inventoried." >&2
    echo "       Rung 0 is vacuously green over an empty population." >&2
    missing=$((missing + 1))
  fi
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
echo "assertion-block coverage OK: $blocks_total/$blocks_total fixture-level \`assertions\`" \
     "block(s) opened by the suite were BOUND to a tracker (runtime ledger — a block" \
     "nobody binds is silent to every other rung)"
# Printed so MIN_OPENED_AREAS can be re-pinned from a CI log instead of being
# guessed or probed locally — a floor nobody can read the real number for is a
# floor that drifts.
echo "area coverage OK: $opened_area_count corpus area(s) OPENED by the suite" \
     "(${#REQUIRED_AREAS[@]} required; floor $MIN_OPENED_AREAS)"
