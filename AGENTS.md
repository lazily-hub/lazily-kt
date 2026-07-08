# lazily-kt

Native Kotlin port of the lazily reactive core — a first-class reactive binding
alongside lazily-rs / lazily-py / lazily-zig (no FFI dependency for the reactive
graph). Plus the lazily-spec IPC wire types and an agent-doc state-projection
consumer.

## Commit & Push

Commit and push completed work at the end of every turn that changed code,
tests, docs, or fixtures — do not leave finished work uncommitted. Run `make
check` first and ensure it is green; stage only the files that belong to the
change (never secrets or private customer names — see the workspace
`runbooks/private-name-hygiene.md`); write a concise commit message in the
repo's existing style; push to the current branch on `origin`. This standing
rule overrides the harness default of "commit only when explicitly asked" for
this repo.

## Architecture

### Reactive core (native, FFI-free)
Mirrors lazily-rs `Context` semantics across all three context layers:
single-threaded base (`Context`), lock-backed thread-safe (`ThreadSafeContext`),
and coroutine-backed async (`AsyncContext`).

- `Context.kt` — `Context`: the reactive dependency graph. Reactive family is
  **Slot** (lazy memoized derived) → **Cell** (mutable source) → **Signal**
  (eager derived), plus **Effect** (side-effecting observer).
  - Pull-based, glitch-free refresh: a slot that reads other slots always
    observes values consistent with the current inputs.
  - `==` (PartialEq) guard on `setCell`: equal value is a no-op.
  - `memo` adds a `==` guard so an equal recompute suppresses downstream.
  - Signal = memo slot + puller effect (eager; value materialized by the time
    `setCell`/`batch` returns).
  - Effect reruns after any tracked dependency invalidates; cleanup closure runs
    before each rerun and on dispose.
  - `batch` coalesces invalidations into one effect flush.
  - Tracking stack auto-discovers dependencies; cycles are detected (throws).
  - Handles (`SlotHandle`/`CellHandle`/`SignalHandle`/`EffectHandle`) are
    lightweight ids over a shared node table, like lazily-rs.
- `StateMachine.kt` — `StateMachine<S, E>`: a reactive FSM backed by a `Cell`
  (the native counterpart of lazily-rs/py/zig). `send`/`state`/`onTransition`/
  `stateIs`. Pure transition `(S, E) -> S?`; `null` rejects (guard); equal
  self-transition suppressed by the cell's `==` guard.
- `StateChart.kt` — `ChartDef` + reactive `StateChart`: the full Harel/SCXML
  chart (compute, never a wire kind), the native counterpart of
  `lazily-formal/LazilyFormal/StateChart.lean` and `lazily-rs/src/statechart.rs`.
  The active configuration lives in a `Cell`; `send` is deterministic by
  construction (a total function of chart + configuration + history + guards +
  event). Implemented subset: compound states, orthogonal (parallel) regions,
  shallow + deep history (record-on-exit / restore-on-enter), entry/exit/
  transition actions (exit innermost-first → transition → entry outermost-first),
  named guards (fail-closed), external + internal transitions. `run` actions
  and `{"expr": …}` context guards are rejected explicitly per the spec's
  implementation-status note; `final` states are accepted as leaves (completion/
  `done` events not raised — the deferred slice the spec permits). Conformance fixtures in
  `src/test/resources/conformance/statechart/` (mirrored from
  `lazily-spec/conformance/statechart/`) are replayed by
  `StateChartConformanceTest`. The agent-doc state-projection IPC fixtures
  (mirrored into `src/test/resources/conformance/agent-doc/`) are replayed by
  `AgentDocStateConformanceTest`, validating the pinned `type_tag` vocabulary and
  decoded payload phases.

### Wire types + projection consumer
- `Ipc.kt` — native lazily-spec IPC wire types (`Snapshot`, `Delta`, `DeltaOp`,
  `IpcMessage`, `PeerPermissions`, optional wire-stable `NodeKey` `key` field,
  and the multi-writer `CrdtSync` plane: `WireStamp`/`CrdtOp`/`CrdtSync` +
  `IpcMessage.CrdtSyncMessage`), kotlinx-serialization-free hand-rolled JSON
  that is byte-compatible with lazily-rs.
- `ShmBlobArena.kt` — in-process host for the shared-memory blob plane
  (counterpart of `lazily-rs::ShmBlobArena`): 40-byte LZSH header + FNV-1a-64,
  byte-compatible across rs/py/zig/kt (pinned by `arena_blob.json`).
- `AsyncContext.kt` — async reactive graph (counterpart of
  `lazily-rs::AsyncContext`, `lazily-spec/docs/async.md`): the
  `Empty`/`Computing`/`Resolved`/`Error` slot state machine with revision-based
  stale discard, in-flight dedup, the five-point cancellation contract,
  compute-context dependency tracking (registered before each awaited read),
  serialized executor-scheduled async effects (`effect_async`/`signal_async`),
  and synchronous `batch`. Coroutine-backed (`kotlinx.coroutines`).
