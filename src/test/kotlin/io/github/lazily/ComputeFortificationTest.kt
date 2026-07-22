package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fortified [Compute] view is the sole tracking surface (`#lzcellkernel`).
 *
 * Mirrors lazily-rs `tests/compute_fortification.rs`. Dependency tracking is
 * **value-threaded**: the recomputing node id rides in the [Compute] handed to
 * the closure, so a read *through it* registers the edge against that node — not
 * via an ambient frame that a suspension could clobber.
 *
 * The two halves of the contract:
 *  1. A **tracked** read through the closure's [Compute] registers a dependency
 *     edge against the recomputing node, so a change recomputes the dependent.
 *  2. The explicit **untracked** escape ([ComputeOps.untracked]) registers **no**
 *     edge, so the dependent neither gains a dependency nor recomputes.
 *
 * Plus the Kotlin-specific runtime fortification: a [Compute] used outside its
 * recompute (the escape lazily-rs forbids at compile time via lifetime + `!Send`)
 * throws rather than misattributing an edge.
 */
class ComputeFortificationTest {
    @Test
    fun `tracked read registers the edge against the recomputing node`() {
        val ctx = Context()
        val a = ctx.source(1)

        var calls = 0
        val b = ctx.computed {
            calls++
            // Tracked read: the edge must attribute to `b`, the node being
            // recomputed — not to any ambient frame.
            get(a) * 10
        }

        assertEquals(10, ctx.get(b))
        assertEquals(1, calls, "first read computes once")

        // Structural: the edge exists in both directions.
        assertEquals(1, ctx.dependentCount(a), "a must have b as its single tracked dependent")
        assertEquals(1, ctx.dependencyCount(b), "b must depend on a")

        // Behavioural: changing a recomputes b.
        a.set(ctx, 5)
        assertEquals(50, ctx.get(b))
        assertEquals(2, calls, "changing the tracked dependency recomputes b")
    }

    @Test
    fun `untracked read registers no edge and does not recompute`() {
        val ctx = Context()
        val a = ctx.source(1)

        var calls = 0
        val d = ctx.computed {
            calls++
            // The explicit untracked escape: read `a` through the owning Context,
            // which forms no dependency edge.
            untracked().get(a) * 10
        }

        assertEquals(10, ctx.get(d))
        assertEquals(1, calls)

        // Structural: no edge was formed by the untracked read.
        assertEquals(0, ctx.dependentCount(a), "an untracked read must not register a dependent")
        assertEquals(0, ctx.dependencyCount(d), "d must have acquired no dependency")

        // Behavioural: changing a does NOT recompute d — its cached value stands.
        a.set(ctx, 5)
        assertEquals(10, ctx.get(d), "untracked dependent keeps its stale value")
        assertEquals(1, calls, "untracked dependent never recomputes")
    }

    @Test
    fun `an effect tracks through its compute view`() {
        val ctx = Context()
        val a = ctx.source(1)

        var runs = 0
        ctx.effect {
            runs++
            get(a)
            null
        }

        assertEquals(1, runs, "effect runs once on creation")
        assertEquals(1, ctx.dependentCount(a), "effect owns the edge to a")

        a.set(ctx, 2)
        assertEquals(2, runs, "a change reruns the tracking effect")
    }

    @Test
    fun `a Compute used outside its recompute throws instead of misattributing`() {
        val ctx = Context()
        val a = ctx.source(1)

        // Smuggle the view out of its recompute (the non-escapable contract
        // lazily-rs enforces at compile time; here it is a runtime guard).
        var escaped: Compute? = null
        val b = ctx.computed {
            escaped = this
            get(a)
        }
        assertEquals(1, ctx.get(b)) // force one recompute so `escaped` is populated

        val stale = escaped
        assertTrue(stale != null, "view captured")
        // Replaying the stale view after its frame returned must throw, not
        // silently register an edge against `b` (or a recycled node).
        assertFailsWith<IllegalStateException> { stale!!.get(a) }
    }
}
