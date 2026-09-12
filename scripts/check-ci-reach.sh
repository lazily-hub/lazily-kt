#!/usr/bin/env bash
# CI-reachability guard (#lzcheckcireachguard).
#
# Fails the build when `make check` runs a gate that CI never reaches. That is the
# drift this guard exists for: someone adds a target to `check`, it passes locally
# forever, and no CI job ever executes it — which is exactly how #lzinteroppeerci
# happened. The interop peer, the single cross-binding wire-compatibility gate, was
# in every binding's `check` and in no binding's workflow, for months.
#
# It also exists because the obvious hand-audit is WRONG. Grepping the workflows
# for "make check" reported all nine bindings as covered; every one of those hits
# was a COMMENT. Comments are the reason this is a script and not a convention:
# only `run:` bodies count here, and comment lines inside them are stripped before
# anything is matched.
#
# WHAT IT PROVES
#
#   For every target in `check`'s prerequisite closure, at least one CI `run:`
#   step invokes the same program with the same distinguishing flags.
#
#   And that the closure IS the pinned set, in three parts that only work
#   together (#pinreachclosure):
#
#     A. the ORACLE — every target the closure was read from Makefile SOURCE is
#        confirmed against `make -n check`, which is the only authority on what
#        `make check` runs;
#     B. MEMBERSHIP — `EXPECTED_CLOSURE_TARGETS` is compared to the closure by set
#        equality, so a gate cannot leave `make check` without the pin leaving
#        with it in the same commit;
#     C. CLASSIFICATION — `EXPECTED_NO_GATE_TARGETS` pins which of those targets
#        are legitimately gate-free, so a recipe cannot be neutered into the
#        gate-free bucket while keeping its name in the closure.
#
#     D. the STEP MAP — `EXPECTED_CI_STEPS` pins, per gate, the NAME of the CI step
#        that runs it, and reach is asked INSIDE that step rather than of every
#        `run:` body at once (#reversereachdirection).
#
# D is what closes the recipe SWAP, which A, B and C do not see: a target whose
# recipe is repointed at a different gate CI already runs — `test-interop-peer:`
# running `./gradlew test`, or `./gradlew build`, a real step in this workflow
# that no closure member runs — satisfied every rung with every count unchanged
# and a byte-identical verdict at exit 0. Both halves were measured here before D
# existed. Step-scoping fails them by name, because the repointed anchor is no
# longer in the step pinned for that target. Its churn is step-NAME-rate, not
# recipe-rate: a recipe that gains a flag moves the recipe and the step together
# and the map does not move. The rejected alternative was a per-target recipe
# CONTENT pin, a second spelling of every recipe kept in sync inside this guard —
# recipe-rate churn, updated reflexively, the passes-when-stale shape this family
# already removed once, and the mistake the normalizer notes below record as
# costing lazily-cpp a hand-written equality assertion.
#
# D also closed a live SUPERSET hole, which is independent of the swap. Reach is
# subsequence matching against a FLAT set of every step's commands, so a step
# whose command contains another target's anchor stands in for that target's own
# step. Measured here: `test-lean-formal` and `test-lazily-formal` both reduce to
# the anchor `lake build` — the working-directory that distinguishes them is not
# a command token — so DELETING either `lake build` step left this guard
# byte-identical at exit 0, still printing `reached` for the target whose step was
# gone. Both directions reproduced. Neither `uses: leanprover/lean-action` step
# helps: this guard cannot see a `uses:` step at all, which is the whole reason
# those two run: steps exist. Step-scoping reddens both deletions.
#
# D IS TWO SETS, and their independence is load-bearing. EXPECTED_CI_STEPS names
# the step per anchor-reached gate; EXPECTED_MAKE_INVOKED_TARGETS names the gates
# CI reaches by running `make <target>` instead, for which a step pin would assert
# nothing. Defining the second population as "whatever the first does not name"
# reads equivalent and is not: cpp and dart both measured green at exit 0 on the
# two-part edit — flip one member's CI step body to `make <that member>` AND
# delete that member's step entry, in ONE edit. Each half alone fails; as a
# complement the halves cancel, since the deleted entry was the only evidence the
# mode had changed. In dart the sole trace was an OK line's count dropping by one,
# which no exit status reflects. dart's formulation, worth keeping verbatim: a
# population pinned only as the complement of another pinned population is not
# pinned against an edit that moves both together — A COUNT IS NOT A PIN.
#
# Measured here against both shapes, each exit 1 naming the target: the edit on
# `test-interop-peer`, and the same on `test`, which holds TWO step entries (CI
# runs `./gradlew test` and the conformance-coverage guard as separate steps).
# Holding more than one entry changes nothing about this fault — the mode rung
# never consulted the entries — but it does make the dishonest edit strictly
# larger: the attacker must delete BOTH entries, and deleting one leaves the
# other pinning a target that is no longer anchor-reached, which fails on its own.
#
# ORDER: the mode rungs run BEFORE the step-pin rungs, and the step-pin rung is
# suppressed for a target nothing in CI reaches. cpp paid for the other order.
# Deleting a make-invoked member's CI step outright makes it want a step pin, so
# the unpinned rung fired FIRST and told the reader to add one — the wrong remedy
# for a deleted step, and reported ahead of the message that says what happened.
#
# The same restructure fixed a latent FALSE RED nothing here had exercised: the
# pin accounting used to run before the excuse check, so the first honest excuse
# anyone added — a target CI deliberately does not run, hence carrying no step
# pin — would have exited 1 demanding a pin for it. Confirmed against the shipped
# script. An excused target is now asked the FLAT reach question on purpose and
# carries no pin.
#
# A THIRD limit on the superset measurement itself, since the fix is only as good
# as the relation it was measured against: containment here is TEXTUAL, over
# anchor tokens. It cannot see a build tool's own task graph. `./gradlew build`
# really does run `:test` — the `Test` step below it lands UP-TO-DATE, as that
# job's own comment says — so `Build` is a genuine superset of `Test` in CI while
# being no superset at all to this guard, whose anchors differ at the `build` /
# `test` token. That direction is conservative (deleting the `Test` step reddens
# even though the tests would still run), and it is the only direction available:
# asking gradle for its task graph means running gradle, which is the job this
# guard runs inside. Stated so the sweep is not read as exhaustive.
#
# WHAT D STILL DOES NOT PROVE, stated rather than implied covered: a recipe
# weakened INSIDE its own pinned step. Anchors match as an in-order SUBSEQUENCE
# and extra CI-side tokens are allowed by design, so a recipe that drops a flag —
# asking for LESS than the pinned step runs — still matches. Measured here by
# dropping `--binding kt` from `assertion-ordering-check`: green, because the step
# still spells it. Step-scoping fixes WHERE a gate is looked for, never WHAT it
# asks for. Only the declined recipe-content pin would close that.
#
# It also bounds the honest claim for the pins. What they assert is NO SILENT
# CHANGE, never CORRECTNESS: a pin cannot name a gate that never existed, and
# written from a broken Makefile it would faithfully pin the breakage. The
# original argument for pinning — that a closure change is visible in a diff of
# the `check:` line — turned out to be true of exactly one of the five attacks
# measured: the rename, the `ifeq`, the neutered recipe and the recipe swap are
# all equally visible edits, and three of the four were undetected before this.
# What survives is narrower: the pin makes the retiring edit INCOMPLETE, so it
# cannot be a one-line deletion. The oracle, not the visibility, is what makes the
# pin describe anything at all.
#
# Two further things a SET pin structurally cannot see, recorded rather than left
# implied:
#
#   ORDER. Prerequisite order is not pinned, and nothing else here pins it. Today
#   nothing in this binding depends on it: every closure target is self-contained,
#   and the one real ordering — the test JVM writing conformance evidence before
#   the coverage guard reads it — lives INSIDE `test`'s own recipe, where make and
#   the shell enforce it. `fmt` running first is a cost preference (fail on style
#   before paying for a compile), not a correctness requirement. If an
#   inter-target ordering requirement is ever introduced, this guard will not
#   notice it changing.
#
#   EDGES. Dropping an edge BETWEEN two closure members can leave the node set
#   unchanged and still pass the oracle, whenever another member already pulls the
#   dependency into the root's run: `make check` keeps working while
#   `make <target>` alone breaks. This binding's closure is flat — all seven gates
#   are direct prerequisites of `check` and none has a Makefile-target
#   prerequisite of its own — so there is no such edge to drop today. The gap
#   opens the moment one is added.
#
# WHAT IT DOES NOT PROVE
#
#   That CI runs it against the same inputs, in the same environment, or that the
#   command means the same thing there. Reach is a floor, not equivalence. The
#   sibling guards (conformance-coverage, assertion-keys, scenario-coverage) are
#   what prove a run examined anything.
#
# HOW A TARGET IS MATCHED
#
#   Recipes are read through `make -n`, so make variables are already expanded and
#   we compare real command lines rather than source text. `make -p` is
#   deliberately NOT used: it dumps the entire environment to stdout, which would
#   print every secret in the job's env into the CI log.
#
#   Each command is split on the shell's sequencing operators, redirections are
#   dropped, and the remainder is reduced to an ANCHOR: the program basename plus
#   its subcommands and flag NAMES (values dropped), with path arguments reduced to
#   basenames and bare path globs discarded. A target is reached when EVERY one of
#   its anchors is a subsequence of some CI command's token list, or when CI runs
#   `make <target>` directly. Every, not any: a target that runs two gates and is
#   half-covered by CI is a gap, and "any" would report it green.
#
#   Keeping flag names in the anchor is what makes the guard falsifiable rather
#   than decorative: `go test -race` does not match a CI step that only runs
#   `go test -count=1`, so dropping the race job reddens this guard instead of
#   being absorbed by the plain test job.
#
#   An argument that is still a VARIABLE reference at this point — `$MANIFEST` in
#   a CI step, or a `$$VAR` a recipe leaves for the shell — names a value the
#   guard cannot resolve, so it becomes a WILDCARD matching exactly one token on
#   the other side (#lzcireachvaranchor). Make and CI routinely spell the same
#   path differently, one through an expanded `$(VAR)` and the other through the
#   environment, and they are the same command. Dropping the token instead, which
#   is what this used to do, lost the argument as well as its value and reported
#   a step that genuinely ran the gate as unreachable — a false RED that cost one
#   binding a hardcoded second spelling of the path plus a hand-written equality
#   assertion, which is a new drift surface invented to satisfy a guard whose job
#   is detecting drift. Arity still counts: `script.sh $A` does not match a CI
#   step that passes no argument at all.
#
#   Commands whose program is a shell builtin or a plain file/text utility carry no
#   gate, so they contribute no anchor. A target with no non-trivial command at all
#   (a mkdir-only reset step, say) is reported as carrying no gate and is not
#   required to appear in CI. It cannot fail a build, so it cannot hide one.
#
# THE EXCUSE LIST IS THE OTHER HALF OF THE DELIVERABLE
#
#   scripts/ci-reach.conf names the workflows that count and the targets that are
#   deliberately local-only, each with a reason. It is the one place a reader can
#   see what this binding does not enforce in CI, in the same spirit as
#   KNOWN_UNCOVERED. Excuses are checked in EVERY direction: an excused target that
#   CI turns out to reach fails, and so does an excuse for a target that is not in
#   the closure at all — a waiver for something nothing runs waives nothing while
#   reading like deliberate coverage. So the list cannot rot into a list of things
#   that used to be true.
set -euo pipefail

