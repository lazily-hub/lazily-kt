LAKE ?= lake
LEAN_SPEC_DIR ?= ../lazily-spec/formal/lean
LEAN_FORMAL_DIR ?= ../lazily-formal

.PHONY: \
	check \
	test \
	benchmark \
	benchmark-scale \
	test-lean-formal \
	test-lazily-formal

check: test test-lean-formal test-lazily-formal

test:
	./gradlew test

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
	test -d "$(LEAN_SPEC_DIR)" || { echo "missing $(LEAN_SPEC_DIR); clone lazily-spec as a sibling or set LEAN_SPEC_DIR"; exit 1; }
	cd "$(LEAN_SPEC_DIR)" && $(LAKE) build

# Build the full Harel state-chart + reactive-graph + keyed-collection +
# async-slot formal model (the executable reference behind the conformance
# fixtures lazily-kt replays) in lazily-formal — the neutral formal-artifact
# home every binding depends on equally.
test-lazily-formal:
	test -d "$(LEAN_FORMAL_DIR)" || { echo "missing $(LEAN_FORMAL_DIR); clone lazily-formal as a sibling or set LEAN_FORMAL_DIR"; exit 1; }
	cd "$(LEAN_FORMAL_DIR)" && $(LAKE) build
