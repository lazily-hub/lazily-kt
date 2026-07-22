@file:JvmName("Benchmarks")
@file:Suppress("UNCHECKED_CAST")

package io.github.lazily

// -- Microbenchmark harness -------------------------------------------------

/**
 * One benchmark measurement: the wall-clock result of running an op many times.
 *
 * Mirrors the lazily-py `BenchmarkResult` shape (samples / total / per-op) and
 * the lazily-rs `benches/context.rs` case coverage. Absolute numbers are
 * JVM-specific (warmup, GC, JIT); the shapes (relative cost across cases) are
 * what carry across runs.
 */
data class BenchmarkResult(
    val group: String,
    val name: String,
    val samples: Int,
    val totalSeconds: Double,
    val perOpSeconds: Double,
) {
    fun format(): String {
        val perNs = perOpSeconds * 1_000_000_000.0
        val perUs = perOpSeconds * 1_000_000.0
        val unit = if (perNs >= 1_000.0) String.format("%.3f us/op", perUs) else String.format("%.1f ns/op", perNs)
        return String.format("%-44s %10d ops  %9.3f ms  %12s", "$group/$name", samples, totalSeconds * 1_000.0, unit)
    }
}

/**
 * One named benchmark entry. [setup] runs before each measurement batch and may
 * return a fixture consumed by [run]; [run] is the op being timed. Use the
 * [Blackhole] to defeat dead-code elimination of computed results.
 */
class Benchmark(
    val group: String,
    val name: String,
    val warmup: Int = DEFAULT_WARMUP,
    val samples: Int = DEFAULT_SAMPLES,
    val setup: () -> Any? = { null },
    val run: (Blackhole, Any?) -> Unit,
)

/** Sink that prevents the JIT from eliminating the work inside a benchmark op. */
open class Blackhole {
    @Volatile
    private var sinkLong: Long = 0L

    fun consume(v: Long) {
        sinkLong = sinkLong xor (v * 0x9E3779B97F4A7C15uL.toLong())
    }

    fun consume(v: Int) = consume(v.toLong())

    fun consume(v: Any?) {
        sinkLong = sinkLong xor System.identityHashCode(v).toLong()
    }
}

private const val DEFAULT_WARMUP = 3_000
private const val DEFAULT_SAMPLES = 10_000

/**
 * Time [bench] and return its [BenchmarkResult]. Runs [Benchmark.warmup]
 * untimed iterations to let the JIT settle, then times [Benchmark.samples]
 * iterations of [Benchmark.run]. A fresh [Blackhole] and the fixture from
 * [Benchmark.setup] are passed each iteration.
 */
fun timeOp(bench: Benchmark): BenchmarkResult {
    val hole = Blackhole()
    for (i in 0 until bench.warmup) {
        val fixture = bench.setup()
        bench.run(hole, fixture)
    }
    val fixture = bench.setup()
    val start = System.nanoTime()
    for (i in 0 until bench.samples) {
        bench.run(hole, fixture)
    }
    val elapsed = (System.nanoTime() - start).toDouble() / 1_000_000_000.0
    hole.consume(fixture)
    return BenchmarkResult(
        group = bench.group,
        name = bench.name,
        samples = bench.samples,
        totalSeconds = elapsed,
        perOpSeconds = elapsed / bench.samples,
    )
}

// Module-private blackhole for warmups where we don't have one threaded through.
private object BlackholeSink : Blackhole()

// -- Constants (parity with lazily-rs benches/context.rs) -------------------

private const val FAN_OUT_WIDTH_32 = 32
private const val FAN_OUT_WIDTH_256 = 256
private const val MEMO_CHAIN_DEPTH = 32
private const val BATCH_STORM_CELLS = 64
private const val SET_CELL_INVALIDATION_FAN_OUT = 512
private const val CONTENTION_ITERS_PER_WORKER = 128
private const val CONTENTION_BATCH_CELLS_PER_WORKER = 4
private val THREAD_WORKERS = intArrayOf(1, 2, 4, 8, 16)
private val EFFECT_THREAD_WORKERS = intArrayOf(8, 16)

// Phase-2 perf-quick-win bench constants.
private const val IPC_PAYLOAD_BYTES = 256
private val RELAY_RECLAIM_PAGES = intArrayOf(256, 1024, 4096)

// -- Setup helpers ----------------------------------------------------------

