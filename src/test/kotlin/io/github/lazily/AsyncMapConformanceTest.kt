package io.github.lazily

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the async keyed reactive collections
 * (`#reactivemap`, async flavor): the [AsyncCellMap] input-cell and [AsyncSlotMap]
 * derived-slot specializations. Proves the [AsyncContext] flavor obeys the
 * eager/lazy contract and present-set monotonicity plus the
 * **eventual-transparency** law (a driven async slot resolves to the canonical
 * value, eager ≡ lazy) proved in `lazily-formal`'s `AsyncMaterialization` module.
 * Mirrors the `async_reactive_family` / `materialization_async_conformance` tests
 * in `lazily-rs`.
 */
class AsyncMapConformanceTest {
    /** Input cells are always resolved: an AsyncCellMap is fully present once built. */
    @Test
    fun cellMapResolvesImmediately() {
        val ctx = AsyncContext()
        val map = AsyncCellMap<Int, Boolean>()
        map.materializeAll(ctx, listOf(1, 2, 3)) { true }
        assertEquals(EntryKind.Cell, map.entryKind)
        assertEquals(3, map.presentCount)
        assertEquals(true, map.observe(ctx, 2))
        assertEquals(listOf(1, 2, 3), map.presentKeys())
    }

    /**
     * A lazy slot map materializes on `getOrInsertWith`, and the value is `null`
     * until driven with [AsyncSlotMap.observeAsync].
     */
    @Test
    fun slotMapDefersAndResolvesOnDrive() = runBlocking {
        val ctx = AsyncContext()
        val map = AsyncSlotMap<Int, Int>()
        assertEquals(0, map.presentCount)
        // Materialize but do not drive → pending, non-blocking observe is null.
        map.getOrInsertWith(ctx, 4) { it * 10 }
        assertTrue(map.isPresent(4))
        assertEquals(1, map.presentCount)
        assertNull(map.observe(ctx, 4))
        // Drive to resolution.
        assertEquals(40, map.observeAsync(ctx, 4))
        // Eventual transparency: once resolved, the non-blocking read sees it too.
        assertEquals(40, map.observe(ctx, 4))
    }

    /** Input cells materialize at build regardless of strategy. */
    @Test
    fun cellMapMaterializesInputsAtBuild() {
        val ctx = AsyncContext()
        val map = AsyncCellMap<String, Int>()
        map.materializeAll(ctx, listOf("a", "b")) { 0 }
        assertEquals(EntryKind.Cell, map.entryKind)
        assertEquals(2, map.presentCount)
    }

    /** A driven async slot resolves to the same canonical value under either strategy. */
    @Test
    fun eventualTransparencyEagerEqualsLazy() = runBlocking {
        val ctxE = AsyncContext()
        val eager = AsyncSlotMap<Int, Int>()
        eager.materializeAll(ctxE, listOf(1, 2, 3)) { it * 2 }
        val ctxL = AsyncContext()
        val lazy = AsyncSlotMap<Int, Int>()
        for (k in listOf(1, 2, 3)) {
            lazy.getOrInsertWith(ctxL, k) { it * 2 }
            assertEquals(eager.observeAsync(ctxE, k), lazy.observeAsync(ctxL, k))
        }
    }

    /** Re-getting a key does not grow the present set. */
    @Test
    fun presentSetGrowsMonotonically() {
        val ctx = AsyncContext()
        val map = AsyncSlotMap<Int, Int>()
        map.getOrInsertWith(ctx, 5) { it }
        map.getOrInsertWith(ctx, 5) { it }
        map.getOrInsertWith(ctx, 9) { it }
        assertEquals(2, map.presentCount)
        assertEquals(listOf(5, 9), map.presentKeys())
        assertFalse(map.isPresent(1))
    }

    /** An input-cell map observes writes. */
    @Test
    fun cellMapReactsToSet() {
        val ctx = AsyncContext()
        val map = AsyncCellMap<Int, Boolean>()
        map.materializeAll(ctx, listOf(10, 20)) { true }
        assertEquals(true, map.observe(ctx, 20))
        map.set(ctx, 20, false)
        assertEquals(false, map.observe(ctx, 20))
    }
}
