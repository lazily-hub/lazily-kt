# Contributing to lazily-kt

Requires JDK 21 and Gradle; the included wrapper works out of the box. Building
the formal models additionally requires Lean 4 (`elan` / `lake`).

## Verify a change

```bash
make check   # == ./gradlew test + build both Lean formal models
```

`make check` runs the Kotlin test suite and builds the two sibling Lean formal
models lazily-kt is bound to:

- `test-lean-formal` — `lazily-spec/formal/lean`: the IPC Snapshot/Delta state
  plane + the `PartialEq` / memo / Signal / batch invariants every binding
  shares.
- `test-lazily-formal` — `lazily-formal`: the full Harel state chart, the
  reactive-graph kernel (Slot/Cell/Signal/Effect), the keyed collection
  (SourceMap/ComputedMap), the ordered tree (SourceTree), keyed reconciliation
  (LIS), and the async slot state machine — the executable reference behind the
  conformance fixtures lazily-kt replays.

Both targets resolve via `LEAN_SPEC_DIR` / `LEAN_FORMAL_DIR` (defaulting to the
sibling submodule paths) and fail with a clear message if the sibling is absent.

## Core API compatibility

`api/lazily-kt.api` is the JetBrains binary-compatibility-validator snapshot for
the complete JVM artifact. `compat/core-api.json` is the normalized generation-1
Core profile view: it pins the canonical `io.github.lazily` names and also checks
the public inline `source`/`computed` declarations that have no standalone JVM
method for the binary dumper to compare.

`compat/core-deprecations.json` records each legacy single-threaded Core spelling
with its introduction, replacement, two-generation/90-day compatibility window,
and earliest removal generation. `compat/core-modules.json` records that Kotlin's
profile modules ship in the same artifact at generation 1; there is therefore no
independently versioned Kotlin module requiring a second oldest/current artifact
matrix yet.

Run `make core-api-check` after any public API change. It runs the native
`apiCheck`, the normalized metadata gate, and mutation self-tests that prove
removed symbols, changed ABI signatures, incomplete deprecations, and missing
module-generation evidence all turn the gate red. Regenerate the native snapshot
with `./gradlew apiDump` only after reviewing the compatibility impact.
