package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the unified keyed reactive family
 * ([ReactiveFamily]) and its materialization mode (`#lzmatmode`) — the layer
 * required of every binding. See `lazily-spec/cell-model.md` § "Materialization
 * mode" and the proofs in `lazily-formal`'s `Materialization` module (proof names
 * referenced per test). Mirrors the `reactive_family` tests in `lazily-rs`.
 */
class ReactiveFamilyConformanceTest {
    /** `default_mode_eager`: the default materialization mode is eager. */
    @Test
    fun defaultModeIsEager() {
        assertEquals(MaterializationMode.Eager, MaterializationMode.Default)
    }

    /** `eager_materializes_all`: eager allocates every declared node up front. */
    @Test
    fun eagerMaterializesAllUpFront() {
        val ctx = Context()
        val fam = ReactiveFamily.eager(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        assertEquals(EntryKind.Slot, fam.entryKind)
        assertEquals(5, fam.presentCount)
        for (k in listOf(0, 1, 2, 5, 9)) assertTrue(fam.isPresent(k))
    }

    /** `lazy_defers_slots`: lazy leaves an unread derived slot unallocated. */
    @Test
    fun lazyDefersSlotsUntilRead() {
        val ctx = Context()
        val fam = ReactiveFamily.lazy(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        assertEquals(MaterializationMode.Lazy, fam.mode)
        assertEquals(0, fam.presentCount)
        assertFalse(fam.isPresent(5))

        // First read materializes just that key ("materialize on pull").
        assertEquals(15, fam.observe(ctx, 5))
        assertTrue(fam.isPresent(5))
        assertEquals(listOf(5), fam.presentKeys())
    }

    /**
     * `eager_lazy_observationally_equivalent` / `observe_canonical`: identical
     * values under either mode.
     */
    @Test
    fun eagerAndLazyObserveIdentically() {
        val ctx = Context()
        val eager = ReactiveFamily.eager(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        val lazy = ReactiveFamily.lazy(ctx, listOf(0, 1, 2, 5, 9)) { it * 3 }
        for (k in listOf(0, 1, 2, 5, 9)) {
            assertEquals(eager.observe(ctx, k), lazy.observe(ctx, k))
        }
    }

    /**
     * `materialize_present_monotone` / `lazy_present_subset_eager`: re-reading a
     * key does not change the present set; the set only grows.
     */
    @Test
    fun presentSetIsMonotoneAcrossReads() {
        val ctx = Context()
        val fam = ReactiveFamily.lazy(ctx, listOf(1, 2, 3, 4, 5)) { it * 2 }
        val sizes = mutableListOf<Int>()
        for (k in listOf(2, 4, 2, 5)) {
            fam.observe(ctx, k)
            sizes.add(fam.presentCount)
        }
        // Re-reading 2 does not re-materialize; sizes are non-decreasing.
        assertEquals(listOf(1, 2, 2, 3), sizes)
        assertEquals(listOf(2, 4, 5), fam.presentKeys())
    }

    /**
     * `materialize_preserves_observe`: materializing one node does not change any
     * other node's observed value.
     */
    @Test
    fun materializingOneNodeDoesNotChangeAnother() {
        val ctx = Context()
        val fam = ReactiveFamily.lazy(ctx, listOf(1, 2, 3)) { it * 10 }
        val before = fam.observe(ctx, 1)
        fam.observe(ctx, 3) // materialize another key
        assertEquals(before, fam.observe(ctx, 1))
    }

    /**
     * `cell_entries_materialized_in_every_mode`: an input-cell family is fully
     * materialized at build under **either** mode.
     */
    @Test
    fun cellFamilyMaterializedInEveryMode() {
        val ctx = Context()
        for (mode in listOf(MaterializationMode.Eager, MaterializationMode.Lazy)) {
            val fam = ReactiveFamily.cells(ctx, listOf("a", "b", "c"), mode) { 0 }
            assertEquals(EntryKind.Cell, fam.entryKind)
            // Cells are always present at build, even under lazy.
            assertEquals(3, fam.presentCount)
        }
    }

    /** Cell entries are writable inputs (materialized-by-set), distinct from derived slots. */
    @Test
    fun cellFamilyEntriesAreWritableInputs() {
        val ctx = Context()
        val fam = ReactiveFamily.cells(ctx, listOf(7)) { it }
        val entry = fam.get(ctx, 7)
        assertTrue(entry is CellEntry)
        assertEquals(7, ctx.getCell(entry.handle))
        ctx.setCell(entry.handle, 100)
        assertEquals(100, fam.observe(ctx, 7))
    }

    /** Slot entries stay reactive: a derived value tracks the input it reads. */
    @Test
    fun slotEntriesRemainReactive() {
        val ctx = Context()
        val base = ctx.cell(2)
        val fam = ReactiveFamily.lazy(ctx, listOf(1, 2, 3)) { it * ctx.getCell(base) }
        assertEquals(10, fam.observe(ctx, 5)) // 5 * 2
        ctx.setCell(base, 3)
        assertEquals(15, fam.observe(ctx, 5)) // 5 * 3 — recomputed
    }
}
