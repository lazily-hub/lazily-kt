# lazily-kt

Native Kotlin port of the **lazily** reactive core — a first-class reactive
binding alongside [`lazily-rs`][rs], [`lazily-py`][py], and [`lazily-zig`][zig],
with **no FFI dependency** for the reactive graph. Plus the [`lazily-spec`][spec]
IPC wire types, a reactive full-Harel state chart, an `AsyncContext` async
reactive graph, an in-process `ShmBlobArena` blob host, and an agent-doc
state-projection consumer.

`io.github.lazily:lazily` · Kotlin 2.0.21 · JVM 21 · v0.4.0

## The reactive family

lazily-kt mirrors lazily-rs `Context` semantics (single-threaded;
`ThreadSafeContext` counterpart is future work):

- **Slot** — a lazily-computed, memoized derived value. Tracks its dependencies
  automatically, computes on first read, caches, and recomputes only when read
  after an upstream change.
- **Cell** — a mutable source value that invalidates dependent Slots/Signals
  when it changes.
- **Signal** — an *eager* derived value (a memo Slot plus a puller Effect) that
  recomputes the instant a dependency changes — the value is materialized by the
  time `setCell` / `batch` returns, with no intermediate unset value.
- **Effect** — a side-effecting observer that reruns whenever a tracked
  dependency invalidates; a cleanup closure runs before each rerun and on
  dispose.

Values are **lazy by default**; reach for `Signal` when you need eager push
semantics. Handles (`SlotHandle` / `CellHandle` / `SignalHandle` /
`EffectHandle`) are lightweight ids over a shared node table, like lazily-rs.

### Why it behaves the way it does

- **Pull-based, glitch-free refresh** — a slot that reads other slots always
  observes values consistent with the current inputs.
- **`==` guard on `setCell`** — setting an equal value is a no-op (no
  downstream cascade).
- **`memo` adds a `==` guard** — an equal recompute suppresses downstream
  invalidation.
- **`batch` coalesces** invalidations into one effect flush.
- **Dynamic dependencies** — the tracking stack auto-discovers edges on each
  recompute (no stale subscriptions); cycles are detected and throw.

## Usage

```kotlin
import io.github.lazily.Context

val ctx = Context()
val a = ctx.cell(2)
val b = ctx.cell(3)

// Lazy: computes on first read, caches, recomputes only when a or b changes.
val sum = ctx.slot { ctx.getCell(a) + ctx.getCell(b) }
ctx.get(sum) // 5

ctx.setCell(a, 10)
ctx.get(sum) // 13

// Eager: recomputes immediately when a dependency changes.
val parity = ctx.signal { if (ctx.getCell(a) % 2 == 0) "even" else "odd" }
ctx.getSignal(parity) // "even"
ctx.setCell(a, 11)
ctx.getSignal(parity) // "odd" — already updated before the read
```

A side-effecting observer with cleanup:

```kotlin
val handle = ctx.effect {
    val v = ctx.getCell(a)
    // returned closure is the cleanup: runs before the next rerun and on dispose
    { println("a was $v") }
}
ctx.setCell(a, 42)   // reruns the effect (previous cleanup ran first)
ctx.disposeEffect(handle)
```

Coalesce multiple writes into one flush:

```kotlin
ctx.batch {
    setCell(a, 1)
    setCell(b, 2)
} // dependent effects fire once
```

## State machine

`StateMachine<S, E>` is a finite state machine backed by a `Cell`, so any slot,
signal, or effect that reads `state` is invalidated on transition. The
transition is pure `(state, event) -> next?`; returning `null` rejects the event
(guard), and an equal self-transition is accepted but suppressed by the cell's
`==` guard:

```kotlin
val m = StateMachine(ctx, "Red") { s, _: String ->
    when (s) {
        "Red" -> "Green"; "Green" -> "Yellow"; "Yellow" -> "Red"
        else -> null
    }
}
m.send("advance") // true
m.state           // "Green"
m.stateIs("Green") // a Signal<Boolean> that tracks the predicate
```

