package io.github.lazily

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the thread-safe keyed reactive collections
 * (`#reactivemap`, thread-safe flavor): the [ThreadSafeCellMap] input-cell and
 * [ThreadSafeSlotMap] derived-slot specializations. Proves the `Send + Sync`
 * flavor obeys the same materialization laws as the single-threaded maps — plus
 * **materialization confluence** (order-independent present set + observed values,
 * proved in `lazily-formal`'s `Materialization` module as
 * `materialize_present_comm` / `materialize_observe_comm`). Mirrors the
 * `thread_safe_reactive_family` / `materialization_threadsafe_conformance` tests
 * in `lazily-rs`.
 */
class ThreadSafeMapConformanceTest {
    /** Eager (materializeAll) allocates every declared derived slot up front. */
    @Test
    fun eagerMaterializesAllUpFront() {
        val ctx = ThreadSafeContext()
        val map = ThreadSafeSlotMap<Int, Int>()
        map.materializeAll(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        assertEquals(EntryKind.Slot, map.entryKind)
        assertEquals(5, map.presentCount)
        for (k in listOf(0, 1, 2, 5, 9)) assertTrue(map.isPresent(k))
        assertEquals(listOf(0, 1, 2, 5, 9), map.presentKeys())
    }

    /** Lazy (getOrInsertWith) leaves an unread derived slot unallocated. */
    @Test
    fun lazyDefersSlotsUntilRead() {
        val ctx = ThreadSafeContext()
        val map = ThreadSafeSlotMap<Int, Int>()
        assertEquals(0, map.presentCount)
        assertFalse(map.isPresent(2))
        assertEquals(20, map.getOrInsertWith(ctx, 2) { it * 10 })
        assertTrue(map.isPresent(2))
        assertEquals(1, map.presentCount)
    }

    /** Input cells are always materialized: a CellMap is fully present once built. */
    @Test
    fun cellMapMaterializesInputsAtBuild() {
        val ctx = ThreadSafeContext()
        val map = ThreadSafeCellMap<String, Int>()
        map.materializeAll(ctx, listOf("a", "b", "c")) { 0 }
        assertEquals(EntryKind.Cell, map.entryKind)
        assertEquals(3, map.presentCount)
    }

    /** Eager and lazy observe identical values for every key. */
    @Test
    fun eagerAndLazyObserveIdentically() {
        val ctxE = ThreadSafeContext()
        val eager = ThreadSafeSlotMap<Int, Int>()
        eager.materializeAll(ctxE, listOf(1, 2, 3)) { it * 2 }
        val ctxL = ThreadSafeContext()
        val lazy = ThreadSafeSlotMap<Int, Int>()
        for (k in listOf(1, 2, 3)) {
            assertEquals(eager.get(ctxE, k), lazy.getOrInsertWith(ctxL, k) { it * 2 })
        }
    }

    /** Re-reading a key does not grow the present set. */
    @Test
    fun presentSetGrowsMonotonically() {
        val ctx = ThreadSafeContext()
        val map = ThreadSafeSlotMap<Int, Int>()
        map.getOrInsertWith(ctx, 5) { it }
        map.getOrInsertWith(ctx, 5) { it } // repeat: no growth
        map.getOrInsertWith(ctx, 9) { it }
        assertEquals(2, map.presentCount)
        assertEquals(listOf(5, 9), map.presentKeys())
    }

    /**
     * Confluence: materializing keys in different orders yields the same present
     * set (as a set) and the same observed values.
     */
    @Test
    fun materializationIsConfluentAcrossOrder() {
        val ctxA = ThreadSafeContext()
        val mapA = ThreadSafeSlotMap<Int, Int>()
        val ctxB = ThreadSafeContext()
        val mapB = ThreadSafeSlotMap<Int, Int>()
        for (k in listOf(1, 2, 3, 4)) mapA.getOrInsertWith(ctxA, k) { it * 7 }
        for (k in listOf(4, 3, 2, 1)) mapB.getOrInsertWith(ctxB, k) { it * 7 }
        assertEquals(mapA.presentKeys().toSet(), mapB.presentKeys().toSet())
        for (k in listOf(1, 2, 3, 4)) assertEquals(mapA.get(ctxA, k), mapB.get(ctxB, k))
    }

    /**
     * The agent-doc liveness shape: cell inputs + a derived count that recomputes
     * reactively when a cell flips — no pull-time scan.
     */
    @Test
    fun derivedCountReactsToCellWrites() {
        val ctx = ThreadSafeContext()
        val liveness = ThreadSafeCellMap<Long, Boolean>()
        liveness.materializeAll(ctx, listOf(10L, 20L, 30L)) { true }
        val liveCount = ctx.computed {
            liveness.presentKeys().count { k -> liveness.observe(this, k) }
        }
        assertEquals(3, ctx.get(liveCount))
        // Flip one editor offline → derived count recomputes reactively.
        liveness.set(ctx, 20L, false)
        assertEquals(2, ctx.get(liveCount))
        liveness.set(ctx, 20L, true)
        assertEquals(3, ctx.get(liveCount))
    }

    /** The whole point: a thread-safe map can mint/observe from many threads. */
    @Test
    fun sharedAcrossThreads() {
        val ctx = ThreadSafeContext()
        val map = ThreadSafeCellMap<Int, Boolean>()
        val threads = (1..8).map { k ->
            thread { assertTrue(ctx.getCellAny(map.entry(ctx, k) { true }.id) as Boolean) }
        }
        threads.forEach { it.join() }
        assertEquals(8, map.presentCount)
    }
}
