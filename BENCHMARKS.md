# lazily-kt Benchmarks

Two benchmark surfaces for the lazily-kt hot paths, mirroring the lazily-rs
`benches/` coverage:

- a **reactive-core micro-bench** ([`Benchmarks.kt`](src/main/kotlin/io/github/lazily/Benchmarks.kt),
  `make benchmark` / `./gradlew benchmark`) mirroring the lazily-rs
  [`benches/context.rs`](../lazily-rs/benches/context.rs) scenarios, and
- a **spreadsheet-scale bench** ([`ScaleBench.kt`](src/main/kotlin/io/github/lazily/ScaleBench.kt),
  `make benchmark-scale`) replicating the lazily-rs
  [`scale`](../lazily-rs/benches/scale.rs) group on a graph of up to 1,000,000
  cells (a full Google Sheets workbook).

## A note on measurement

The JVM has a moving performance floor (JIT compilation, GC, allocator
coalescing), so these benches use a hand-rolled harness that mirrors the
lazily-py wall-clock approach (`Benchmark` / `BenchmarkResult` / `timeOp`) plus a
**warmup** phase: each case runs its untimed warmup iterations to let the JIT
settle, then times the measurement iterations under `System.nanoTime()`. A
`Blackhole` defeats dead-code elimination of computed results.

Treat the absolute numbers as indicative of this machine/VM; the **shapes**
(relative cost across cases, the flat viewport curve, the contention scaling)
are what carry across runs. This is the same posture lazily-zig's BENCHMARKS.md
takes ("the shapes are what matter across runs").

### Hardware / environment

| | |
|---|---|
| CPU | AMD Ryzen 9 9950X3D (16 cores / 32 threads) |
| RAM | 186 GiB |
| OS | Linux 7.1.3 (CachyOS), x86-64 |
| Kotlin | 2.0.21 |
| JVM | OpenJDK 24.0.2 (GraalVM) |

## Reproduce

```bash
make benchmark          # reactive-core micro-bench (wall-clock)
make benchmark-scale    # spreadsheet-scale bench, default N=1,000,000
```

Override the scale graph size:

```bash
LAZILY_SCALE_N=2000000 make benchmark-scale
# or via a Gradle property:
./gradlew benchmarkScale -Plazily.scaleN=100000
```

## Reactive-core micro-bench (`make benchmark`)

Wall-clock per-op. Mirrors the lazily-rs `benches/context.rs` groups.

### Cached reads / cold first get

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `cached_reads/context` | 274.7 ns | Warm-cache slot re-read (single-threaded `Context`). |
| `cached_reads/thread_safe_context` | 163.4 ns | Warm-cache slot re-read (lock-backed). |
| `cold_first_get/context` | 865.6 ns | Fresh `Context` + one slot + first read per iteration. |
| `cold_first_get/thread_safe_context` | 1.147 us | Fresh `ThreadSafeContext` + one slot + first read. |

### Dependency fan-out (invalidate a root, read all `N` dependents)

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `dependency_fan_out/context/32` | 742.2 ns | 32 derived slots. |
| `dependency_fan_out/thread_safe_context/32` | 1.732 us | 32 derived slots (lock-backed). |
| `dependency_fan_out/context/256` | 8.180 us | 256 derived slots. |
| `dependency_fan_out/thread_safe_context/256` | 14.340 us | 256 derived slots (lock-backed). |

### Set-cell invalidation / memo suppression / effect flushing / batch storms

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `set_cell_invalidation/high_fan_out/512` | 129.7 ns | Invalidate one cell with 512 lazy dependents (no eager recompute — invalidation is lazy). |
| `memo_equality_suppression/context` | 766.2 ns | A 32-deep memo chain whose recompute is unchanged — the `==` guard suppresses the downstream cascade. |
| `memo_equality_suppression/thread_safe_context` | 1.005 us | Same, lock-backed. |
| `effect_flushing/context` | 56.3 ns | One `setCell` → one effect rerun + flush. |
| `effect_flushing/thread_safe_context` | 44.0 ns | Same, lock-backed. |
| `batch_storms/context/64` | 333.2 ns | Batch of 64 `setCell` → one coalesced flush. |
| `batch_storms/thread_safe_context/64` | 1.071 us | Same, lock-backed. |

### Typed cache reads (steady state)

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `typed_cache_reads/context_slot` | 28.6 ns | Cached slot read (`Context`). |
| `typed_cache_reads/context_cell` | 23.2 ns | Cached cell read (`Context`). |
| `typed_cache_reads/thread_safe_slot` | 52.6 ns | Cached slot read (lock-backed). |
| `typed_cache_reads/thread_safe_cell` | 39.8 ns | Cached cell read (lock-backed). |

### Thread-safe contention (`workers` threads sharing one `ThreadSafeContext`)

Each worker does `128` invalidate-then-read iterations per sample.

| Benchmark / workers | 1 | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `same_slot_write_read` | 123.8 us | 210.2 us | 334.4 us | 561.7 us | 1126.7 us |
| `independent_slots` | 62.2 us | 160.6 us | 220.5 us | 498.7 us | 1111.3 us |
| `read_mostly_waiters` | 66.7 us | 118.1 us | 147.3 us | 232.0 us | 593.4 us |
| `batched_write_bursts` | 116.9 us | 382.9 us | 1001.4 us | 3199.9 us | 11818.5 us |

### Thread-safe effect contention (8 / 16 workers)

| Benchmark / workers | 8 | 16 |
|---|---:|---:|
| `queue_coalescing` | 2918.9 us | 11713.0 us |
| `cleanup_execution` | 929.6 us | 2873.3 us |
| `batch_flush` | 4580.5 us | 13596.4 us |

## Spreadsheet-scale bench (`make benchmark-scale`)

A spreadsheet-shaped graph: `N` input cells + `N` formula slots where
`formula[i] = input[i] + input[i-1]` (local fan-in, like `=A_i + A_{i-1}`).
Default `N = 1,000,000` (~2M reactive nodes).

| Case | elapsed | per-element | What it measures |
|------|--------:|------------:|------------------|
| `build` | 199.5 ms | 199.5 ns | Construct all `2N` nodes from scratch. |
| `cold_full_recalc` | 933.6 ms | 933.6 ns | First read of every formula (forces every compute). |
| `viewport_recalc` | 6.2 ms | 6.2 ns | Edit one input, read only a 1,000-formula window — the lazy-pull win: off-viewport formulas stay dirty and never recompute. |
| `full_recalc_invalidate_all` | 3721.1 ms | 3.721 us | Touch every input, then read every formula (worst-case full-sheet edit). |

The **viewport curve is flat** relative to graph size: editing one input and
reading a bounded window costs ~6 ms whether the sheet is 100K or 1M rows,
because lazily only recomputes the formulas actually read. Compare
`viewport_recalc` (6.2 ns/element) against `full_recalc_invalidate_all`
(3.721 us/element) — a ~600× win for the lazy pull.

## Related

- [`lazily-rs` `benches/`](../lazily-rs/benches/) — the canonical Criterion-backed
  benches this mirrors (`context.rs`, `scale.rs`).
- [`lazily-py` `benchmarks.py`](../lazily-py/src/lazily/benchmarks.py) — the
  wall-clock harness shape (`Benchmark` / `BenchmarkResult` / `timeOp`).
- [`lazily-zig` `src/benches/`](../lazily-zig/src/benches/) — counter- and
  wall-clock-based sibling benches.