MAKE_BIN="${MAKE:-make}"
ROOT_TARGET="${CI_REACH_ROOT_TARGET:-check}"
CONF="${CI_REACH_CONF:-scripts/ci-reach.conf}"

if [ ! -f Makefile ]; then
	echo "check-ci-reach: no Makefile in $(pwd)" >&2
	exit 1
fi

# ---------------------------------------------------------------- configuration

workflows=()
workflow_count=0
excused_targets=()
excused_reasons=()
excuse_count=0

if [ -f "$CONF" ]; then
	while IFS= read -r line || [ -n "$line" ]; do
		line="${line%%$'\r'}"
		case "$line" in
		'#'* | '') continue ;;
		esac
		key="${line%%:*}"
		val="${line#*:}"
		val="$(printf '%s' "$val" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
		case "$key" in
		workflow)
			workflows+=("$val")
			workflow_count=$((workflow_count + 1))
			;;
		excuse)
			tgt="${val%%[[:space:]]*}"
			reason="${val#"$tgt"}"
			reason="$(printf '%s' "$reason" | sed -e 's/^[[:space:]]*//')"
			if [ -z "$reason" ]; then
				echo "check-ci-reach: excuse for '$tgt' has no reason — an excuse without a reason is not an excuse" >&2
				exit 1
			fi
			excused_targets+=("$tgt")
			excused_reasons+=("$reason")
			excuse_count=$((excuse_count + 1))
			;;
		*)
			echo "check-ci-reach: unknown key '$key' in $CONF" >&2
			exit 1
			;;
		esac
	done <"$CONF"
fi

if [ "$workflow_count" -eq 0 ]; then
	workflows=(".github/workflows/ci.yml")
	workflow_count=1
fi

for wf in "${workflows[@]}"; do
	if [ ! -f "$wf" ]; then
		echo "check-ci-reach: workflow '$wf' listed in $CONF does not exist" >&2
		exit 1
	fi
done

# ------------------------------------------------------------- the closure pin
#
# WHICH targets have to be in the closure (#pinreachclosure).
#
# Everything below measures the closure of $ROOT_TARGET. Nothing used to pin what
# that closure CONTAINS, and that is a hole wide enough to retire a gate through.
# Measured on this repo: delete `test-interop-peer` from `check:`s prerequisite
# list and this guard printed
#
#   check-ci-reach: OK — 6 target(s) reached by CI, 0 excused, 1 carrying no gate
#
# and exited 0. The count moved 7 -> 6 and the target that LEFT was never named,
# so the single cross-binding wire-compatibility gate could stop being required
# with this guard's own approval. That is #lzinteroppeerci again one level up: the
# guard proved CI reaches every target `make check` runs, and said nothing about
# `make check` still running the targets that matter. dart, cpp and rs reproduced
# it the same way, which is why all ten bindings pin the same rule.
#
# SET EQUALITY, not a floor and not a ceiling. A floor (`>= 7 targets`) passes a
# SWAP: drop the interop peer, add a cheap target, count restored. A ceiling
# self-disables — it starts with zero slack and gains slack with every legitimate
# migration until the same attack fits underneath it again. The property that has
# to hold is FAILS-WHEN-STALE, not passes-when-stale: the same reasoning that
# replaced MAX_LEDGERED_BLOCKS with EXPECTED_LEDGERED_BLOCKS in the sibling
# conformance guard, and the reason the name carries the `EXPECTED_` prefix, which
# already means exact equality in these repos.
#
# A PIN is the right shape here rather than a probe, and the reason is what the
# attack touches. The two holes closed last cycle — a prerequisite `make -n`
# cannot read being reported as "no gate", and `grep -c` exiting 1 on a zero
# count — live inside recipes and leave the `check:` line untouched, so there is
# nothing for a reviewer to see and only an executable probe finds them. Dropping
# a target from the closure is the opposite: it is already a visible one-line edit
# to `check:`. The pin adds no detection there. What it adds is INCOMPLETENESS —
# the diff that retires a gate no longer builds, so it has to say so out loud in
# this file too.
#
# No pin here is environment-overridable, deliberately. A pin a caller can relax
# is a pin that vanishes exactly when someone finds it inconvenient.
#
# MEMBERSHIP IS NOT ENOUGH ON ITS OWN, and the two pins below are the halves it
# needs. lazily-js measured both:
#
#   * prereqs_of() reads the closure out of Makefile SOURCE TEXT — the first line
#     matching `^check:` — and never asks make. Wrap the prerequisite list in an
#     `ifeq` and the two diverge: awk reads the first branch, make parses the
#     other. Measured here with the `test-interop-peer` gate behind
#     `ifeq ($(SKIP_SLOW),)`: under SKIP_SLOW=1 `make -n check` runs no
#     `interopPeerCheck` at all, and this guard's verdict was BYTE-IDENTICAL to
#     healthy, membership pin included — the pinned set and the executed set were
#     simply different objects. A dead `ifeq (0,1)` branch does it with no
#     variable at all. That is why the ORACLE below is the load-bearing part and
#     the membership pin is only meaningful on top of it.
#   * keeping the NAME and neutering the RECIPE (`test-interop-peer:` + `true`)
#     leaves membership intact and moves the target into the gate-free bucket,
#     where nothing is required of it. Measured here at exit 0. Hence
#     EXPECTED_NO_GATE_TARGETS: being gate-free is a claim, so it is pinned.
EXPECTED_ROOT_TARGET="check"
EXPECTED_CLOSURE_TARGETS=(
	assertion-ordering-check
	check
	ci-reach
	fmt
	test
	test-interop-peer
	test-lazily-formal
	test-lean-formal
)

# Which closure targets legitimately carry no checkable command. `check` itself is
# a pure aggregator with an empty recipe, and that is the whole list: every other
# target in the closure runs a gate, so any other name appearing here means a
# recipe stopped running one.
EXPECTED_NO_GATE_TARGETS=(
	check
)

# WHICH CI STEP runs each gate (#reversereachdirection).
#
# Everything else here asks "does SOME CI command spell this target's anchors".
# That flat question is what leaves the recipe SWAP open, and the header above
# records it as unproven: point `test-interop-peer:` at `./gradlew build` — a real
# step in this workflow that no closure member runs — and the anchor is found, the
# oracle agrees (the root runs the same changed recipe), membership and
# classification are untouched, and the verdict is byte-identical at exit 0.
# Measured here, both halves: repointed at `./gradlew build` (a step no member
# runs) and at `./gradlew test` (another member's gate).
#
# So reach is asked INSIDE a pinned step instead. Repoint a member anywhere else
# and its anchors are no longer in ITS step, so it fails by name. The churn is
# step-NAME-rate, not recipe-rate: a recipe that gains a flag moves the recipe and
# the CI step together and this mapping does not move. That is the property a
# per-recipe-content pin lacked, and the reason that pin was declined — it would
# be updated reflexively on every recipe edit and become the passes-when-stale
# check this family already removed once.
#
# WHAT IT DOES NOT CLOSE, stated rather than implied covered: a recipe weakened
# INSIDE its own pinned step. Anchors match as an in-order SUBSEQUENCE and extra
# CI-side tokens are allowed by design, so dropping a flag from a recipe — the
# recipe asking for LESS than the step runs — still matches. Only the declined
# per-recipe-content pin would close that. Step-scoping closes WHERE a gate is
# looked for, never WHAT it asks for.
#
# TWO STEPS for one target is legitimate and required here: `test` runs
# `./gradlew test` and then the conformance-coverage guard, and CI runs those as
# two separate named steps. So the value is a LIST. It is checked in both
# directions — every anchor must be found among the named steps, and every named
# step must account for at least one anchor — because a pin that may name extra
# steps is a pin that can smuggle the flat haystack back in one step at a time.
#
# A pin is REFUSED for a make-invocation-reached target. `fmt` is reached because
# CI runs `make fmt`, not because CI spells `./gradlew spotlessCheck` anywhere; it
# has no independent CI-side spelling, so naming a step for it would assert
# nothing and read like it asserted something. CI's instruction there is "run the
# target", and a repoint is undetectable from CI — correctly, because CI
# faithfully runs whatever the target runs.
#
# Step names are matched exactly and must be UNIQUE among the `run:` steps of the
# listed workflows: the mapping is by name, so two steps sharing one name make the
# pin ambiguous, and this refuses rather than picking. Family-wide measurement:
# rs has 69 run: steps and 65 distinct names, js 23/22, cs 23/21, cpp 20/19 — the
# collision is common enough that the check is not theoretical. This workflow's
# 11 run: steps carry 11 distinct names.
EXPECTED_CI_STEPS=(
	'assertion-ordering-check=Assertion observation ordering (#lzassertordering)'
	'ci-reach=CI-reachability guard (#lzcheckcireachguard)'
	'test=Test'
	'test=Guard — conformance fixtures actually replayed (#lzspecconf)'
	'test-interop-peer=Interop peer self-check (#lzinteroppeerci)'
	'test-lazily-formal=lazily-formal model builds under `make test-lazily-formal`'
	'test-lean-formal=Lean formal model builds under `make test-lean-formal`'
)

