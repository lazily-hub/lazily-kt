package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit conformance tests for the keyed reactive collections (`#reactivemap`) — the
 * [ComputedMap] derived-slot specialization and the [SourceMap] input-cell
 * specialization on a single-threaded [Context]. Mirrors the `cell_family` tests
 * in `lazily-rs`. Materialization has no eager/lazy mode flag: eager is the
 * pre-mint loop [ComputedMap.materializeAll], lazy is mint-on-access
 * [ComputedMap.getOrInsertWith].
 */
class ComputedMapConformanceTest {
    /** Eager (materializeAll) allocates every declared derived slot up front. */
    @Test
    fun eagerMaterializesAllUpFront() {
        val ctx = Context()
        val map = ComputedMap<Int, Int>()
        map.materializeAll(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        assertEquals(EntryKind.Slot, map.entryKind)
        assertEquals(5, map.presentCount)
        for (k in listOf(0, 1, 2, 5, 9)) assertTrue(map.isPresent(k))
    }

    /** Lazy (getOrInsertWith) leaves an unread derived slot unallocated. */
    @Test
    fun lazyDefersSlotsUntilRead() {
        val ctx = Context()
        val map = ComputedMap<Int, Int>()
        assertEquals(0, map.presentCount)
        assertFalse(map.isPresent(5))

        // First read materializes just that key ("materialize on pull").
        assertEquals(15, map.getOrInsertWith(ctx, 5) { it * 3 })
        assertTrue(map.isPresent(5))
        assertEquals(listOf(5), map.presentKeys())
    }

    /** Eager and lazy observe identical values for every key. */
    @Test
    fun eagerAndLazyObserveIdentically() {
        val ctx = Context()
        val eager = ComputedMap<Int, Int>()
        eager.materializeAll(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        val lazy = ComputedMap<Int, Int>()
        for (k in listOf(0, 1, 2, 5, 9)) {
            assertEquals(eager.get(ctx, k), lazy.getOrInsertWith(ctx, k) { it * 3 })
        }
    }

    /** Re-reading a key does not change the present set; the set only grows. */
    @Test
    fun presentSetIsMonotoneAcrossReads() {
        val ctx = Context()
        val map = ComputedMap<Int, Int>()
        val sizes = mutableListOf<Int>()
        for (k in listOf(2, 4, 2, 5)) {
            map.getOrInsertWith(ctx, k) { it * 2 }
            sizes.add(map.presentCount)
        }
        assertEquals(listOf(1, 2, 2, 3), sizes)
        assertEquals(listOf(2, 4, 5), map.presentKeys())
    }

    /** Materializing one node does not change any other node's observed value. */
    @Test
    fun materializingOneNodeDoesNotChangeAnother() {
        val ctx = Context()
        val map = ComputedMap<Int, Int>()
        val before = map.getOrInsertWith(ctx, 1) { it * 10 }
        map.getOrInsertWith(ctx, 3) { it * 10 } // materialize another key
        assertEquals(before, map.getOrInsertWith(ctx, 1) { it * 10 })
    }

    /** A derived slot stays reactive: its value tracks the input it reads. */
    @Test
    fun slotEntriesRemainReactive() {
        val ctx = Context()
        val base = ctx.source(2)
        val map = ComputedMap<Int, Int>()
        assertEquals(10, map.getOrInsertWith(ctx, 5) { it * get(base) }) // 5 * 2
        base.set(ctx, 3)
        assertEquals(15, map.get(ctx, 5)) // 5 * 3 — recomputed
    }

    /** `get` on an absent key returns null (does not mint). */
    @Test
    fun getOnAbsentKeyIsNull() {
        val ctx = Context()
        val map = ComputedMap<Int, Int>()
        assertNull(map.get(ctx, 7))
        assertFalse(map.isPresent(7))
    }

    /** The SourceMap input-cell specialization: entries are materialized, writable inputs. */
    @Test
    fun cellMapEntriesAreWritableInputs() {
        val ctx = Context()
        val map = SourceMap<Int, Int>(ctx)
        map.insert(7, 7)
        assertEquals(EntryKind.Cell, map.entryKind)
        assertEquals(1, map.presentCount)
        val handle = map.value(7)
        assertEquals(7, ctx.get(handle))
        handle.set(ctx, 100)
        assertEquals(100, map.get(7))
    }

    /**
     * The pre-v2 keyed-collection names are **deprecated, not removed** — existing
     * callers must still compile and construct the same types. This test is the
     * compile-time proof: if a deprecated typealias were deleted, this file stops
     * compiling.
     */
    @Suppress("DEPRECATION")
    @Test
    fun deprecatedPreV2NamesStillResolve() {
        val ctx = Context()
        val cellMap: CellMap<Int, Int> = SourceMap(ctx, listOf(1 to 10))
        assertEquals(EntryKind.Cell, cellMap.entryKind)
        assertEquals(10, cellMap.get(1))

        val slotMap: SlotMap<Int, Int> = ComputedMap()
        slotMap.materializeAll(ctx, listOf(1, 2)) { it * 3 }
        assertEquals(EntryKind.Slot, slotMap.entryKind)
        assertEquals(6, slotMap.get(ctx, 2))

        // Thread-safe and async flavors alias the renamed classes too.
        val tsCellMap: ThreadSafeCellMap<Int, Int> = ThreadSafeSourceMap()
        val tsSlotMap: ThreadSafeSlotMap<Int, Int> = ThreadSafeComputedMap()
        val asyncCellMap: AsyncCellMap<Int, Int> = AsyncSourceMap()
        val asyncSlotMap: AsyncSlotMap<Int, Int> = AsyncComputedMap()
        assertEquals(EntryKind.Cell, tsCellMap.entryKind)
        assertEquals(EntryKind.Slot, tsSlotMap.entryKind)
        assertEquals(EntryKind.Cell, asyncCellMap.entryKind)
        assertEquals(EntryKind.Slot, asyncSlotMap.entryKind)

        // The ordered keyed tree aliases the renamed class too.
        val cellTree: CellTree<String, Int> = SourceTree(ctx)
        cellTree.addRoot("a", 1)
        cellTree.insertChild("a", "b", 2)
        assertEquals(2, cellTree.get("b"))
        assertEquals(listOf("b"), cellTree.children("a").keysNow())
    }
}
