package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The v2 Cell kernel (`#lzcellkernel`): the two concrete handles [Source] and
 * [Computed] (the value-node concept [Cell] over them), guarded computeds, and
 * the eager construction `computed().eager()` (eager bit + `eagerBy` side table).
 *
 * ## Write protection is a compile error (§3)
 *
 * `set` / `merge` are extension functions declared on [Source] only, so a
 * [Computed] has no such method. The line below does not compile — verified
 * with the compiler, since Kotlin/Gradle has no compile-fail harness:
 *
 * ```
 * val g: Computed<Int> = ctx.computed { 1 }
 * g.set(ctx, 2)   // ERROR: Unresolved reference — `set` exists only on Source<T>
 * ```
 */
class CellKernelTest {
    private data class Box(val value: Int)

    @Test
    fun source_cell_reads_and_writes() {
        val ctx = Context()
        val n = ctx.source(1)
        assertEquals(1, ctx.get(n))
        n.set(ctx, 5)
        assertEquals(5, ctx.get(n))
        n.merge(ctx, 9) // KeepLatest default ⇒ a merge is a replace
        assertEquals(9, ctx.get(n))
    }

    @Test
    fun computed_is_guarded_and_reads_the_genus() {
        val ctx = Context()
        val n = ctx.source(2)
        var computes = 0
        val doubled = ctx.computed { computes++; get(n) * 2 }
        assertEquals(4, ctx.get(doubled))
        assertEquals(4, ctx.get(doubled)) // cached — no recompute
        assertEquals(1, computes)
        n.set(ctx, 3)
        assertEquals(6, ctx.get(doubled))
        assertEquals(2, computes)
    }

    @Test
    fun shared_reference_read_parity_covers_source_and_computed() {
        // #lzrsgetarc: Kotlin's ordinary get is naturally a shared-reference
        // read. Pin the three lazily-formal parity properties with identity,
        // refresh, and one exact tracked edge for each Cell kind.
        val ctx = Context()
        val initial = Box(1)
        val source = ctx.source(initial)
        val computed = ctx.computed { get(source) }
        val sourceReader = ctx.computed { get(source).value }
        val computedReader = ctx.computed { get(computed).value }

        assertSame(initial, ctx.get(source))
        assertSame(initial, ctx.get(computed))
        assertEquals(1, ctx.get(sourceReader))
        assertEquals(1, ctx.get(computedReader))
        assertEquals(1, ctx.dependencyCount(sourceReader))
        assertEquals(1, ctx.dependencyCount(computedReader))

        val replacement = Box(2)
        source.set(ctx, replacement)
        assertSame(replacement, ctx.get(source))
        assertSame(replacement, ctx.get(computed))
        assertEquals(2, ctx.get(sourceReader))
        assertEquals(2, ctx.get(computedReader))
    }

    @Test
    fun equal_recompute_suppresses_downstream() {
        // The guard: a computed that recomputes to an equal value bumps no
        // version, so a dependent computed does not re-run (all cells guarded).
        val ctx = Context()
        val n = ctx.source(1)
        val parity = ctx.computed { get(n) % 2 } // 1 -> 1 when n goes 1 -> 3
        var downstream = 0
        val watcher = ctx.computed { downstream++; get(parity) }
        assertEquals(1, ctx.get(watcher))
        assertEquals(1, downstream)
        n.set(ctx, 3) // parity recomputes to 1 (unchanged) ⇒ watcher must not re-run
        assertEquals(1, ctx.get(watcher))
        assertEquals(1, downstream)
    }

    @Test
    fun eager_computed_materializes_and_reverts_on_lazy() {
        val ctx = Context()
        val n = ctx.source(1)
        var computes = 0
        val f = ctx.computed { computes++; get(n) + 100 }.eager(ctx)
        assertTrue(f.isEager(ctx))
        assertEquals(1, computes, "an eager computed materializes at creation, without a read")
        assertEquals(1, ctx.dependentCount(f), "the puller is a dependent of the computed")

        n.set(ctx, 2)
        assertEquals(2, computes, "eager: recomputed by the time set() returns, no read")
        assertEquals(102, ctx.get(f))

        f.lazy(ctx)
        assertFalse(f.isEager(ctx))
        assertEquals(0, ctx.dependentCount(f), "lazy tears down the puller")

        n.set(ctx, 5)
        assertEquals(2, computes, "lazy: no eager recompute")
        assertEquals(105, ctx.get(f)) // recomputes on read
        assertEquals(3, computes)
    }

    @Test
    fun eager_is_idempotent_and_returns_the_same_handle() {
        val ctx = Context()
        val n = ctx.source(0)
        val f = ctx.computed { get(n) }.eager(ctx)
        assertEquals(1, ctx.dependentCount(f))
        val same = f.eager(ctx)
        assertEquals(f, same, "eager returns the same (mutated) handle")
        assertEquals(1, ctx.dependentCount(f), "a second eager attaches no second puller")
    }

    @Test
    fun disposing_an_eager_computed_tears_down_its_puller() {
        val ctx = Context()
        val n = ctx.source(0)
        val f = ctx.computed { get(n) }.eager(ctx)
        assertEquals(1, ctx.dependentCount(n), "the computed reads the source")
        f.dispose(ctx)
        assertTrue(ctx.isDisposed(f))
        assertEquals(0, ctx.dependentCount(n), "disposal detaches the computed and its puller")
        // A later write must not resurrect a stranded puller.
        n.set(ctx, 1)
    }
}