# Targets whose CI reach is `make <target>` in a run: body rather than a spelling
# of their own commands, and which must therefore carry NO step pin. Pinned as a
# set for the same reason as the other two: silently moving a target between
# "spelled by CI" and "CI runs make" changes what this guard can prove about it,
# and without the pin that move is invisible.
#
# Measured here, and worth recording because the naive count is the trap this
# file's own header documents: a regex for `make <target>` over the raw workflow
# text finds ELEVEN hits. One is a real invocation (`run: make fmt`). Five are
# comments, three are step NAMES — `Lean formal model builds under
# \`make test-lean-formal\`` is not a command — and one is `make check` inside a
# quoted `echo "::error::..."`. Measured from `run:` bodies with comments and
# quoted strings excluded, which is what ci_commands does, the count is ONE.
EXPECTED_MAKE_INVOKED_TARGETS=(
	fmt
)

# Count the non-blank lines of a newline-joined list. `grep -c` is not used on
# purpose: it exits 1 on a zero count, which under `set -e` turns "the pin and the
# closure agree" into a dead guard (#lzgrepcpipefail).
count_lines() {
	printf '%s\n' "$1" | awk 'NF { n++ } END { print n + 0 }'
}

if [ "${#EXPECTED_CLOSURE_TARGETS[@]}" -eq 0 ]; then
	echo "check-ci-reach: EXPECTED_CLOSURE_TARGETS is empty — an empty pin is satisfied" >&2
	echo "       by an empty closure, so it would pin nothing (#pinreachclosure)." >&2
	exit 1
fi

for pinned_target in "${EXPECTED_CLOSURE_TARGETS[@]}"; do
	if [ -z "$pinned_target" ]; then
		echo "check-ci-reach: EXPECTED_CLOSURE_TARGETS contains an empty entry — a blank" >&2
		echo "       name matches no target and silently shrinks the pin (#pinreachclosure)." >&2
		exit 1
	fi
done

# The pin is a SET, and it is written sorted so that its diff reads as one line
# per target. Assert both properties about the literal above: a duplicate makes
# the pin's own length a lie, and an unsorted append is how a pin stops being
# reviewable. LC_ALL=C so the order does not depend on the runner's locale.
pinned_as_written="$(printf '%s\n' "${EXPECTED_CLOSURE_TARGETS[@]}")"
pinned_targets="$(printf '%s\n' "${EXPECTED_CLOSURE_TARGETS[@]}" | LC_ALL=C sort)"
pinned_unique_count="$(printf '%s\n' "$pinned_targets" | LC_ALL=C sort -u | awk 'NF { n++ } END { print n + 0 }')"

if [ "$pinned_unique_count" -ne "${#EXPECTED_CLOSURE_TARGETS[@]}" ]; then
	echo "check-ci-reach: EXPECTED_CLOSURE_TARGETS lists ${#EXPECTED_CLOSURE_TARGETS[@]} entries but only" >&2
	echo "       $pinned_unique_count distinct target(s) — a duplicated name makes the pin's own" >&2
	echo "       length a lie (#pinreachclosure)." >&2
	exit 1
fi

if [ "$pinned_as_written" != "$pinned_targets" ]; then
	echo "check-ci-reach: EXPECTED_CLOSURE_TARGETS is not in sorted order. It is a set, and" >&2
	echo "       it is kept sorted so a change to it is one reviewable line. Sort it:" >&2
	printf '%s\n' "$pinned_targets" | sed 's/^/           /' >&2
	exit 1
fi

# Same three properties for the gate-free pin, plus containment: claiming a target
# is gate-free only means something if that target is in the closure at all.
pinned_nogate_as_written="$(printf '%s\n' ${EXPECTED_NO_GATE_TARGETS[@]+"${EXPECTED_NO_GATE_TARGETS[@]}"} | awk 'NF')"
pinned_nogate="$(printf '%s\n' "$pinned_nogate_as_written" | awk 'NF' | LC_ALL=C sort)"
pinned_nogate_unique_count="$(printf '%s\n' "$pinned_nogate" | LC_ALL=C sort -u | awk 'NF { n++ } END { print n + 0 }')"

if [ "$pinned_nogate_unique_count" -ne "$(count_lines "$pinned_nogate_as_written")" ]; then
	echo "check-ci-reach: EXPECTED_NO_GATE_TARGETS repeats a name — a duplicate makes the" >&2
	echo "       pin's own length a lie (#pinreachclosure)." >&2
	exit 1
fi

if [ "$pinned_nogate_as_written" != "$pinned_nogate" ]; then
	echo "check-ci-reach: EXPECTED_NO_GATE_TARGETS is not in sorted order. Sort it:" >&2
	printf '%s\n' "$pinned_nogate" | sed 's/^/           /' >&2
	exit 1
fi

unpinned_nogate="$(LC_ALL=C comm -23 <(printf '%s\n' "$pinned_nogate") <(printf '%s\n' "$pinned_targets") | awk 'NF')"
if [ -n "$unpinned_nogate" ]; then
	echo "check-ci-reach: EXPECTED_NO_GATE_TARGETS names target(s) that are not in" >&2
	echo "       EXPECTED_CLOSURE_TARGETS:" >&2
	printf '%s\n' "$unpinned_nogate" | awk 'NF { print "  - " $0 }' >&2
	echo "       Calling a target gate-free says something about a target the closure" >&2
	echo "       contains. Outside it, it says nothing (#pinreachclosure)." >&2
	exit 1
fi

# Pin the ROOT as well. Without this, renaming `check:` and pointing the guard at
# the new name would compare the pin against a closure it never described — and
# renaming it while leaving the guard alone empties the closure to a single
# unbuildable target.
if [ "$ROOT_TARGET" != "$EXPECTED_ROOT_TARGET" ]; then
	echo "check-ci-reach: auditing root target '$ROOT_TARGET', but EXPECTED_ROOT_TARGET pins" >&2
	echo "       '$EXPECTED_ROOT_TARGET'. EXPECTED_CLOSURE_TARGETS describes" >&2
	echo "       '$EXPECTED_ROOT_TARGET' and nothing else, so comparing it to another root's" >&2
	echo "       closure would be a pin judging a set it never described." >&2
	echo "       Either audit '$EXPECTED_ROOT_TARGET', or rename the root and re-pin BOTH" >&2
	echo "       EXPECTED_ROOT_TARGET and EXPECTED_CLOSURE_TARGETS in the same commit" >&2
	echo "       (#pinreachclosure)." >&2
	exit 1
fi

# ------------------------------------------------------- make target extraction

# A Makefile may set .RECIPEPREFIX to something other than tab (lazily-rs uses
# `>`), which puts recipe lines at column 0 where a rule line lives. Without this
# a recipe such as `>cargo test --features a:b` reads as a rule named `>cargo`.
RECIPE_PREFIX="$(awk -F= '/^[[:space:]]*\.RECIPEPREFIX[[:space:]]*[:+]?=/ {
	v = $2; gsub(/^[[:space:]]+|[[:space:]]+$/, "", v); if (v != "") print substr(v, 1, 1); exit
}' Makefile)"

# Prerequisites of a target, straight from the Makefile source, with `\`
# continuations joined and trailing comments removed. Order-only prerequisites are
# dropped: they constrain ordering, not what runs.
prereqs_of() {
	awk -v target="$1" -v rp="$RECIPE_PREFIX" '
		BEGIN { pat = "^" target ":([^=]|$)"; if (rp == "") rp = "\t" }
		{
			line = $0
			# Only the ACTUAL recipe prefix marks a recipe line. Treating any
			# leading whitespace as one loses a rule that is merely indented,
			# which under a non-tab .RECIPEPREFIX is perfectly legal make and
			# collapses the whole closure to a single target. A continuation is
			# exempt: under the default tab prefix a wrapped prerequisite list is
			# normally tab-indented.
			if (!cont && substr(line, 1, 1) == rp) next
			sub(/^[[:space:]]+/, "", line)
			if (cont) {
				buf = buf " " line
				if (line ~ /\\[[:space:]]*$/) next
				cont = 0
				emit(buf)
				exit
			}
			if (line !~ pat) next
			buf = line
			if (line ~ /\\[[:space:]]*$/) { cont = 1; next }
			emit(buf)
			exit
		}
		function emit(s,   rest, n, i, parts) {
			gsub(/\\/, " ", s)
			sub(/#.*$/, "", s)
			rest = substr(s, index(s, ":") + 1)
			sub(/\|.*$/, "", rest)
			n = split(rest, parts, /[[:space:]]+/)
			for (i = 1; i <= n; i++) if (parts[i] != "") print parts[i]
		}
	' Makefile
}

# Is this name an explicit rule in the Makefile?
is_makefile_target() {
	awk -v target="$1" -v rp="$RECIPE_PREFIX" '
		BEGIN { pat = "^" target ":([^=]|$)"; if (rp == "") rp = "\t"; found = 0 }
		substr($0, 1, 1) == rp { next }
		{ line = $0; sub(/^[[:space:]]+/, "", line) }
		line ~ pat { found = 1; exit }
		END { exit found ? 0 : 1 }
	' Makefile
}

# Breadth-first closure of ROOT_TARGET's prerequisites, parents before children.
closure=""
queue="$ROOT_TARGET"
seen=" "
while [ -n "$queue" ]; do
	current="${queue%%$'\n'*}"
	if [ "$current" = "$queue" ]; then queue=""; else queue="${queue#*$'\n'}"; fi
	[ -n "$current" ] || continue
	case "$seen" in
	*" $current "*) continue ;;
	esac
	seen="$seen$current "
	closure="$closure$current"$'\n'
	while IFS= read -r dep; do
		[ -n "$dep" ] || continue
		if is_makefile_target "$dep"; then
			queue="$queue$dep"$'\n'
		fi
	done < <(prereqs_of "$current")
done

# ------------------------------------------------ closure membership, both ways
#
# Set equality against the pin. Both directions are reported separately and BY
# NAME, because they are different mistakes with different remedies:
#
#   pinned but not in the closure -> a gate was dropped from (or renamed in) the
#                                    root's prerequisites. It is no longer run,
#                                    so its CI reach is no longer required.
#   in the closure but not pinned -> a gate was added without being pinned, so
#                                    the next drop would be invisible again.
#
# The remedies are spelled out in both messages on purpose. A pin that can only
# ever be satisfied by editing it teaches people to edit it reflexively, which is
# how a pin becomes a formality — so each message has to distinguish "you broke a
# gate" from "you meant to change the closure".
closure_targets="$(printf '%s' "$closure" | awk 'NF' | LC_ALL=C sort -u)"
pin_only="$(LC_ALL=C comm -23 <(printf '%s\n' "$pinned_targets") <(printf '%s\n' "$closure_targets"))"
closure_only="$(LC_ALL=C comm -13 <(printf '%s\n' "$pinned_targets") <(printf '%s\n' "$closure_targets"))"
pin_only_count="$(count_lines "$pin_only")"
closure_only_count="$(count_lines "$closure_only")"

