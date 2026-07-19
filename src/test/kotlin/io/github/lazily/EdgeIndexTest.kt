package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge-index tests (`#lzspecedgeindex`).
 *
 * The index is an implementation concern — the reactive contract fixes the edge
 * *set*, not how membership is tested — so these assert on observable graph
 * behaviour at widths that straddle the promote threshold, plus a few direct
 * [SmallEdgeList] assertions for the state transitions that behaviour alone
 * cannot distinguish.
 *
 * The scale ladder that proves registration is actually O(1) lives in
 * `EdgeIndexLoad` and is deliberately manual: it climbs to millions of nodes.
 */
class EdgeIndexTest {
    // -- SmallEdgeList state transitions ----------------------------------

    @Test
    fun `promotes past the threshold and still dedups`() {
        val edges = SmallEdgeList()
        val width = EDGE_INDEX_THRESHOLD * 4
        for (i in 0 until width) assertTrue(edges.add(i), "add($i) should be new")
        assertTrue(edges.isIndexed(), "should have promoted at width $width")
        assertEquals(width, edges.size)

        // Every re-registration is a no-op: the edge set is idempotent.
        for (i in 0 until width) assertFalse(edges.add(i), "add($i) should be a duplicate")
        assertEquals(width, edges.size)
        for (i in 0 until width) assertTrue(i in edges, "$i should still be a member")
    }

    @Test
    fun `stays unindexed at and below the threshold`() {
        val edges = SmallEdgeList()
        for (i in 0 until EDGE_INDEX_THRESHOLD) edges.add(i)
        assertFalse(edges.isIndexed(), "must not promote at exactly the threshold")
        edges.add(EDGE_INDEX_THRESHOLD)
        assertTrue(edges.isIndexed(), "must promote one past the threshold")
    }

    @Test
    fun `demotes only well below the promote threshold`() {
        val edges = SmallEdgeList()
        val width = EDGE_INDEX_THRESHOLD * 2
        for (i in 0 until width) edges.add(i)
        assertTrue(edges.isIndexed())

        // Shrink to exactly one past the promote threshold: still indexed.
        for (i in width downTo EDGE_INDEX_THRESHOLD + 2) edges.remove(i - 1)
        assertEquals(EDGE_INDEX_THRESHOLD + 1, edges.size)
        assertTrue(edges.isIndexed())

        // Hysteresis: crossing back under the *promote* threshold must not
        // demote, or a list oscillating by one rebuilds its index every
        // recompute. Measured at 21x the steady-state cost without this.
        edges.remove(EDGE_INDEX_THRESHOLD)
        assertEquals(EDGE_INDEX_THRESHOLD, edges.size)
        assertTrue(edges.isIndexed(), "must not demote at the promote threshold")

        // It demotes only once it reaches the demote threshold.
        while (edges.size > EDGE_INDEX_DEMOTE_THRESHOLD) edges.remove(edges.size - 1)
        assertFalse(edges.isIndexed(), "must demote at the demote threshold")
        // ...and is still correct afterwards.
        for (i in 0 until EDGE_INDEX_DEMOTE_THRESHOLD) assertTrue(i in edges)
    }

    @Test
    fun `boundary oscillation does not thrash the index`() {
        val edges = SmallEdgeList()
        val width = EDGE_INDEX_THRESHOLD + 1
        for (i in 0 until width) edges.add(i)
        assertTrue(edges.isIndexed())
        // The exact remove/re-add cycle a recompute performs, at exactly the
        // width where a shared promote/demote boundary would thrash.
        repeat(500) { round ->
            val victim = round % width
            assertTrue(edges.remove(victim))
            assertTrue(edges.add(victim))
            assertTrue(edges.isIndexed(), "demoted mid-oscillation at round $round")
            assertEquals(width, edges.size)
        }
        for (i in 0 until width) assertTrue(i in edges)
    }

    @Test
    fun `clear drops the index`() {
        val edges = SmallEdgeList()
        for (i in 0 until EDGE_INDEX_THRESHOLD * 2) edges.add(i)
        assertTrue(edges.isIndexed())
        edges.clear()
        assertFalse(edges.isIndexed())
        assertEquals(0, edges.size)
        assertFalse(0 in edges)
        // Rebuilds cleanly from empty.
        for (i in 100 until 100 + EDGE_INDEX_THRESHOLD * 2) edges.add(i)
        assertEquals(EDGE_INDEX_THRESHOLD * 2, edges.size)
        assertFalse(0 in edges, "a cleared element must not reappear")
    }

