package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactiveContextTest {
    @Test
    fun cell_get_and_set() {
        val ctx = Context()
        val a = ctx.cell(10)
        val b = ctx.cell(20)
        assertEquals(10, ctx.getCell(a))
        assertEquals(20, ctx.getCell(b))
        ctx.setCell(a, 99)
        assertEquals(99, ctx.getCell(a))
    }

    @Test
    fun cell_partial_eq_guard_suppresses_noop_set() {
        val ctx = Context()
        val src = ctx.cell(1)
        var runs = 0
        val derived = ctx.computed { runs++; ctx.getCell(src) * 2 }
        assertEquals(2, ctx.get(derived))
        assertEquals(1, runs)
        // Equal set: no invalidation, no recompute on next read.
        ctx.setCell(src, 1)
        assertEquals(2, ctx.get(derived))
        assertEquals(1, runs)
        // Real change: recomputes.
        ctx.setCell(src, 5)
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
        val a = ctx.cell(2)
        val b = ctx.cell(3)
        val sum = ctx.computed { ctx.getCell(a) + ctx.getCell(b) }
        assertEquals(5, ctx.get(sum))
        ctx.setCell(a, 10)
        assertEquals(13, ctx.get(sum))
        ctx.setCell(b, 20)
        assertEquals(30, ctx.get(sum))
    }

    @Test
    fun slot_chained_and_glitch_free() {
        val ctx = Context()
        val src = ctx.cell(1)
        val mid = ctx.computed { ctx.getCell(src) + 1 }
        val leaf = ctx.computed { ctx.get(mid) * 10 }
        assertEquals(20, ctx.get(leaf))
        ctx.setCell(src, 4)
        // mid = 5, leaf = 50 — consistent, no stale intermediate observed.
        assertEquals(50, ctx.get(leaf))
        assertEquals(5, ctx.get(mid))
    }

    @Test
    fun memo_guard_suppresses_downstream_on_equal_recompute() {
        val ctx = Context()
        val trigger = ctx.cell(1)
        // Projects to a constant regardless of trigger; memo guard keeps downstream.
        val constant = ctx.computed { ctx.getCell(trigger); 7 }
        var leafRuns = 0
        val leaf = ctx.computed { leafRuns++; ctx.get(constant) + 1 }
        assertEquals(8, ctx.get(leaf))
        assertEquals(1, leafRuns)
        ctx.setCell(trigger, 2) // constant recomputes to 7 (equal) → leaf must NOT recompute
        assertEquals(8, ctx.get(leaf))
        assertEquals(1, leafRuns)
    }

    @Test
    fun effect_reruns_on_dependency_change_and_cleans_up() {
        val ctx = Context()
        val src = ctx.cell(0)
        val seen = mutableListOf<Int>()
        val handle = ctx.effect {
            val v = ctx.getCell(src)
            seen.add(v)
            val cleanup: () -> Unit = { seen.add(-1) }
            cleanup
        }
        // Initial run.
        assertEquals(listOf(0), seen)
        ctx.setCell(src, 1)
        // Cleanup of prev run (-1) then new run (1).
        assertEquals(listOf(0, -1, 1), seen)
        ctx.disposeEffect(handle)
        // Disposed: final cleanup runs.
        assertEquals(listOf(0, -1, 1, -1), seen)
        ctx.setCell(src, 2)
        // No further reruns.
        assertEquals(listOf(0, -1, 1, -1), seen)
    }

    @Test
    fun signal_is_eager_and_memo_guarded() {
        val ctx = Context()
        val src = ctx.cell(2)
        var sigRuns = 0
        val sig = ctx.signal { sigRuns++; ctx.getCell(src) * 3 }
        // Eager: computed at creation.
        assertEquals(1, sigRuns)
        assertEquals(6, ctx.getSignal(sig))
        // Eager recompute on set, by the time setCell returns.
        ctx.setCell(src, 5)
        assertEquals(2, sigRuns)
        assertEquals(15, ctx.getSignal(sig))
        // Memo guard: equal recompute (src back to 5 is no-op). Use a derived that stays equal.
        val toggle = ctx.cell(true)
        val proj = ctx.signal { ctx.getCell(toggle); 100 }
        var downstreamRuns = 0
        val downstream = ctx.computed { downstreamRuns++; ctx.getSignal(proj) }
        assertEquals(100, ctx.get(downstream))
        assertEquals(1, downstreamRuns)
        ctx.setCell(toggle, false) // proj recomputes to 100 (equal) → downstream not invalidated
        assertEquals(100, ctx.get(downstream))
        assertEquals(1, downstreamRuns)
    }

    @Test
    fun signal_composes_with_other_signals() {
        val ctx = Context()
        val a = ctx.cell(2)
        val b = ctx.cell(3)
        val sa = ctx.signal { ctx.getCell(a) * 10 }
        val sb = ctx.signal { ctx.getCell(b) * 10 }
        val sum = ctx.signal { ctx.getSignal(sa) + ctx.getSignal(sb) }
        assertEquals(50, ctx.getSignal(sum))
        ctx.setCell(a, 4)
        assertEquals(70, ctx.getSignal(sum))
    }

    @Test
    fun batch_coalesces_invalidations() {
        val ctx = Context()
        val a = ctx.cell(1)
        val b = ctx.cell(1)
        val sum = ctx.computed { ctx.getCell(a) + ctx.getCell(b) }
        var effectRuns = 0
        ctx.effect { ctx.get(sum); effectRuns++; null }
        assertEquals(1, effectRuns)
        // Two sets in one batch → one combined effect rerun.
        ctx.batch {
            ctx.setCell(a, 10)
            ctx.setCell(b, 20)
        }
        assertEquals(2, effectRuns)
        assertEquals(30, ctx.get(sum))
    }

    @Test
    fun dynamic_dependencies_rediscover_on_recompute() {
        val ctx = Context()
        val branch = ctx.cell(true)
        val left = ctx.cell("L")
        val right = ctx.cell("R")
        val dyn = ctx.computed {
            if (ctx.getCell(branch)) ctx.getCell(left) else ctx.getCell(right)
        }
        assertEquals("L", ctx.get(dyn))
        // Changing the unread branch must NOT recompute (not a dependency yet).
        ctx.setCell(right, "R2")
        assertEquals("L", ctx.get(dyn))
        // Flip branch: now depends on right, drops left.
        ctx.setCell(branch, false)
        assertEquals("R2", ctx.get(dyn))
        // left is no longer a dependency.
        ctx.setCell(left, "L2")
        assertEquals("R2", ctx.get(dyn))
    }

    @Test
    fun circular_dependency_is_detected() {
        val ctx = Context()
        // A slot that reads itself: capture its own id post-creation.
        val selfId = intArrayOf(0)
        val self = ctx.slot<Int> { ctx.get(SlotHandle(selfId[0])) }
        selfId[0] = self.id
        try {
            ctx.get(self)
            throw AssertionError("expected circular dependency to be detected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("circular dependency", ignoreCase = true))
        }
    }
}