# The other direction on the excuse list, symmetric with KNOWN_UNCOVERED's
# "lists 'X', which is not in the canonical corpus". An excuse is consulted only
# for targets the closure contains, so an excuse naming anything else was a
# silent no-op: `0 excused`, no complaint, exit 0.
in_closure() {
	local want="$1" line
	while IFS= read -r line; do
		[ "$line" = "$want" ] && return 0
	done <<<"$closure"
	return 1
}

dangling_excuses=""
dangling_excuse_count=0
for excuse_idx in ${excused_targets[@]+"${!excused_targets[@]}"}; do
	if ! in_closure "${excused_targets[$excuse_idx]}"; then
		dangling_excuses="$dangling_excuses${excused_targets[$excuse_idx]}"$'\n'
		dangling_excuse_count=$((dangling_excuse_count + 1))
	fi
done

pin_status=0

if [ "$pin_only_count" -gt 0 ]; then
	echo "check-ci-reach: FAILED — $pin_only_count target(s) pinned in EXPECTED_CLOSURE_TARGETS are" >&2
	echo "       NOT in \`$MAKE_BIN $ROOT_TARGET\`'s prerequisite closure:" >&2
	printf '%s\n' "$pin_only" | awk 'NF { print "  - " $0 }' >&2
	echo "       A pinned target that left the closure means A GATE WAS DROPPED: \`$ROOT_TARGET\`" >&2
	echo "       does not run it any more, so no CI step is required to reach it and this" >&2
	echo "       guard would have approved its absence — one lower number in the OK line and" >&2
	echo "       the missing target never named. Two remedies, and they are not the same:" >&2
	echo "         * you broke a gate -> restore it to \`$ROOT_TARGET:\`'s prerequisites." >&2
	echo "         * you meant it     -> delete it from EXPECTED_CLOSURE_TARGETS in the SAME" >&2
	echo "                               commit that removes the target, and say why there." >&2
	echo "       Editing the pin to turn a red run green is the failure this pin exists to" >&2
	echo "       make visible (#pinreachclosure)." >&2
	pin_status=1
fi

if [ "$closure_only_count" -gt 0 ]; then
	[ "$pin_status" -eq 0 ] || echo >&2
	echo "check-ci-reach: FAILED — $closure_only_count target(s) run by \`$MAKE_BIN $ROOT_TARGET\` are NOT" >&2
	echo "       pinned in EXPECTED_CLOSURE_TARGETS:" >&2
	printf '%s\n' "$closure_only" | awk 'NF { print "  - " $0 }' >&2
	echo "       A gate joined the closure without being pinned. Until it is, it can leave" >&2
	echo "       again silently, which is the hole this pin closes. Add it to" >&2
	echo "       EXPECTED_CLOSURE_TARGETS in scripts/check-ci-reach.sh, keeping the list" >&2
	echo "       sorted, in the same commit that adds the target (#pinreachclosure)." >&2
	pin_status=1
fi

if [ "$dangling_excuse_count" -gt 0 ]; then
	[ "$pin_status" -eq 0 ] || echo >&2
	echo "check-ci-reach: FAILED — $dangling_excuse_count excuse(s) in $CONF name a target that is not" >&2
	echo "       in \`$MAKE_BIN $ROOT_TARGET\`'s closure at all:" >&2
	printf '%s\n' "$dangling_excuses" | awk 'NF { print "  - " $0 }' >&2
	echo "       An excuse is only ever consulted for a target the closure contains, so this" >&2
	echo "       one waived nothing while reading like coverage that was deliberately given" >&2
	echo "       up. Same rule as KNOWN_UNCOVERED, which already refuses an entry that is" >&2
	echo "       not in the canonical corpus. Two remedies:" >&2
	echo "         * the target was renamed or dropped -> restore it, or fix the spelling." >&2
	echo "         * the excuse is dead                -> delete it from $CONF." >&2
	echo "       (#pinreachclosure)" >&2
	pin_status=1
fi

if [ "$pin_status" -ne 0 ]; then
	exit 1
fi

# `make -n` for a target emits its prerequisites' commands first, then its own.
# Asking make for the prerequisite list alone yields exactly that prefix — make
# applies the same de-duplication to both invocations — so removing it leaves the
# target's own recipe. Diagnostics make writes about targets it has nothing to do
# for are not commands and are dropped.
# A recipe line broken across physical lines with `\` reaches the shell as ONE
# command, and make -n prints it the way the Makefile spells it. Joining here is
# what keeps `VAR=x \` + `go test ./...` from being read as two commands, the
# second of which is where the whole gate lives.
join_continuations() {
	awk '
		{
			line = $0
			if (line ~ /\\[[:space:]]*$/) {
				sub(/\\[[:space:]]*$/, "", line)
				buf = buf line " "
				next
			}
			print buf line
			buf = ""
		}
		END { if (buf != "") print buf }
	'
}

dry_run() {
	"$MAKE_BIN" -n "$@" 2>/dev/null | grep -v -e '^make\[' -e '^make:' | join_continuations || true
}

own_commands() {
	local target="$1"
	local deps=()
	local dep_count=0
	while IFS= read -r dep; do
		[ -n "$dep" ] || continue
		if is_makefile_target "$dep"; then
			deps+=("$dep")
			dep_count=$((dep_count + 1))
		fi
	done < <(prereqs_of "$target")

	if [ "$dep_count" -eq 0 ]; then
		dry_run "$target"
		return
	fi
	local prefix
	prefix="$(dry_run "${deps[@]}" | wc -l)"
	dry_run "$target" | tail -n +"$((prefix + 1))"
}

# ------------------------------------------------------------- workflow scraping

# Command lines from every `run:` step. Comment lines inside a run body are
# stripped here — the whole reason this guard is a script.
ci_commands() {
	awk '
		function flush() { if (buf != "") { print buf; buf = "" } }
		{
			line = $0
			indent = match(line, /[^ ]/) - 1
			if (indent < 0) indent = 9999

			if (inblock) {
				if (line ~ /^[[:space:]]*$/) next
				if (indent <= block_indent) { flush(); inblock = 0 }
				else {
					sub(/^[[:space:]]+/, "", line)
					if (substr(line, 1, 1) == "#") next
					if (line ~ /\\[[:space:]]*$/) {
						sub(/\\[[:space:]]*$/, "", line)
						buf = buf " " line
						next
					}
					if (buf != "") { print buf " " line; buf = "" } else print line
					next
				}
			}

			if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*[|>][-+]?[[:space:]]*$/) {
				inblock = 1
				block_indent = indent
				buf = ""
				next
			}
			if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*[^|>[:space:]]/) {
				sub(/^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*/, "", line)
				print line
			}
		}
		END { flush() }
	' "$@"
}

# The same scrape, keyed by the STEP NAME that ran each command
# (#reversereachdirection).
#
# `ci_commands` above flattens every `run:` body in every listed workflow into
# one haystack, and that flat set is what makes the recipe-swap attack work:
# "reached" means "some command ANYWHERE in CI spells this", so a recipe
# repointed at a different step's command is still reached. Keying the haystack
# by step name is what lets EXPECTED_CI_STEPS below ask the narrower question,
# "does the step pinned for THIS target spell it".
#
# A step's name is read from the `name:` of the sequence item the `run:` belongs
# to, and the item boundary — a `- ` at the step's own indent — RESETS it. Without
# that reset an unnamed step would silently inherit the previous step's name (or
# the job's), which is the mis-attribution this mapping cannot afford: it would
# credit one step's command to its neighbour. An unnamed `run:` step is therefore
# reported with the \001unnamed sentinel and refused by the caller rather than
# guessed at.
#
# The name must precede the `run:` inside the item. YAML does not care, this does,
# and the refusal says so — the alternative is a two-pass scrape to buy an
# ordering nothing in this family uses.
ci_step_commands() {
	awk '
		BEGIN { UNNAMED = "\001unnamed" }
		function emit(cmd) { print (step == "" ? UNNAMED : step) "\t" cmd }
		function flush() { if (buf != "") { emit(buf); buf = "" } }
		{
			line = $0
			indent = match(line, /[^ ]/) - 1
			if (indent < 0) indent = 9999

			if (inblock) {
				if (line ~ /^[[:space:]]*$/) next
				if (indent <= block_indent) { flush(); inblock = 0 }
				else {
					sub(/^[[:space:]]+/, "", line)
					if (substr(line, 1, 1) == "#") next
					if (line ~ /\\[[:space:]]*$/) {
						sub(/\\[[:space:]]*$/, "", line)
						buf = buf " " line
						next
					}
					if (buf != "") { emit(buf " " line); buf = "" } else emit(line)
					next
				}
			}

			# A new sequence item is a new step: forget the last name.
			if (line ~ /^[[:space:]]*-[[:space:]]/) step = ""

			if (line ~ /^[[:space:]]*(-[[:space:]]+)?name:[[:space:]]*[^[:space:]]/) {
				nm = line
				sub(/^[[:space:]]*(-[[:space:]]+)?name:[[:space:]]*/, "", nm)
				sub(/[[:space:]]+$/, "", nm)
				step = nm
			}

			if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*[|>][-+]?[[:space:]]*$/) {
				inblock = 1
				block_indent = indent
				buf = ""
				# Remember the name AT THE RUN, so a `name:` on a later item cannot
				# be picked up by a block that is still open when it appears.
				next
			}
			if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*[^|>[:space:]]/) {
				sub(/^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*/, "", line)
				emit(line)
			}
		}
		END { flush() }
	' "$@"
}