    @Test
    fun `removal is exact under heavy churn`() {
        // Backward-shift deletion is the part most likely to be subtly wrong:
        // a mishandled probe chain silently loses an unrelated key. Churn a
        // promoted list hard and check the set on every step.
        val edges = SmallEdgeList()
        val width = EDGE_INDEX_THRESHOLD * 3
        val expected = HashSet<Int>()
        // Ids spaced to force probe collisions rather than a tidy sequence.
        for (i in 0 until width) { edges.add(i * 7); expected.add(i * 7) }
        val rng = java.util.Random(20260719)
        repeat(5_000) {
            val victim = rng.nextInt(width) * 7
            if (rng.nextBoolean()) {
                assertEquals(expected.remove(victim), edges.remove(victim))
            } else {
                assertEquals(expected.add(victim), edges.add(victim))
            }
        }
        assertEquals(expected.size, edges.size)
        for (e in expected) assertTrue(e in edges, "$e lost from the index")
        for (e in edges) assertTrue(e in expected, "$e resurrected in the index")
    }

    // -- Observable graph behaviour ---------------------------------------

    @Test
    fun `wide fan-out propagates to every dependent`() {
        for (width in intArrayOf(
            EDGE_INDEX_DEMOTE_THRESHOLD,
            EDGE_INDEX_THRESHOLD,
            EDGE_INDEX_THRESHOLD + 1,
            EDGE_INDEX_THRESHOLD * 8,
        )) {
            val ctx = Context()
            val topic = ctx.cell(0)
            val subs = (0 until width).map { i -> ctx.computed { ctx.getCell(topic) + i } }
            for (i in 0 until width) assertEquals(i, ctx.get(subs[i]), "width=$width initial")
            ctx.setCell(topic, 100)
            // A stale index entry surfaces as a *missed update*, not a crash.
            for (i in 0 until width) assertEquals(100 + i, ctx.get(subs[i]), "width=$width after publish")
        }
    }

    @Test
    fun `wide fan-out survives repeated invalidation`() {
        val ctx = Context()
        val width = EDGE_INDEX_THRESHOLD * 4
        val topic = ctx.cell(0)
        val subs = (0 until width).map { i -> ctx.computed { ctx.getCell(topic) + i } }
        for (round in 1..25) {
            ctx.setCell(topic, round)
            for (i in 0 until width) assertEquals(round + i, ctx.get(subs[i]), "round=$round i=$i")
        }
    }

    @Test
    fun `a recycled id does not inherit an index`() {
        // In lazily-rs the index is a side table keyed by owner, so a recycled
        // id can alias a stale index onto an unrelated node. Here the index is a
        // field of the SmallEdgeList inside the Node, and a fresh Node is
        // allocated on every cell/computed/effect — including when allocId hands
        // back a recycled id — so the hazard is structurally absent. This pins
        // that, since it is a property of the layout rather than of any code
        // that could be re-introduced silently.
        val ctx = Context()
        val width = EDGE_INDEX_THRESHOLD * 2

        // Build a wide effect fan-out over a topic, then dispose it all so the
        // ids go back on the free list.
        val topic = ctx.cell(0)
        val seen = IntArray(width)
        val effects = (0 until width).map { i -> ctx.effect { seen[i] = ctx.getCell(topic); null } }
        ctx.setCell(topic, 7)
        for (i in 0 until width) assertEquals(7, seen[i])
        for (e in effects) ctx.disposeEffect(e)

        // Re-create the same number of nodes; allocId hands back the freed ids.
        val topic2 = ctx.cell(0)
        val subs = (0 until width).map { i -> ctx.computed { ctx.getCell(topic2) + i } }
        for (i in 0 until width) assertEquals(i, ctx.get(subs[i]))
        ctx.setCell(topic2, 500)
        for (i in 0 until width) assertEquals(500 + i, ctx.get(subs[i]))

        // The disposed effects are gone and the old topic has no dependents left.
        ctx.setCell(topic, 9)
        for (i in 0 until width) assertEquals(7, seen[i], "disposed effect $i re-ran")
    }

    @Test
    fun `wide fan-out works in the thread-safe context`() {
        val ctx = ThreadSafeContext()
        val width = EDGE_INDEX_THRESHOLD * 4
        val topic = ctx.cell(0)
        val subs = (0 until width).map { i -> ctx.computed { ctx.getCell(topic) + i } }
        for (i in 0 until width) assertEquals(i, ctx.get(subs[i]))
        ctx.setCell(topic, 100)
        for (i in 0 until width) assertEquals(100 + i, ctx.get(subs[i]))
    }

