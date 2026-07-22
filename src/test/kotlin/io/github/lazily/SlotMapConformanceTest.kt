package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit conformance tests for the keyed reactive collections (`#reactivemap`) — the
 * [SlotMap] derived-slot specialization and the [CellMap] input-cell
 * specialization on a single-threaded [Context]. Mirrors the `cell_family` tests
 * in `lazily-rs`. Materialization has no eager/lazy mode flag: eager is the
 * pre-mint loop [SlotMap.materializeAll], lazy is mint-on-access
 * [SlotMap.getOrInsertWith].
 */
class SlotMapConformanceTest {
    /** Eager (materializeAll) allocates every declared derived slot up front. */
    @Test
    fun eagerMaterializesAllUpFront() {
        val ctx = Context()
        val map = SlotMap<Int, Int>()
        map.materializeAll(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        assertEquals(EntryKind.Slot, map.entryKind)
        assertEquals(5, map.presentCount)
        for (k in listOf(0, 1, 2, 5, 9)) assertTrue(map.isPresent(k))
    }

    /** Lazy (getOrInsertWith) leaves an unread derived slot unallocated. */
    @Test
    fun lazyDefersSlotsUntilRead() {
        val ctx = Context()
        val map = SlotMap<Int, Int>()
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
        val eager = SlotMap<Int, Int>()
        eager.materializeAll(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        val lazy = SlotMap<Int, Int>()
        for (k in listOf(0, 1, 2, 5, 9)) {
            assertEquals(eager.get(ctx, k), lazy.getOrInsertWith(ctx, k) { it * 3 })
        }
    }

    /** Re-reading a key does not change the present set; the set only grows. */
    @Test
    fun presentSetIsMonotoneAcrossReads() {
        val ctx = Context()
        val map = SlotMap<Int, Int>()
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
        val map = SlotMap<Int, Int>()
        val before = map.getOrInsertWith(ctx, 1) { it * 10 }
        map.getOrInsertWith(ctx, 3) { it * 10 } // materialize another key
        assertEquals(before, map.getOrInsertWith(ctx, 1) { it * 10 })
    }

    /** A derived slot stays reactive: its value tracks the input it reads. */
    @Test
    fun slotEntriesRemainReactive() {
        val ctx = Context()
        val base = ctx.source(2)
        val map = SlotMap<Int, Int>()
        assertEquals(10, map.getOrInsertWith(ctx, 5) { it * get(base) }) // 5 * 2
        base.set(ctx, 3)
        assertEquals(15, map.get(ctx, 5)) // 5 * 3 — recomputed
    }

    /** `get` on an absent key returns null (does not mint). */
    @Test
    fun getOnAbsentKeyIsNull() {
        val ctx = Context()
        val map = SlotMap<Int, Int>()
        assertNull(map.get(ctx, 7))
        assertFalse(map.isPresent(7))
    }

    /** The CellMap input-cell specialization: entries are materialized, writable inputs. */
    @Test
    fun cellMapEntriesAreWritableInputs() {
        val ctx = Context()
        val map = CellMap<Int, Int>(ctx)
        map.insert(7, 7)
        assertEquals(EntryKind.Cell, map.entryKind)
        assertEquals(1, map.presentCount)
        val handle = map.value(7)
        assertEquals(7, ctx.get(handle))
        handle.set(ctx, 100)
        assertEquals(100, map.get(7))
    }
}
