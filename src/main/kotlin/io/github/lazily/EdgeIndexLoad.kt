@file:JvmName("EdgeIndexLoad")

package io.github.lazily

/**
 * Width-ladder pub/sub load test (`#lzspecedgeindex`).
 *
 * Mirrors `lazily-rs` `examples/pubsub_load.rs`. Existing bench suites
 * (`Benchmarks`, `ScaleBench`) measure scale as *node count* and hold fan-out
 * at 2, so edge-registration width was never a variable — which is exactly why
 * the O(n^2) dedup scan hid there. This measures the other axis: one topic cell
 * with `width` subscriber slots, so every subscriber registers an edge into the
 * same dependent list.
 *
 * Method is **climb, project, refuse**: never jump straight to the target. Each
 * rung measures bytes/subscriber, projects the next rung's footprint from *that*
 * measurement, and refuses to climb if the projection would not leave
 * [MEMORY_FLOOR_BYTES] of headroom.
 *
 * Manual / on-demand only — not wired into `make check` or CI. Run with:
 *
 * ```bash
 * ./gradlew edgeIndexLoad -Plazily.loadMaxWidth=1000000
 * # heap: -Plazily.loadHeap=12g
 * ```
 *
 * Rungs cluster around the promote threshold on purpose: a single shared
 * promote/demote boundary shows up as a spike at exactly threshold+1 and is
 * invisible at every other width.
 */

private val LADDER = intArrayOf(
    32, 64, 96, 128, 129, 160, 256, 1_024, 4_096, 65_536,
    262_144, 1_000_000, 4_000_000, 10_000_000,
)

private const val MEMORY_FLOOR_BYTES = 512L * 1024 * 1024

private const val NOTIFY_ROUNDS = 5

data class RungResult(
    val width: Int,
    val buildNanosPerSub: Double,
    val notifyNanosPerSub: Double,
    val bytesPerSub: Double,
    val liveBytes: Long,
)

private fun gcSettle() {
    repeat(4) {
        System.gc()
        Thread.sleep(30)
    }
}

private fun usedBytes(): Long {
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
}

