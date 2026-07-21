package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactiveContextTest {
    @Test
    fun cell_get_and_set() {
        val ctx = Context()
        val a = ctx.source(10)
        val b = ctx.source(20)
        assertEquals(10, ctx.get(a))
        assertEquals(20, ctx.get(b))
        a.set(ctx, 99)
        assertEquals(99, ctx.get(a))
    }

    @Test
    fun cell_partial_eq_guard_suppresses_noop_set() {
        val ctx = Context()
        val src = ctx.source(1)
        var runs = 0
        val derived = ctx.computed { runs++; ctx.get(src) * 2 }
        assertEquals(2, ctx.get(derived))
        assertEquals(1, runs)
        // Equal set: no invalidation, no recompute on next read.
        src.set(ctx, 1)
        assertEquals(2, ctx.get(derived))
        assertEquals(1, runs)
        // Real change: recomputes.
        src.set(ctx, 5)
        assertEquals(10, ctx.get(derived))
        assertEquals(2, runs)
    }

    @Test
    fun slot_is_lazy_and_caches() {
        val ctx = Context()
        var calls = 0
        val s = ctx.computed { calls++; 42 }
        assertFalse(ctx.isSet(s))
        assertEquals(42, ctx.get(s))
        assertEquals(1, calls)
        assertTrue(ctx.isSet(s))
        // Cached: no recompute.
        assertEquals(42, ctx.get(s))
        assertEquals(1, calls)
    }

    @Test
    fun slot_tracks_cell_dependency_and_invalidates() {
        val ctx = Context()
        val a = ctx.source(2)
        val b = ctx.source(3)
        val sum = ctx.computed { ctx.get(a) + ctx.get(b) }
        assertEquals(5, ctx.get(sum))
        a.set(ctx, 10)
        assertEquals(13, ctx.get(sum))
        b.set(ctx, 20)
        assertEquals(30, ctx.get(sum))
    }

    @Test
    fun slot_chained_and_glitch_free() {
        val ctx = Context()
        val src = ctx.source(1)
        val mid = ctx.computed { ctx.get(src) + 1 }
        val leaf = ctx.computed { ctx.get(mid) * 10 }
        assertEquals(20, ctx.get(leaf))
        src.set(ctx, 4)
        // mid = 5, leaf = 50 — consistent, no stale intermediate observed.
        assertEquals(50, ctx.get(leaf))
        assertEquals(5, ctx.get(mid))
    }

    @Test
    fun memo_guard_suppresses_downstream_on_equal_recompute() {
        val ctx = Context()
        val trigger = ctx.source(1)
        // Projects to a constant regardless of trigger; memo guard keeps downstream.
        val constant = ctx.computed { ctx.get(trigger); 7 }
        var leafRuns = 0
        val leaf = ctx.computed { leafRuns++; ctx.get(constant) + 1 }
        assertEquals(8, ctx.get(leaf))
        assertEquals(1, leafRuns)
        trigger.set(ctx, 2) // constant recomputes to 7 (equal) → leaf must NOT recompute
        assertEquals(8, ctx.get(leaf))
        assertEquals(1, leafRuns)
    }

    @Test
    fun effect_reruns_on_dependency_change_and_cleans_up() {
        val ctx = Context()
        val src = ctx.source(0)
        val seen = mutableListOf<Int>()
        val handle = ctx.effect {
            val v = ctx.get(src)
            seen.add(v)
            val cleanup: () -> Unit = { seen.add(-1) }
            cleanup
        }
        // Initial run.
        assertEquals(listOf(0), seen)
        src.set(ctx, 1)
        // Cleanup of prev run (-1) then new run (1).
        assertEquals(listOf(0, -1, 1), seen)
        ctx.disposeEffect(handle)
        // Disposed: final cleanup runs.
        assertEquals(listOf(0, -1, 1, -1), seen)
        src.set(ctx, 2)
        // No further reruns.
        assertEquals(listOf(0, -1, 1, -1), seen)
    }

    @Test
    fun signal_is_eager_and_memo_guarded() {
        val ctx = Context()
        val src = ctx.source(2)
        var sigRuns = 0
        val sig = ctx.computed { sigRuns++; ctx.get(src) * 3 }.eager(ctx)
        // Eager: computed at creation.
        assertEquals(1, sigRuns)
        assertEquals(6, ctx.get(sig))
        // Eager recompute on set, by the time setCell returns.
        src.set(ctx, 5)
        assertEquals(2, sigRuns)
        assertEquals(15, ctx.get(sig))
        // Memo guard: equal recompute (src back to 5 is no-op). Use a derived that stays equal.
        val toggle = ctx.source(true)
        val proj = ctx.computed { ctx.get(toggle); 100 }.eager(ctx)
        var downstreamRuns = 0
        val downstream = ctx.computed { downstreamRuns++; ctx.get(proj) }
        assertEquals(100, ctx.get(downstream))
        assertEquals(1, downstreamRuns)
        toggle.set(ctx, false) // proj recomputes to 100 (equal) → downstream not invalidated
        assertEquals(100, ctx.get(downstream))
        assertEquals(1, downstreamRuns)
    }

    @Test
    fun signal_composes_with_other_signals() {
        val ctx = Context()
        val a = ctx.source(2)
        val b = ctx.source(3)
        val sa = ctx.computed { ctx.get(a) * 10 }.eager(ctx)
        val sb = ctx.computed { ctx.get(b) * 10 }.eager(ctx)
        val sum = ctx.computed { ctx.get(sa) + ctx.get(sb) }.eager(ctx)
        assertEquals(50, ctx.get(sum))
        a.set(ctx, 4)
        assertEquals(70, ctx.get(sum))
    }

    @Test
    fun batch_coalesces_invalidations() {
        val ctx = Context()
        val a = ctx.source(1)
        val b = ctx.source(1)
        val sum = ctx.computed { ctx.get(a) + ctx.get(b) }
        var effectRuns = 0
        ctx.effect { ctx.get(sum); effectRuns++; null }
        assertEquals(1, effectRuns)
        // Two sets in one batch → one combined effect rerun.
        ctx.batch {
            a.set(ctx, 10)
            b.set(ctx, 20)
        }
        assertEquals(2, effectRuns)
        assertEquals(30, ctx.get(sum))
    }

    @Test
    fun dynamic_dependencies_rediscover_on_recompute() {
        val ctx = Context()
        val branch = ctx.source(true)
        val left = ctx.source("L")
        val right = ctx.source("R")
        val dyn = ctx.computed {
            if (ctx.get(branch)) ctx.get(left) else ctx.get(right)
        }
        assertEquals("L", ctx.get(dyn))
        // Changing the unread branch must NOT recompute (not a dependency yet).
        right.set(ctx, "R2")
        assertEquals("L", ctx.get(dyn))
        // Flip branch: now depends on right, drops left.
        branch.set(ctx, false)
        assertEquals("R2", ctx.get(dyn))
        // left is no longer a dependency.
        left.set(ctx, "L2")
        assertEquals("R2", ctx.get(dyn))
    }

    @Test
    fun circular_dependency_is_detected() {
        val ctx = Context()
        // A slot that reads itself: capture its own id post-creation.
        val selfId = intArrayOf(0)
        val self = ctx.slot<Int> { ctx.get(Computed(selfId[0])) }
        selfId[0] = self.id
        try {
            ctx.get(self)
            throw AssertionError("expected circular dependency to be detected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("circular dependency", ignoreCase = true))
        }
    }
}
