# lazily-kt

Native Kotlin port of the **lazily** reactive core — a first-class reactive
binding alongside [`lazily-rs`][rs], [`lazily-py`][py], and [`lazily-zig`][zig],
with **no FFI dependency** for the reactive graph. Plus the [`lazily-spec`][spec]
IPC wire types, a reactive full-Harel state chart, an `AsyncContext` async
reactive graph, a lock-backed `ThreadSafeContext`, an in-process `ShmBlobArena`
blob host, and an agent-doc state-projection consumer.

`io.github.lazily:lazily` · Kotlin 2.0.21 · JVM 21 · v0.40.0

## Feature Set

The full `lazily` capability set and its cross-language coverage across every
binding. Legend: ✅ shipped · `~` partial · `—` absent · `⊘` not applicable. The
canonical matrix with per-cell notes and platform carve-outs lives in
[`lazily-spec` § Cross-Language Coverage](https://github.com/lazily-hub/lazily-spec/blob/main/docs/coverage.md).

<!-- coverage-table:start -->
#### Summary — family × language

| Family | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Reactive graph | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Materialization | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Family sync | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Statecharts | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Keyed collections | ✅ | ✅ | ✅ | ✅ | ✅ | ~ | ✅ | ✅ | ✅ |
| Reactive queue | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Broadcast topic | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Work queue | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CRDT data types | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Lossless tree | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Egress | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ingress | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Wire codec | ✅ | ✅ | ✅ | ✅ | ~ | ✅ | ✅ | ✅ | ✅ |
| Transport & FFI | ✅ | ✅ | ✅ | ~ | ~ | ✅ | ✅ | ~ | ✅ |
| Message passing | ✅ | ✅ | ✅ | ✅ | ✅ | ~ | ✅ | ✅ | ✅ |
| Reliable sync | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ |
| Distributed plane | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Causal receipts | ~ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Security boundary | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Membership | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Coordination | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Presence | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Temporal | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Rate shaping | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Windowing | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Resilience | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Portable stdlib | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Service plane | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Instrumentation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

**Roll-up rule:** a family cell is `✅` only when *every required* row in that family is `✅`; `~` when the family is mixed (some shipped or partial); `—` when no required row is shipped or partial; `⊘` only when every required row in the family is not applicable. Rows the spec marks **MAY** (`optional`, shown as *opt* below) are excluded from the roll-up — declining an optional feature is not a gap.

#### Reactive graph

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Reactive graph [^reactive-graph] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Thread-safe context [^thread-safe-context] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Async reactive context [^async-reactive-context] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Merge algebra [^merge-algebra] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Materialization

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Keyed-map materialization [^keyed-map-materialization] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Thread-safe keyed map [^thread-safe-keyed-map] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Async keyed map [^async-keyed-map] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Family sync

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Keyed-map sync [^keyed-map-sync] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Statecharts

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Flat state machine [^flat-state-machine] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Harel state charts [^harel-state-charts] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Keyed collections

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Keyed reactive maps [^keyed-reactive-maps] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ReactiveMap core — single-threaded [^reactivemap-core-single-threaded] | ✅ | ✅ | ✅ | ✅ | ✅ | ~ | ✅ | ✅ | ✅ |
| ReactiveMap core — thread-safe [^reactivemap-core-thread-safe] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ReactiveMap core — async [^reactivemap-core-async] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Exact-key dependency availability [^exact-key-dependency-availability] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Atomic ordered move [^atomic-ordered-move] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Memoized semantic tree [^memoized-semantic-tree] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Stable-id alignment [^stable-id-alignment] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Reactive queue

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Reactive queue core — single-threaded [^reactive-queue-core-single-threaded] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reactive queue core — thread-safe [^reactive-queue-core-thread-safe] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reactive queue core — async [^reactive-queue-core-async] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Broadcast topic

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Broadcast topic core — single-threaded [^broadcast-topic-core-single-threaded] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Broadcast topic core — thread-safe [^broadcast-topic-core-thread-safe] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Broadcast topic core — async [^broadcast-topic-core-async] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Work queue

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Work queue core — single-threaded [^work-queue-core-single-threaded] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Work queue core — thread-safe [^work-queue-core-thread-safe] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Work queue core — async [^work-queue-core-async] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### CRDT data types

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Free-text character CRDT [^free-text-character-crdt] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| TextCrdt delta sync [^textcrdt-delta-sync] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CrdtTree lossless document [^crdttree-lossless-document] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Move-aware sequence CRDT [^move-aware-sequence-crdt] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Registers (LWW/MV) + PnCounter [^registers] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Lossless tree

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Lossless tree CRDT core [^lossless-tree-crdt-core] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Lossless tree — anti-entropy [^lossless-tree-anti-entropy] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Lossless tree — merge convergence [^lossless-tree-merge-convergence] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Egress

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| RelayCell [^relaycell] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Ingress

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Reactive ingress [^reactive-ingress] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ingress — thread-safe [^ingress-thread-safe] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ingress — async [^ingress-async] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Wire codec

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| IPC wire — Snapshot/Delta/CrdtSync [^ipc-wire] | ✅ | ✅ | ✅ | ✅ | ~ | ✅ | ✅ | ✅ | ✅ |
| Frame codec — json [^frame-codec-json] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Frame codec — msgpack [^frame-codec-msgpack] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Frame codec — postcard *(opt)* [^frame-codec-postcard] | ✅ | — | — | — | — | — | — | — | — |
| NodeId/PeerId exact-representation [^nodeid-peerid-exact-representation] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| NodeKey null-leniency [^nodekey-null-leniency] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Capability negotiation [^capability-negotiation] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Transport & FFI

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Shared-memory blob path [^shared-memory-blob-path] | ✅ | ✅ | ✅ | ~ | ~ | ✅ | ✅ | ~ | ✅ |
| Cross-process zero-copy transport [^cross-process-zero-copy-transport] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| C-ABI FFI boundary [^c-abi-ffi-boundary] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Message passing

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Message-passing + RPC command plane [^message-passing-rpc-command-plane] | ✅ | ✅ | ✅ | ✅ | ✅ | ~ | ✅ | ✅ | ✅ |

#### Reliable sync

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Reliable sync [^reliable-sync] | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ | ~ |
| Storage-independent durable outbox [^storage-independent-durable-outbox] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reliable-sync transport seam [^reliable-sync-transport-seam] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Distributed plane

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Distributed CRDT plane [^distributed-crdt-plane] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Distributed plane — WebRTC [^distributed-plane-webrtc] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| State projection / mirror [^state-projection-mirror] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Causal receipts

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Causal receipts [^causal-receipts] | ~ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Security boundary

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Permission boundary [^permission-boundary] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Membership

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Membership + failure detection [^membership-failure-detection] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Coordination

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Distributed coordination [^distributed-coordination] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Presence

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Presence + ephemeral plane [^presence-ephemeral-plane] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Temporal

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Temporal sources [^temporal-sources] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Rate shaping

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Rate-shaping operators [^rate-shaping-operators] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Windowing

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Stream windowing [^stream-windowing] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Resilience

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Fault tolerance [^fault-tolerance] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Portable stdlib

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Portable stdlib Timer [^portable-stdlib-timer] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Portable stdlib Timeout [^portable-stdlib-timeout] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Portable stdlib RevisionBarrier [^portable-stdlib-revision-barrier] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Service plane

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Embedded-service plane [^embedded-service-plane] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### Instrumentation

| Feature | Rust | Python | Kotlin | JS | Dart | Zig | Go | C++ | C# |
| --------- | :----: | :------: | :------: | :--: | :----: | :---: | :--: | :---: | :--: |
| Instrumentation / benchmarks [^instrumentation-benchmarks] | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

[^reactive-graph]: Reactive graph — two cell kinds (nodes `SourceCell` / `ComputedCell`; handles `Source<T, M>` / `Computed<T>`) + `Effect` sink + eager `Computed` (`computed().eager()`) / all cells guarded / batch
[^keyed-map-materialization]: Keyed-map materialization (`ComputedMap`) — mint-on-access derived slots: transparency + deferral (`#lzmatmode`)
[^thread-safe-keyed-map]: Thread-safe keyed map (`ThreadSafeComputedMap`) — `Send + Sync` + materialization confluence (`#lzmatmode`)
[^async-keyed-map]: Async keyed map (`AsyncComputedMap`) — eventual transparency (`#lzmatmode`)
[^keyed-map-sync]: Keyed-map sync — membership propagation + materialize-on-ingest + derived-aggregate transparency (`#lzfamilysync`)
[^thread-safe-context]: Thread-safe context (lock-backed)
[^async-reactive-context]: Async reactive context
[^flat-state-machine]: Flat state machine
[^harel-state-charts]: Harel state charts
[^keyed-reactive-maps]: Keyed reactive maps (`ReactiveMap`: `SourceMap` / `ComputedMap`) + `SourceTree` + reconcile
[^reactivemap-core-single-threaded]: `ReactiveMap` **Core surface** — single-threaded flavor (cell-model.md § Core surface vs. binding extensions)
[^reactivemap-core-thread-safe]: `ReactiveMap` **Core surface** — thread-safe flavor (ordering + membership reactivity)
[^reactivemap-core-async]: `ReactiveMap` **Core surface** — async flavor (ordering + membership reactivity)
[^exact-key-dependency-availability]: Exact-key dependency availability (`DependencyMap`: observe before publish, unrelated-key isolation, stable identity; `#lzdependencyavailability`)
[^atomic-ordered-move]: Atomic ordered move replayed against **all three flavors** (`cellmap_atomic_move` + `cellmap_independence`)
[^memoized-semantic-tree]: Memoized semantic tree (`SemTree`)
[^stable-id-alignment]: Stable-id alignment (manufactured identity)
[^reactive-queue-core-single-threaded]: Reactive queue (`QueueCell` SPSC/MPSC + `QueueStorage` adapter) **Core surface** — single-threaded flavor
[^reactive-queue-core-thread-safe]: Reactive queue (`QueueCell` SPSC/MPSC + `QueueStorage` adapter) **Core surface** — thread-safe flavor (reader kinds + closure lifecycle)
[^reactive-queue-core-async]: Reactive queue (`QueueCell` SPSC/MPSC + `QueueStorage` adapter) **Core surface** — async flavor (reader kinds + eventual transparency)
[^broadcast-topic-core-single-threaded]: Broadcast topic (`TopicCell`) **Core surface** — single-threaded flavor — independent cursors + durable replay + safe GC (`#lztopiccell`)
[^broadcast-topic-core-thread-safe]: Broadcast topic (`TopicCell`) **Core surface** — thread-safe flavor (reader kinds + closure lifecycle)
[^broadcast-topic-core-async]: Broadcast topic (`TopicCell`) **Core surface** — async flavor (reader kinds + eventual transparency)
[^work-queue-core-single-threaded]: Competing-consumer work queue (`WorkQueueCell`) **Core surface** — single-threaded flavor — exclusive leases + ack/nack + redelivery + DLQ (`#lzworkqueue`)
[^work-queue-core-thread-safe]: Competing-consumer work queue (`WorkQueueCell`) **Core surface** — thread-safe flavor (reader kinds + closure lifecycle)
[^work-queue-core-async]: Competing-consumer work queue (`WorkQueueCell`) **Core surface** — async flavor (reader kinds + eventual transparency)
[^merge-algebra]: Merge algebra + `Source<T, M>` — associative `MergePolicy` (`KeepLatest`/`Sum`/`Max`/`SetUnion`/`RawFifo`), `Cell ≡ Source<KeepLatest>`, read-any-cell/write-`Source` split (`#relaycell`)
[^relaycell]: RelayCell — conflating relay + `BackpressurePolicy` + `SpillStore` + `Transport` + Inbox/Outbox + Rate/Window/Expiry/Priority/keyed policies (`#relaycell`)
[^free-text-character-crdt]: Free-text character CRDT (`TextCrdt`)
[^textcrdt-delta-sync]: `TextCrdt` delta sync (`version_vector` / `delta_since` / `apply_delta`)
[^crdttree-lossless-document]: `CrdtTree` lossless document contract (`#lzcrdttree`)
[^move-aware-sequence-crdt]: Move-aware sequence CRDT (`SeqCrdt`)
[^lossless-tree-crdt-core]: Lossless tree CRDT core (`LosslessTreeCrdt`, M1)
[^lossless-tree-anti-entropy]: Lossless tree — dotted-frontier anti-entropy
[^lossless-tree-merge-convergence]: Lossless tree — concurrent merge convergence
[^registers]: Registers (LWW / MV) + `PnCounter` + `CellCrdt`
[^ipc-wire]: IPC wire — `Snapshot` + `Delta` + `CrdtSync`
[^frame-codec-json]: Frame codec — `json` **reference codec**: dependency-free interop floor, FFI baseline form, byte-canonical (**MUST**) — executable round-trip obligation (`conformance/codec/frame_roundtrip_json.json`, `#lzmsgpackparity`)
[^frame-codec-msgpack]: Frame codec — `msgpack` **cross-language binary default**: externally-tagged frame over named-field maps, semantic (not byte-identical) round-trip (**MUST**) — executable round-trip obligation (`conformance/codec/frame_roundtrip_msgpack.json`, `#lzmsgpackparity`). Shipping *a* MessagePack codec does not earn this mark: lazily-cpp read `~` here while its private internally-tagged framing wore the token, and only flipped once it shipped the spec wire (`#lzcppmsgpackwire`)
[^frame-codec-postcard]: Frame codec — `postcard` positional same-schema fast path: smallest + byte-canonical, not cross-language (**MAY**)
[^nodeid-peerid-exact-representation]: `NodeId` / `PeerId` exact-representation bound (**MUST**) — a decoder that cannot represent a received identifier exactly rejects the frame rather than rounding it (`conformance/codec/nodeid_exact_range.json`, `#lzspecdecoderbound`). A binding's exact range MAY be narrower than the `u64` wire type; ✅ means it refuses outside that range instead of substituting a neighbouring id, not that it carries the full `u64`. Exact ranges: full `u64` in Rust / Zig / C#, unbounded in Python, `[0, 2^63)` in Kotlin / Go / C++, `[0, 2^53)` in JS, and platform-split in Dart (63-bit on the VM, 53-bit on web). protocol.md stated only the PRODUCER half until this audit, and two C++ decoders were substituting rather than refusing.
[^nodekey-null-leniency]: `NodeKey` null-leniency on decode (**MUST**) — omit-when-absent binds the ENCODER; a decoder reads both an omitted `key` and an explicit `key: null` as absent, refusing neither and constructing a key from neither (`conformance/codec/nodekey_null_leniency.json`, `#lzkeynullstrict`). Replayed on BOTH optional-key sites (`NodeSnapshot`, the `NodeAdd` delta op) in both codecs, and the fixture pins the RE-ENCODED field set as well: reading null as absent and writing it back out is a correct decode with a non-conforming encoder. Before the audit lazily-py and lazily-zig refused the null form, and lazily-kt decoded it into a real key named `null` — all three had the same field right on `CrdtOp`, in the same file.
[^shared-memory-blob-path]: Shared-memory blob path (`ShmBlobArena`)
[^cross-process-zero-copy-transport]: Cross-process zero-copy transport (`BlobBackend` / shm / arrow)
[^distributed-crdt-plane]: Distributed CRDT plane (`CrdtPlaneRuntime` / anti-entropy)
[^reliable-sync]: Reliable sync — resync coordinator + at-least-once durable outbox + OR-set/LWW liveness (`#lzsync`)
[^storage-independent-durable-outbox]: Storage-independent durable outbox (`OutboxStore` + shared outbox protocol; SQLite/Room/IndexedDB/file adapters)
[^reliable-sync-transport-seam]: Reliable-sync transport seam + full-duplex `SyncDriver` loop (`IpcSink`/`IpcSource`, `#sync-driver`)
[^distributed-plane-webrtc]: Distributed plane — WebRTC transport + signaling
[^state-projection-mirror]: State projection / mirror
[^causal-receipts]: Causal receipts (`CausalReceipts` outcome projection)
[^message-passing-rpc-command-plane]: Message-passing + RPC command plane (`command-plane-v1`)
[^c-abi-ffi-boundary]: C-ABI FFI boundary
[^permission-boundary]: Permission boundary (`PeerPermissions` / `RemoteOp`)
[^capability-negotiation]: Capability negotiation (`SessionHandshake`)
[^instrumentation-benchmarks]: Instrumentation / benchmarks
[^temporal-sources]: Temporal sources — `TimerCell` / `IntervalCell` / `CronCell` / `DeadlineCell` over a logical clock (`#lztime`)
[^rate-shaping-operators]: Rate-shaping operators — `DebounceCell` / `ThrottleCell` / `SampleCell` / `ProbabilisticSampleCell` (`#lzrateshape`)
[^membership-failure-detection]: Membership + failure detection — `MembershipCell` (SWIM + Phi-accrual) / `PeerSet` / `PeerChangeEvent` (`#lzmemb`)
[^distributed-coordination]: Distributed coordination — `LeaseCell` / `LeaderCell` / `LockCell` / `SemaphoreCell` / `BarrierCell`+`QuorumCell` (`#lzcoord`)
[^presence-ephemeral-plane]: Presence + ephemeral plane — `PresenceCell` / `AwarenessCell` / `EphemeralCell` + `Ephemeral`/`Durable` markers (`#lzpresence`)
[^stream-windowing]: Stream windowing — `TumblingWindow` / `SlidingWindow` / `SessionWindow` over the merge algebra (`#lzwindow`)
[^fault-tolerance]: Fault tolerance — `CircuitBreakerCell` / `RetryPolicyCell` / `BulkheadCell` / `TimeoutCell` (`#lzresilience`)
[^portable-stdlib-timer]: Portable stdlib `Timer` (`stdlib_timer_v1`) — canonical fixture + mutation-gate verified
[^portable-stdlib-timeout]: Portable stdlib caller-driven `Timeout<T>` (`stdlib_timeout_v1`) — distinct from reactive `TimeoutCell`
[^portable-stdlib-revision-barrier]: Portable stdlib `RevisionBarrier` (`stdlib_revision_barrier_v1`) — register/recheck lost-wakeup guard
[^embedded-service-plane]: Embedded-service plane — `HealthCell` / `ReadinessCell` / `DiscoveryCell` / `ServiceRegistry` (`#lzservice`)
[^reactive-ingress]: Transport-agnostic reactive ingress (`IngressCell`) — keyed lifecycle scopes, generation/sequence/freshness envelopes, reorder buffer, accepted/dropped/error receipt readers (`#designimplementtransport`)
[^ingress-thread-safe]: Ingress family — `Send + Sync` flavor (`ThreadSafeIngressCell`): one frontier walk per admission (`#designimplementtransport`)
[^ingress-async]: Ingress family — async flavor (`AsyncIngressCell`): admission is not async-coloured (`#designimplementtransport`)
<!-- coverage-table:end -->

CRDT convergence and the wire protocol are pinned by the shared conformance fixtures
and JSON Schemas in `lazily-spec` and the Lean models in `lazily-formal`.
## The reactive family

lazily-kt mirrors lazily-rs `Context` semantics across all three context layers
— single-threaded base, lock-backed thread-safe, and coroutine-backed async:

- **Computed** — a lazily-computed, memoized derived value. Tracks its dependencies
  automatically, computes on first read, caches, and recomputes only when read
  after an upstream change.
- **Source** — a mutable value that invalidates dependent Computeds/Signals
  when it changes.
- **Effect** — a side-effecting observer that reruns whenever a tracked
  dependency invalidates; a cleanup closure runs before each rerun and on
  dispose.

The core primitives are **Source** / **Computed** / **Effect**. **`Signal` is a
derived construct, not a core primitive** — `Signal ≡ Computed.eager`, a guarded
Computed plus a puller Effect that recomputes the instant a dependency changes, so its
value is materialized by the time `set` / `batch` returns (no intermediate
unset value).

Values are **lazy by default**; reach for the derived `Signal` when you need
eager push semantics. `SlotHandle` and `CellHandle` remain compatibility aliases;
`Computed`, `Source`, `SignalHandle`, and `EffectHandle` are the canonical
lightweight ids over the shared node table.

### Why it behaves the way it does

- **Pull-based, glitch-free refresh** — a Computed that reads other nodes always
  observes values consistent with the current inputs.
- **`==` guard on `set`** — setting an equal value is a no-op (no
  downstream cascade).
- **`computed` has a `==` guard** — an equal recompute suppresses downstream
  invalidation.
- **`batch` coalesces** invalidations into one effect flush.
- **Dynamic dependencies** — the tracking stack auto-discovers edges on each
  recompute (no stale subscriptions); cycles are detected and throw.

## Usage

```kotlin
import io.github.lazily.Context

val ctx = Context()
val a = ctx.source(2)
val b = ctx.source(3)

// Lazy: computes on first read, caches, recomputes only when a or b changes.
val sum = ctx.slot { ctx.get(a) + ctx.get(b) }
ctx.get(sum) // 5

ctx.set(a, 10)
ctx.get(sum) // 13

// Eager: recomputes immediately when a dependency changes.
val parity = ctx.signal { if (ctx.get(a) % 2 == 0) "even" else "odd" }
ctx.getSignal(parity) // "even"
ctx.set(a, 11)
ctx.getSignal(parity) // "odd" — already updated before the read
```

A side-effecting observer with cleanup:

```kotlin
val handle = ctx.effect {
    val v = ctx.get(a)
    // returned closure is the cleanup: runs before the next rerun and on dispose
    { println("a was $v") }
}
ctx.set(a, 42)   // reruns the effect (previous cleanup ran first)
ctx.disposeEffect(handle)
```

Coalesce multiple writes into one flush:

```kotlin
ctx.batch {
    set(a, 1)
    set(b, 2)
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
Cells are the synchronous input layer (`cell` / `get` / `set`);
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
val a = ctx.source(2)
val sum = ctx.computedAsync { get(a) + 3 }
ctx.getAsync(sum)             // suspends, computes, caches -> 5
ctx.set(a, 10)
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
- The keyed cell collections layer (`SourceMap` / `ComputedMap` / `SourceTree` /
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
- `QueueFamilyConformanceTest` replays all eleven canonical queue-family
  fixtures (five `queuecell_*`, four `topiccell_*`, two `workqueue_*`) against
  `Context`, `ThreadSafeContext`, and `AsyncContext`. Its capability/skip ledger,
  positive step counts, exact `steps[].expected.invalidates` checks, and
  concurrency probes prevent a staged or non-reactive flavor from reporting
  green.
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
`set`/`batch`**, preserving glitch-free pull-based ordering. The JVM memory
model's monitor happens-before guarantee is the counterpart of Rust's
`Send + Sync` obligation. Handles (`ThreadSafeComputed` /
`ThreadSafeSource` / `ThreadSafeEffectHandle` / `ThreadSafeSignalHandle`)
are value classes — clonable by value — so a handle minted on one thread may be
read on another through the shared context. A `ThreadLocal` tracking stack
mirrors lazily-rs's `thread_local!` tracking, so two threads computing
concurrently never mix their dependency edges. `ReentrantLock` is reentrant, so a
compute/effect callback that re-enters the same context (e.g. a slot reading
another slot) does not self-deadlock. `batch` runs its whole block under the
lock, so a batch is atomic across threads.

```kotlin
val ctx = ThreadSafeContext()
val src = ctx.source(1)
val doubled = ctx.signal { ctx.get(src) * 2 }  // eager, materialized
val eff = ctx.effect { println("now ${ctx.get(doubled)}"); null }

// From any thread: clonable handle, synchronous observer.
ctx.set(src, 21)   // observer fires synchronously before this returns
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
- **`SemTree`** — a memoized semantic derivation over a `SourceTree` (`#lzsemtree`).
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

`SourceMap` / `ComputedMap` and `SourceTree` are the native
implementation of the [`lazily-spec`][spec] keyed cell collections layer
([Cell Model § Keyed cell collections](https://github.com/lazily-hub/lazily-spec/blob/main/cell-model.md#keyed-cell-collections)) — a **composition of cells**, not a new cell kind. Each entry is an ordinary cell; a dedicated membership cell tracks the key set; a dedicated order cell tracks the ordered key list. The three reactive planes are independent by construction:

- writing an entry value invalidates only that entry's value readers;
- inserting / removing a key invalidates membership + order readers, never unrelated entry value readers;
- a pure reorder (atomic move) invalidates order readers only — membership readers (`len` / `contains`) and value readers are untouched, and the moved entry keeps its same cell handle (not remove + re-mint).

```kotlin
val ctx = Context()
val map = SourceMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3))

map.setValue("a", 10)        // value reader of "a" only
map.insert("d", 4)           // membership + order readers
map.moveTo("b", 3)           // order reader only; "b" keeps its handle
map.keysNow()                // [a, c, d, b]
```

`reconcile(prior, target)` diffs two keyed sequences **by stable key**, emitting
the minimal move-minimized `{insert, remove, move, update}` op set (longest-
increasing-subsequence over prior indices preserved); applying it to a live
`SourceMap` keeps stable entries' value cells un-invalidated. `SourceTree` composes
the same guarantees node-by-node for an ordered keyed tree.

## Reactive queue

`QueueCell` is the native implementation of the [`lazily-spec`][spec] reactive
queue ([Cell Model § Reactive queues](https://github.com/lazily-hub/lazily-spec/blob/main/cell-model.md#reactive-queues))
— a FIFO collection composed of reactive cells, **not a new cell kind**. It is
specified as a **single-producer, single-consumer (SPSC)** primitive;
**MPSC** (multi-producer) is a *usage rule* on the same primitive — multiple
producers push inside one `batch { … }` and the batch serializes the pushes into
a deterministic order. There is no separate `MPSCQueueCell` type.

The reactive shell owns demand-driven reader-kind computeds (`head` / `len` /
`is_empty` / `is_full`) plus a `closed` source and invalidates **by reader
kind**: a push to a non-empty queue does NOT invalidate the `head` reader (head
unchanged); a pop does. Each successful op clears exactly the computeds whose
values changed in one multi-root frontier walk; an unobserved reader is never
eagerly derived. The storage backend is pluggable via `QueueStorage`; the
default `VecDequeStorage` is unbounded, and a bounded form exposes reactive
backpressure via `is_full`.

The same API shape is available as `ThreadSafeQueueCell` / `AsyncQueueCell`,
`ThreadSafeTopicCell` / `AsyncTopicCell`, and
`ThreadSafeWorkQueueCell` / `AsyncWorkQueueCell`. Async reader derives are
synchronous because their values are already in memory; when composed inside an
async computed, overloads accepting `AsyncComputeContext` register the edge.

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

## Transport-agnostic reactive ingress

`IngressCell` is the native implementation of the [`lazily-spec`][spec]
transport-agnostic ingress family
([transport-ingress.md](https://github.com/lazily-hub/lazily-spec/blob/main/docs/transport-ingress.md),
`#designimplementtransport`). A client consuming a remote stream usually grows
four accidental mechanisms — a `refresh()` loop, a hand-rolled relevance check, a
reconnect path that forgets what it applied, and one copy of all three per
transport. Each of those is a *derive* being simulated with a call, and this
family makes them derives while keeping the transport a value the primitive never
touches.

The admission algebra lives in the graph-agnostic `IngressCore` and the
reactivity lives in the shells (`IngressCell` / `ThreadSafeIngressCell` /
`AsyncIngressCell`), the same split the queue and map families make: invalidation
is a graph write, so every core mutator returns an `IngressChange` — *which*
reader kinds the transition dirtied — and each shell clears exactly that set in
**one** frontier walk, so no reader ever observes "new value, old authority".

Each keyed scope exposes four reader kinds (`value` / `readiness` / `authority` /
`retry`) plus three independent receipt channels (`accepted` / `dropped` /
`error`) and a derived `IngressSchedule`. The negative cases are the contract: a
buffered out-of-order envelope invalidates nothing and mints no receipt, a `tick`
inside the freshness horizon invalidates nothing, an empty drain invalidates
nothing, and a suspend invalidates readiness only.

Admission applies a **normative** order — lifecycle → generation fence →
freshness → generation handoff → dedupe → ordering → backpressure → merge. Two
orderings are load-bearing: the fence outranks dedupe (else a zombie producer
reads as a duplicate) and freshness outranks ordering (else an expired envelope
takes a reorder slot). A generation handoff is a **baseline reset** — it discards
the superseded incarnation's buffered successors *and* its undrained window.
Backpressure reuses the relay `Overflow` policy, validated against the merge
algebra's `conflates` flag at construction exactly as `RelayCell` does, and
`Block` refuses **without** advancing the watermark so the producer's retry stays
in order. A drain is an egress, not an ack: it never moves the watermark.

**Admission is not async-coloured.** Whether an envelope is admissible is a
function of the fence, the watermark, the reorder buffer, and the observed clock —
nothing to await — so the async flavor uses synchronous computes on the async
graph and returns plain values like the other two. Awaiting belongs to the
transport, which is outside the primitive by construction.

```kotlin
val ctx = Context()
val ingress = IngressCell<String, Long>(ctx, IngressPolicy(freshnessHorizon = 100), sum())

// In-order delivery folds into one coalesced window under ⊕.
ingress.admit(IngressEnvelope("alpha", generation = 1, sequence = 0, stampedAt = 0, payload = 5))
ingress.admit(IngressEnvelope("alpha", generation = 1, sequence = 1, stampedAt = 0, payload = 7))
assertEquals(12L, ingress.value("alpha"))
assertEquals(IngressReadiness.Ready, ingress.readiness("alpha"))

// Out of order buffers, and invalidates nothing a reader can observe.
ingress.admit(IngressEnvelope("alpha", 1, 4, 0, 9))   // Buffered(gapFrom = 2)

// A zombie producer is fenced before its sequence is even consulted.
val zombie = ingress.admit(IngressEnvelope("alpha", 0, 0, 0, 99))
assertEquals(IngressAdmission.Dropped(IngressDropReason.StaleGeneration), zombie)
assertEquals(1, ingress.dropped().size)   // dropped only; accepted is untouched

// A drain is an egress: the watermark does not move, so a replay resumes here.
assertEquals(12L, ingress.drain("alpha"))
assertEquals(1L, ingress.view("alpha")?.deliveredThrough)

// The transport is a value. `pump` admits a decoded batch, then asks the
// transport to replay whatever gap the algebra still reports.
val transport = InProcIngress<String, Long>(IngressTransportKind.EventChannel)
transport.push(IngressEnvelope("beta", 1, 0, 0, 1))
transport.push(IngressEnvelope("beta", 1, 2, 0, 4))
ingress.pump(transport)
assertEquals(listOf("beta" to ReplayRequest(1, 1)), transport.replays())
```

The canonical `conformance/ingress/*.json` corpus is replayed against **all
three** flavors by `IngressFamilyConformanceTest`, with `invalidates` asserted per
reader kind in both directions through a cache-validity probe (so
over-invalidation is as visible as under-), a positive replayed-step count per
flavor, and a three-row capability ledger enforced by grepping `src/main/kotlin`
in both directions.

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
  (SourceMap/ComputedMap), the ordered tree (SourceTree), keyed reconciliation (LIS),
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

## The lazily family

lazily is one reactive kernel — `Source` / `Computed` / `Effect`, keyed
collections, state charts, CRDTs, and a distributed plane — implemented natively
in each language and held to a single cross-language contract:

- [`lazily-spec`][spec] — the wire protocol, the generated feature matrix, and
  the conformance corpus every binding replays.
- [`lazily-formal`][formal] — the Lean 4 formal model the bindings share.

| repo | language |
|---|---|
| [`lazily-rs`][rs] | Rust — the reference implementation |
| [`lazily-py`][py] | Python |
| [`lazily-go`][go] | Go |
| **`lazily-kt`** | Kotlin / JVM — you are here |
| [`lazily-js`][js] | JavaScript / TypeScript |
| [`lazily-cs`][cs] | C# / .NET |
| [`lazily-cpp`][cpp] | C++ |
| [`lazily-zig`][zig] | Zig |
| [`lazily-dart`][dart] | Dart / Flutter |
| [`lazily-react`][react] | React / Preact bindings layered over [`lazily-js`][js] — not a separate language binding |

Per-binding feature parity is tracked in the `coverage.json`-generated matrix in
[`lazily-spec`][spec]; read it there rather than any hand copy.

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
[go]: https://github.com/lazily-hub/lazily-go
[js]: https://github.com/lazily-hub/lazily-js
[cs]: https://github.com/lazily-hub/lazily-cs
[cpp]: https://github.com/lazily-hub/lazily-cpp
[zig]: https://github.com/lazily-hub/lazily-zig
[dart]: https://github.com/lazily-hub/lazily-dart
[react]: https://github.com/lazily-hub/lazily-react
[spec]: https://github.com/lazily-hub/lazily-spec
[formal]: https://github.com/lazily-hub/lazily-formal