# One line per `run:` step: its name (or the \001unnamed sentinel). Separate from
# ci_step_commands because that one emits a line per COMMAND, which cannot answer
# "do two steps share a name" — a six-command step looks like six steps there.
ci_step_list() {
	awk '
		BEGIN { UNNAMED = "\001unnamed" }
		{
			line = $0
			indent = match(line, /[^ ]/) - 1
			if (indent < 0) indent = 9999
			if (inblock) {
				if (line ~ /^[[:space:]]*$/) next
				if (indent > block_indent) next
				inblock = 0
			}
			if (line ~ /^[[:space:]]*-[[:space:]]/) step = ""
			if (line ~ /^[[:space:]]*(-[[:space:]]+)?name:[[:space:]]*[^[:space:]]/) {
				nm = line
				sub(/^[[:space:]]*(-[[:space:]]+)?name:[[:space:]]*/, "", nm)
				sub(/[[:space:]]+$/, "", nm)
				step = nm
			}
			if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*[|>][-+]?[[:space:]]*$/) {
				print (step == "" ? UNNAMED : step)
				inblock = 1
				block_indent = indent
				next
			}
			if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*[^|>[:space:]]/)
				print (step == "" ? UNNAMED : step)
		}
	' "$@"
}

# ------------------------------------------------------------------- normalizing

