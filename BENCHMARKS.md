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
| `cached_reads/context` | 47.3 ns | Warm-cache slot re-read (single-threaded `Context`). |
| `cached_reads/thread_safe_context` | 58.5 ns | Warm-cache slot re-read (lock-backed). |
| `cold_first_get/context` | 648.5 ns | Fresh `Context` + one slot + first read per iteration. |
| `cold_first_get/thread_safe_context` | 824.9 ns | Fresh `ThreadSafeContext` + one slot + first read. |

### Dependency fan-out (invalidate a root, read all `N` dependents)

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `dependency_fan_out/context/32` | 181.8 ns | 32 derived slots. |
| `dependency_fan_out/thread_safe_context/32` | 391.0 ns | 32 derived slots (lock-backed). |
| `dependency_fan_out/context/256` | 821.1 ns | 256 derived slots. |
| `dependency_fan_out/thread_safe_context/256` | 2.958 us | 256 derived slots (lock-backed). |

### Set-cell invalidation / memo suppression / effect flushing / batch storms

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `set_cell_invalidation/high_fan_out/512` | 73.8 ns | Invalidate one cell with 512 lazy dependents (no eager recompute — invalidation is lazy). |
| `memo_equality_suppression/context` | 49.8 ns | A 32-deep memo chain whose recompute is unchanged — the `==` guard suppresses the downstream cascade. |
| `memo_equality_suppression/thread_safe_context` | 37.4 ns | Same, lock-backed. |
| `effect_flushing/context` | 41.1 ns | One `setCell` → one effect rerun + flush. |
| `effect_flushing/thread_safe_context` | 39.3 ns | Same, lock-backed. |
| `batch_storms/context/64` | 280.0 ns | Batch of 64 `setCell` → one coalesced flush. |
| `batch_storms/thread_safe_context/64` | 1.136 us | Same, lock-backed. |

### Typed cache reads (steady state)

| Benchmark | per-op | What it measures |
|-----------|-------:|------------------|
| `typed_cache_reads/context_slot` | 18.2 ns | Cached slot read (`Context`). |
| `typed_cache_reads/context_cell` | 19.0 ns | Cached cell read (`Context`). |
| `typed_cache_reads/thread_safe_slot` | 39.8 ns | Cached slot read (lock-backed). |
| `typed_cache_reads/thread_safe_cell` | 36.5 ns | Cached cell read (lock-backed). |

### Thread-safe contention (`workers` threads sharing one `ThreadSafeContext`)

Each worker does `128` invalidate-then-read iterations per sample.

| Benchmark / workers | 1 | 2 | 4 | 8 | 16 |
|---|---:|---:|---:|---:|---:|
| `same_slot_write_read` | 42.5 us | 129.1 us | 202.8 us | 293.5 us | 696.8 us |
| `independent_slots` | 55.9 us | 103.5 us | 124.5 us | 325.8 us | 545.7 us |
| `read_mostly_waiters` | 33.7 us | 70.5 us | 94.8 us | 259.6 us | 469.8 us |
| `batched_write_bursts` | 94.0 us | 230.4 us | 659.6 us | 2140.7 us | 9914.6 us |

### Thread-safe effect contention (8 / 16 workers)

| Benchmark / workers | 8 | 16 |
|---|---:|---:|
| `queue_coalescing` | 1802.0 us | 8304.2 us |
| `cleanup_execution` | 714.2 us | 1855.5 us |
| `batch_flush` | 2502.5 us | 11459.9 us |

## Spreadsheet-scale bench (`make benchmark-scale`)

A spreadsheet-shaped graph: `N` input cells + `N` formula slots where
`formula[i] = input[i] + input[i-1]` (local fan-in, like `=A_i + A_{i-1}`).
Default `N = 1,000,000` (~2M reactive nodes).

| Case | elapsed | per-element | What it measures |
|------|--------:|------------:|------------------|
| `build` | 103.0 ms | 103.0 ns | Construct all `2N` nodes from scratch. |
| `cold_full_recalc` | 207.7 ms | 207.7 ns | First read of every formula (forces every compute). |
| `viewport_recalc` | 2.4 ms | 2.4 ns | Edit one input, read only a 1,000-formula window — the lazy-pull win: off-viewport formulas stay dirty and never recompute. |
| `full_recalc_invalidate_all` | 1587.2 ms | 1.587 us | Touch every input, then read every formula (worst-case full-sheet edit). |

The **viewport curve is flat** relative to graph size: editing one input and
reading a bounded window costs ~2 ms whether the sheet is 100K or 1M rows,
because lazily only recomputes the formulas actually read. Compare
`viewport_recalc` (2.4 ns/element) against `full_recalc_invalidate_all`
(1.587 us/element) — a ~660× win for the lazy pull.

## Related

- [`lazily-rs` `benches/`](../lazily-rs/benches/) — the canonical Criterion-backed
  benches this mirrors (`context.rs`, `scale.rs`).
- [`lazily-py` `benchmarks.py`](../lazily-py/src/lazily/benchmarks.py) — the
  wall-clock harness shape (`Benchmark` / `BenchmarkResult` / `timeOp`).
- [`lazily-zig` `src/benches/`](../lazily-zig/src/benches/) — counter- and
  wall-clock-based sibling benches.
