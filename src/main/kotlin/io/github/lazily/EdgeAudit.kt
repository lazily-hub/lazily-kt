@file:JvmName("EdgeAudit")

package io.github.lazily

/**
 * Fan-out audit for the two quadratics found in sibling bindings
 * (`#lzspecedgeindex`), neither of which is the edge *registration* dedup that
 * [EdgeIndexLoad] already covers.
 *
 * **Defect A — edge removal.** `lazily-zig` and `lazily-dart` both scanned the
 * dependent list linearly on *removal*, which runs during cascade and teardown.
 * zig proved the two halves do not compose: with registration fixed but `remove`
 * reverted to a scan, teardown returned to its unfixed baseline. So a green
 * registration ladder is no evidence at all about removal, and this measures the
 * removal path directly.
 *
 * **Defect B — the effect-flush path.** `lazily-cpp` popped an effect off the
 * pending queue, unscheduled it, and then scanned the *entire remaining queue*
 * for an id that provably could not be in it — O(W^2) per publish, answered in
 * O(1) by a flag it already had. There the edge index alone did not fix wide
 * notify. This measures notify per effect at fixed effect count.
 *
 * ## Why the control is wide-vs-narrow and not absolute growth
 *
 * Absolute growth across a width ladder is an unsound test. It moved 2.63x on a
 * zig engine that was *correct*, and `notify` grows 6.8x from cache effects
 * alone — both would read as a defect. So every rung here runs two arms with
 * **identical node counts and identical effect counts**, differing only in
 * fan-out:
 *
 * - `wide`   — one topic cell with `W` effects on it (fan-out `W`).
 * - `narrow` — `W/2` topic cells with two effects each (fan-out 2), plus filler
 *   cells in the wide arm so both arms allocate the same number of nodes.
 *
 * Both arms flush exactly `W` effects and touch the same amount of memory. A
 * per-edge quadratic can only show up in `wide`, so the `wide/narrow` ratio
 * isolates fan-out from cache, allocator, and GC effects that hit both equally.
 * A flat ratio across the ladder is the pass condition; a ratio that climbs with
 * `W` is the defect.
 *
 * ## Method
 *
 * Ratios, not absolute nanoseconds. This box runs other measuring agents
 * concurrently, so absolute ns/effect is not a stable property of the machine —
 * but both arms of a ratio see the same contention, so ratios survive it. Arms
 * are therefore **interleaved within a rung**, never run as two separate passes,
 * and the reported figure is a median over repeats. Load average is printed with
 * the results so a contaminated run is identifiable as such.
 *
 * Manual / on-demand only:
 *
 * ```bash
 * ./gradlew edgeAudit
 * # Defect A naive arm. Quadratic by construction, so it needs a lower ceiling.
 * ./gradlew edgeAudit -Plazily.forceScanRemove=true -Plazily.auditMaxWidth=65536
 * ```
 *
 * ## What this found
 *
 * **Defect A is absent.** Removal is genuinely O(1), not merely believed to be.
 * The forced-naive arm is what establishes it — wide/narrow ratios at identical
 * rungs, interleaved arms, load average 3.3-4.0 throughout:
 *
 * ```
 * width          teardown w/n            notify w/n
 *              fixed     forced       fixed     forced
 *   256         1.62x      2.81x       1.20x      7.23x
 *  1024         1.90x     11.61x       1.31x     30.24x
 *  4096         2.69x     44.82x       1.04x     64.62x
 * 16384         2.12x    158.41x       2.71x    175.50x
 * 65536         1.59x    755.65x       1.66x   1072.58x
 * ```
 *
 * Put the scan back and teardown degrades 755x against its own equal-node-count
 * control at width 65536, drifting 269x across the ladder; the shipped path is
 * flat (drift 0.98x). So the harness detects a removal quadratic with three
 * orders of magnitude of margin, and does not detect one here.
 *
 * **A Defect-B-shaped quadratic was present, and is fixed** — a scan of the
 * pending-effect collection for an id provably not in it, answered in O(1) by
 * the `scheduledEffects` set that already existed. `lazily-cpp` had it in
 * `run_effect`; here it was in `Context.disposeEffect`, which is why the
 * *teardown* column carried it rather than `notify`. Before the fix, teardown at
 * width 262144 was 209482 ns/effect against an 19.6 ns/effect control — 10677x,
 * growing linearly with width. After, 45.6 ns against 18.5 ns — 2.46x, flat.
 * See `Context.disposeEffect` for the mechanism, which is not the obvious one.
 */

