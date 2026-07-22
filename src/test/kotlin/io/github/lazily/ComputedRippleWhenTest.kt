package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Context.computedRippleWhen] (#lzcellkernel) — a guarded computed with an
 * explicit, PURE change predicate (`true` = propagate). Covers the two
 * motivating shapes: a custom significance policy, and "propagate every N" where
 * the increment evidence lives in the value (so the predicate stays pure).
 *
 * Identities: `computed(f)` == `computedRippleWhen(f) { o, n -> o != n }`
 * (natural equality); the always-propagate pass-through is
 * `computedRippleWhen(f) { _, _ -> true }`.
 */
class ComputedRippleWhenTest {
    @Test
    fun custom_significance_propagates_on_proxy_change() {
        val ctx = Context()
        val input = ctx.source(0L)

        // Derived value carries a `bucket` proxy; propagate only when the bucket
        // changes, ignoring the raw payload.
        val derived = ctx.computedRippleWhen(
            { val v = ctx.get(input); v to (v / 10) }, // (payload, bucket)
            { old, new -> old.second != new.second }, // propagate when bucket changed
        )

        var recomputes = 0
        val observer = ctx.computed { recomputes++; ctx.get(derived).first }

        assertEquals(0L, ctx.get(observer))
        val base = recomputes

        // Same bucket (0..9): dependent stays cached.
        input.set(ctx, 3)
        assertEquals(0L, ctx.get(observer), "suppressed: proxy bucket unchanged")
        assertEquals(base, recomputes, "no dependent recompute within a bucket")

        // Crossing a bucket boundary propagates.
        input.set(ctx, 12)
        assertEquals(12L, ctx.get(observer), "propagated: bucket changed")
        assertEquals(base + 1, recomputes)
    }

    @Test
    fun propagate_every_n_via_value_carried_counter() {
        val ctx = Context()
        val input = ctx.source(0L)

        // "Propagate every 3rd increment" — evidence (the counter) is IN the
        // value, so the predicate is a pure function of (old, new): propagate
        // only when the count crosses a size-3 window boundary.
        val sampled = ctx.computedRippleWhen(
            { ctx.get(input) },
            { old, new -> new / 3 != old / 3 },
        )

        var seen = 0
        val observer = ctx.computed { seen++; ctx.get(sampled) }

        assertEquals(0L, ctx.get(observer))
        val base = seen

        // 0 -> 1 -> 2 stay in window [0,3): suppressed.
        input.set(ctx, 1)
        input.set(ctx, 2)
        assertEquals(0L, ctx.get(observer))
        assertEquals(base, seen, "window not crossed yet")

        // 3 crosses into [3,6): propagate.
        input.set(ctx, 3)
        assertEquals(3L, ctx.get(observer))
        assertEquals(base + 1, seen)
    }

    @Test
    fun computed_is_computed_ripple_when_not_equal() {
        // `computed(f)` behaves as `computedRippleWhen(f) { o, n -> o != n }`.
        val ctx = Context()
        val input = ctx.source(0L)

        val viaComputed = ctx.computed { minOf(ctx.get(input), 1L) }
        val viaWhen = ctx.computedRippleWhen(
            { minOf(ctx.get(input), 1L) },
            { o, n -> o != n },
        )

        var a = 0
        var b = 0
        val obsA = ctx.computed { a++; ctx.get(viaComputed) }
        val obsB = ctx.computed { b++; ctx.get(viaWhen) }
        assertEquals(0L, ctx.get(obsA))
        assertEquals(0L, ctx.get(obsB))
        val baseA = a
        val baseB = b

        // 0 -> 5 both clamp to 1: both guards suppress identically.
        input.set(ctx, 5)
        assertEquals(1L, ctx.get(obsA))
        assertEquals(1L, ctx.get(obsB))
        assertEquals(baseA + 1, a)
        assertEquals(baseB + 1, b)

        // 5 -> 9 both stay 1: both suppress the dependent.
        input.set(ctx, 9)
        assertEquals(1L, ctx.get(obsA))
        assertEquals(1L, ctx.get(obsB))
        assertEquals(baseA + 1, a, "computed suppressed equal recompute")
        assertEquals(baseB + 1, b, "computedRippleWhen(!=) matches computed")
    }

    @Test
    fun pass_through_always_propagates() {
        val ctx = Context()
        val input = ctx.source(0L)
        // ripple-when { _, _ -> true } installs an always-propagate guard: even
        // an equal recompute propagates (the pass-through identity).
        val passthrough = ctx.computedRippleWhen(
            { ctx.get(input); 0L }, // depend on input, but always yield the same value
            { _, _ -> true },
        )

        var recomputes = 0
        val observer = ctx.computed { recomputes++; ctx.get(passthrough) }

        assertEquals(0L, ctx.get(observer))
        val base = recomputes

        // Value stays 0, but the guard always propagates, so the dependent re-fires.
        input.set(ctx, 5)
        assertEquals(0L, ctx.get(observer))
        assertTrue(
            recomputes > base,
            "pass-through propagates even when the value is unchanged",
        )
    }
}
