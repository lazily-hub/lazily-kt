@file:JvmName("EdgeIndexCrossover")

package io.github.lazily

/**
 * Scan-vs-index crossover sweep (`#lzspecedgeindex`).
 *
 * The promote threshold in [EDGE_INDEX_THRESHOLD] is **not portable** — it is
 * the degree at which a linear scan of contiguous ints costs more than one
 * open-addressed probe, and both sides move with the language, the collection,
 * the hash, and the JIT. This measures it in Kotlin rather than importing
 * another binding's constant.
 *
 * Method: drive the real [SmallEdgeList] at each degree and report the cost of
 * one steady-state churn cycle. Run twice, with the index forced permanently off
 * and permanently on, and diff the columns:
 *
 * ```bash
 * ./gradlew edgeIndexCrossover -Plazily.edgeIndexThreshold=100000000   # scan
 * ./gradlew edgeIndexCrossover -Plazily.edgeIndexThreshold=0           # indexed
 * ```
 *
 * Two JVM runs rather than two classes in one run, because the threshold is a
 * static final read at class init and because a single run would leave the JIT
 * with a megamorphic call site that contaminates both columns.
 *
 * Driving the list directly rather than a whole `Context` is deliberate: a
 * Context recompute mixes edge registration with compute, allocation, and
 * propagation, and the crossover is a property of the registration path alone.
 *
 * Manual / on-demand.
 */

private const val WARMUP_OPS = 200_000
private const val MEASURE_OPS = 50_000
private const val SAMPLES = 31

private fun median(v: DoubleArray): Double {
    val s = v.sortedArray()
    return s[s.size / 2]
}

/**
 * Nanoseconds per remove + re-add churn cycle on a real [SmallEdgeList] already
 * holding [degree] edges.
 *
 * That churn cycle is the operation the reactive core actually performs:
 * `recomputeSlotNow` drops the node from each dependency's dependent list and
 * the tracked read immediately puts it back, so a live graph pays exactly this
 * on every recompute.
 *
 * Two earlier attempts at this measurement were both wrong, recorded here
 * because the shape of the error is the point:
 *
 * 1. Timing build-from-empty folded the index's one-time array allocation into
 *    the per-registration figure, so the "crossover" tracked the allocation
 *    rather than the scan.
 * 2. Hand-writing standalone scan and indexed classes compared a boxed
 *    `ArrayList<Int>` scan against a raw `IntArray` probe and reported the index
 *    winning at degree 2 — where the real class holds two unboxed `Int` fields
 *    and does not allocate at all. The model was not the code.
 *
 * So this drives the shipping class, and the two columns come from two JVM runs
 * with different `-Dlazily.edgeIndexThreshold` settings rather than from a model.
 */
private fun measureChurn(degree: Int): Double {
    val edges = SmallEdgeList()
    for (i in 0 until degree) edges.add(i)
    val samples = DoubleArray(SAMPLES)
    for (s in 0 until SAMPLES) {
        var sink = 0
        val start = System.nanoTime()
        for (op in 0 until MEASURE_OPS) {
            val victim = op % degree
            edges.remove(victim)
            edges.add(victim)
            sink += edges.size
        }
        samples[s] = (System.nanoTime() - start).toDouble() / MEASURE_OPS
        check(sink > 0)
    }
    return median(samples)
}

private val DEGREES: IntArray =
    System.getProperty("lazily.crossoverDegrees")
        ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toIntArray()
        ?: intArrayOf(
            3, 4, 6, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64, 80, 96, 128, 160, 256, 512, 1024, 4096,
        )

fun main() {
    // Warm the churn path before the first degree is measured.
    var warm = 0
    val w = SmallEdgeList()
    for (i in 0 until 48) w.add(i)
    for (op in 0 until WARMUP_OPS) {
        val v = op % 48
        w.remove(v); w.add(v)
        warm += w.size
    }
    check(warm > 0)

    println("edge-index crossover sweep (#lzspecedgeindex)")
    println("medians of $SAMPLES samples x $MEASURE_OPS ops, ns per remove+re-add churn cycle")
    println("promote=$EDGE_INDEX_THRESHOLD demote=$EDGE_INDEX_DEMOTE_THRESHOLD")
    println()
    println(String.format("%8s %12s", "degree", "ns/churn"))
    for (d in DEGREES) {
        println(String.format("%8d %12.2f", d, measureChurn(d)))
    }
}
