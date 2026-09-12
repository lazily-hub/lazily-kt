LAKE ?= lake
LAZILY_SPEC_DIR ?= ../lazily-spec
SPEC_CONFORMANCE_DIR ?= $(LAZILY_SPEC_DIR)/conformance
LEAN_SPEC_DIR ?= ../lazily-spec/formal/lean
LEAN_FORMAL_DIR ?= ../lazily-formal

# ONE run id per `make` invocation (#lzstalemanifest).
#
# The conformance evidence files are written by the Gradle test JVM and read by
# a separate shell step, so the two are only connected by bytes on disk. Gradle
# caches `:test` aggressively: with nothing changed it prints
# `> Task :test UP-TO-DATE`, no JVM starts, nothing is written, and the previous
# run's manifest, scenario ledger and block ledger are still sitting there. Every
# rung of the evidence ladder then reads last week's bytes and reports OK about a
# run that never happened — and RTK strips Gradle task lines, so the
# `UP-TO-DATE` that explains it is invisible in captured output.
#
# So the test JVM stamps this value into the first line of every evidence file it
# writes, and every guard that reads one REQUIRES the stamp to equal the id of
# the invocation running the guard. A cached `:test` writes no stamp, the stale
# id stays on disk, and the guard fails by name instead of trusting it.
#
# SIMPLY EXPANDED (`:=`), not recursive: `?=` and `=` would re-run the $(shell)
# at every reference and hand a different id to the Gradle step than to the
# guard, which fails closed but for a confusing reason. An inherited value wins
# (CI sets one per job, because CI runs `./gradlew test` and the guard as two
# separate steps rather than through this file) — `$(or ...)` short-circuits, so
# the $(shell) does not even run in that case.
#
# The consequence is deliberate and worth stating: a SECOND `make check` with
# nothing changed now FAILS. Gradle prints `> Task :test UP-TO-DATE`, no evidence
# is rewritten, and the guard refuses last run's bytes by name. That invocation
# used to exit 0 having replayed nothing at all, which is the bug — so the
# refusal is the fix, not a regression. To check again for real, re-run the suite:
# `./gradlew cleanTest test` (or `./gradlew cleanTest && make check`). CI is
# unaffected: it starts from a fresh checkout, and its own per-job id covers the
# `./gradlew build` that runs the tests and the `./gradlew test` step that is
# UP-TO-DATE behind it.
#
# The value is nanosecond-resolution UTC epoch plus make's own pid.
LAZILY_CONFORMANCE_RUN_ID := $(or $(LAZILY_CONFORMANCE_RUN_ID),make-$(shell date -u +%s%N)-$(shell echo $$PPID))
export LAZILY_CONFORMANCE_RUN_ID

.PHONY: \
	check \
	fmt \
	fmt-fix \
	test \
	test-interop-peer \
	benchmark \
	benchmark-scale \
test-lean-formal \
test-lazily-formal \
assertion-ordering-check \
ci-reach

check: fmt test test-interop-peer test-lean-formal test-lazily-formal assertion-ordering-check ci-reach

assertion-ordering-check:
	python3 ../lazily-spec/scripts/check-assertion-ordering.py --binding kt --root .

# The formatting GATE (#lazilyformattinggate). spotless + ktlint, both versions
# pinned exactly in build.gradle.kts — see the comment there for why pinning the
# plugin alone would leave the style free to move.
#
# spotlessCheck is the gate; spotlessApply writes and is deliberately not in
# `check`. A formatter that rewrites the tree it is judging exits 0 whatever it
# found, which is a gate that cannot fail (#lzruffautofixvacuity).
fmt:
	./gradlew spotlessCheck

fmt-fix:
	./gradlew spotlessApply

# Conformance fixtures are read only from the canonical ../lazily-spec sibling
# (#lzspecconf) — there is no bundled fallback, because a fallback is exactly
# what makes spec drift invisible. The coverage guard then asserts the fixtures
# actually replayed, which an absence guard alone cannot catch.
#
# The absence messages below separate their two clauses with an em dash rather
# than a semicolon on purpose. check-ci-reach.sh splits recipe lines on shell
# sequencing operators before it strips quoting, so a `;` inside a quoted
# message becomes a phantom command — `clone lazily-spec as a sibling ...` was
# reported as an unreachable gate. Keep the prose free of `;` and `&&`.
test:
	test -d "$(SPEC_CONFORMANCE_DIR)" || { echo "missing $(SPEC_CONFORMANCE_DIR) — clone lazily-spec as a sibling or set LAZILY_SPEC_DIR"; exit 1; }
	./gradlew test
	./scripts/check-conformance-coverage.sh

test-interop-peer:
	./gradlew interopPeerCheck

# Reactive-core microbenchmark (parity with lazily-rs benches/context.rs):
# cached reads, cold first get, fan-out, invalidation, memo suppression, effect
# flushing, batch storms, typed cache reads, thread-safe contention.
benchmark:
	./gradlew benchmark

# Spreadsheet-scale benchmark (parity with lazily-rs benches/scale.rs). Default
# N=1,000,000 (~2M reactive nodes). Override with LAZILY_SCALE_N or
# -Plazily.scaleN=<n>.
benchmark-scale:
	./gradlew benchmarkScale

# Build the lazily-spec IPC formal model (Snapshot/Delta state plane + the
# PartialEq/memo/Signal/batch invariants shared by every binding).
test-lean-formal:
	test -d "$(LEAN_SPEC_DIR)" || { echo "missing $(LEAN_SPEC_DIR) — clone lazily-spec as a sibling or set LEAN_SPEC_DIR"; exit 1; }
	cd "$(LEAN_SPEC_DIR)" && $(LAKE) build

# Build the full Harel state-chart + reactive-graph + keyed-collection +
# async-slot formal model (the executable reference behind the conformance
# fixtures lazily-kt replays) in lazily-formal — the neutral formal-artifact
# home every binding depends on equally.
test-lazily-formal:
	test -d "$(LEAN_FORMAL_DIR)" || { echo "missing $(LEAN_FORMAL_DIR) — clone lazily-formal as a sibling or set LEAN_FORMAL_DIR"; exit 1; }
	cd "$(LEAN_FORMAL_DIR)" && $(LAKE) build

# CI-reachability guard (#lzcheckcireachguard). Fails when a target above runs a
# gate no CI workflow step reaches — the drift that hid #lzinteroppeerci in every
# binding for months. It guards itself: `ci-reach` is in `check`, so CI has to run
# it too or this target reports itself missing.
ci-reach:
	./scripts/check-ci-reach.sh
