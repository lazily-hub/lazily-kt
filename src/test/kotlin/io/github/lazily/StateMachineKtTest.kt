package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateMachineKtTest {
    private enum class Light { Red, Green, Yellow }

    @Test
    fun traffic_light_transitions() {
        val ctx = Context()
        val m = StateMachine(ctx, Light.Red) { s, _: String ->
            when (s) {
                Light.Red -> Light.Green
                Light.Green -> Light.Yellow
                Light.Yellow -> Light.Red
            }
        }
        assertEquals(Light.Red, m.state)
        assertTrue(m.send("advance")); assertEquals(Light.Green, m.state)
        assertTrue(m.send("advance")); assertEquals(Light.Yellow, m.state)
        assertTrue(m.send("advance")); assertEquals(Light.Red, m.state)
    }

    @Test
    fun guard_rejection() {
        val ctx = Context()
        val m = StateMachine(ctx, "open") { s, e: String ->
            when (s) {
                "open" -> if (e == "close") "closed" else null
                "closed" -> if (e == "open") "open" else null
                else -> null
            }
        }
        assertFalse(m.send("open")) // invalid from open
        assertEquals("open", m.state)
        assertTrue(m.send("close"))
        assertEquals("closed", m.state)
        assertFalse(m.send("close"))
        assertEquals("closed", m.state)
    }

    @Test
    fun self_transition_suppressed_by_partial_eq() {
        val ctx = Context()
        var effectRuns = 0
        val m = StateMachine(ctx, 42) { s, _: Int -> s } // always self-transition
        m.onTransition { _, _ -> effectRuns++ }
        assertEquals(0, effectRuns)
        assertTrue(m.send(0)) // accepted…
        assertEquals(0, effectRuns) // …but equal state → no transition effect
        assertEquals(42, m.state)
    }

    @Test
    fun on_transition_fires_only_on_real_change() {
        val ctx = Context()
        val log = mutableListOf<Pair<String, String>>()
        // Transition always returns "B": A->B is a real change, then B->B is a
        // self-transition suppressed by the cell's == guard (no observation fires).
        val m = StateMachine(ctx, "A") { _, _: String -> "B" }
        m.onTransition { old, new -> log.add(old to new) }
        assertTrue(m.send("go"))
        assertEquals(listOf("A" to "B"), log)
        assertTrue(m.send("go")) // accepted, but equal state → no transition fires
        assertEquals(listOf("A" to "B"), log)
    }

    @Test
    fun state_is_signal_tracks_membership() {
        val ctx = Context()
        val m = StateMachine(ctx, Light.Red) { s, _: String ->
            when (s) {
                Light.Red -> Light.Green
                Light.Green -> Light.Yellow
                Light.Yellow -> Light.Red
            }
        }
        val isGreen = m.stateIs(Light.Green)
        assertFalse(ctx.get(isGreen))
        m.send("advance")
        assertTrue(ctx.get(isGreen))
        m.send("advance")
        assertFalse(ctx.get(isGreen))
    }

    @Test
    fun state_composes_reactively() {
        val ctx = Context()
        val m = StateMachine(ctx, 0) { s, step: Int -> s + step }
        val doubled = ctx.computed { get(m.stateCell()) * 2 }
        assertEquals(0, ctx.get(doubled))
        m.send(5)
        assertEquals(10, ctx.get(doubled))
        m.send(3)
        assertEquals(16, ctx.get(doubled))
    }
}
