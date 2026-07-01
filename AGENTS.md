# lazily-kt

Native Kotlin port of the lazily reactive core — a first-class reactive binding
alongside lazily-rs / lazily-py / lazily-zig (no FFI dependency for the reactive
graph). Plus the lazily-spec IPC wire types and an agent-doc state-projection
consumer.

## Architecture

### Reactive core (native, FFI-free)
Mirrors lazily-rs `Context` semantics (single-threaded; `ThreadSafeContext`
counterpart is future work).

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
  named guards (fail-closed), external + internal transitions. `run` actions,
  `{"expr": …}` context guards, and `final`/completion (`done`) are rejected
  explicitly per the spec's implementation-status note. Conformance fixtures in
  `src/test/resources/conformance/statechart/` (mirrored from
  `lazily-spec/conformance/statechart/`) are replayed by
  `StateChartConformanceTest`.

### Wire types + projection consumer
- `Ipc.kt` — native lazily-spec IPC wire types (`Snapshot`, `Delta`, `DeltaOp`,
  `IpcMessage`, `PeerPermissions`), kotlinx-serialization-free hand-rolled JSON.
- `StateGraphMirror.kt` — pure native mirror that applies `snapshot`/`delta`.
- `StateProjectionClient.kt` / `StateProjectionBridgeSupport.kt` — agent-doc
  state-projection consumers.
- `LazilyFFI.kt` — JNA bindings to the agent-doc binary's C-ABI projection
  surface. This is an **optional transport** for consuming authoritative
  projections from the Rust binary; it is independent of the reactive core. A
  state chart or other compute runs natively — never via this FFI channel.

## Commands

```bash
make check   # == ./gradlew test
```

## Related Projects

- `lazily-spec` — canonical wire protocol + state-chart conformance fixtures.
- `lazily-formal` — Lean 4 formal model (Primitive, flat `StateMachine`, full
  Harel `StateChart`) — the executable reference behind the chart fixtures.
- `lazily-rs` / `lazily-py` / `lazily-zig` — sibling reactive cores.

<!-- tsift:code-navigation v=0.1.73 -->
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