private data class FanOutFixture(
    val ctx: Any,
    val root: Any,
    val slots: List<*>,
)

private fun setupContextFanOut(width: Int): FanOutFixture {
    val ctx = Context()
    val root = ctx.source(0L)
    val slots = (0 until width).map { offset -> ctx.computed { get(root) + offset } }
    for (slot in slots) BlackholeSink.consume(ctx.get(slot as Computed<Long>))
    return FanOutFixture(ctx, root, slots)
}

private fun setupThreadSafeFanOut(width: Int): FanOutFixture {
    val ctx = ThreadSafeContext()
    val root = ctx.source(0L)
    val slots = (0 until width).map { offset -> ctx.computed { ctx.get(root) + offset } }
    for (slot in slots) BlackholeSink.consume(ctx.get(slot as ThreadSafeComputed<Long>))
    return FanOutFixture(ctx, root, slots)
}

private data class MemoChainFixture(
    val ctx: Any,
    val root: Any,
    val tail: Any,
)

private fun setupContextMemoChain(depth: Int): MemoChainFixture {
    val ctx = Context()
    val root = ctx.source(0L)
    var tail: Computed<Long> = ctx.computed { get(root) % 2L }
    repeat(depth) {
        val prev = tail
        tail = ctx.computed { get(prev) + 1L }
    }
    BlackholeSink.consume(ctx.get(tail))
    return MemoChainFixture(ctx, root, tail)
}

private fun setupThreadSafeMemoChain(depth: Int): MemoChainFixture {
    val ctx = ThreadSafeContext()
    val root = ctx.source(0L)
    var tail: ThreadSafeComputed<Long> = ctx.memo { ctx.get(root) % 2L }
    repeat(depth) {
        val prev = tail
        tail = ctx.computed { ctx.get(prev) + 1L }
    }
    BlackholeSink.consume(ctx.get(tail))
    return MemoChainFixture(ctx, root, tail)
}

private data class BatchStormFixture(
    val ctx: Any,
    val cells: List<*>,
    val read: () -> Long,
)

private fun setupContextBatchStorm(cellsLen: Int): BatchStormFixture {
    val ctx = Context()
    val cells = (0 until cellsLen).map { idx -> ctx.source(idx.toLong()) }
    val cellsForEffect = cells.toList()
    val sink = longArrayOf(0L)
    ctx.effect {
        var total = 0L
        for (cell in cellsForEffect) total += get(cell as Source<Long>)
        sink[0] = total
        null
    }
    return BatchStormFixture(ctx, cells) { sink[0] }
}

private fun setupThreadSafeBatchStorm(cellsLen: Int): BatchStormFixture {
    val ctx = ThreadSafeContext()
    val cells = (0 until cellsLen).map { idx -> ctx.source(idx.toLong()) }
    val cellsForEffect = cells.toList()
    val sink = java.util.concurrent.atomic.AtomicLong(0L)
    ctx.effect {
        var total = 0L
        for (cell in cellsForEffect) total += ctx.get(cell as ThreadSafeSource<Long>)
        sink.set(total)
        null
    }
    return BatchStormFixture(ctx, cells) { sink.get() }
}

// -- Benchmark case builders ------------------------------------------------

fun benchCachedReads(): List<BenchmarkResult> {
    val group = "cached_reads"
    return listOf(
        timeOp(Benchmark(group, "context", setup = {
            val ctx = Context()
            val root = ctx.source(21L)
            val doubled = ctx.computed { get(root) * 2L }
            BlackholeSink.consume(ctx.get(doubled))
            Triple(ctx, root, doubled)
        }) { hole, fixture ->
            val f = fixture as Triple<Context, Source<Long>, Computed<Long>>
            hole.consume(f.first.get(f.third as Computed<Long>))
        }),
        timeOp(Benchmark(group, "thread_safe_context", setup = {
            val ctx = ThreadSafeContext()
            val root = ctx.source(21L)
            val doubled = ctx.computed { ctx.get(root) * 2L }
            BlackholeSink.consume(ctx.get(doubled))
            Triple(ctx, root, doubled)
        }) { hole, fixture ->
            val f = fixture as Triple<ThreadSafeContext, ThreadSafeSource<Long>, ThreadSafeComputed<Long>>
            hole.consume(f.first.get(f.third as ThreadSafeComputed<Long>))
        }),
    )
}

