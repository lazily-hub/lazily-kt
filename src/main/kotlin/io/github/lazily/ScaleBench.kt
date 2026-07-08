@file:JvmName("ScaleBench")

package io.github.lazily

/**
 * Spreadsheet-scale benchmark — mirrors lazily-rs `benches/scale.rs`
 * (`#lzscalebench`). Models a spreadsheet-shaped graph: `N` input cells plus `N`
 * formula slots, where `formula[i] = input[i] + input[i-1]` (local fan-in, like
 * a column of `=A_i + A_{i-1}`). Four cases cover the spreadsheet lifecycle:
 *
 * - `build` — construct all `2N` nodes.
 * - `cold_full_recalc` — first read of every formula (forces every compute).
 * - `viewport_recalc` — edit one input, then read only a bounded viewport; the
 *   lazy-pull win (off-viewport formulas stay dirty and never recompute).
 * - `full_recalc_invalidate_all` — touch every input, then read every formula.
 *
 * Run via `make benchmark-scale` (default N = 1,000,000) or override the size:
 *
 * ```bash
 * LAZILY_SCALE_N=2000000 make benchmark-scale
 * ```
 */

private data class Graph(
    val ctx: Context,
    val inputs: List<CellHandle<Long>>,
    val formulas: List<SlotHandle<Long>>,
)

private fun scaleN(): Int =
    System.getenv("LAZILY_SCALE_N")?.toIntOrNull()
        ?: DEFAULT_SCALE_N

private fun viewportSize(n: Int): Int =
    (System.getenv("LAZILY_SCALE_VIEWPORT")?.toIntOrNull() ?: DEFAULT_VIEWPORT).coerceAtMost(n)

private const val DEFAULT_SCALE_N = 1_000_000
private const val DEFAULT_VIEWPORT = 1_000

private fun buildGraph(n: Int): Graph {
    val ctx = Context()
    val inputs = ArrayList<CellHandle<Long>>(n)
    for (i in 0 until n) inputs += ctx.cell(i.toLong())
    val formulas = ArrayList<SlotHandle<Long>>(n)
    for (i in 0 until n) {
        val a = inputs[i]
        val b = inputs[i.coerceAtLeast(1) - 1]
        formulas += ctx.computed { ctx.getCell(a) + ctx.getCell(b) }
    }
    return Graph(ctx, inputs, formulas)
}

private fun readAll(ctx: Context, formulas: List<SlotHandle<Long>>): Long {
    var acc = 0L
    for (f in formulas) acc += ctx.get(f)
    return acc
}

data class ScaleResult(val name: String, val n: Int, val elapsedMillis: Double, val perElementNanos: Double) {
    fun format(): String {
        val perUs = elapsedMillis * 1000.0 / n
        val unit = if (perUs >= 1.0) String.format("%.3f us/element", perUs) else String.format("%.1f ns/element", perUs * 1000.0)
        return String.format("%-28s %12d elements  %11.3f ms  %12s", name, n, elapsedMillis, unit)
    }
}

private fun timeMillis(name: String, n: Int, body: () -> Long): ScaleResult {
    val start = System.nanoTime()
    val acc = body()
    val elapsedMs = (System.nanoTime() - start).toDouble() / 1_000_000.0
    BlackholeSinkScale.consume(acc)
    return ScaleResult(name, n, elapsedMs, elapsedMs * 1_000_000.0 / n)
}

private object BlackholeSinkScale {
    @Volatile private var sink: Long = 0L
    fun consume(v: Long) { sink = sink xor (v * 0x9E3779B97F4A7C15uL.toLong()) }
}

fun runScaleBenchmarks(n: Int = scaleN()): List<ScaleResult> {
    val viewport = viewportSize(n)
    val results = ArrayList<ScaleResult>()

    // build: construct all 2N nodes from scratch.
    results += timeMillis("build", n) {
        val graph = buildGraph(n)
        BlackholeSinkScale.consume((graph.inputs.size + graph.formulas.size).toLong())
        graph.inputs.size.toLong()
    }

    // cold_full_recalc: first read of every formula forces every compute.
    results += timeMillis("cold_full_recalc", n) {
        val graph = buildGraph(n)
        readAll(graph.ctx, graph.formulas)
    }

    // viewport_recalc: build + warm ONCE; each iteration edits one input and
    // reads only a viewport window. Off-viewport formulas stay dirty.
    val mid = n / 2
    val lo = (mid - viewport / 2).coerceAtLeast(0)
    val hi = (lo + viewport).coerceAtMost(n)
    run {
        val graph = buildGraph(n)
        BlackholeSinkScale.consume(readAll(graph.ctx, graph.formulas))
        var tick = 0L
        val iters = 20
        results += timeMillis("viewport_recalc", n) {
            var acc = 0L
            repeat(iters) {
                tick += 1
                graph.ctx.setCell(graph.inputs[mid], tick)
                for (f in graph.formulas.subList(lo, hi)) acc += graph.ctx.get(f)
            }
            acc
        }
    }

    // full_recalc_invalidate_all: build + warm ONCE; each iteration touches every
    // input and reads every formula (worst-case full-sheet edit).
    run {
        val graph = buildGraph(n)
        BlackholeSinkScale.consume(readAll(graph.ctx, graph.formulas))
        var tick = 0L
        val iters = 3
        results += timeMillis("full_recalc_invalidate_all", n) {
            var acc = 0L
            repeat(iters) {
                tick += 1
                val base = tick
                for ((i, cell) in graph.inputs.withIndex()) {
                    graph.ctx.setCell(cell, base + i)
                }
                acc += readAll(graph.ctx, graph.formulas)
            }
            acc
        }
    }

    return results
}

fun main() {
    val n = scaleN()
    println("lazily-kt scale benchmark (N=$n)")
    println("==============================")
    val results = runScaleBenchmarks(n)
    println()
    for (result in results) println(result.format())
    println()
    println("(JVM ${System.getProperty("java.version")}; set LAZILY_SCALE_N to change graph size)")
}
