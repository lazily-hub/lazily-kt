package io.github.lazily

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the thread-safe keyed reactive family
 * ([ThreadSafeReactiveFamily]) and its materialization mode (`#lzmatmode`,
 * thread-safe flavor). Proves the `Send + Sync` flavor obeys the same three
 * materialization laws as the single-threaded [ReactiveFamily] — plus
 * **materialization confluence** (order-independent present set + observed
 * values, proved in `lazily-formal`'s `Materialization` module as
 * `materialize_present_comm` / `materialize_observe_comm`). Mirrors the
 * `thread_safe_reactive_family` tests and the
 * `materialization_threadsafe_conformance` replay in `lazily-rs`.
 */
class ThreadSafeReactiveFamilyConformanceTest {
    /** `default_mode_eager`: the default materialization mode is eager. */
    @Test
    fun defaultModeIsEager() {
        assertEquals(MaterializationMode.Eager, MaterializationMode.Default)
    }

    /** `eager_materializes_all`: eager allocates every declared node up front. */
    @Test
    fun eagerMaterializesAllUpFront() {
        val ctx = ThreadSafeContext()
        val fam = ThreadSafeReactiveFamily.eager(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        assertEquals(EntryKind.Slot, fam.entryKind)
        assertEquals(MaterializationMode.Eager, fam.mode)
        assertEquals(5, fam.presentCount)
        for (k in listOf(0, 1, 2, 5, 9)) assertTrue(fam.isPresent(k))
        assertEquals(listOf(0, 1, 2, 5, 9), fam.presentKeys())
    }

    /** `lazy_defers_slots`: lazy leaves an unread derived slot unallocated. */
    @Test
    fun lazyDefersSlotsUntilRead() {
        val ctx = ThreadSafeContext()
        val fam = ThreadSafeReactiveFamily.lazy(ctx, listOf<Int>()) { it * 10 }
        assertEquals(MaterializationMode.Lazy, fam.mode)
        assertEquals(0, fam.presentCount)
        assertFalse(fam.isPresent(2))
        assertEquals(20, fam.observe(ctx, 2))
        assertTrue(fam.isPresent(2))
        assertEquals(1, fam.presentCount)
    }

    /** `lazy_cell_entries_still_materialize_at_build`: cells are always materialized. */
    @Test
    fun cellFamilyMaterializedInEveryMode() {
        val ctx = ThreadSafeContext()
        for (mode in listOf(MaterializationMode.Eager, MaterializationMode.Lazy)) {
            val fam = ThreadSafeReactiveFamily.cells(ctx, listOf("a", "b", "c"), mode) { 0 }
            assertEquals(EntryKind.Cell, fam.entryKind)
            assertEquals(3, fam.presentCount)
        }
    }

    /**
     * `observational_transparency_eager_equals_lazy` / `observe_canonical`:
     * identical values under either mode.
     */
    @Test
    fun eagerAndLazyObserveIdentically() {
        val ctxE = ThreadSafeContext()
        val eager = ThreadSafeReactiveFamily.eager(ctxE, listOf(1, 2, 3)) { it * 2 }
        val ctxL = ThreadSafeContext()
        val lazy = ThreadSafeReactiveFamily.lazy(ctxL, listOf(1, 2, 3)) { it * 2 }
        for (k in listOf(1, 2, 3)) {
            assertEquals(eager.observe(ctxE, k), lazy.observe(ctxL, k))
        }
    }

    /** `present_set_grows_monotonically`: re-reading a key does not grow the present set. */
    @Test
    fun presentSetGrowsMonotonically() {
        val ctx = ThreadSafeContext()
        val fam = ThreadSafeReactiveFamily.lazy(ctx, listOf<Int>()) { it }
        fam.observe(ctx, 5)
        fam.observe(ctx, 5) // repeat: no growth
        fam.observe(ctx, 9)
        assertEquals(2, fam.presentCount)
        assertEquals(listOf(5, 9), fam.presentKeys())
    }

    /**
     * `materialize_present_comm` / `materialize_observe_comm`: **confluence** —
     * materializing keys in different orders yields the same present set (as a set)
     * and the same observed values.
     */
    @Test
    fun materializationIsConfluentAcrossOrder() {
        val ctxA = ThreadSafeContext()
        val famA = ThreadSafeReactiveFamily.lazy(ctxA, listOf<Int>()) { it * 7 }
        val ctxB = ThreadSafeContext()
        val famB = ThreadSafeReactiveFamily.lazy(ctxB, listOf<Int>()) { it * 7 }
        for (k in listOf(1, 2, 3, 4)) famA.observe(ctxA, k)
        for (k in listOf(4, 3, 2, 1)) famB.observe(ctxB, k)
        assertEquals(famA.presentKeys().toSet(), famB.presentKeys().toSet())
        for (k in listOf(1, 2, 3, 4)) assertEquals(famA.observe(ctxA, k), famB.observe(ctxB, k))
    }

    /**
     * The agent-doc liveness shape: cell inputs + a derived count that recomputes
     * reactively when a cell flips — no pull-time scan.
     */
    @Test
    fun derivedCountReactsToCellWrites() {
        val ctx = ThreadSafeContext()
        val liveness = ThreadSafeReactiveFamily.cells(ctx, listOf(10L, 20L, 30L)) { true }
        val liveCount = ctx.computed {
            liveness.presentKeys().count { k -> liveness.observe(this, k) }
        }
        assertEquals(3, ctx.get(liveCount))
        // Flip one editor offline → derived count recomputes reactively.
        val h20 = liveness.get(ctx, 20L)
        assertTrue(h20 is ThreadSafeCellEntry)
        ctx.setCell(h20.handle, false)
        assertEquals(2, ctx.get(liveCount))
        ctx.setCell(h20.handle, true)
        assertEquals(3, ctx.get(liveCount))
    }

    /** The whole point: a thread-safe family can be observed from many threads. */
    @Test
    fun sharedAcrossThreads() {
        val ctx = ThreadSafeContext()
        val fam = ThreadSafeReactiveFamily.cells(ctx, (1..8).toList()) { true }
        val threads = (1..8).map { k ->
            thread { assertTrue(fam.observe(ctx, k)) }
        }
        threads.forEach { it.join() }
        assertEquals(8, fam.presentCount)
    }
}