# Reduce command text to anchors, one per line, each a space-separated token list.
anchors() {
	awk '
		BEGIN {
			# Sentinel for an unresolvable variable reference. Deliberately not a
			# string any real argument can be.
			ANY = "\001any"
			split(": true false echo printf cd pushd popd mkdir rmdir rm cp mv ln touch " \
			      "export unset set local read eval exec trap wait sleep exit return " \
			      "if then else elif fi for while until do done case esac function " \
			      "test [ [[ pwd ls cat head tail sed awk grep egrep fgrep sort uniq " \
			      "wc tr cut paste tee xargs env dirname basename date git", t, / /)
			for (i in t) if (t[i] != "") trivial[t[i]] = 1
		}
		{
			n = split(split_unquoted($0), cmds, /\n/)
			for (i = 1; i <= n; i++) emit(cmds[i])
		}
		# Split on the shell'"'"'s sequencing operators, but ONLY outside quotes. Doing
		# this before quotes are stripped is what stops a `;` inside a message —
		# `echo "missing $(DIR); clone the sibling"` — from being read as a second
		# command and inventing an anchor for a gate that does not exist. That is a
		# false RED, so it costs a real target its verdict.
		function split_unquoted(s,   i, c, nxt, len, inq, q, out) {
			out = ""; inq = 0; q = ""; len = length(s)
			for (i = 1; i <= len; i++) {
				c = substr(s, i, 1)
				if (inq) {
					if (c == q) { inq = 0; q = "" }
					out = out c
					continue
				}
				if (c == "\"" || c == "'"'"'" || c == "`") { inq = 1; q = c; out = out c; continue }
				nxt = substr(s, i + 1, 1)
				if (c == ";") { out = out "\n"; continue }
				if ((c == "&" && nxt == "&") || (c == "|" && nxt == "|")) { out = out "\n"; i++; continue }
				if (c == "|") { out = out "\n"; continue }
				out = out c
			}
			return out
		}
		function emit(cmd,   m, j, tok, out, prog, started, parts) {
			gsub(/[`"'"'"']/, " ", cmd)
			gsub(/\$\(/, " ", cmd)
			gsub(/\$\{/, " ", cmd)
			gsub(/[(){}]/, " ", cmd)
			m = split(cmd, parts, /[[:space:]]+/)
			prog = ""
			out = ""
			started = 0
			for (j = 1; j <= m; j++) {
				tok = parts[j]
				if (tok == "" || tok == "\\") continue
				if (tok ~ /^[0-9]*>>?$/ || tok == "<" || tok ~ /^[0-9]+>&[0-9]+$/) break
				if (!started) {
					if (tok ~ /^[A-Za-z_][A-Za-z0-9_]*=/) continue
					started = 1
					prog = tok
					sub(/.*\//, "", prog)
					if (prog == "" || (prog in trivial)) return
					out = prog
					continue
				}
				if (tok ~ /^-/) {
					sub(/=.*$/, "", tok)
					out = out " " tok
					continue
				}
				if (tok ~ /^\.{1,3}$/ || tok ~ /^\.{1,2}\/\.{0,3}$/) continue
				if (tok ~ /\//) {
					sub(/\/+$/, "", tok)
					sub(/.*\//, "", tok)
					if (tok == "" || tok ~ /^\.{1,3}$/) continue
				}
					# A token that is still a shell/make VARIABLE reference names a
					# value this guard cannot resolve — a CI step spelling a path as
					# "$LAZILY_CONFORMANCE_MANIFEST" and a Makefile recipe spelling the
					# same path through an expanded $(VAR) are the same command. Dropping
					# it (what this used to do) loses the ARGUMENT as well as its value,
					# so `script.sh <path>` no longer matched a CI step that really ran
					# `script.sh "$PATH"` and the target was reported unreachable. That is
					# a false RED, and it cost lazily-cpp a hardcoded second spelling of
					# the path plus a hand-written equality assertion to keep the two in
					# sync — a new drift surface invented to satisfy a guard that exists
					# to detect drift.
					#
					# Emit a WILDCARD instead: one token that matches one token, so arity
					# is preserved. `script.sh $A` still fails against a CI step that
					# passes no argument at all. This is the same looseness the normalizer
					# already applies to paths, which it reduces to basenames — reach is a
					# floor, not equivalence, exactly as the header says.
					if (substr(tok, 1, 1) == "$") { out = out " " ANY; continue }
				out = out " " tok
			}
			if (started && out != "") print out
		}
	'
}

# --------------------------------------------------------------------- matching

ci_raw="$(mktemp)"
ci_anchor="$(mktemp)"
trap 'rm -f "$ci_raw" "$ci_anchor"' EXIT
ci_commands "${workflows[@]}" >"$ci_raw"
anchors <"$ci_raw" | sort -u >"$ci_anchor"

if [ ! -s "$ci_anchor" ]; then
	echo "check-ci-reach: no run: steps found in ${workflows[*]} — a guard with an empty haystack passes everything" >&2
	exit 1
fi

# The same haystack, keyed by step name (#reversereachdirection): one
# `STEP<TAB>ANCHOR` line per anchor, so a target's reach can be asked of the step
# pinned for it instead of of all of CI at once.
ci_step_raw="$(mktemp)"
ci_step_anchor="$(mktemp)"
trap 'rm -f "$ci_raw" "$ci_anchor" "$ci_step_raw" "$ci_step_anchor"' EXIT
ci_step_commands "${workflows[@]}" >"$ci_step_raw"

# An unnamed `run:` step cannot be pinned, and worse, its commands would be
# credited to whichever step named itself last. Refuse it here rather than let the
# mapping describe the wrong step. Adding a `name:` is not a behaviour change.
unnamed_steps="$(awk -F'\t' '$1 == "\001unnamed" { print $2 }' "$ci_step_raw")"
if [ -n "$unnamed_steps" ]; then
	echo "check-ci-reach: FAILED — run: step(s) with no name: in ${workflows[*]}:" >&2
	printf '%s\n' "$unnamed_steps" | awk 'NF { print "  - run: " $0 }' >&2
	echo "       EXPECTED_CI_STEPS maps a gate to the NAME of the step that runs it, so an" >&2
	echo "       unnamed step is unmappable — and its commands would otherwise be credited" >&2
	echo "       to the previous named step, which is worse than unmapped. Give it a" >&2
	echo "       \`name:\`, BEFORE its \`run:\` (a name is not a behaviour change)" >&2
	echo "       (#reversereachdirection)." >&2
	exit 1
fi

while IFS=$'\t' read -r st cmd; do
	[ -n "$st" ] || continue
	printf '%s\n' "$cmd" | anchors | awk -v s="$st" 'NF { print s "\t" $0 }'
done <"$ci_step_raw" | LC_ALL=C sort -u >"$ci_step_anchor"

# The flat haystack and the step-keyed one must hold the same anchors. They are
# two scrapes of the same text, so any difference is a bug in one of them — and a
# step-keyed scrape that quietly dropped a step would make every target pinned to
# it fail for the wrong reason, or (if it dropped them all) make the
# step-scoped check vacuous.
if ! LC_ALL=C diff -q <(cut -f2- "$ci_step_anchor" | LC_ALL=C sort -u) \
	<(LC_ALL=C sort -u "$ci_anchor") >/dev/null; then
	echo "check-ci-reach: FAILED — the flat and step-keyed workflow scrapes disagree about" >&2
	echo "       which anchors CI runs. They read the same run: bodies, so this is a bug in" >&2
	echo "       one of the two scrapers, not a finding about the workflow" >&2
	echo "       (#reversereachdirection)." >&2
	exit 1
fi

# Step names are matched exactly, so they have to be unique among run: steps.
#
# Counted per STEP, not per command: ci_step_raw holds one line per command, so a
# step with six `test -d` lines appears six times there and two steps sharing a
# name are indistinguishable in it. ci_step_list emits one line per run: step.
dup_steps="$(ci_step_list "${workflows[@]}" | LC_ALL=C sort | uniq -d)"
if [ -n "$dup_steps" ]; then
	echo "check-ci-reach: FAILED — two or more run: steps share a name:" >&2
	printf '%s\n' "$dup_steps" | awk 'NF { print "  - " $0 }' >&2
	echo "       EXPECTED_CI_STEPS maps by name, so a shared name makes the pin ambiguous" >&2
	echo "       and this refuses rather than picking one (#reversereachdirection)." >&2
	exit 1
fi

ci_step_names="$(ci_step_list "${workflows[@]}" | LC_ALL=C sort -u | awk 'NF')"

# ------------------------------------------------- the step pin, parsed + checked
pinned_step_targets=()
pinned_step_names=()
pin_count=0
while IFS= read -r entry; do
	[ -n "$entry" ] || continue
	pt="${entry%%=*}"
	pn="${entry#*=}"
	if [ "$pt" = "$entry" ] || [ -z "$pt" ] || [ -z "$pn" ]; then
		echo "check-ci-reach: malformed EXPECTED_CI_STEPS entry '$entry' — expected 'target=step name'" >&2
		exit 1
	fi
	pinned_step_targets+=("$pt")
	pinned_step_names+=("$pn")
	pin_count=$((pin_count + 1))
done < <(printf '%s\n' ${EXPECTED_CI_STEPS[@]+"${EXPECTED_CI_STEPS[@]}"})

# A pinned step that no run: step carries. This is the direct check on the pin
# itself: without it a mapping could name a step that was renamed or deleted, and
# the target pinned to it would fail with a message about its anchors instead of
# about the stale pin.
#
# Matched with awk rather than `grep -qxF`. `grep -q` exits on its first match, so
# under `pipefail` a `printf | grep -q` pipeline can return the WRITER's SIGPIPE
# (141) instead of grep's 0 — a match read as a miss, and here a false RED naming
# a pin that is perfectly good. Eleven step names sit far below the 64KiB pipe
# buffer so it does not arm today, which is exactly the property that makes it a
# trap: it arms when the workflow grows (#lzgrepcpipefail). awk reads all of its
# input and decides in END.
missing_pin_steps=""
for i in "${!pinned_step_names[@]}"; do
	if ! printf '%s\n' "$ci_step_names" |
		awk -v s="${pinned_step_names[$i]}" 'BEGIN { miss = 1 } $0 == s { miss = 0 } END { exit miss }'; then
		missing_pin_steps="$missing_pin_steps${pinned_step_targets[$i]} -> ${pinned_step_names[$i]}"$'\n'
	fi
done
if [ -n "$missing_pin_steps" ]; then
	echo "check-ci-reach: FAILED — EXPECTED_CI_STEPS names step(s) that no run: step in" >&2
	echo "       ${workflows[*]} carries:" >&2
	printf '%s\n' "$missing_pin_steps" | awk 'NF { print "  - " $0 }' >&2
	echo "       Either the step was renamed or deleted (update the pin in the SAME commit" >&2
	echo "       and check the gate still runs), or the pin never matched (#reversereachdirection)." >&2
	exit 1
fi

# The anchors of the step(s) pinned for one target, as a haystack for
# anchor_reached. Empty output is impossible here: the step names are already
# proven to exist, and a named step with no anchor at all cannot be pinned —
# that is the "accounts for at least one anchor" half, checked in the main loop.
pinned_step_haystack() {
	local target="$1" i
	for i in "${!pinned_step_targets[@]}"; do
		if [ "${pinned_step_targets[$i]}" = "$target" ]; then
			awk -F'\t' -v s="${pinned_step_names[$i]}" '$1 == s { print $2 }' "$ci_step_anchor"
		fi
	done
}

pinned_steps_of() {
	local target="$1" i
	for i in "${!pinned_step_targets[@]}"; do
		[ "${pinned_step_targets[$i]}" = "$target" ] && printf '%s\n' "${pinned_step_names[$i]}"
	done
	return 0
}

has_step_pin() {
	local target="$1" i
	for i in "${!pinned_step_targets[@]}"; do
		[ "${pinned_step_targets[$i]}" = "$target" ] && return 0
	done
	return 1
}

# Does the haystack contain a command whose tokens contain this anchor as an
# in-order subsequence? Extra flags and arguments on the haystack side are fine;
# missing ones are not.
#
# The haystack defaults to CI's anchors, and the make-derived oracle below passes
# the root target's own anchors instead. One matcher, deliberately: "the same
# command" has to mean one thing in this guard, or its two halves can disagree
# about whether a gate is the gate.
anchor_reached() {
	awk -v want="$1" '
		BEGIN { ANY = "\001any"; wn = split(want, w, / /) }
		{
			hn = split($0, h, / /)
			wi = 1
			# A wildcard on EITHER side matches, because either side may be the
			# one that spelled the argument through a variable.
			for (hi = 1; hi <= hn && wi <= wn; hi++)
				if (h[hi] == w[wi] || h[hi] == ANY || w[wi] == ANY) wi++
			if (wi > wn) { found = 1; exit }
		}
		END { exit found ? 0 : 1 }
	' "${2:-$ci_anchor}"
}

# CI invoking the target through make counts as reach without any anchor work.
make_invokes() {
	awk -v target="$1" '
		{
			n = split($0, t, / /)
			if (t[1] != "make") next
			for (i = 2; i <= n; i++) if (t[i] == target) { found = 1; exit }
		}
		END { exit found ? 0 : 1 }
	' "$ci_anchor"
}

is_excused() {
	local t="$1" i
	for i in "${!excused_targets[@]}"; do
		[ "${excused_targets[$i]}" = "$t" ] && return 0
	done
	return 1
}

excuse_reason() {
	local t="$1" i
	for i in "${!excused_targets[@]}"; do
		if [ "${excused_targets[$i]}" = "$t" ]; then
			printf '%s' "${excused_reasons[$i]}"
			return
		fi
	done
}

# Refuse a target whose recipe `make -n` cannot even READ (#lzgrepcpipefail).
#
# dry_run() swallows both halves of a make failure: `2>/dev/null` eats the
# "No rule to make target ..." line and the trailing `|| true` eats the exit 2.
# An unbuildable PREREQUISITE therefore reaches the loop below as empty stdout,
# which it reads as "recipe runs no checkable command" — the target drops out of
# `reached` into `no gate` and the guard still reports OK.
#
# Measured on this repo: adding `test: does-not-exist.stamp` to the Makefile
# moved `test` — `./gradlew test` plus the whole conformance ladder — out of
# reached, and the guard still printed `OK — 6 target(s) reached by CI, 0
# excused, 2 carrying no gate` and exited 0. That is a false green produced by an
# ordinary commit which never touches this script; judging this guard against the
# Makefile AS WRITTEN misses it, because the guard's job is to survive the edit
# that changes it. Three sibling bindings reproduced the same hole the same way.
#
# So readability is asserted HERE, in the MAIN shell, before any recipe text is
# interpreted. Deliberately NOT inside dry_run(): that runs inside `$(...)`, so a
# non-zero exit or an `exit 1` there dies in the subshell and the caller still
# sees an empty string — which is the masking this exists to refuse.
#
# An EMPTY recipe stays legitimate (`check` itself carries none, and is reported
# as carrying no gate). This asks the different question of whether make could
# read the recipe at all.
unreadable_count=0
while IFS= read -r target; do
	[ -n "$target" ] || continue
	if make_err="$("$MAKE_BIN" -n "$target" 2>&1 >/dev/null)"; then
		continue
	fi
	unreadable_count=$((unreadable_count + 1))
	printf 'UNREADABLE %-24s %s\n' "$target" "${make_err%%$'\n'*}" >&2
done <<<"$closure"

if [ "$unreadable_count" -gt 0 ]; then
	echo "check-ci-reach: FAILED — \`$MAKE_BIN -n\` cannot read $unreadable_count target(s) above." >&2
	echo "       Their recipes were never examined, so reporting them as carrying no" >&2
	echo "       gate would be a pass over nothing: an unbuildable prerequisite is" >&2
	echo "       missing EVIDENCE, not evidence that a gate is absent" >&2
	echo "       (#lzgrepcpipefail). Fix the Makefile, or the prerequisite it names." >&2
	exit 1
fi

# ------------------------------------------------- the make-derived ORACLE (A)
#
# Everything above this point trusted prereqs_of(), and prereqs_of() reads
# Makefile SOURCE TEXT: the first line matching `^check:`, `\` continuations
# joined. It never asks make, and it does not evaluate conditionals. So the awk
# closure and the closure make actually runs are two different objects, and an
# `ifeq` is enough to separate them:
#
#   ifeq ($(SKIP_SLOW),)
#   check: fmt test test-interop-peer ... ci-reach   # the ONLY ^check: line read
#   else
#   check: fmt test ... ci-reach                     # what make actually parses
#   endif
#
# Measured on this repo under SKIP_SLOW=1: `make -n check` runs no
# `interopPeerCheck`, and this guard still printed `reached test-interop-peer`
# with a verdict byte-identical to healthy — membership pin and all, because a set
# equal to a set that describes nothing describes nothing. A dead `ifeq (0,1)`
# branch does the same with no variable to set. lazily-js found it; this guard is
# ported family-wide, so it held here too.
#
# So the source-derived closure is CONFIRMED against make. For each target, every
# ANCHOR of the commands make would run for it must appear among the anchors of
# the commands make would run for the root. That is the one question make can
# answer cheaply and exactly.
#
# ANCHORS, not raw command lines. Requiring byte-equal lines looks stronger and is
# actually unusable here: this Makefile mints LAZILY_CONFORMANCE_RUN_ID per
# invocation from `date -u +%s%N` and `$$PPID`, and the oracle necessarily runs
# `make -n` once per target plus once for the root — different invocations,
# different ids. The moment any recipe interpolates that id a raw-line oracle
# reddens on EVERY run, including the root against itself. Measured here both
# ways: adding `-Plazily.runId=$(LAZILY_CONFORMANCE_RUN_ID)` to the test recipe,
# and adding the id as a positional argument, each made the raw-line form report
# `check` and `test` as absent from the root's own run. lazily-gd hit exactly this
# on the target carrying its suite. A guard that is red on every invocation gets
# weakened or deleted, and weakening the oracle to stop the red is the real
# failure here — so the comparison uses the normal form this guard already uses to
# decide whether two commands are the same command, which drops flag VALUES and
# reduces paths to basenames.
#
# Two `make -n` invocations must still agree on the recipe TEXT, so the run id is
# pinned to a fixed value for the oracle's own invocations. `$(or ...)` lets an
# inherited value win, so this costs nothing and removes the volatility at its
# source instead of loosening the comparison until it tolerates it — which also
# covers the positional form, where the id is not a flag value and the normalizer
# would keep it. `-n` executes nothing, so no evidence file is stamped with it.
#
# `make -n` only, never `make -p`: -p builds the default goal and dumps the whole
# environment to stdout, which in CI means printing every secret in the job's env
# into the log.
#
# A target with NO anchor is skipped here — there is nothing to confirm, and
# whether being gate-free is legitimate is part C's question, not this one.
oracle_dry_run() {
	env LAZILY_CONFORMANCE_RUN_ID=ci-reach-oracle "$MAKE_BIN" -n "$@" 2>/dev/null |
		grep -v -e '^make\[' -e '^make:' | join_continuations || true
}

root_anchor="$(mktemp)"
trap 'rm -f "$ci_raw" "$ci_anchor" "$root_anchor"' EXIT
oracle_dry_run "$ROOT_TARGET" | anchors | LC_ALL=C sort -u >"$root_anchor"

if [ ! -s "$root_anchor" ]; then
	echo "check-ci-reach: \`$MAKE_BIN -n $ROOT_TARGET\` yielded no anchor at all, so the" >&2
	echo "       oracle would confirm every target against an empty haystack and confirm" >&2
	echo "       anything. A root that runs nothing is the vacuous case this refuses" >&2
	echo "       (#pinreachclosure)." >&2
	exit 1
fi

oracle_missing_count=0
while IFS= read -r target; do
	[ -n "$target" ] || continue
	target_anchor_list="$(oracle_dry_run "$target" | anchors | LC_ALL=C sort -u)"
	[ -n "$target_anchor_list" ] || continue
	absent=""
	while IFS= read -r a; do
		[ -n "$a" ] || continue
		if ! anchor_reached "$a" "$root_anchor"; then
			absent="$absent$a"$'\n'
		fi
	done <<<"$target_anchor_list"
	[ -n "$absent" ] || continue
	oracle_missing_count=$((oracle_missing_count + 1))
	printf 'ORACLE   %-24s in the source closure, but `%s -n %s` does not run its commands\n' \
		"$target" "$MAKE_BIN" "$ROOT_TARGET" >&2
	printf '%s\n' "$absent" | awk 'NF { print "           absent from the root run: " $0 }' >&2
done <<<"$closure"

if [ "$oracle_missing_count" -gt 0 ]; then
	echo >&2
	echo "check-ci-reach: FAILED — the closure read from the Makefile SOURCE is not the" >&2
	echo "       closure \`$MAKE_BIN $ROOT_TARGET\` runs. $oracle_missing_count target(s) above are in the source" >&2
	echo "       closure and their commands are absent from the root's own run, so every" >&2
	echo "       verdict printed about them describes a gate that does not execute — and" >&2
	echo "       EXPECTED_CLOSURE_TARGETS would still match, because it is pinned against" >&2
	echo "       the source closure too." >&2
	echo "       Usual cause: a conditional (\`ifeq\`, \`ifdef\`, a dead branch) around the" >&2
	echo "       prerequisite list, so make parses one list and this guard reads another." >&2
	echo "       Give \`$ROOT_TARGET\` ONE unconditional prerequisite list. If a gate really" >&2
	echo "       must be optional, that is a different target, pinned as itself" >&2
	echo "       (#pinreachclosure)." >&2
	exit 1
fi

printf 'pinned   %s closure target(s) of `%s %s`, exactly matching EXPECTED_CLOSURE_TARGETS and confirmed against `%s -n %s`\n' \
	"${#EXPECTED_CLOSURE_TARGETS[@]}" "$MAKE_BIN" "$ROOT_TARGET" "$MAKE_BIN" "$ROOT_TARGET"

status=0
unreached=""
unreached_count=0
stale=""
stale_count=0
nogate=""
nogate_count=0
reached=0
excused_ok=0
make_invoked_seen=""
anchor_reached_seen=""
idle_pins=""

while IFS= read -r target; do
	[ -n "$target" ] || continue

	target_anchors="$(own_commands "$target" | anchors | sort -u || true)"

	if [ -z "$target_anchors" ]; then
		nogate="$nogate$target"$'\n'
		nogate_count=$((nogate_count + 1))
		continue
	fi

	hit=1
	missing_anchors=""

	# WHICH MODE reaches this target, decided before anything is required of it.
	# The two modes ask different questions, they are pinned as two separate SETS,
	# and neither set is the complement of the other — see
	# EXPECTED_MAKE_INVOKED_TARGETS for the edit that reasoning cost cpp and dart.
	if make_invokes "$target"; then
		mode=make
	else
		mode=anchor
	fi

	if is_excused "$target"; then
		# An excused target is asked the FLAT question on purpose: does CI reach it
		# AT ALL. Step-scoping it would let a stale excuse survive whenever the step
		# that reaches the target is not the step pinned for it — and an excused
		# target carries no pin by the rule below, because an excuse says CI does not
		# run it. So the excuse rungs are deliberately untouched by the step map.
		if [ "$mode" = anchor ]; then
			while IFS= read -r a; do
				[ -n "$a" ] || continue
				anchor_reached "$a" || hit=0
			done <<<"$target_anchors"
		fi
		if [ "$hit" -eq 1 ]; then
			stale="$stale$target"$'\n'
			stale_count=$((stale_count + 1))
		else
			excused_ok=$((excused_ok + 1))
			printf 'excused  %-32s %s\n' "$target" "$(excuse_reason "$target")"
		fi
		continue
	fi

	if [ "$mode" = make ]; then
		# CI runs `make <target>`: reach needs no anchor work, and there is no
		# independent CI-side spelling to pin a step against
		# (#reversereachdirection).
		make_invoked_seen="$make_invoked_seen$target"$'\n'
	else
		anchor_reached_seen="$anchor_reached_seen$target"$'\n'
		if ! has_step_pin "$target"; then
			# Nothing is asked of an unpinned target below, so the pin-set equality
			# check after the loop is what makes this a failure. Fall back to the
			# flat haystack meanwhile, so the target is still reported honestly
			# rather than silently credited.
			while IFS= read -r a; do
				[ -n "$a" ] || continue
				if ! anchor_reached "$a"; then
					hit=0
					missing_anchors="$missing_anchors$a"$'\n'
				fi
			done <<<"$target_anchors"
		else
			step_hay="$(mktemp)"
			pinned_step_haystack "$target" >"$step_hay"
			while IFS= read -r a; do
				[ -n "$a" ] || continue
				if ! anchor_reached "$a" "$step_hay"; then
					hit=0
					missing_anchors="$missing_anchors$a"$'\n'
				fi
			done <<<"$target_anchors"
			rm -f "$step_hay"

			# The other direction: every pinned step must account for at least one
			# of this target's anchors. Without it the pin may name extra steps,
			# and a pin that tolerates extra steps is the flat haystack again, one
			# step at a time — `test=Test` plus every other step in the job passes
			# exactly as the unscoped check did.
			while IFS= read -r st; do
				[ -n "$st" ] || continue
				one_hay="$(mktemp)"
				awk -F'\t' -v s="$st" '$1 == s { print $2 }' "$ci_step_anchor" >"$one_hay"
				used=0
				while IFS= read -r a; do
					[ -n "$a" ] || continue
					if anchor_reached "$a" "$one_hay"; then
						used=1
						break
					fi
				done <<<"$target_anchors"
				rm -f "$one_hay"
				if [ "$used" -eq 0 ]; then
					idle_pins="$idle_pins$target -> $st"$'\n'
				fi
			done < <(pinned_steps_of "$target")
		fi
	fi

	if [ "$hit" -eq 1 ]; then
		reached=$((reached + 1))
		printf 'reached  %s\n' "$target"
	else
		unreached="$unreached$target"$'\n'
		unreached_count=$((unreached_count + 1))
		printf 'MISSING  %s\n' "$target"
		if has_step_pin "$target"; then
			while IFS= read -r st; do
				[ -n "$st" ] || continue
				printf '           pinned CI step: %s\n' "$st"
			done < <(pinned_steps_of "$target")
			while IFS= read -r a; do
				[ -n "$a" ] || continue
				printf '           absent from its pinned step(s): `%s`\n' "$a"
			done <<<"$missing_anchors"
		else
			while IFS= read -r a; do
				[ -n "$a" ] || continue
				printf '           no CI run: step matches `%s`\n' "$a"
			done <<<"$missing_anchors"
		fi
	fi
done <<<"$closure"

while IFS= read -r target; do
	[ -n "$target" ] || continue
	printf 'no gate  %-32s recipe runs no checkable command\n' "$target"
done <<<"$nogate"

# ----------------------------------------------- classification, both ways (C)
#
# "Carries no gate" is the one verdict above that requires NOTHING of a target:
# no CI step, no excuse, no reason. So reclassifying a target into it retires its
# gate while leaving membership, the oracle and the target's name untouched.
# Measured on this repo by replacing `./gradlew interopPeerCheck` with `true`: the
# closure still held 8, the pin still matched, and the guard exited 0 having moved
# the single cross-binding wire-compatibility gate into the bucket that is asked
# for nothing. `true` is on the trivial-program list, so the target contributed no
# anchor and simply stopped being audited.
#
# Pinned by set equality for the same reason as membership, and reported in both
# directions because they are opposite mistakes: one is a gate that stopped
# running, the other is a gate that started.
nogate_targets="$(printf '%s' "$nogate" | awk 'NF' | LC_ALL=C sort -u)"
nogate_unpinned="$(LC_ALL=C comm -13 <(printf '%s\n' "$pinned_nogate") <(printf '%s\n' "$nogate_targets") | awk 'NF')"
nogate_stale="$(LC_ALL=C comm -23 <(printf '%s\n' "$pinned_nogate") <(printf '%s\n' "$nogate_targets") | awk 'NF')"

if [ -n "$nogate_unpinned" ]; then
	echo >&2
	echo "check-ci-reach: FAILED — target(s) carrying no gate that are NOT pinned gate-free" >&2
	echo "       in EXPECTED_NO_GATE_TARGETS:" >&2
	printf '%s\n' "$nogate_unpinned" | awk 'NF { print "  - " $0 }' >&2
	echo "       This target is still in \`$ROOT_TARGET\`'s closure and still pinned, but its" >&2
	echo "       recipe no longer runs anything this guard can check, so nothing is" >&2
	echo "       required of it any more — no CI step, no excuse, no reason. That is how a" >&2
	echo "       gate is retired without the closure changing. Two remedies:" >&2
	echo "         * you neutered a gate -> restore the recipe (a recipe of \`true\`, \`echo\`," >&2
	echo "                                  or a plain file utility carries no gate)." >&2
	echo "         * it is genuinely a no-op now -> add it to EXPECTED_NO_GATE_TARGETS in" >&2
	echo "                                  the SAME commit, and say why there." >&2
	echo "       (#pinreachclosure)" >&2
	status=1
fi

if [ -n "$nogate_stale" ]; then
	echo >&2
	echo "check-ci-reach: FAILED — target(s) pinned gate-free in EXPECTED_NO_GATE_TARGETS" >&2
	echo "       that DO carry a gate now:" >&2
	printf '%s\n' "$nogate_stale" | awk 'NF { print "  - " $0 }' >&2
	echo "       That is progress, not a break: the target gained a real command and is now" >&2
	echo "       audited for CI reach like any other. Remove it from" >&2
	echo "       EXPECTED_NO_GATE_TARGETS so the pin stops understating what this binding" >&2
	echo "       checks (#pinreachclosure)." >&2
	status=1
fi

# ------------------------------- the step pin, both directions (#reversereachdirection)
#
# Pinned by SET EQUALITY against the split the loop above measured, for the same
# reason membership is: without it, a target can move between "CI spells its
# commands" and "CI runs make" — or out of the pinned set entirely — and the
# guard would quietly ask less of it than it did yesterday. A missing pin asks
# nothing, which is the shape every hole this file records had.
#
# TWO SETS, NEITHER THE COMPLEMENT OF THE OTHER. The make-invoked population has
# its own array rather than being "whatever EXPECTED_CI_STEPS does not name", and
# that distinction is the whole of this block. Measured green at exit 0 in cpp and
# dart before they fixed it: change one member's CI step body to
# `make <that member>` AND delete that member's gate-step entry, in ONE edit. Each
# half alone fails; with the population defined as a complement the halves CANCEL,
# because the deleted entry is the very evidence that would have made the mode
# change visible. In dart the only trace was an OK line's count dropping by one,
# which no exit status reflects. dart's formulation: a population pinned only as
# the complement of another pinned population is not pinned against an edit that
# moves both together — A COUNT IS NOT A PIN. Measured here against both shapes,
# each exit 1: `test-interop-peer`'s step body to `make test-interop-peer` with its
# entry deleted, and the same on `test`, the member holding TWO entries, with both
# deleted.
#
# ORDER MATTERS HERE, and cpp paid for getting it wrong. The MODE rungs run FIRST.
# Delete a make-invoked member's CI step outright and the unpinned rung fires too —
# the target is no longer make-invoked, so it wants a pin — and it tells the reader
# to add one, which is the wrong remedy for a deleted step. So the mode change is
# reported first, the unpinned rung is SUPPRESSED for a target that is unreached
# (nothing to pin a step name against, and the unreached rung below carries the
# right remedy), and the stale-mode message says which of the two things happened
# instead of assuming the happier one.
anchor_reached_set="$(printf '%s' "$anchor_reached_seen" | awk 'NF' | LC_ALL=C sort -u)"
make_invoked_set="$(printf '%s' "$make_invoked_seen" | awk 'NF' | LC_ALL=C sort -u)"
unreached_set="$(printf '%s' "$unreached" | awk 'NF' | LC_ALL=C sort -u)"
pinned_step_set="$(printf '%s\n' ${pinned_step_targets[@]+"${pinned_step_targets[@]}"} | awk 'NF' | LC_ALL=C sort -u)"
pinned_make_set="$(printf '%s\n' ${EXPECTED_MAKE_INVOKED_TARGETS[@]+"${EXPECTED_MAKE_INVOKED_TARGETS[@]}"} | awk 'NF' | LC_ALL=C sort -u)"

# --- mode, first -------------------------------------------------------------
make_invoked_unpinned="$(LC_ALL=C comm -13 <(printf '%s\n' "$pinned_make_set") <(printf '%s\n' "$make_invoked_set") | awk 'NF')"
if [ -n "$make_invoked_unpinned" ]; then
	echo >&2
	echo "check-ci-reach: FAILED — target(s) reached only because a CI run: step invokes" >&2
	echo "       \`make <target>\`, not pinned in EXPECTED_MAKE_INVOKED_TARGETS:" >&2
	printf '%s\n' "$make_invoked_unpinned" | awk 'NF { print "  - " $0 }' >&2
	echo "       This is a WEAKER kind of reach than a spelled command — CI's instruction is" >&2
	echo "       \"run the target\", so a repoint of that recipe is invisible from CI. Moving a" >&2
	echo "       gate into it retires what this guard can prove about it, so it has to be" >&2
	echo "       said out loud HERE and not merely implied by the absence of a step pin:" >&2
	echo "       deleting the step pin and flipping the CI step to \`make <target>\` in one" >&2
	echo "       edit is the pair of changes this array exists to keep visible" >&2
	echo "       (#reversereachdirection)." >&2
	status=1
fi

make_invoked_stale="$(LC_ALL=C comm -23 <(printf '%s\n' "$pinned_make_set") <(printf '%s\n' "$make_invoked_set") | awk 'NF')"
if [ -n "$make_invoked_stale" ]; then
	# Which way did it move? A target CI now SPELLS is progress. A target CI no
	# longer reaches at all is a deleted step, and "move it to EXPECTED_CI_STEPS"
	# would be nonsense advice about a step that is gone.
	stale_now_spelled=""
	stale_now_unreached=""
	while IFS= read -r t; do
		[ -n "$t" ] || continue
		if printf '%s\n' "$unreached_set" | awk -v s="$t" 'BEGIN { miss = 1 } $0 == s { miss = 0 } END { exit miss }'; then
			stale_now_unreached="$stale_now_unreached$t"$'\n'
		else
			stale_now_spelled="$stale_now_spelled$t"$'\n'
		fi
	done <<<"$make_invoked_stale"

	if [ -n "$stale_now_unreached" ]; then
		echo >&2
		echo "check-ci-reach: FAILED — target(s) pinned in EXPECTED_MAKE_INVOKED_TARGETS whose" >&2
		echo "       CI step is GONE:" >&2
		printf '%s\n' "$stale_now_unreached" | awk 'NF { print "  - " $0 }' >&2
		echo "       No run: step invokes \`make <target>\` for these any more, and no step spells" >&2
		echo "       their commands either, so CI runs the gate by neither route. The remedy is" >&2
		echo "       to restore the step (or excuse the target with a reason in $CONF) — NOT to" >&2
		echo "       add a step pin, which is what the unpinned rung would otherwise have told" >&2
		echo "       you (#reversereachdirection)." >&2
		status=1
	fi
	if [ -n "$stale_now_spelled" ]; then
		echo >&2
		echo "check-ci-reach: FAILED — target(s) pinned in EXPECTED_MAKE_INVOKED_TARGETS that CI" >&2
		echo "       now reaches by SPELLING their commands instead:" >&2
		printf '%s\n' "$stale_now_spelled" | awk 'NF { print "  - " $0 }' >&2
		echo "       That is progress — a spelled command can be step-pinned like any other" >&2
		echo "       gate. Move it from EXPECTED_MAKE_INVOKED_TARGETS to EXPECTED_CI_STEPS" >&2
		echo "       (#reversereachdirection)." >&2
		status=1
	fi
fi

# --- then the step pin -------------------------------------------------------
unpinned_gates="$(LC_ALL=C comm -13 <(printf '%s\n' "$pinned_step_set") <(printf '%s\n' "$anchor_reached_set") | awk 'NF')"
# Suppressed for a target nothing in CI reaches: there is no step name to pin, and
# the unreached rung below says what to do about it. Safe to suppress because an
# unreached target already fails there, so this can never turn a red into a green.
unpinned_gates="$(LC_ALL=C comm -23 <(printf '%s\n' "$unpinned_gates") <(printf '%s\n' "$unreached_set") | awk 'NF')"
if [ -n "$unpinned_gates" ]; then
	echo >&2
	echo "check-ci-reach: FAILED — target(s) whose commands CI spells but which name no CI" >&2
	echo "       step in EXPECTED_CI_STEPS:" >&2
	printf '%s\n' "$unpinned_gates" | awk 'NF { print "  - " $0 }' >&2
	echo "       An unpinned target is checked against every run: body in the workflow, so" >&2
	echo "       its recipe can be repointed at any other step and stay green. Add" >&2
	echo "       'target=<exact step name>' (#reversereachdirection)." >&2
	status=1
fi

pinned_non_gates="$(LC_ALL=C comm -23 <(printf '%s\n' "$pinned_step_set") <(printf '%s\n' "$anchor_reached_set") | awk 'NF')"
if [ -n "$pinned_non_gates" ]; then
	echo >&2
	echo "check-ci-reach: FAILED — EXPECTED_CI_STEPS pins a step for target(s) that are not" >&2
	echo "       anchor-reached closure members:" >&2
	printf '%s\n' "$pinned_non_gates" | awk 'NF { print "  - " $0 }' >&2
	echo "       Either the target left \`$ROOT_TARGET\`'s closure, or it carries no gate, or it is" >&2
	echo "       excused, or CI reaches it by running \`make <target>\` — in which case it has no" >&2
	echo "       independent CI-side spelling and a step pin for it asserts nothing. Remove the" >&2
	echo "       pin (#reversereachdirection)." >&2
	status=1
fi

if [ -n "$idle_pins" ]; then
	echo >&2
	echo "check-ci-reach: FAILED — pinned CI step(s) that account for none of their target's" >&2
	echo "       anchors:" >&2
	printf '%s\n' "$idle_pins" | awk 'NF { print "  - " $0 }' >&2
	echo "       A pin that may name steps the gate does not use is the flat haystack again," >&2
	echo "       one step at a time. Name only the step(s) that run this target's commands" >&2
	echo "       (#reversereachdirection)." >&2
	status=1
fi

# A guard that examined nothing must not report OK — the same vacuity rule the
# conformance guards apply (#lzvacuousrun).
if [ "$((reached + excused_ok + unreached_count))" -eq 0 ]; then
	echo "check-ci-reach: '$ROOT_TARGET' has no prerequisite target carrying a gate — nothing was verified" >&2
	exit 1
fi

if [ "$stale_count" -gt 0 ]; then
	echo >&2
	while IFS= read -r t; do
		[ -n "$t" ] || continue
		echo "check-ci-reach: '$t' is excused in $CONF but CI DOES reach it — remove the excuse" >&2
	done <<<"$stale"
	status=1
fi

if [ "$unreached_count" -gt 0 ]; then
	echo >&2
	echo "check-ci-reach: $unreached_count target(s) run by 'make $ROOT_TARGET' that no CI run: step reaches:" >&2
	while IFS= read -r t; do
		[ -n "$t" ] || continue
		echo "  - $t" >&2
	done <<<"$unreached"
	echo >&2
	echo "Add a CI step that runs it, or add an excuse with a reason to $CONF." >&2
	status=1
fi

if [ "$status" -eq 0 ]; then
	printf 'pinned   %s gate-free target(s), exactly matching EXPECTED_NO_GATE_TARGETS\n' \
		"$(count_lines "$pinned_nogate")"
	printf 'pinned   %s gate step name(s) for %s anchor-reached target(s) and %s make-invoked target(s), reach checked INSIDE the pinned step\n' \
		"$pin_count" "$(count_lines "$anchor_reached_set")" "$(count_lines "$make_invoked_set")"
	echo "check-ci-reach: OK — $reached target(s) reached by CI, $excused_ok excused, $nogate_count carrying no gate"
fi
exit "$status"
