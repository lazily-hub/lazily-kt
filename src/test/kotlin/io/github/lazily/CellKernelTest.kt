package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Cell kernel (`#lzcellkernel`): the genus [Cell]`<T, K>` over [SourceCell]
 * and [FormulaCell], guarded formulas, and the eager construction
 * `formula().drive()` (driven bit + `drivenBy` side table).
 *
 * ## Write protection is a compile error (§4)
 *
 * `set` / `merge` are extension functions declared on `Cell<T, Source<M>>`, so a
 * [FormulaCell] has no such method. The line below does not compile — verified
 * with the compiler, since Kotlin/Gradle has no compile-fail harness:
 *
 * ```
 * val g: FormulaCell<Int> = ctx.formula { 1 }
 * g.set(ctx, 2)   // ERROR: Unresolved reference — receiver type mismatch
 *                 // (`set` exists only on the source kind, Cell<T, Source<M>>)
 * ```
 */
class CellKernelTest {
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
    fun formula_is_guarded_and_reads_the_genus() {
        val ctx = Context()
        val n = ctx.source(2)
        var computes = 0
        val doubled = ctx.formula { computes++; ctx.get(n) * 2 }
        assertEquals(4, ctx.get(doubled))
        assertEquals(4, ctx.get(doubled)) // cached — no recompute
        assertEquals(1, computes)
        n.set(ctx, 3)
        assertEquals(6, ctx.get(doubled))
        assertEquals(2, computes)
    }

    @Test
    fun driven_formula_is_eager_and_reverts_on_undrive() {
        val ctx = Context()
        val n = ctx.source(1)
        var computes = 0
        val f = ctx.formula { computes++; ctx.get(n) + 100 }.drive(ctx)
        assertTrue(f.isDriven(ctx))
        assertEquals(1, computes, "a driven formula materializes at creation, without a read")
        assertEquals(1, ctx.dependentCount(f), "the puller is a dependent of the formula")

        n.set(ctx, 2)
        assertEquals(2, computes, "eager: recomputed by the time set() returns, no read")
        assertEquals(102, ctx.get(f))

        f.undrive(ctx)
        assertFalse(f.isDriven(ctx))
        assertEquals(0, ctx.dependentCount(f), "undrive tears down the puller")

        n.set(ctx, 5)
        assertEquals(2, computes, "lazy after undrive: no eager recompute")
        assertEquals(105, ctx.get(f)) // recomputes on read
        assertEquals(3, computes)
    }

    @Test
    fun drive_is_idempotent_and_returns_the_same_handle() {
        val ctx = Context()
        val n = ctx.source(0)
        val f = ctx.formula { ctx.get(n) }.drive(ctx)
        assertEquals(1, ctx.dependentCount(f))
        val same = f.drive(ctx)
        assertEquals(f, same, "drive returns the same (mutated) handle")
        assertEquals(1, ctx.dependentCount(f), "a second drive attaches no second puller")
    }

    @Test
    fun disposing_a_driven_formula_tears_down_its_puller() {
        val ctx = Context()
        val n = ctx.source(0)
        val f = ctx.formula { ctx.get(n) }.drive(ctx)
        assertEquals(1, ctx.dependentCount(n), "the formula reads the source")
        f.dispose(ctx)
        assertTrue(ctx.isDisposed(f))
        assertEquals(0, ctx.dependentCount(n), "disposal detaches the formula and its puller")
        // A later write must not resurrect a stranded puller.
        n.set(ctx, 1)
    }
}