fun benchColdFirstGet(): List<BenchmarkResult> {
    val group = "cold_first_get"
    return listOf(
        timeOp(Benchmark(group, "context", warmup = 200, samples = 1_000, setup = { 0 }) { hole, _ ->
            // Per-iteration fresh context + slot (build is part of the op).
            val ctx = Context()
            val root = ctx.source(21L)
            val doubled = ctx.computed { get(root) * 2L }
            hole.consume(ctx.get(doubled))
        }),
        timeOp(Benchmark(group, "thread_safe_context", warmup = 200, samples = 1_000, setup = { 0 }) { hole, _ ->
            val ctx = ThreadSafeContext()
            val root = ctx.source(21L)
            val doubled = ctx.computed { ctx.get(root) * 2L }
            hole.consume(ctx.get(doubled))
        }),
    )
}

fun benchDependencyFanOut(): List<BenchmarkResult> {
    val group = "dependency_fan_out"
    val out = ArrayList<BenchmarkResult>()
    for (width in intArrayOf(FAN_OUT_WIDTH_32, FAN_OUT_WIDTH_256)) {
        out += timeOp(Benchmark(group, "context/$width", samples = 2_000, setup = {
            setupContextFanOut(width)
        }) { hole, fixture ->
            val f = fixture as FanOutFixture
            val ctx = f.ctx as Context
            (f.root as Source<Long>).set(ctx, 1L)
            var total = 0L
            for (slot in f.slots) total += ctx.get(slot as Computed<Long>)
            hole.consume(total)
        })
        out += timeOp(Benchmark(group, "thread_safe_context/$width", samples = 2_000, setup = {
            setupThreadSafeFanOut(width)
        }) { hole, fixture ->
            val f = fixture as FanOutFixture
            val ctx = f.ctx as ThreadSafeContext
            ctx.set(f.root as ThreadSafeSource<Long>, 1L)
            var total = 0L
            for (slot in f.slots) total += ctx.get(slot as ThreadSafeComputed<Long>)
            hole.consume(total)
        })
    }
    return out
}

fun benchSetCellInvalidation(): List<BenchmarkResult> {
    val group = "set_cell_invalidation"
    return listOf(
        timeOp(Benchmark(group, "high_fan_out/$SET_CELL_INVALIDATION_FAN_OUT", samples = 1_000, setup = {
            setupThreadSafeFanOut(SET_CELL_INVALIDATION_FAN_OUT)
        }) { hole, fixture ->
            val f = fixture as FanOutFixture
            val ctx = f.ctx as ThreadSafeContext
            ctx.set(f.root as ThreadSafeSource<Long>, 1L)
            hole.consume(f.slots.size)
        }),
    )
}

fun benchMemoEqualitySuppression(): List<BenchmarkResult> {
    val group = "memo_equality_suppression"
    return listOf(
        timeOp(Benchmark(group, "context", samples = 5_000, setup = {
            setupContextMemoChain(MEMO_CHAIN_DEPTH)
        }) { hole, fixture ->
            val f = fixture as MemoChainFixture
            val ctx = f.ctx as Context
            (f.root as Source<Long>).set(ctx, 2L)
            hole.consume(ctx.get(f.tail as Computed<Long>))
        }),
        timeOp(Benchmark(group, "thread_safe_context", samples = 5_000, setup = {
            setupThreadSafeMemoChain(MEMO_CHAIN_DEPTH)
        }) { hole, fixture ->
            val f = fixture as MemoChainFixture
            val ctx = f.ctx as ThreadSafeContext
            ctx.set(f.root as ThreadSafeSource<Long>, 2L)
            hole.consume(ctx.get(f.tail as ThreadSafeComputed<Long>))
        }),
    )
}