    // -- Descheduling on dispose (`#lzspecedgeindex`) ----------------------
    //
    // `disposeEffect` now consults `scheduledEffects` before searching the
    // pending queue, because the unguarded search was O(retained capacity) even
    // when the queue was empty and made teardown of a wide fan-out quadratic.
    // The cost is invisible to a unit test; what a unit test can pin is that the
    // guard did not cost correctness, i.e. that disposing an effect which *is*
    // currently queued still stops it running. The timing half lives in the
    // manual `edgeAudit` ladder.

    @Test
    fun `disposing a queued effect while flushing still deschedules it`() {
        val ctx = Context()
        val c = ctx.cell(0)
        var victimRuns = 0
        var disposerRan = false
        // The frontier pops LIFO, so the *last* registered effect runs first.
        // The disposer must therefore be registered last for the victim to still
        // be queued when the dispose happens.
        val victim = ctx.effect { ctx.getCell(c); victimRuns++; null }
        val disposer = ctx.effect {
            ctx.getCell(c)
            disposerRan = true
            ctx.disposeEffect(victim)
            null
        }
        assertEquals(1, victimRuns, "victim runs once at registration")

        ctx.setCell(c, 1)
        // Guard against a vacuous pass: if the victim ran before the disposer,
        // the dispose never happened against a queued effect and this test would
        // prove nothing.
        assertTrue(disposerRan, "disposer must have run")
        assertEquals(1, victimRuns, "victim was disposed while queued and must not rerun")
        assertFalse(ctx.isEffectActive(victim), "victim should be inactive")
        assertTrue(ctx.isEffectActive(disposer))
    }

    @Test
    fun `disposing an unqueued effect leaves other pending effects intact`() {
        // The guard must not skip descheduling work it still owes: disposing an
        // effect that is not queued must leave every queued sibling running.
        val ctx = Context()
        val c = ctx.cell(0)
        val runs = IntArray(3)
        val hs = (0 until 3).map { i -> ctx.effect { ctx.getCell(c); runs[i]++; null } }
        ctx.disposeEffect(hs[1])
        ctx.setCell(c, 1)
        assertEquals(2, runs[0])
        assertEquals(1, runs[1], "disposed effect must not rerun")
        assertEquals(2, runs[2])
    }

    @Test
    fun `wide teardown after a wide flush leaves the graph consistent`() {
        // Reproduces the shape that was quadratic: one topic, a wide effect
        // fan-out, a publish that fills and drains the pending queue, then a
        // full teardown. Every dispose used to rescan the drained queue's
        // retained capacity.
        val ctx = Context()
        val width = EDGE_INDEX_THRESHOLD * 8
        val topic = ctx.cell(0)
        val runs = IntArray(width)
        val hs = (0 until width).map { i -> ctx.effect { ctx.getCell(topic); runs[i]++; null } }
        ctx.setCell(topic, 1)
        for (i in 0 until width) assertEquals(2, runs[i], "effect $i should have rerun once")

        for (h in hs) ctx.disposeEffect(h)
        for (h in hs) assertFalse(ctx.isEffectActive(h))
        ctx.setCell(topic, 2)
        for (i in 0 until width) assertEquals(2, runs[i], "disposed effect $i re-ran after teardown")
    }

    @Test
    fun `thread-safe context deschedules a queued effect on dispose`() {
        val ctx = ThreadSafeContext()
        val c = ctx.cell(0)
        var victimRuns = 0
        var disposerRan = false
        val victim = ctx.effect { ctx.getCell(c); victimRuns++; null }
        ctx.effect { ctx.getCell(c); disposerRan = true; ctx.disposeEffect(victim); null }
        assertEquals(1, victimRuns)
        ctx.setCell(c, 1)
        assertTrue(disposerRan, "disposer must have run")
        assertEquals(1, victimRuns, "victim was disposed while queued and must not rerun")
    }

    @Test
    fun `wide fan-in refreshes every dependency`() {
        // The other axis: one slot reading many cells, so it is the slot's
        // *dependency* list that crosses the threshold. recomputeSlotNow clears
        // that list on every recompute, which must drop the index with it.
        val ctx = Context()
        val width = EDGE_INDEX_THRESHOLD * 4
        val inputs = (0 until width).map { ctx.cell(1) }
        val total = ctx.computed { inputs.sumOf { ctx.getCell(it) } }
        assertEquals(width, ctx.get(total))
        ctx.setCell(inputs[width - 1], 100)
        assertEquals(width - 1 + 100, ctx.get(total))
        for (i in 0 until width) ctx.setCell(inputs[i], 2)
        assertEquals(width * 2, ctx.get(total))
    }
}