## State chart

`StateChart` is a Harel/SCXML **hierarchical** state machine — the native
counterpart of [`lazily-formal`][formal]'s `LazilyFormal.StateChart` and
`lazily-rs/src/statechart.rs`. A chart is **compute, not protocol**: it is never
serialized as a distinct wire kind, and its active configuration lives in a
`Cell`, so any slot/signal/effect reading `configuration`, `activeLeaves`, or
`matches` is invalidated on a real transition.

Implemented subset (per the spec's implementation-status note): compound states,
orthogonal (parallel) regions, shallow + deep history (record-on-exit /
restore-on-enter), entry/exit/transition actions (exit innermost-first →
transition → entry outermost-first), named guards (fail-closed), and external +
internal transitions. `run` actions and `{"expr": …}` context guards are
rejected explicitly; `final` states are accepted as leaves (completion/`done`
events are not raised, the deferred slice the spec permits).

```kotlin
import io.github.lazily.Context
import io.github.lazily.ChartDef
import io.github.lazily.StateChart
import kotlinx.serialization.json.Json

val def = ChartDef.fromJson(Json.parseToJsonElement(chartJson))
val ctx = Context()
val chart = StateChart(ctx, def)

chart.activeLeaves(ctx)                       // initial leaves
chart.send(ctx, "TICK", emptyMap())           // true if any transition was taken
chart.matches(ctx, "playing")                 // hierarchical "state-in" predicate
chart.lastActions()                           // exit → transition → entry actions
```

`send` is deterministic by construction — a total function of
`(chart, configuration, history, guards, event)`, mirroring the Lean
`StateChart.send`.

## lazily-spec IPC

`Ipc.kt` implements the language-agnostic [`lazily-spec`][spec] wire types —
`Snapshot`, `Delta`, `DeltaOp` (all seven variants: `CellSet`, `SlotValue`,
`Invalidate`, `NodeAdd`, `NodeRemove`, `EdgeAdd`, `EdgeRemove`), `IpcMessage`,
`NodeState` (`Payload` / `SharedBlob` / `Opaque`), `IpcValue`
(`Inline` / `SharedBlob`), `ShmBlobRef`, `PeerPermissions`, the optional
wire-stable `NodeKey` (`key` field on `NodeSnapshot`/`NodeAdd`, omitted in JSON
when absent), and the multi-writer `CrdtSync` plane (`WireStamp`, `CrdtOp`,
`CrdtSync`, and the `IpcMessage.CrdtSyncMessage` variant). Every type
round-trips the canonical externally-tagged JSON shape via `toJson()` /
`fromJson()`; `IpcMessage` adds `encodeJson()` / `decodeJson()` for direct
transport, and the JSON codec is byte-compatible with lazily-rs.

`StateGraphMirror` is a pure native mirror that applies a `snapshot` and then
`delta` ops to a local node/edge view, and `Snapshot` / `Delta` / `CrdtSync`
expose `filterReadable(permissions, peer)` for per-peer capability filtering.

## Shared-memory blob arena

`ShmBlobArena` is the in-process host for the shared-memory blob plane — the
Kotlin counterpart of `lazily-rs::ShmBlobArena`. It writes a fixed 40-byte LZSH
header (`{ magic, version, header_len, generation, epoch, len, checksum }`,
little-endian) before each payload and validates the header + FNV-1a-64
checksum on read, so `IpcMessage` control frames carry compact `ShmBlobRef`
descriptors instead of embedding large bytes inline. The byte layout and
checksum are identical across lazily-rs / lazily-py / lazily-zig / lazily-kt,
pinned by `conformance/arena_blob.json` (`ShmBlobArenaTest`). This host is
heap-backed (a `ByteArray`); a future transport may back it with a memory-mapped
`ByteBuffer` for true cross-process sharing without changing the contract.

## Async reactive context

`AsyncContext` is a **separate** reactive surface for `suspend`-returning
computations — the Kotlin counterpart of `lazily-rs::AsyncContext` and the
[`lazily-spec`][spec] Async Reactive Context contract. It is **compute, not
protocol**: only resolved slot values cross IPC/FFI as ordinary cell payloads.
Cells are the synchronous input layer (`cell` / `getCell` / `setCell`);
`computedAsync` / `memoAsync` slots and `effectAsync` effects are async. It
implements the full contract: the `Empty` / `Computing` / `Resolved` / `Error`
slot state machine with revision-based stale-completion discard, in-flight
deduplication (concurrent `getAsync` callers share one compute), the five-point
cancellation contract (waiter-cancellation-safe, stale-discard, explicit
cancel, disposal-awaits-cleanups, cleanup-before-body), compute-context
dependency tracking registered before each awaited read, executor-scheduled
serialized async effects, and synchronous `batch` that schedules async reruns
only at the outermost exit. `signalAsync` is the eager (memo slot + puller
effect) counterpart of the synchronous `Signal`.

```kotlin
val ctx = AsyncContext()
val a = ctx.cell(2)
val sum = ctx.computedAsync { getCell(a) + 3 }
ctx.getAsync(sum)             // suspends, computes, caches -> 5
ctx.setCell(a, 10)
ctx.getAsync(sum)             // dependency invalidated -> 13
```

## State-projection consumer (optional FFI)

`StateProjectionClient` and `StateProjectionBridgeSupport` consume the agent-doc
binary's `DocumentStateProjection` over its C ABI. `LazilyFFI` provides the JNA
bindings to that C-ABI surface. This is an **optional transport** for consuming
authoritative projections from the Rust binary — it is independent of the
reactive core. A state chart or any other compute runs natively, never via this
FFI channel (routing chart logic through JNA to a Rust `Context` would be
circular).

## Conformance

lazily-kt replays the shared [`lazily-spec`][spec] conformance fixtures:

- IPC fixtures round-trip through `IpcMessage.fromJson` / `toJson`
  (`IpcConformanceTest`).
- The agent-doc state-projection IPC fixtures
  (`conformance/agent-doc/snapshot_agent_doc_state.json`,
  `conformance/agent-doc/delta_agent_doc_state.json`) decode, round-trip, and
  validate their `type_tag` vocabulary + decoded payload phases
  (`AgentDocStateConformanceTest`).
- The `ShmBlobArena` host fixture (`conformance/arena_blob.json`) is replayed
  byte-for-byte — descriptor, 40-byte LZSH header, payload region, FNV-1a-64
  checksum, and round-trip read (`ShmBlobArenaTest`).
- State-chart fixtures mirrored into
  `src/test/resources/conformance/statechart/` are replayed by
  `StateChartConformanceTest`, asserting `accepted`, `active`, `matches`, and
  `actions` identically to every other binding.
- The Async Reactive Context contract (slot state machine, stale discard,
  cancellation, dependency tracking, effect cleanup ordering, batch) is covered
  by `AsyncContextTest`.

Not yet implemented: the keyed-collection surfaces (`CellFamily` / `CellMap` /
`CellTree` / `reconcile` / `SemTree` / CRDT text), which no other JVM/JS binding
ships either.

## Development

```bash
make check   # == ./gradlew test
```

Requires JDK 21 and Gradle (the included wrapper works out of the box).

## See also

- [`lazily-spec`][spec] — language-agnostic wire protocol + the conformance
  fixtures (IPC and state-chart) every binding replays.
- [`lazily-formal`][formal] — Lean 4 formal model (shared primitives, flat FSM
  kernel, full Harel `StateChart`); the executable reference behind the
  state-chart fixtures and the deterministic `send` lazily-kt inherits.
- [`lazily-rs`][rs] / [`lazily-py`][py] / [`lazily-zig`][zig] — sibling reactive
  cores.

[rs]: https://github.com/lazily-hub/lazily-rs
[py]: https://github.com/lazily-hub/lazily-py
[zig]: https://github.com/lazily-hub/lazily-zig
[spec]: https://github.com/lazily-hub/lazily-spec
[formal]: https://github.com/lazily-hub/lazily-formal