fun benchEffectFlushing(): List<BenchmarkResult> {
    val group = "effect_flushing"
    return listOf(
        timeOp(Benchmark(group, "context", samples = 5_000, setup = {
            val ctx = Context()
            val root = ctx.source(0L)
            val seen = longArrayOf(0L)
            ctx.effect {
                seen[0] += get(root)
                null
            }
            Fixture3(ctx, root, seen)
        }) { hole, fixture ->
            val f = fixture as Fixture3<Context, Source<Long>, LongArray>
            val ctx = f.a
            f.b.set(ctx, f.c[0] + 1L)
            hole.consume(f.c[0])
        }),
        timeOp(Benchmark(group, "thread_safe_context", samples = 5_000, setup = {
            val ctx = ThreadSafeContext()
            val root = ctx.source(0L)
            val seen = java.util.concurrent.atomic.AtomicLong(0L)
            ctx.effect {
                seen.addAndGet(ctx.get(root))
                null
            }
            Fixture3(ctx, root, seen)
        }) { hole, fixture ->
            val f = fixture as Fixture3<ThreadSafeContext, ThreadSafeSource<Long>, java.util.concurrent.atomic.AtomicLong>
            val ctx = f.a
            ctx.set(f.b, f.c.get() + 1L)
            hole.consume(f.c.get())
        }),
    )
}

fun benchBatchStorms(): List<BenchmarkResult> {
    val group = "batch_storms"
    return listOf(
        timeOp(Benchmark(group, "context/$BATCH_STORM_CELLS", samples = 2_000, setup = {
            setupContextBatchStorm(BATCH_STORM_CELLS)
        }) { hole, fixture ->
            val f = fixture as BatchStormFixture
            val ctx = f.ctx as Context
            var base = BATCH_STORM_CELLS.toLong() + 1
            ctx.batch {
                for ((offset, cell) in (f.cells as List<Source<Long>>).withIndex()) {
                    set(cell, base + offset)
                }
            }
            hole.consume(f.read())
            base += BATCH_STORM_CELLS
        }),
        timeOp(Benchmark(group, "thread_safe_context/$BATCH_STORM_CELLS", samples = 2_000, setup = {
            setupThreadSafeBatchStorm(BATCH_STORM_CELLS)
        }) { hole, fixture ->
            val f = fixture as BatchStormFixture
            val ctx = f.ctx as ThreadSafeContext
            var base = BATCH_STORM_CELLS.toLong() + 1
            ctx.batch {
                for ((offset, cell) in (f.cells as List<ThreadSafeSource<Long>>).withIndex()) {
                    set(cell, base + offset)
                }
            }
            hole.consume(f.read())
            base += BATCH_STORM_CELLS
        }),
    )
}

fun benchTypedCacheReads(): List<BenchmarkResult> {
    val group = "typed_cache_reads"
    return listOf(
        timeOp(Benchmark(group, "context_slot", setup = {
            val ctx = Context()
            val cell = ctx.source(42L)
            val slot = ctx.computed { get(cell) }
            BlackholeSink.consume(ctx.get(slot))
            Fixture2(ctx, slot)
        }) { hole, fixture ->
            val f = fixture as Fixture2<Context, Computed<Long>>
            hole.consume(f.a.get(f.b))
        }),
        timeOp(Benchmark(group, "context_cell", setup = {
            val ctx = Context()
            val cell = ctx.source(99L)
            Fixture2(ctx, cell)
        }) { hole, fixture ->
            val f = fixture as Fixture2<Context, Source<Long>>
            hole.consume(f.a.get(f.b))
        }),
        timeOp(Benchmark(group, "thread_safe_slot", setup = {
            val ctx = ThreadSafeContext()
            val cell = ctx.source(42L)
            val slot = ctx.computed { ctx.get(cell) }
            BlackholeSink.consume(ctx.get(slot))
            Fixture2(ctx, slot)
        }) { hole, fixture ->
            val f = fixture as Fixture2<ThreadSafeContext, ThreadSafeComputed<Long>>
            hole.consume(f.a.get(f.b))
        }),
        timeOp(Benchmark(group, "thread_safe_cell", setup = {
            val ctx = ThreadSafeContext()
            val cell = ctx.source(99L)
            Fixture2(ctx, cell)
        }) { hole, fixture ->
            val f = fixture as Fixture2<ThreadSafeContext, ThreadSafeSource<Long>>
            hole.consume(f.a.get(f.b))
        }),
    )
}

// Typed mutable 2/3-tuples so fixtures survive the Any? boundary without casts
// losing component types (avoids @Suppress-on-destructuring issues).
private class Fixture2<A, B>(val a: A, val b: B)
private class Fixture3<A, B, C>(val a: A, val b: B, val c: C)

// -- Thread-safe contention (parity with lazily-rs thread_safe_contention) ---

