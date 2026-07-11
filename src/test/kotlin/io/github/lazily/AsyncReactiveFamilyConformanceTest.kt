package io.github.lazily

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the async keyed reactive family
 * ([AsyncReactiveFamily]) and its materialization mode (`#lzmatmode`, async
 * flavor). Proves the [AsyncContext] flavor obeys the eager/lazy contract and
 * present-set monotonicity plus the **eventual-transparency** law (a driven
 * async slot resolves to the canonical value, eager ≡ lazy) proved in
 * `lazily-formal`'s `AsyncMaterialization` module. Mirrors the
 * `async_reactive_family` tests and the `materialization_async_conformance`
 * replay in `lazily-rs`.
 */
class AsyncReactiveFamilyConformanceTest {
    /** `eager_cell_family_resolves_immediately`: input cells are always resolved. */
    @Test
    fun eagerCellFamilyResolvesImmediately() {
        val ctx = AsyncContext()
        val fam = AsyncReactiveFamily.cells(ctx, listOf(1, 2, 3)) { true }
        assertEquals(EntryKind.Cell, fam.entryKind)
        assertEquals(3, fam.presentCount)
        assertEquals(true, fam.observe(ctx, 2))
        assertEquals(listOf(1, 2, 3), fam.presentKeys())
    }

    /**
     * `lazy_slot_family_defers_until_read`: a lazy slot family materializes on
     * `get`, and the value is `null` until driven with [AsyncReactiveFamily.observeAsync].
     */
    @Test
    fun lazySlotFamilyDefersAndResolvesOnDrive() = runBlocking {
        val ctx = AsyncContext()
        val fam = AsyncReactiveFamily.lazy(ctx, listOf<Int>()) { it * 10 }
        assertEquals(MaterializationMode.Lazy, fam.mode)
        assertEquals(0, fam.presentCount)
        // Materialize but do not drive → pending, non-blocking observe is null.
        val handle = fam.get(ctx, 4)
        assertTrue(handle is AsyncSlotEntry)
        assertTrue(fam.isPresent(4))
        assertEquals(1, fam.presentCount)
        assertNull(fam.observe(ctx, 4))
        // Drive to resolution.
        assertEquals(40, fam.observeAsync(ctx, 4))
        // Eventual transparency: once resolved, the non-blocking read sees it too.
        assertEquals(40, fam.observe(ctx, 4))
    }

    /** `cell_entries_still_materialize_at_build`: cells materialize under either mode. */
    @Test
    fun cellFamilyMaterializedInEveryMode() {
        val ctx = AsyncContext()
        for (mode in listOf(MaterializationMode.Eager, MaterializationMode.Lazy)) {
            val fam = AsyncReactiveFamily.cells(ctx, listOf("a", "b"), mode) { 0 }
            assertEquals(EntryKind.Cell, fam.entryKind)
            assertEquals(2, fam.presentCount)
        }
    }

    /**
     * `eventual_transparency_eager_equals_lazy`: a driven async slot resolves to
     * the same canonical value under either mode.
     */
    @Test
    fun eventualTransparencyEagerEqualsLazy() = runBlocking {
        val ctxE = AsyncContext()
        val eager = AsyncReactiveFamily.eager(ctxE, listOf(1, 2, 3)) { it * 2 }
        val ctxL = AsyncContext()
        val lazy = AsyncReactiveFamily.lazy(ctxL, listOf(1, 2, 3)) { it * 2 }
        for (k in listOf(1, 2, 3)) {
            assertEquals(eager.observeAsync(ctxE, k), lazy.observeAsync(ctxL, k))
        }
    }

    /** `present_set_grows_monotonically`: re-getting a key does not grow the present set. */
    @Test
    fun presentSetGrowsMonotonically() {
        val ctx = AsyncContext()
        val fam = AsyncReactiveFamily.lazy(ctx, listOf<Int>()) { it }
        fam.get(ctx, 5)
        fam.get(ctx, 5)
        fam.get(ctx, 9)
        assertEquals(2, fam.presentCount)
        assertEquals(listOf(5, 9), fam.presentKeys())
        assertFalse(fam.isPresent(1))
    }

    /** `cell_family_reacts_to_set`: an input-cell family observes writes. */
    @Test
    fun cellFamilyReactsToSet() {
        val ctx = AsyncContext()
        val fam = AsyncReactiveFamily.cells(ctx, listOf(10, 20)) { true }
        assertEquals(true, fam.observe(ctx, 20))
        val h = fam.get(ctx, 20)
        assertTrue(h is AsyncCellEntry)
        ctx.setCell(h.handle, false)
        assertEquals(false, fam.observe(ctx, 20))
    }
}