private fun median(values: DoubleArray): Double {
    val sorted = values.sortedArray()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * One rung: build a `width`-wide fan-out over a single topic cell, publish, and
 * verify every survivor observed the final publish.
 *
 * A stale index entry does not crash — it drops a subscriber from the dependent
 * list, so it surfaces here as a missed update. That is what the survivor check
 * is for.
 */
private fun runRung(width: Int): RungResult {
    gcSettle()
    val before = usedBytes()

    val ctx = Context()
    val topic = ctx.source(0L)
    val subs = ArrayList<Computed<Long>>(width)

    val buildStart = System.nanoTime()
    for (i in 0 until width) {
        subs += ctx.computed { ctx.get(topic) + i }
    }
    // Force the edges to actually register: a lazy slot registers its
    // dependency on first compute, not at construction.
    for (s in subs) ctx.get(s)
    val buildNanos = (System.nanoTime() - buildStart).toDouble()

    gcSettle()
    val liveBytes = usedBytes() - before

    // Notify: publish, then read every subscriber. Propagation walks bare ids,
    // so this should be unaffected by the edge-index change; if it moves, the
    // model is wrong.
    val notifySamples = DoubleArray(NOTIFY_ROUNDS)
    for (round in 0 until NOTIFY_ROUNDS) {
        val v = (round + 1).toLong()
        val start = System.nanoTime()
        topic.set(ctx, v)
        var acc = 0L
        for (s in subs) acc += ctx.get(s)
        notifySamples[round] = (System.nanoTime() - start).toDouble() / width
        check(acc != Long.MIN_VALUE)
    }

    // Every survivor observes the final publish.
    val expectedTopic = NOTIFY_ROUNDS.toLong()
    var missed = 0
    for (i in 0 until width) {
        if (ctx.get(subs[i]) != expectedTopic + i) missed++
    }
    check(missed == 0) { "width=$width: $missed subscriber(s) missed the final publish" }

    return RungResult(
        width = width,
        buildNanosPerSub = buildNanos / width,
        notifyNanosPerSub = median(notifySamples),
        bytesPerSub = liveBytes.toDouble() / width,
        liveBytes = liveBytes,
    )
}

private fun warmup() {
    var sink = 0L
    repeat(400) {
        val ctx = Context()
        val topic = ctx.source(0L)
        val subs = ArrayList<Computed<Long>>(256)
        for (i in 0 until 256) subs += ctx.computed { ctx.get(topic) + i }
        for (s in subs) sink += ctx.get(s)
        topic.set(ctx, 1L)
        for (s in subs) sink += ctx.get(s)
    }
    check(sink != 0L)
}

fun main() {
    val maxWidth = System.getProperty("lazily.loadMaxWidth")?.toIntOrNull()
        ?: System.getenv("LAZILY_LOAD_MAX_WIDTH")?.toIntOrNull()
        ?: 1_000_000
    val maxHeap = Runtime.getRuntime().maxMemory()

    // Warm the JIT before the first rung. Without this the narrow rungs are
    // measured cold and read as 100x their steady-state cost, which both hides
    // the boundary-spike check and makes the whole ladder unreadable.
    warmup()

    println("edge-index width ladder (#lzspecedgeindex)")
    println("max heap: ${maxHeap / (1024 * 1024)} MiB, target width: $maxWidth")
    println()
    println(String.format("%12s %14s %14s %12s", "width", "build ns/sub", "notify ns/sub", "bytes/sub"))

    val results = ArrayList<RungResult>()
    var ceiling = 0
    var limitingFactor = "reached target"

    for ((i, width) in LADDER.withIndex()) {
        if (width > maxWidth) {
            limitingFactor = "target width $maxWidth"
            break
        }
        // Climb, project, refuse: project this rung from the *last measured*
        // bytes/sub, not from a guess, and refuse if it would eat the floor.
        val last = results.lastOrNull()
        if (last != null) {
            val projected = (last.bytesPerSub * width).toLong()
            val headroom = maxHeap - projected
            if (headroom < MEMORY_FLOOR_BYTES) {
                limitingFactor =
                    "projected ${projected / (1024 * 1024)} MiB at width $width from " +
                        "${"%.1f".format(last.bytesPerSub)} B/sub measured at width ${last.width}; " +
                        "would leave ${headroom / (1024 * 1024)} MiB against a " +
                        "${MEMORY_FLOOR_BYTES / (1024 * 1024)} MiB floor"
                println("REFUSED width $width: $limitingFactor")
                break
            }
        }

        val r = try {
            runRung(width)
        } catch (e: OutOfMemoryError) {
            limitingFactor = "OutOfMemoryError at width $width"
            println("OOM at width $width")
            break
        }
        results += r
        ceiling = width
        println(
            String.format(
                "%12d %14.1f %14.1f %12.1f",
                r.width, r.buildNanosPerSub, r.notifyNanosPerSub, r.bytesPerSub,
            ),
        )
        check(i >= 0)
    }

    println()
    println("ladder ceiling: $ceiling ($limitingFactor)")
    println()
    assertLadder(results)
}

/**
 * Assertions, not just prints. These are what turn the ladder from a benchmark
 * into a test.
 */
fun assertLadder(results: List<RungResult>) {
    val failures = ArrayList<String>()

    // 1. Linear build: ns/sub must not blow up from 1k -> 1M. A surviving
    //    O(n^2) registration shows up here and nowhere else.
    val lo = results.firstOrNull { it.width >= 1_024 }
    val hi = results.lastOrNull { it.width >= 262_144 }
    if (lo != null && hi != null && hi.width > lo.width) {
        val ratio = hi.buildNanosPerSub / lo.buildNanosPerSub
        val msg = "build ns/sub ${lo.width}->${hi.width}: %.2fx".format(ratio)
        if (ratio >= 2.0) failures += "FAIL $msg (want < 2.00x)" else println("ok   $msg (want < 2.00x)")
    } else {
        println("skip build-linearity: ladder did not reach 262144")
    }

    // 2. bytes/sub flat within ~20% across the ladder (ignore the narrow rungs,
    //    where the Context's own fixed overhead dominates the per-sub figure).
    val wide = results.filter { it.width >= 4_096 }
    if (wide.size >= 2) {
        val min = wide.minOf { it.bytesPerSub }
        val max = wide.maxOf { it.bytesPerSub }
        val spread = max / min
        val msg = "bytes/sub spread over width>=4096: %.2fx (%.1f..%.1f B)".format(spread, min, max)
        if (spread > 1.20) failures += "FAIL $msg (want <= 1.20x)" else println("ok   $msg (want <= 1.20x)")
    } else {
        println("skip bytes/sub flatness: fewer than two rungs at width>=4096")
    }

    // 3. Notify must stay flat: propagation walks bare ids and never touches the
    //    edge index, so the edge-index change must not move it.
    val nLo = results.firstOrNull { it.width >= 1_024 }
    val nHi = results.lastOrNull { it.width >= 262_144 }
    if (nLo != null && nHi != null && nHi.width > nLo.width) {
        val ratio = nHi.notifyNanosPerSub / nLo.notifyNanosPerSub
        val msg = "notify ns/sub ${nLo.width}->${nHi.width}: %.2fx".format(ratio)
        if (ratio >= 3.0) failures += "FAIL $msg (want < 3.00x)" else println("ok   $msg (want < 3.00x)")
    }

    // 4. No spike at the promote boundary. Demotion without hysteresis shows up
    //    as a build-cost spike at exactly threshold+1 and is invisible elsewhere.
    for (i in 1 until results.size - 1) {
        val prev = results[i - 1]
        val cur = results[i]
        val next = results[i + 1]
        val neighbours = (prev.buildNanosPerSub + next.buildNanosPerSub) / 2.0
        if (cur.buildNanosPerSub > neighbours * 2.5) {
            failures += "FAIL boundary spike at width ${cur.width}: " +
                "%.1f ns/sub against %.1f ns/sub either side".format(cur.buildNanosPerSub, neighbours)
        }
    }
    println("ok   no build spike >2.5x its neighbours at any rung")

    println()
    if (failures.isEmpty()) {
        println("ALL LADDER ASSERTIONS PASSED")
    } else {
        failures.forEach { println(it) }
        throw AssertionError("${failures.size} ladder assertion(s) failed")
    }
}