private fun runWorkersWithBarrier(
    workers: Int,
    body: (workerIdx: Int, sink: java.util.concurrent.atomic.AtomicLong) -> Unit,
): Long {
    val barrier = java.util.concurrent.CyclicBarrier(workers)
    val sink = java.util.concurrent.atomic.AtomicLong(0L)
    val threads = (0 until workers).map { workerIdx ->
        Thread {
            barrier.await()
            body(workerIdx, sink)
        }.also { it.isDaemon = true }
    }
    for (t in threads) t.start()
    for (t in threads) t.join()
    return sink.get()
}

private fun runThreadSafeSameSlotContention(workers: Int): Long {
    val ctx = ThreadSafeContext()
    val root = ctx.source(1L)
    val value = ctx.computed { ctx.get(root) + 1L }
    BlackholeSink.consume(ctx.get(value))
    return runWorkersWithBarrier(workers) { workerIdx, sink ->
        var sum = 0L
        for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
            val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter).toLong()
            ctx.set(root, next)
            sum += ctx.get(value)
        }
        sink.addAndGet(sum)
    }
}

private fun runThreadSafeIndependentSlotContention(workers: Int): Long {
    val ctx = ThreadSafeContext()
    val roots = (0 until workers).map { ctx.source(it.toLong()) }
    val values = roots.map { root -> ctx.computed { ctx.get(root) + 1L } }
    for (value in values) BlackholeSink.consume(ctx.get(value))
    return runWorkersWithBarrier(workers) { workerIdx, sink ->
        val root = roots[workerIdx]
        val value = values[workerIdx]
        var sum = 0L
        for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
            val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter).toLong()
            ctx.set(root, next)
            sum += ctx.get(value)
        }
        sink.addAndGet(sum)
    }
}

private fun runThreadSafeReadMostlyContention(workers: Int): Long {
    val ctx = ThreadSafeContext()
    val root = ctx.source(1L)
    val value = ctx.computed { ctx.get(root) + 1L }
    BlackholeSink.consume(ctx.get(value))
    return runWorkersWithBarrier(workers) { workerIdx, sink ->
        var sum = 0L
        for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
            if (workerIdx == 0) ctx.set(root, iter.toLong())
            sum += ctx.get(value)
        }
        sink.addAndGet(sum)
    }
}

private fun runThreadSafeBatchedWriteBursts(workers: Int): Long {
    val ctx = ThreadSafeContext()
    val workerCells = (0 until workers).map { worker ->
        (0 until CONTENTION_BATCH_CELLS_PER_WORKER).map { offset ->
            ctx.source((worker * CONTENTION_BATCH_CELLS_PER_WORKER + offset).toLong())
        }
    }
    val allCells = workerCells.flatten()
    val total = ctx.computed {
        var sum = 0L
        for (cell in allCells) sum += ctx.get(cell)
        sum
    }
    BlackholeSink.consume(ctx.get(total))
    return runWorkersWithBarrier(workers) { workerIdx, sink ->
        val cells = workerCells[workerIdx]
        var sum = 0L
        for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
            ctx.batch {
                for ((offset, cell) in cells.withIndex()) {
                    val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter) * CONTENTION_BATCH_CELLS_PER_WORKER + offset
                    set(cell, next.toLong())
                }
            }
            sum += ctx.get(total)
        }
        sink.addAndGet(sum)
    }
}

fun benchThreadSafeContention(): List<BenchmarkResult> {
    val group = "thread_safe_contention"
    val out = ArrayList<BenchmarkResult>()
    val cases = listOf(
        "same_slot_write_read" to { w: Int -> runThreadSafeSameSlotContention(w) },
        "independent_slots" to { w: Int -> runThreadSafeIndependentSlotContention(w) },
        "read_mostly_waiters" to { w: Int -> runThreadSafeReadMostlyContention(w) },
        "batched_write_bursts" to { w: Int -> runThreadSafeBatchedWriteBursts(w) },
    )
    for ((name, runner) in cases) {
        for (workers in THREAD_WORKERS) {
            out += timeOp(Benchmark(group, "$name/$workers", warmup = 200, samples = 30) { hole, _ ->
                hole.consume(runner(workers))
            })
        }
    }
    return out
}

