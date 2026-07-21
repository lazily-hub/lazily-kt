# lazily-kt

Native Kotlin port of the **lazily** reactive core — a first-class reactive
binding alongside [`lazily-rs`][rs], [`lazily-py`][py], and [`lazily-zig`][zig],
with **no FFI dependency** for the reactive graph. Plus the [`lazily-spec`][spec]
IPC wire types, a reactive full-Harel state chart, an `AsyncContext` async
reactive graph, a lock-backed `ThreadSafeContext`, an in-process `ShmBlobArena`
blob host, and an agent-doc state-projection consumer.

`io.github.lazily:lazily` · Kotlin 2.0.21 · JVM 21 · v0.29.0

## Feature Set

The full `lazily` capability set and its cross-language coverage across every
binding. Legend: ✅ shipped · `~` partial · `—` absent or not applicable. The
canonical matrix with per-cell notes and platform carve-outs lives in
[`lazily-spec` § Cross-Language Coverage](https://github.com/lazily-hub/lazily-spec/blob/main/docs/coverage.md).

<!-- coverage-table:start -->
| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: |
| Reactive graph — kernel `Cell<T, K>` (`SourceCell` / `FormulaCell` / `Effect`) + driven `FormulaCell` (`formula().drive()`) / guarded formulas / batch | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Keyed-map materialization (`SlotMap`) — mint-on-access derived slots: transparency + deferral (`#lzmatmode`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Thread-safe keyed map (`ThreadSafeSlotMap`) — `Send + Sync` + materialization confluence (`#lzmatmode`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Async keyed map (`AsyncSlotMap`) — eventual transparency (`#lzmatmode`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Keyed-map sync — membership propagation + materialize-on-ingest + derived-aggregate transparency (`#lzfamilysync`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Thread-safe context (lock-backed) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Async reactive context | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Flat state machine | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Harel state charts | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Keyed reactive maps (`ReactiveMap`: `CellMap` / `SlotMap`) + `CellTree` + reconcile | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Memoized semantic tree (`SemTree`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Stable-id alignment (manufactured identity) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reactive queue (`QueueCell` SPSC/MPSC + `QueueStorage` adapter) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Broadcast topic (`TopicCell`) — independent cursors + durable replay + safe GC (`#lztopiccell`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Competing-consumer work queue (`WorkQueueCell`) — exclusive leases + ack/nack + redelivery + DLQ (`#lzworkqueue`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Merge algebra + `SourceCell<T, M>` — associative `MergePolicy` (`KeepLatest`/`Sum`/`Max`/`SetUnion`/`RawFifo`), `Cell ≡ SourceCell<KeepLatest>`, read-genus/write-`Source<M>` split (`#relaycell`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| RelayCell — conflating relay + `BackpressurePolicy` + `SpillStore` + `Transport` + Inbox/Outbox + Rate/Window/Expiry/Priority/keyed policies (`#relaycell`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Free-text character CRDT (`TextCrdt`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `TextCrdt` delta sync (`version_vector` / `delta_since` / `apply_delta`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `CrdtTree` lossless document contract (`#lzcrdttree`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Move-aware sequence CRDT (`SeqCrdt`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Lossless tree CRDT core (`LosslessTreeCrdt`, M1) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Lossless tree — dotted-frontier anti-entropy | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Lossless tree — concurrent merge convergence | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Registers (LWW / MV) + `PnCounter` + `CellCrdt` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| IPC wire — `Snapshot` + `Delta` + `CrdtSync` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Shared-memory blob path (`ShmBlobArena`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Cross-process zero-copy transport (`BlobBackend` / shm / arrow) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Distributed CRDT plane (`CrdtPlaneRuntime` / anti-entropy) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reliable sync — resync coordinator + at-least-once durable outbox + OR-set/LWW liveness (`#lzsync`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Storage-independent durable outbox (`OutboxStore` + shared outbox protocol; SQLite/Room/IndexedDB/file adapters) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reliable-sync transport seam + full-duplex `SyncDriver` loop (`IpcSink`/`IpcSource`, `#sync-driver`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Distributed plane — WebRTC transport + signaling | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| State projection / mirror | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Causal receipts (`CausalReceipts` outcome projection) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Message-passing + RPC command plane (`command-plane-v1`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| C-ABI FFI boundary | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Permission boundary (`PeerPermissions` / `RemoteOp`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Capability negotiation (`SessionHandshake`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Instrumentation / benchmarks | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Temporal sources — `TimerCell` / `IntervalCell` / `CronCell` / `DeadlineCell` over a logical clock (`#lztime`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Rate-shaping operators — `DebounceCell` / `ThrottleCell` / `SampleCell` / `ProbabilisticSampleCell` (`#lzrateshape`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Membership + failure detection — `MembershipCell` (SWIM + Phi-accrual) / `PeerSet` / `PeerChangeEvent` (`#lzmemb`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Distributed coordination — `LeaseCell` / `LeaderCell` / `LockCell` / `SemaphoreCell` / `BarrierCell`+`QuorumCell` (`#lzcoord`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Presence + ephemeral plane — `PresenceCell` / `AwarenessCell` / `EphemeralCell` + `Ephemeral`/`Durable` markers (`#lzpresence`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Stream windowing — `TumblingWindow` / `SlidingWindow` / `SessionWindow` over the merge algebra (`#lzwindow`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Fault tolerance — `CircuitBreakerCell` / `RetryPolicyCell` / `BulkheadCell` / `TimeoutCell` (`#lzresilience`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Embedded-service plane — `HealthCell` / `ReadinessCell` / `DiscoveryCell` / `ServiceRegistry` (`#lzservice`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
<!-- coverage-table:end -->

CRDT convergence and the wire protocol are pinned by the shared conformance fixtures
and JSON Schemas in `lazily-spec` and the Lean models in `lazily-formal`.
## The reactive family

lazily-kt mirrors lazily-rs `Context` semantics across all three context layers
— single-threaded base, lock-backed thread-safe, and coroutine-backed async:

- **Slot** — a lazily-computed, memoized derived value. Tracks its dependencies
  automatically, computes on first read, caches, and recomputes only when read
  after an upstream change.
- **Cell** — a mutable source value that invalidates dependent Slots/Signals
  when it changes.
- **Effect** — a side-effecting observer that reruns whenever a tracked
  dependency invalidates; a cleanup closure runs before each rerun and on
  dispose.

The core primitives are **Cell** / **Slot** / **Effect**. **`Signal` is a
derived construct, not a core primitive** — `Signal ≡ Slot.eager`, a memo Slot
plus a puller Effect that recomputes the instant a dependency changes, so its
value is materialized by the time `setCell` / `batch` returns (no intermediate
unset value).

Values are **lazy by default**; reach for the derived `Signal` when you need
eager push semantics. Handles (`SlotHandle` / `CellHandle` / `SignalHandle` /
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
- State-chart fixtures read from the canonical sibling
  `../lazily-spec/conformance/statechart/` are replayed by
  `StateChartConformanceTest`, asserting `accepted`, `active`, `matches`, and
  `actions` identically to every other binding.
- The Async Reactive Context contract (slot state machine, stale discard,
  cancellation, dependency tracking, effect cleanup ordering, batch) is covered
  by `AsyncContextTest`.
- The Thread-safe Reactive Context contract (lock-backed Cell/Slot/Signal/Effect,
  `==`/memo guards, glitch-free refresh, synchronous eager flush, reentrant
  callbacks, atomic cross-thread `batch`, clonable handles, per-thread dependency
  tracking) — the spec's `thread_safe = host` row — is covered by
  `ThreadSafeContextTest`, including multi-thread convergence, cross-thread
  handle reads, and a thread-safe `ThreadSafeStateMachine`.
- The keyed cell collections layer (`CellMap` / `SlotMap` / `CellTree` /
  keyed reconciliation) replays the shared `conformance/collections/` fixtures
  (`CollectionsConformanceTest`) — value / set-membership / order reactivity
  independence, stable handles, and atomic move.
- The CRDT and semantic-tree collection models replay the remaining shared
  `conformance/collections/` fixtures (`CollectionsCrdtConformanceTest`) — the
  move-aware sequence CRDT (`seqcrdt_convergence`), the Fugue/RGA character CRDT
  (`textcrdt_convergence`), the memoized semantic tree (`semtree_incremental`),
  and manufactured text identity (`stableid_alignment`). All seven
  `conformance/collections/` fixtures are now replayed, covering the full
  [Binding Conformance Matrix](https://github.com/lazily-hub/lazily-spec/blob/main/protocol.md#binding-conformance-matrix)
  keyed-collections + CRDT rows.
- The reactive queue (`QueueCell` SPSC + MPSC-via-`batch()` usage rule +
  `QueueStorage` adapter) replays the five `queuecell_*.json` fixtures
  (`QueueCellConformanceTest`) — SPSC total FIFO, popped-head reader-kind
  independence, MPSC multi-writer inside `batch()`, bounded reactive
  backpressure (`is_full`), and the closure lifecycle.
- The distributed CRDT plane runtime (LWW / MV / PN-counter registers, HLC
  clock, `StampFrontier`, causal-stability watermark, idempotent ingress into a
  reactive root cell) is covered by `CrdtRuntimeTest`.
- The C-ABI FFI host boundary (`LazilyFfiBytes` / `LazilyFfiStatus` /
  `LazilyFfiMessageKind` incl. `CrdtSync = 3`, decode→`IpcMessage`→canonical
  JSON re-encode, panic-guarded) is covered by `LazilyFfiBoundaryTest`.

Not yet implemented: the `ffi = host` symbol export is provided as a JVM
embeddable channel + C header + JNI-ready native entry table ([`src/main/resources/native/lazily_ffi.h`](src/main/resources/native/lazily_ffi.h)); real `extern "C"` symbol export ships via a Graal native-image build of the artifact.

## Thread-safe reactive context

`ThreadSafeContext` ([`ThreadSafeContext.kt`](src/main/kotlin/io/github/lazily/ThreadSafeContext.kt))
is the lock-backed counterpart of `Context` — the
[`thread_safe` capability](https://github.com/lazily-hub/lazily-spec/blob/main/protocol.md#concurrency-layers-are-required)
the spec requires of any binding whose platform exposes preemptive
multi-threading. The JVM/Kotlin runtime structurally supports OS threads and a
shared heap, so lazily-kt declares `thread_safe = host` (not `none`).

It satisfies the spec contract: a single `ReentrantLock` serializes every graph
mutation and read, so observers fire **synchronously within the invalidating
`setCell`/`batch`**, preserving glitch-free pull-based ordering. The JVM memory
model's monitor happens-before guarantee is the counterpart of Rust's
`Send + Sync` obligation. Handles (`ThreadSafeSlotHandle` /
`ThreadSafeCellHandle` / `ThreadSafeEffectHandle` / `ThreadSafeSignalHandle`)
are value classes — clonable by value — so a handle minted on one thread may be
read on another through the shared context. A `ThreadLocal` tracking stack
mirrors lazily-rs's `thread_local!` tracking, so two threads computing
concurrently never mix their dependency edges. `ReentrantLock` is reentrant, so a
compute/effect callback that re-enters the same context (e.g. a slot reading
another slot) does not self-deadlock. `batch` runs its whole block under the
lock, so a batch is atomic across threads.

```kotlin
val ctx = ThreadSafeContext()
val src = ctx.cell(1)
val doubled = ctx.signal { ctx.getCell(src) * 2 }  // eager, materialized
val eff = ctx.effect { println("now ${ctx.get(doubled)}"); null }

// From any thread: clonable handle, synchronous observer.
ctx.setCell(src, 21)   // observer fires synchronously before this returns
```

`ThreadSafeStateMachine` mirrors `StateMachine` over a `ThreadSafeContext` — the
flat FSM whose `send`/`state`/`onTransition`/`stateIs` are safe to call from any
thread sharing the context.

## CRDT sequence / text + manufactured identity + semantic tree

Beyond the single-value register plane, lazily-kt implements the cell-model's
mergeable sequence and text surfaces — the native counterparts of the
`lazily-rs` models and the `conformance/collections/` compute fixtures:

- **`SeqCrdt`** — a move-aware, mergeable ordered sequence (`#lzseqcrdt`). Each
  element is three independent LWW registers (value, fractional-index position,
  tombstone). A move is a *single* LWW reassignment of position (not
  delete+reinsert), so concurrent moves of the same element converge to the
  later stamp without duplication, and a concurrent move + value edit both
  apply. Merge is commutative, associative, and idempotent; the caller-driven
  HLC keeps behaviour deterministic.
- **`TextCrdt`** — a Fugue/RGA-style character CRDT (`#lztextcrdt`) for
  concurrent free-text edits. Each character is an element with a unique `OpId`
  + left-origin; order is a pure deterministic function of the element set, so
  merge (a union of elements, tombstones sticky) converges regardless of
  delivery order. Includes causally-stable tombstone GC. It also implements the
  `CrdtTree` document contract: identity-preserving merge, version-vector delta,
  empty-frontier snapshot, and materialized value share one state model.
- **`SemTree`** — a memoized semantic derivation over a `CellTree` (`#lzsemtree`).
  One memo slot per node folds `(node value, child derived values)`; editing one
  node recomputes only its **ancestor chain** (a sibling subtree stays cached),
  and the memo guard stops propagation when the folded result is unchanged.
- **`StableId`** — manufactured identity for markdown text (`#lzstableid`):
  in-band anchors (survive body rewrite), content-derived hashes of normalized
  text (survive reflow/reorder), and word-LCS similarity alignment (≥ 0.5 ⇒
  `Edited`, else `Inserted`). `assignStableKeys` flows identity through an edit
  so the reconciler emits `Update`, not remove+insert.

## Durable outbox stores

`io.github.lazily.outbox.Outbox<S>` owns the shared append/ack/prune/replay
protocol while `OutboxStore` supplies ordered byte persistence. The default
`InMemoryOutbox` follows that same protocol. Android hosts can implement the
small `RoomOutboxDao` boundary and wrap it in `RoomStore`; Room annotations and
database ownership stay in the application, so the portable JVM artifact does
not acquire an Android dependency.

## Keyed cell collections

`CellMap` / `SlotMap` and `CellTree` are the native
implementation of the [`lazily-spec`][spec] keyed cell collections layer
([Cell Model § Keyed cell collections](https://github.com/lazily-hub/lazily-spec/blob/main/cell-model.md#keyed-cell-collections)) — a **composition of cells**, not a new cell kind. Each entry is an ordinary cell; a dedicated membership cell tracks the key set; a dedicated order cell tracks the ordered key list. The three reactive planes are independent by construction:

- writing an entry value invalidates only that entry's value readers;
- inserting / removing a key invalidates membership + order readers, never unrelated entry value readers;
- a pure reorder (atomic move) invalidates order readers only — membership readers (`len` / `contains`) and value readers are untouched, and the moved entry keeps its same cell handle (not remove + re-mint).

```kotlin
val ctx = Context()
val map = CellMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3))

map.setValue("a", 10)        // value reader of "a" only
map.insert("d", 4)           // membership + order readers
map.moveTo("b", 3)           // order reader only; "b" keeps its handle
map.keysNow()                // [a, c, d, b]
```

`reconcile(prior, target)` diffs two keyed sequences **by stable key**, emitting
the minimal move-minimized `{insert, remove, move, update}` op set (longest-
increasing-subsequence over prior indices preserved); applying it to a live
`CellMap` keeps stable entries' value cells un-invalidated. `CellTree` composes
the same guarantees node-by-node for an ordered keyed tree.

## Reactive queue

`QueueCell` is the native implementation of the [`lazily-spec`][spec] reactive
queue ([Cell Model § Reactive queues](https://github.com/lazily-hub/lazily-spec/blob/main/cell-model.md#reactive-queues))
— a FIFO collection composed of reactive cells, **not a new cell kind**. It is
specified as a **single-producer, single-consumer (SPSC)** primitive;
**MPSC** (multi-producer) is a *usage rule* on the same primitive — multiple
producers push inside one `batch { … }` and the batch serializes the pushes into
a deterministic order. There is no separate `MPSCQueueCell` type.

The reactive shell owns the reader-kind version cells (`head` / `len` /
`is_empty` / `is_full` / `closed`) and invalidates **by reader kind**: a push to
a non-empty queue does NOT invalidate the `head` reader (head unchanged); a pop
does. This reader-kind independence falls out of the `==` guard on `setCell` —
after each op the shell re-derives each reader-kind cell from storage and writes
it back, and a cell whose value did not change is not invalidated. The storage
backend is pluggable via `QueueStorage`; the default `VecDequeStorage` is
unbounded, and a bounded form exposes reactive backpressure via `is_full`.

```kotlin
val ctx = Context()
val q = QueueCell.unbounded<String>(ctx)

// SPSC: total FIFO.
q.tryPush("a")
q.tryPush("b")
assertEquals("a", q.head())
assertEquals(2, q.len())

assertEquals("a", (q.tryPop() as QueuePop.Value).value)
assertEquals("b", (q.tryPop() as QueuePop.Value).value)
assertTrue(q.isEmpty())

// MPSC: multiple producers push inside one batch → one invalidation pass.
ctx.batch {
    q.tryPush("p1-a")
    q.tryPush("p2-a")
    q.tryPush("p1-b")
}
assertEquals(3, q.len())

// Bounded queue → reactive backpressure via is_full.
val bq = QueueCell.bounded<Int>(ctx, 2)
bq.tryPush(1); bq.tryPush(2)
assertTrue(bq.isFull())            // at capacity
assertEquals(QueuePushError.Full, bq.tryPush(3))
(bq.tryPop() as QueuePop.Value)    // pop frees a slot
assertFalse(bq.isFull())           // is_full invalidated (true → false)
```

The shell / storage split (`QueueStorage` interface + `VecDequeStorage` default)
is the integration seam for future backends — a distributed `RaftQueueStorage`
or an external-broker adapter (`KafkaStorage`, etc.) plugs into the same
reactive shell without changing the API.

`WorkQueueCell` supplies the competing-consumer sibling: workers pull exclusive
FIFO leases, settle them with worker-owned delivery IDs, and unacked items
redeliver after the strict visibility deadline. Repeated failures route to the
DLQ at `maxDeliveries`; `pendingLen`, `isEmpty`, `inFlightLen`, and
`deadLetterLen` are independent reactive reads.

```kotlin
val work = WorkQueueCell<String>(ctx, visibilityTimeout = 30, maxDeliveries = 3)
work.push("render-report")
val delivery = requireNotNull(work.claim("worker-a", now = 100))
check(work.ack("worker-a", delivery.deliveryId))
```

The instance is the local serialization point. Distributed/HA assignment must
put `claim` behind a leader or consensus log.

## Distributed CRDT plane

The `CrdtSync` wire types live in `Ipc.kt`; `Crdt.kt` is the **runtime
integration slice** (`#lzcrdtplane5b`) — the `merge: crdt` ingress mechanism
([Cell Model § Multi-write cells](https://github.com/lazily-hub/lazily-spec/blob/main/cell-model.md#multi-write-cells)):
local edits mint `CrdtOp`s; remote `CrdtOp`s merge into a `ReplicatedCell` and
the converged value is fed into the reactive graph as an ordinary cell update
(equality-guarded, so an equal merge invalidates nothing). It includes a hybrid
logical clock (`CrdtClock`), the per-peer `StampFrontier` (per-peer `max`), the
causal-stability **watermark** (`min` over membership — fail-closed when a
member is unobserved), the tombstone **GC** contract, and the LWW / MV /
PN-counter register types. Merge is commutative, associative, and idempotent;
out-of-order, duplicated, or batched delivery all converge.

### Distributed plane — transport + signaling (seam + platform adapter)

`CrdtPlaneRuntime` (`CrdtPlane.kt`) is the per-session runtime glue over those
primitives: it owns the plane clock/frontier/membership, an op-log (dedup by
`(node, stamp)`), a `NodeId`↔`NodeKey` index, and a registry of replicated root
cells. `localUpdate` mints a broadcastable `CrdtOp` (or `null` for a
value-preserving edit); `ingest` folds a peer's `CrdtSync` frame in exactly once
(idempotent re-delivery applies 0), advancing the clock, frontier, and
membership; `syncFrame` / `syncFrameSince` / `syncReply` drive anti-entropy.

The networking is a **consumer-provided seam** — no bundled native WebRTC
library. `DataChannel` (`DataChannel.kt`) is the ordered byte-frame surface a
real `RTCDataChannel` backend must supply; `WebRtcSink` / `WebRtcSource`
(`WebRtcTransport.kt`) bridge it to IPC with outbound per-peer permission
filtering (omission, not redaction). The signaling wire protocol
(`ClientMessage` / `ServerMessage`, kebab-case tags), the `RoomCore` server
router (anti-spoof `to`→`from` rewrite, roster-excludes-self, `unknown_target`),
and a `SignalingClient` over a `SignalingSocket` seam live in `Signaling.kt`.
Everything is testable via zero-dependency in-memory loopbacks
(`InMemoryDataChannel`, `InMemorySignalingSocket`); wiring a real WebSocket /
WebRTC backend is the deliberate follow-up.

## C-ABI FFI boundary

`LazilyFfiBoundary.kt` exposes lazily-kt's **own** C-ABI FFI host boundary
([protocol § FFI Boundary](https://github.com/lazily-hub/lazily-spec/blob/main/protocol.md#ffi-boundary),
[`ffi.json`](https://github.com/lazily-hub/lazily-spec/blob/main/schemas/ffi.json)):
`LazilyFfiBytes` / `LazilyFfiStatus` / `LazilyFfiMessageKind` (with the required
`CrdtSync = 3` discriminant), explicit allocation ownership, panics caught
before crossing the C ABI (`LazilyFfiChannel.panicGuard`), and a channel that
decodes each accepted frame as `IpcMessage` and re-encodes canonical JSON bytes.
The JVM channel is the conformance-tested surface; the `extern "C"` symbols in
`lazily_ffi.h` are exported via a Graal native-image build or the JNI shim
(`LazilyFfiNative`). lazily-kt's platform CAN host a native in-process boundary,
so it declares the `ffi = host` capability.

## Development

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
  (CellMap/SlotMap), the ordered tree (CellTree), keyed reconciliation (LIS),
  and the async slot state machine — the executable reference behind the
  conformance fixtures lazily-kt replays.

Both targets resolve via `LEAN_SPEC_DIR` / `LEAN_FORMAL_DIR` (defaulting to the
sibling submodule paths) and fail with a clear message if the sibling is absent.

Requires JDK 21 and Gradle (the included wrapper works out of the box). Building
the formal models additionally requires Lean 4 (`elan` / `lake`).

## Benchmarks

Performance benchmarks mirroring the lazily-rs [`benches/`](https://github.com/lazily-hub/lazily-rs/tree/main/benches)
coverage ([`BENCHMARKS.md`](BENCHMARKS.md) for full results):

```bash
make benchmark          # reactive-core micro-bench (parity with context.rs)
make benchmark-scale    # spreadsheet-scale bench, default N=1,000,000 (scale.rs)
```

The micro-bench (`Benchmarks.kt`) covers cached reads, cold first get,
dependency fan-out, set-cell invalidation, memo equality suppression, effect
flushing, batch storms, typed cache reads, and thread-safe contention. The
scale bench (`ScaleBench.kt`) models a spreadsheet of `2N` reactive nodes and
highlights the lazy-pull viewport win (off-viewport formulas stay dirty and
never recompute). Override the graph size with `LAZILY_SCALE_N`.

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
