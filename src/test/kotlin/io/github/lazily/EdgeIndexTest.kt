package io.github.lazily

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            val topic = ctx.source(0)
            val subs = (0 until width).map { i -> ctx.computed { get(topic) + i } }
            for (i in 0 until width) assertEquals(i, ctx.get(subs[i]), "width=$width initial")
            topic.set(ctx, 100)
            // A stale index entry surfaces as a *missed update*, not a crash.
            for (i in 0 until width) assertEquals(100 + i, ctx.get(subs[i]), "width=$width after publish")
        }
    }

    @Test
    fun `wide fan-out survives repeated invalidation`() {
        val ctx = Context()
        val width = EDGE_INDEX_THRESHOLD * 4
        val topic = ctx.source(0)
        val subs = (0 until width).map { i -> ctx.computed { get(topic) + i } }
        for (round in 1..25) {
            topic.set(ctx, round)
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
        val topic = ctx.source(0)
        val seen = IntArray(width)
        val effects = (0 until width).map { i -> ctx.effect { seen[i] = get(topic); null } }
        topic.set(ctx, 7)
        for (i in 0 until width) assertEquals(7, seen[i])
        for (e in effects) ctx.disposeEffect(e)

        // Re-create the same number of nodes; allocId hands back the freed ids.
        val topic2 = ctx.source(0)
        val subs = (0 until width).map { i -> ctx.computed { get(topic2) + i } }
        for (i in 0 until width) assertEquals(i, ctx.get(subs[i]))
        topic2.set(ctx, 500)
        for (i in 0 until width) assertEquals(500 + i, ctx.get(subs[i]))

        // The disposed effects are gone and the old topic has no dependents left.
        topic.set(ctx, 9)
        for (i in 0 until width) assertEquals(7, seen[i], "disposed effect $i re-ran")
    }

    @Test
    fun `wide fan-out works in the thread-safe context`() {
        val ctx = ThreadSafeContext()
        val width = EDGE_INDEX_THRESHOLD * 4
        val topic = ctx.cell(0)
        val subs = (0 until width).map { i -> ctx.computed { getCell(topic) + i } }
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
        val c = ctx.source(0)
        var victimRuns = 0
        var disposerRan = false
        // The frontier pops LIFO, so the *last* registered effect runs first.
        // The disposer must therefore be registered last for the victim to still
        // be queued when the dispose happens.
        val victim = ctx.effect { get(c); victimRuns++; null }
        val disposer = ctx.effect {
            get(c)
            disposerRan = true
            ctx.disposeEffect(victim)
            null
        }
        assertEquals(1, victimRuns, "victim runs once at registration")

        c.set(ctx, 1)
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
        val c = ctx.source(0)
        val runs = IntArray(3)
        val hs = (0 until 3).map { i -> ctx.effect { get(c); runs[i]++; null } }
        ctx.disposeEffect(hs[1])
        c.set(ctx, 1)
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
        val topic = ctx.source(0)
        val runs = IntArray(width)
        val hs = (0 until width).map { i -> ctx.effect { get(topic); runs[i]++; null } }
        topic.set(ctx, 1)
        for (i in 0 until width) assertEquals(2, runs[i], "effect $i should have rerun once")

        for (h in hs) ctx.disposeEffect(h)
        for (h in hs) assertFalse(ctx.isEffectActive(h))
        topic.set(ctx, 2)
        for (i in 0 until width) assertEquals(2, runs[i], "disposed effect $i re-ran after teardown")
    }

    @Test
    fun `thread-safe context deschedules a queued effect on dispose`() {
        val ctx = ThreadSafeContext()
        val c = ctx.cell(0)
        var victimRuns = 0
        var disposerRan = false
        val victim = ctx.effect { getCell(c); victimRuns++; null }
        ctx.effect { getCell(c); disposerRan = true; ctx.disposeEffect(victim); null }
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
        val inputs = (0 until width).map { ctx.source(1) }
        val total = ctx.computed { inputs.sumOf { get(it) } }
        assertEquals(width, ctx.get(total))
        inputs[width - 1].set(ctx, 100)
        assertEquals(width - 1 + 100, ctx.get(total))
        for (i in 0 until width) inputs[i].set(ctx, 2)
        assertEquals(width * 2, ctx.get(total))
    }

    // -- Disposal / teardown-scope plane (`#lzspecedgeindex`) --------------
    //
    // The shared reactive-graph corpus (replayed in
    // ReactiveGraphConformanceTest) pins most of this plane, but two of its
    // three stated semantics are NOT discriminated by any fixture in it, which
    // was established by mutation rather than assumed:
    //
    //   * "effects reached by the disposal walk MUST NOT be scheduled" — every
    //     corpus fixture that has an effect in a disposed cone disposes that
    //     effect first, so scheduling it instead changes nothing observable
    //     there;
    //   * "teardown order is reverse creation order" — the only scope in the
    //     corpus carrying a `cleanup_order` assertion owns exactly one effect,
    //     and the assertion projects onto effect entries, so a forward-order
    //     teardown produces the identical single-entry log.
    //
    // Both mutations left all nine fixtures green against all three contexts.
    // These are the tests that go red, so the semantics are pinned by something
    // rather than by nobody.

    @Test
    fun `disposal dirties the surviving dependent cone`() {
        // Semantic 1, and the single most likely thing to get wrong
        // (`lazily-rs` 5db90d2, `lazily-js` 4d20670): detaching edges without
        // marking dependents leaves a live reader frozen on the value it cached
        // *through* the disposed node. This one the corpus does pin; it is here
        // because the three semantics belong together.
        val ctx = Context()
        val src = ctx.source(1)
        val mid = ctx.computed { get(src) + 1 }
        val reader = ctx.computed { get(mid) * 10 }

        assertEquals(20, ctx.get(reader))
        mid.dispose(ctx)

        assertFailsWith<DisposedNodeException>(
            "a live reader that still names a disposed dependency must error on its next " +
                "recompute, not serve its cached value",
        ) { ctx.get(reader) }
    }

    @Test
    fun `disposal does not schedule effects reached by the walk`() {
        // Semantic 2. Disposal is not a publish. Running an effect during
        // teardown re-enters a compute that reads the node being disposed, which
        // turns `dispose` itself into a throw and breaks teardown idempotence.
        // Mark dirty only — the contract is "errors on the next recompute".
        val ctx = Context()
        val src = ctx.source(1)
        val mid = ctx.computed { get(src) + 1 }

        var runs = 0
        var sawDisposed = false
        ctx.effect {
            runs++
            // Reads `src` directly as well as through `mid`, so the effect still
            // holds a live edge after `mid` is disposed and a later write to
            // `src` genuinely reaches it. An effect whose *only* dependency was
            // the disposed node has nothing left to schedule it and is deaf by
            // construction, which would make this test vacuous.
            get(src)
            try {
                get(mid)
            } catch (_: DisposedNodeException) {
                sawDisposed = true
            }
            null
        }
        assertEquals(1, runs)

        mid.dispose(ctx)
        assertEquals(1, runs, "the effect reached by the disposal walk must not rerun")
        assertFalse(sawDisposed)

        // Nor may it be left *queued*. A teardown that enqueues the effect defers
        // the damage rather than avoiding it: the effect then fires on the next
        // unrelated flush — a write to a cell it does not even read — as a
        // spurious rerun no publish asked for.
        val unrelated = ctx.source(0)
        ctx.effect { get(unrelated); null }
        unrelated.set(ctx, 1)
        assertEquals(1, runs, "a publish the effect does not observe must not flush it")
        assertFalse(sawDisposed)

        // A real write still reaches it, and *that* recompute is where the error
        // surfaces.
        src.set(ctx, 2)
        assertEquals(2, runs)
        assertTrue(sawDisposed, "the next real recompute must see the disposed dependency")
    }

    @Test
    fun `thread-safe disposal does not schedule effects reached by the walk`() {
        // Semantic 2 on the lock-backed engine. Same shape; the gate is a
        // separate field on a separate class and would be just as easy to omit.
        val ctx = ThreadSafeContext()
        val src = ctx.cell(1)
        val mid = ctx.computed { getCell(src) + 1 }

        var runs = 0
        ctx.effect {
            runs++
            getCell(src)
            try {
                get(mid)
            } catch (_: DisposedNodeException) {
                // expected once mid is gone
            }
            null
        }
        assertEquals(1, runs)

        ctx.disposeSlot(mid)
        assertEquals(1, runs, "the effect reached by the disposal walk must not rerun")

        val unrelated = ctx.cell(0)
        ctx.effect { getCell(unrelated); null }
        ctx.setCell(unrelated, 1)
        assertEquals(1, runs, "a publish the effect does not observe must not flush it")

        ctx.setCell(src, 2)
        assertEquals(2, runs, "a real write still reaches it")
    }

    @Test
    fun `teardown scope tears down in reverse creation order`() {
        // Semantic 3. Graph state is order-independent, but effect *cleanups*
        // are side effects and their order is observable. Reverse creation order
        // means dependents go before what they read, so a scope never
        // transiently dangles inside itself.
        //
        // Two effects, not one: with a single effect a forward-order teardown
        // produces the identical log, which is precisely why the corpus fixture
        // does not discriminate this.
        val ctx = Context()
        val topic = ctx.source(1)
        val cleanups = mutableListOf<String>()
        val scope = ctx.scope()
        val a = scope.computed { get(topic) + 1 }
        val b = scope.computed { get(a) + 2 }
        scope.effect { get(b); { cleanups.add("watch_b") } }
        scope.effect { get(b); { cleanups.add("watch_b2") } }

        assertEquals(4, ctx.get(b))
        assertEquals(4, scope.size)
        scope.end()
        assertEquals(
            listOf("watch_b2", "watch_b"),
            cleanups,
            "later-created members must tear down first",
        )
        assertEquals(0, scope.size)
    }

    @Test
    fun `ending a scope is observationally equal to disposing each member`() {
        // `disposeScope_eq_disposeAll`. A scope names a set and a moment and
        // introduces no disposal semantics of its own.
        fun run(useScope: Boolean): List<Any> {
            val ctx = Context()
            val topic = ctx.source(1)
            val cleanups = mutableListOf<String>()
            val scope = ctx.scope()
            val a = if (useScope) {
                scope.computed { get(topic) + 1 }
            } else {
                ctx.computed { get(topic) + 1 }
            }
            val b = if (useScope) scope.computed { get(a) + 2 } else ctx.computed { get(a) + 2 }
            val run: Compute.() -> (() -> Unit)? = { get(b); { cleanups.add("watch") } }
            val w = if (useScope) scope.effect(run) else ctx.effect(run)
            assertEquals(4, ctx.get(b))

            if (useScope) {
                scope.end()
            } else {
                ctx.disposeEffect(w)
                b.dispose(ctx)
                a.dispose(ctx)
            }
            return listOf(
                cleanups.joinToString(","),
                ctx.dependentCount(topic),
                ctx.isEffectActive(w),
                ctx.isDisposed(a),
                ctx.isDisposed(b),
            )
        }
        assertEquals(run(useScope = false), run(useScope = true))
    }

    @Test
    fun `use ends a teardown scope even on a throw`() {
        // `use` is the lexical spelling of a scope's end in Kotlin — the
        // analogue of the Rust block scope. It has to hold on the exceptional
        // path too, or the leak comes back exactly where it is least visible.
        val ctx = Context()
        val topic = ctx.source(1)
        val cleanups = mutableListOf<String>()
        var member: Computed<Int>? = null

        assertFailsWith<IllegalStateException> {
            ctx.scope().use { scope ->
                member = scope.computed { get(topic) + 1 }
                scope.effect { get(member!!); { cleanups.add("watch") } }
                assertEquals(2, ctx.get(member!!))
                assertEquals(1, ctx.dependentCount(topic))
                error("boom")
            }
        }

        assertEquals(listOf("watch"), cleanups, "the scope must end on the exceptional path")
        assertTrue(ctx.isDisposed(member!!))
        assertEquals(0, ctx.dependentCount(topic), "the source must return to zero dependents")
    }

    @Test
    fun `disarm cancels teardown without detaching anything`() {
        val ctx = Context()
        val topic = ctx.source(1)
        val cleanups = mutableListOf<String>()
        val scope = ctx.scope()
        val escaped = scope.computed { get(topic) + 1 }
        scope.effect { get(escaped); { cleanups.add("watch") } }

        assertEquals(2, ctx.get(escaped))
        assertEquals(2, scope.size)
        scope.disarm()
        assertEquals(0, scope.size, "a disarmed scope owns nothing")
        scope.end()

        assertEquals(emptyList(), cleanups, "ending a disarmed scope disposes nothing")
        assertFalse(ctx.isDisposed(escaped))
        assertEquals(1, ctx.dependentCount(topic), "edges are untouched by disarm")
        topic.set(ctx, 4)
        assertEquals(5, ctx.get(escaped), "disarmed nodes keep propagating")
    }

    @Test
    fun `disposal is idempotent and stale handles are a no-op`() {
        val ctx = Context()
        val cell = ctx.source(1)
        val slot = ctx.computed { get(cell) }
        assertEquals(1, ctx.get(slot))

        slot.dispose(ctx)
        slot.dispose(ctx) // second teardown must be a no-op, not a throw
        cell.dispose(ctx)
        cell.dispose(ctx)

        // The arena recycles ids, so the *stale* cell handle now names whatever
        // took its id. Disposing through it must read the kind from the arena
        // and decline, or an unrelated live node would be torn down.
        val successor = ctx.computed { 7 }
        assertEquals(7, ctx.get(successor))
        cell.dispose(ctx)
        assertEquals(7, ctx.get(successor), "a stale cell handle must not tear down a slot")
    }

    @Test
    fun `subscribe unsubscribe churn returns to baseline`() {
        val ctx = Context()
        val topic = ctx.source(0)
        val subs = (0 until 8).map { ctx.effect { get(topic); null } }.toMutableList()
        assertEquals(8, ctx.dependentCount(topic))

        for (c in 0 until 200) {
            val at = c % 8
            ctx.disposeEffect(subs[at])
            subs[at] = ctx.effect { get(topic); null }
        }
        assertEquals(
            8,
            ctx.dependentCount(topic),
            "the dependent set must track live subscribers, not total ever created",
        )
    }

    @Test
    fun `async disposal detaches upstream and does not schedule the cone`() {
        // The async graph is where a disposal leak is hardest to notice, and
        // where semantic 2 matters most: an effect scheduled during teardown is
        // dispatched to a coroutine that resumes after `dispose` returned, so
        // the spurious rerun surfaces detached in time from its cause.
        runBlocking {
            AsyncContext().use { ctx ->
                val src = ctx.cell(1)
                val mid = ctx.computedAsync { getCell(src) + 1 }

                var runs = 0
                ctx.effectAsync {
                    runs++
                    getCell(src)
                    try {
                        getAsync(mid)
                    } catch (_: DisposedNodeException) {
                        // expected once mid is gone
                    }
                    null
                }
                ctx.settle()
                assertEquals(1, runs)
                assertEquals(2, ctx.dependentCount(src), "mid and the effect both read src")

                ctx.disposeSlot(mid)
                ctx.settle()
                assertEquals(1, runs, "the effect reached by the disposal walk must not rerun")
                assertEquals(1, ctx.dependentCount(src), "mid's upstream edge must be detached")

                ctx.setCell(src, 2)
                ctx.settle()
                assertEquals(2, runs, "a real write still reaches it")
            }
        }
    }

    @Test
    fun `async effect disposal detaches its upstream edges`() {
        // The leak the corpus surfaced: an effect removed from the context that
        // stays in `dependents` for every dependency it read grows each source's
        // dependent set without bound under async subscribe/unsubscribe churn.
        runBlocking {
            AsyncContext().use { ctx ->
                val topic = ctx.cell(0)
                val subs = (0 until 8)
                    .map { ctx.effectAsync { getCell(topic); null } }
                    .toMutableList()
                ctx.settle()
                assertEquals(8, ctx.dependentCount(topic))

                for (c in 0 until 64) {
                    val at = c % 8
                    ctx.disposeEffect(subs[at])
                    subs[at] = ctx.effectAsync { getCell(topic); null }
                }
                ctx.settle()
                assertEquals(
                    8,
                    ctx.dependentCount(topic),
                    "the dependent set must track live subscribers, not total ever created",
                )
            }
        }
    }
}