fun benchEffectContention(): List<BenchmarkResult> {
    val group = "thread_safe_effect_contention"
    val out = ArrayList<BenchmarkResult>()

    fun queueCoalescing(workers: Int): Long {
        val ctx = ThreadSafeContext()
        val workerCells = (0 until workers).map { worker ->
            (0 until CONTENTION_BATCH_CELLS_PER_WORKER).map { offset ->
                ctx.source((worker * CONTENTION_BATCH_CELLS_PER_WORKER + offset).toLong())
            }
        }
        val allCells = workerCells.flatten()
        val sink = java.util.concurrent.atomic.AtomicLong(0L)
        val runs = java.util.concurrent.atomic.AtomicLong(0L)
        ctx.effect {
            runs.incrementAndGet()
            var total = 0L
            for (cell in allCells) total += ctx.get(cell)
            sink.set(total)
            null
        }
        return runWorkersWithBarrier(workers) { workerIdx, acc ->
            val cells = workerCells[workerIdx]
            var sum = 0L
            for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
                ctx.batch {
                    for ((offset, cell) in cells.withIndex()) {
                        val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter) * CONTENTION_BATCH_CELLS_PER_WORKER + offset
                        set(cell, next.toLong())
                    }
                }
                sum += sink.get() + runs.get()
            }
            acc.addAndGet(sum)
        }
    }

    fun cleanupExecution(workers: Int): Long {
        val ctx = ThreadSafeContext()
        val cells = (0 until workers).map { ctx.source(it.toLong()) }
        val sink = java.util.concurrent.atomic.AtomicLong(0L)
        val cleanups = java.util.concurrent.atomic.AtomicLong(0L)
        val effect = ctx.effect {
            var total = 0L
            for (cell in cells) total += ctx.get(cell)
            sink.set(total)
            val localCleanups = cleanups
            { localCleanups.incrementAndGet() }
        }
        val total = runWorkersWithBarrier(workers) { workerIdx, acc ->
            val cell = cells[workerIdx]
            var sum = 0L
            for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
                val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter).toLong()
                ctx.set(cell, next)
                sum += sink.get() + cleanups.get()
            }
            acc.addAndGet(sum)
        }
        ctx.disposeEffect(effect)
        return total + cleanups.get()
    }

    fun batchFlush(workers: Int): Long {
        val ctx = ThreadSafeContext()
        val workerCells = (0 until workers).map { worker ->
            (0 until CONTENTION_BATCH_CELLS_PER_WORKER).map { offset ->
                ctx.source((worker * CONTENTION_BATCH_CELLS_PER_WORKER + offset).toLong())
            }
        }
        val allCells = workerCells.flatten()
        val total = ctx.computed {
            var sum = 0L
            for (cell in allCells) sum += ctx.get(cell)
            sum
        }
        val sink = java.util.concurrent.atomic.AtomicLong(0L)
        ctx.effect {
            sink.set(ctx.get(total))
            null
        }
        return runWorkersWithBarrier(workers) { workerIdx, acc ->
            val cells = workerCells[workerIdx]
            var sum = 0L
            for (iter in 0 until CONTENTION_ITERS_PER_WORKER) {
                ctx.batch {
                    batch {
                        for ((offset, cell) in cells.withIndex()) {
                            if (offset % 2 == 0) {
                                val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter) * CONTENTION_BATCH_CELLS_PER_WORKER + offset
                                set(cell, next.toLong())
                            }
                        }
                    }
                    for ((offset, cell) in cells.withIndex()) {
                        if (offset % 2 == 1) {
                            val next = (workerIdx * CONTENTION_ITERS_PER_WORKER + iter) * CONTENTION_BATCH_CELLS_PER_WORKER + offset
                            set(cell, next.toLong())
                        }
                    }
                }
                sum += sink.get()
            }
            acc.addAndGet(sum)
        }
    }

    val cases = listOf(
        "queue_coalescing" to ::queueCoalescing,
        "cleanup_execution" to ::cleanupExecution,
        "batch_flush" to ::batchFlush,
    )
    for ((name, runner) in cases) {
        for (workers in EFFECT_THREAD_WORKERS) {
            out += timeOp(Benchmark(group, "$name/$workers", warmup = 100, samples = 20) { hole, _ ->
                hole.consume(runner(workers))
            })
        }
    }
    return out
}

// -- Phase-2 perf quick wins (#lzktbytearray / #lzktsublistclear / #lzktclocklock) --