- `ThreadSafeContext.kt` — lock-backed thread-safe reactive graph (counterpart
  of `lazily-rs::ThreadSafeContext`, the spec's `thread_safe = host` layer): a
  single `ReentrantLock` serializes every graph mutation/read so observers fire
  synchronously within the invalidating `setCell`/`batch` preserving glitch-free
  pull-based ordering (JVM monitor happens-before = Rust `Send + Sync`);
  clonable value-class handles; per-thread (`ThreadLocal`) dependency tracking;
  reentrant callbacks; atomic cross-thread `batch`. `ThreadSafeStateMachine.kt`
  mirrors `StateMachine` over it (flat FSM safe from any sharing thread).
- `StateGraphMirror.kt` — pure native mirror that applies `snapshot`/`delta`.
- `StateProjectionClient.kt` / `StateProjectionBridgeSupport.kt` — agent-doc
  state-projection consumers.
- `LazilyFFI.kt` — JNA bindings to the agent-doc binary's C-ABI projection
  surface. This is an **optional transport** for consuming authoritative
  projections from the Rust binary; it is independent of the reactive core. A
  state chart or other compute runs natively — never via this FFI channel.
- `Collections.kt` — native keyed cell collections layer (`CellMap` + the
  `CellFamily` factory, `CellTree` ordered keyed tree, move-minimized LIS
  `reconcile`): a composition of cells (not a new cell kind) with independent
  value / set-membership / order reactivity, stable handles, and atomic move.
  Conformance fixtures in `conformance/collections/` (loaded from the sibling
  `lazily-spec` submodule) are replayed by `CollectionsConformanceTest`.
- `Crdt.kt` — distributed CRDT cell plane **runtime** (`#lzcrdtplane5b`): the
  `merge: crdt` ingress mechanism — HLC `CrdtClock`, per-peer `StampFrontier`,
  causal-stability watermark, GC contract, LWW / MV / PN-counter registers, and
  `ReplicatedCell` ingress into a reactive root cell (idempotent / commutative /
  associative merge; local edits mint `CrdtOp`s, remote ops merge and feed the
  converged value as an ordinary equality-guarded cell update).
- `LazilyFfiBoundary.kt` — lazily-kt's **own** C-ABI FFI host boundary
  (`LazilyFfiBytes` / `LazilyFfiStatus` / `LazilyFfiMessageKind` incl.
  `CrdtSync = 3`, decode→`IpcMessage`→canonical JSON re-encode, panic-guarded).
  The JVM channel is conformance-tested; the `extern "C"` symbols
  (`src/main/resources/native/lazily_ffi.h`) export via a Graal native-image
  build or the JNI shim. lazily-kt declares the `ffi = host` capability.
- `SeqCrdt.kt` — move-aware mergeable ordered sequence CRDT (`#lzseqcrdt`):
  per-element independent LWW registers (value, fractional-index position,
  tombstone); a move is a single LWW reassign (not delete+reinsert), so
  concurrent moves converge without duplication. Caller-driven HLC.
- `TextCrdt.kt` — Fugue/RGA character CRDT (`#lztextcrdt`) for concurrent
  free-text edits: OpId + left-origin tree, deterministic order, sticky
  tombstones, causally-stable GC. Delta sync (`#lztextsync`):
  `versionVector` / `deltaSince` / `applyDelta` — a whole-state snapshot is
  `deltaSince({})`, and `applyDelta`-ing it rebuilds a mergeable replica
  (OpIds preserved), pinned by the `textcrdt_delta_sync.json` fixture.
- `SemTree.kt` — memoized semantic tree over a `CellTree` (`#lzsemtree`): one
  memo slot per node; an edit recomputes only the ancestor chain and the memo
  guard stops propagation when the fold is unchanged.
- `StableId.kt` — manufactured identity for markdown text (`#lzstableid`):
  anchors, normalized content hashes, word-LCS similarity alignment,
  `assignStable_keys` flow through edits.
- The five CRDT/semantic collection fixtures (`seqcrdt_convergence`,
  `textcrdt_convergence`, `textcrdt_delta_sync`, `semtree_incremental`,
  `stableid_alignment`) are replayed by `CollectionsCrdtConformanceTest`;
  together with `CollectionsConformanceTest` they cover all eight
  `conformance/collections/` fixtures.

### Benchmarks
- `Benchmarks.kt` — reactive-core microbenchmark harness (parity with lazily-rs
  `benches/context.rs`): cached reads, cold first get, dependency fan-out,
  set-cell invalidation, memo equality suppression, effect flushing, batch
  storms, typed cache reads, and thread-safe contention. Wall-clock with a JIT
  warmup phase; mirrors the lazily-py `Benchmark`/`BenchmarkResult`/`timeOp`
  shape.
- `ScaleBench.kt` — spreadsheet-scale bench (parity with lazily-rs
  `benches/scale.rs`): `build` / `cold_full_recalc` / `viewport_recalc` /
  `full_recalc_invalidate_all` on a graph of up to 1,000,000 cells. Run with
  `make benchmark-scale`; override `N` via `LAZILY_SCALE_N`.
- `BENCHMARKS.md` — captured results + reproduction notes.

## Commands

```bash
make check   # == ./gradlew test + test-lean-formal + test-lazily-formal
make test    # ./gradlew test (Kotlin suite only)
make benchmark        # reactive-core micro-bench (parity with context.rs)
make benchmark-scale  # spreadsheet-scale bench, default N=1,000,000 (scale.rs)
make test-lean-formal   # build lazily-spec/formal/lean (IPC state-plane proofs)
make test-lazily-formal # build lazily-formal (state-chart + reactive + collection formal model)
```

`make check` builds the two sibling Lean formal models lazily-kt is bound to
(`lazily-spec/formal/lean` and `lazily-formal`), mirroring the lazily-rs
conformance gate. They default to `../lazily-spec/formal/lean` and
`../lazily-formal` and can be redirected via `LEAN_SPEC_DIR` / `LEAN_FORMAL_DIR`.

## Related Projects

- `lazily-spec` — canonical wire protocol + state-chart conformance fixtures.
- `lazily-formal` — Lean 4 formal model (Primitive, flat `StateMachine`, full
  Harel `StateChart`) — the executable reference behind the chart fixtures.
- `lazily-rs` / `lazily-py` / `lazily-zig` — sibling reactive cores.

<!-- tsift:code-navigation v=0.1.74 -->
## Code Navigation

Keep this block self-contained for Codex/OpenCode prompt reuse. If this repository also ships current `.claude/skills/tsift/SKILL.md` or `runbooks/code-navigation.md`, use those deeper runbooks for command detail instead of expanding this block.

Run `tsift status` at session start from the owning repo root. If the task or file lives under a git submodule (for example `src/tsift/...`), switch to that submodule root first so the harness loads the narrower local instructions and repo state instead of the superproject root. If status prints a `run:` recommendation for stale or missing tsift state, run `tsift status --fix` before relying on tsift results; when the harness cannot perform write commands, ask the user to run the printed command instead. Codex projects can install a prompt-time auto-reindex hook with `tsift init --codex`; OpenCode projects can install per-project tsift command shortcuts with `tsift init --opencode`.

Use the commands listed in its `use:` output:
- `tsift --envelope source-read <file> --budget normal` — AST-symbol projection with span metadata and source-window expansion commands (prefer over cat/head for source code files)
- `tsift --envelope symbol-read <symbol> --budget normal` — token-budgeted symbol body, AST span metadata, child refs, and graph/source expansion commands
- `tsift --envelope search <query> --budget normal` — AST-aware hybrid search preview (prefer over grep/rg)
- `tsift --envelope explain <symbol> --budget normal` — callers, callees, community preview
- `tsift graph <symbol> --callers` / `--callees` — call graph navigation
- `tsift summarize <symbol>` — cached summary (only when listed in `use:`)
- `tsift workflow search` — ordered exact/search/explain/summarize/digest recipe that preserves result handles across expansions

When a search envelope includes `report.scale_guard`, run one of its `narrow_commands` before dispatching parallel agents. The guard means the original result set or corpus is broad enough that fan-out should start from a narrower cited handle, path, or exact query.

Prefer bounded digest commands over raw transcript, diff, and verbose-log reads:
- `tsift --envelope session-review <path> --next-context --budget normal` or `tsift --envelope context-pack <path> --budget normal` instead of replaying long session docs, JSONL transcripts, or agent-doc runtime logs with `cat`, `tail`, or `sed`.
- `tsift diff-digest [path]` (`--cached`, `--revision <rev>`) instead of `git diff`, `git show`, or patch-style `git log`.
- `tsift --envelope digest-runner --kind test --path . --shell-command '<test command>'` / `tsift --envelope digest-runner --kind log --path . --shell-command '<build command>'` for noisy test/build/install output, or let the rewrite/hooks create those artifact-backed envelopes for `cargo test`, `pytest`, and verbose cargo commands.
- If RTK is installed, digest-runner delegates supported generic command families through `rtk rewrite` and records the chosen compact filter in `report.filter` while preserving tsift artifact handles.
- Codex, OpenCode, and other harnesses without Claude-style `PreToolUse` hooks should run `tsift rewrite --run '<command>'` before broad `rg`/recursive grep, raw transcript/session/log reads, `git diff`/`git show`/single-patch `git log`, `cargo test`/`pytest`, and cargo build/check/clippy/install commands so the same search, session-digest, diff-digest, and digest-runner rewrites apply manually. OpenCode can install this path as `/tsift-rewrite-run` with `tsift init --opencode`.

For local verification, run `make check` before committing. After local changes, check the latest GitHub Actions CI run with `gh run list --workflow CI --limit 1` and fix any failing tests before calling the work complete.

Only read full source files when tsift results are insufficient.
<!-- /tsift:code-navigation -->