private val AUDIT_LADDER = intArrayOf(256, 1_024, 4_096, 16_384, 65_536, 262_144)

private const val REPEATS = 5

private fun auditMedian(values: DoubleArray): Double {
    val sorted = values.sortedArray()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/** One arm's three phases, each normalised per effect. */
data class ArmSample(val buildNs: Double, val notifyNs: Double, val teardownNs: Double)

data class AuditRung(
    val width: Int,
    val wide: ArmSample,
    val narrow: ArmSample,
)

private var auditSink: Long = 0L

/**
 * Run one arm.
 *
 * @param topics how many topic cells to spread [width] effects across; 1 is the
 *   wide arm, `width / 2` the narrow control.
 * @param filler extra unused cells, so both arms allocate the same node count.
 */
private fun runArm(width: Int, topics: Int, filler: Int): ArmSample {
    val ctx = Context()
    val topicIds = ArrayList<Source<Long>>(topics)
    for (i in 0 until topics) topicIds += ctx.source(0L)
    repeat(filler) { ctx.source(0L) }

    val handles = ArrayList<Effect>(width)
    val perTopic = width / topics

    // Build: effect construction runs the body once, which is where the tracked
    // read registers the edge.
    val buildStart = System.nanoTime()
    for (i in 0 until width) {
        val t = topicIds[i / perTopic]
        handles += ctx.effect {
            auditSink += get(t)
            null
        }
    }
    val buildNs = (System.nanoTime() - buildStart).toDouble() / width

    // Notify: publish to every topic and flush. Both arms flush exactly `width`
    // effects; only the fan-out per publish differs. This is the Defect B probe.
    val notifySamples = DoubleArray(REPEATS)
    for (round in 0 until REPEATS) {
        val v = (round + 1).toLong()
        val start = System.nanoTime()
        for (t in topicIds) t.set(ctx, v)
        notifySamples[round] = (System.nanoTime() - start).toDouble() / width
    }
    val notifyNs = auditMedian(notifySamples)

    // Teardown: drop every effect. Each dispose removes one edge from its topic's
    // dependent list — degree `width` in the wide arm, degree 2 in the narrow
    // one. This is the Defect A probe.
    val teardownStart = System.nanoTime()
    for (h in handles) ctx.disposeEffect(h)
    val teardownNs = (System.nanoTime() - teardownStart).toDouble() / width

    return ArmSample(buildNs, notifyNs, teardownNs)
}

/** Interleave the arms so both see the same load; take the median of repeats. */
private fun runRungInterleaved(width: Int, repeats: Int): AuditRung {
    val wb = DoubleArray(repeats); val wn = DoubleArray(repeats); val wt = DoubleArray(repeats)
    val nb = DoubleArray(repeats); val nn = DoubleArray(repeats); val nt = DoubleArray(repeats)
    val narrowTopics = maxOf(1, width / 2)
    for (r in 0 until repeats) {
        // wide: 1 topic + (narrowTopics - 1) filler cells == narrow's node count.
        val w = runArm(width, topics = 1, filler = narrowTopics - 1)
        val n = runArm(width, topics = narrowTopics, filler = 0)
        wb[r] = w.buildNs; wn[r] = w.notifyNs; wt[r] = w.teardownNs
        nb[r] = n.buildNs; nn[r] = n.notifyNs; nt[r] = n.teardownNs
        System.gc()
    }
    return AuditRung(
        width,
        ArmSample(auditMedian(wb), auditMedian(wn), auditMedian(wt)),
        ArmSample(auditMedian(nb), auditMedian(nn), auditMedian(nt)),
    )
}

private fun auditWarmup() {
    repeat(60) {
        runArm(512, topics = 1, filler = 255)
        runArm(512, topics = 256, filler = 0)
    }
    check(auditSink != Long.MIN_VALUE)
}

fun main() {
    val forced = System.getProperty("lazily.forceScanRemove")?.toBoolean() ?: false
    println("edge audit: removal + effect-flush fan-out (#lzspecedgeindex)")
    println("forceScanRemove=$forced  edgeIndexThreshold=$EDGE_INDEX_THRESHOLD")
    println("load average: ${readLoadAverage()}")
    println()

    auditWarmup()

    println(
        String.format(
            "%9s %22s %22s %22s",
            "width", "build w/n", "notify w/n", "teardown w/n",
        ),
    )
    val rungs = ArrayList<AuditRung>()
    // The forced-naive arm is quadratic by construction, so it needs a lower
    // ceiling to finish at all — that it cannot reach the top rung in the time
    // the fixed build takes is itself part of the result.
    val maxWidth = System.getProperty("lazily.auditMaxWidth")?.toIntOrNull() ?: Int.MAX_VALUE
    for (width in AUDIT_LADDER) {
        if (width > maxWidth) break
        val r = runRungInterleaved(width, REPEATS)
        rungs += r
        println(
            String.format(
                "%9d  %7.1f %7.1f %5.2fx  %7.1f %7.1f %5.2fx  %7.1f %7.1f %5.2fx",
                width,
                r.wide.buildNs, r.narrow.buildNs, r.wide.buildNs / r.narrow.buildNs,
                r.wide.notifyNs, r.narrow.notifyNs, r.wide.notifyNs / r.narrow.notifyNs,
                r.wide.teardownNs, r.narrow.teardownNs, r.wide.teardownNs / r.narrow.teardownNs,
            ),
        )
    }
    println()
    println("load average after: ${readLoadAverage()}")
    println()
    assertAudit(rungs)
}

private fun readLoadAverage(): String =
    runCatching { java.io.File("/proc/loadavg").readText().trim() }.getOrDefault("n/a")

/**
 * The pass condition is a **flat** wide/narrow ratio, not a small one.
 *
 * A constant offset between the arms is expected and benign — the wide arm's
 * single dependent list is one big object with different cache behaviour than
 * `W/2` small ones. What a quadratic looks like is that ratio *growing* with
 * width, so each phase is checked by how much its ratio moves from the narrowest
 * rung to the widest, which cancels the constant.
 */
fun assertAudit(rungs: List<AuditRung>) {
    if (rungs.size < 2) {
        println("skip: need at least two rungs")
        return
    }
    val failures = ArrayList<String>()
    val lo = rungs.first()
    val hi = rungs.last()

    fun check(name: String, loR: Double, hiR: Double, limit: Double) {
        val drift = hiR / loR
        val msg = "$name wide/narrow ratio ${lo.width}->${hi.width}: " +
            "%.2fx -> %.2fx (drift %.2fx)".format(loR, hiR, drift)
        if (drift >= limit) failures += "FAIL $msg (want drift < %.2fx)".format(limit)
        else println("ok   $msg (want drift < %.2fx)".format(limit))
    }

    // A real O(W^2) removal at these widths is a 1000x drift, not a 4x one; the
    // limit only has to sit clear of measurement noise, not close to 1.
    check(
        "teardown",
        lo.wide.teardownNs / lo.narrow.teardownNs,
        hi.wide.teardownNs / hi.narrow.teardownNs,
        4.0,
    )
    check(
        "notify",
        lo.wide.notifyNs / lo.narrow.notifyNs,
        hi.wide.notifyNs / hi.narrow.notifyNs,
        4.0,
    )
    check(
        "build",
        lo.wide.buildNs / lo.narrow.buildNs,
        hi.wide.buildNs / hi.narrow.buildNs,
        4.0,
    )

    println()
    if (failures.isEmpty()) {
        println("ALL AUDIT ASSERTIONS PASSED")
    } else {
        failures.forEach { println(it) }
        throw AssertionError("${failures.size} audit assertion(s) failed")
    }
}