/**
 * IPC payload construction + serialization (#lzktbytearray / #lzktindexedloop):
 * `IpcValue.Inline`/`NodeState.Payload` now hold a primitive `ByteArray` with no
 * eager init validation, and `bytesToJson` walks it by index. Measures the
 * realistic construct-then-serialize cost of a small inline payload.
 */
fun benchIpcPayloadSerialization(): List<BenchmarkResult> {
    val group = "ipc_payload"
    val payload = ByteArray(IPC_PAYLOAD_BYTES) { it.toByte() }
    return listOf(
        timeOp(Benchmark(group, "inline_serialize/$IPC_PAYLOAD_BYTES", setup = { payload }) { hole, fixture ->
            val src = fixture as ByteArray
            hole.consume(IpcValue.Inline(src).toJson())
        }),
        timeOp(Benchmark(group, "payload_serialize/$IPC_PAYLOAD_BYTES", setup = { payload }) { hole, fixture ->
            val src = fixture as ByteArray
            hole.consume(NodeState.Payload(src).toJson())
        }),
    )
}

/**
 * Build a fresh [SpillStore], spill [pages] cold pages, ack the whole tail, and
 * reclaim (#lzktsublistclear). The reclaim sweep was O(N²) (N `removeAt(0)`
 * shifts); `subList.clear()` makes the whole sweep O(N), so the per-op cost
 * scales linearly with the page count instead of quadratically.
 */
private fun runRelayReclaimCycle(pages: Int): Long {
    val store = SpillStore<Long>(SpillMode.AppendCompact, pageSize = Long.MAX_VALUE, keepLatest())
    var lastId = 0L
    for (i in 0 until pages) {
        store.spill(i.toLong(), 1L)
        lastId = i.toLong()
    }
    store.ackThrough(lastId)
    store.reclaim()
    return store.pageCount().toLong()
}

fun benchRelayReclaim(): List<BenchmarkResult> {
    val group = "relay_reclaim"
    val out = ArrayList<BenchmarkResult>()
    for (pages in RELAY_RECLAIM_PAGES) {
        out += timeOp(Benchmark(group, "append_reclaim/$pages", warmup = 50, samples = 200) { hole, _ ->
            hole.consume(runRelayReclaimCycle(pages))
        })
    }
    return out
}

/**
 * HLC clock throughput (#lzktclocklock): [CrdtClock.tick]/[observe] no longer
 * take a monitor (the reactive [Context] driving a replica is single-threaded),
 * so each op is the raw wall-read + compare/increment. One op per sample.
 */
fun benchHlcTick(): List<BenchmarkResult> {
    val group = "hlc_tick"
    return listOf(
        timeOp(Benchmark(group, "tick", setup = { CrdtClock(peer = 1) }) { hole, fixture ->
            val clock = fixture as CrdtClock
            hole.consume(clock.tick())
        }),
        timeOp(Benchmark(group, "observe", setup = { CrdtClock(peer = 1) to WireStamp(0L, 0L, 2L) }) { hole, fixture ->
            val (clock, stamp) = fixture as Pair<CrdtClock, WireStamp>
            clock.observe(stamp)
            hole.consume(clock)
        }),
    )
}

// -- Entry point ------------------------------------------------------------

/**
 * Run the full reactive-core benchmark suite and return each result. Mirrors
 * the lazily-rs `benches/context.rs` coverage: cached reads, cold first get,
 * dependency fan-out, set-cell invalidation, memo equality suppression, effect
 * flushing, batch storms, typed cache reads, and thread-safe contention.
 */
fun runBenchmarks(): List<BenchmarkResult> =
    benchCachedReads() +
        benchColdFirstGet() +
        benchDependencyFanOut() +
        benchSetCellInvalidation() +
        benchMemoEqualitySuppression() +
        benchEffectFlushing() +
        benchBatchStorms() +
        benchTypedCacheReads() +
        benchThreadSafeContention() +
        benchEffectContention() +
        benchIpcPayloadSerialization() +
        benchRelayReclaim() +
        benchHlcTick()

fun main() {
    println("lazily-kt benchmarks")
    println("====================")
    val results = runBenchmarks()
    println()
    var group = ""
    for (result in results) {
        if (result.group != group) {
            group = result.group
            println("-- $group --")
        }
        println("  " + result.format())
    }
    println()
    println("(${results.size} cases; JVM ${System.getProperty("java.version")})")
}
