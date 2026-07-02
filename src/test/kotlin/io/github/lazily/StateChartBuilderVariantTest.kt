package io.github.lazily

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the v0.19-parity additions: the typed Kotlin [ChartBuilder] (a chart
 * defined in Kotlin must behave identically to the same chart parsed from JSON)
 * and [ThreadSafeStateChart] (chart over [ThreadSafeContext]).
 */
class StateChartBuilderVariantTest {
    private val jsonSrc = """
        {
          "initial": "root",
          "states": {
            "root": { "parallel": true },
            "flow": { "parent": "root", "initial": "idle" },
            "idle": { "parent": "flow", "on": { "go": { "target": "done", "guard": "ready" } } },
            "done": { "parent": "flow", "kind": "final" },
            "net": { "parent": "root", "initial": "up" },
            "up": { "parent": "net", "on": { "drop": { "target": "down" } } },
            "down": { "parent": "net", "on": { "restore": { "target": "up" } } }
          }
        }
    """.trimIndent()

    private fun jsonChart(): ChartDef = ChartDef.fromJson(Json.parseToJsonElement(jsonSrc))

    private fun builtChart(): ChartDef =
        ChartBuilder()
            .state(StateBuilder.parallel("root"))
            .state(StateBuilder.compound("flow", "idle").parent("root"))
            .state(StateBuilder.atomic("idle").parent("flow").onGuarded("go", "done", "ready"))
            .state(StateBuilder.final("done").parent("flow"))
            .state(StateBuilder.compound("net", "up").parent("root"))
            .state(StateBuilder.atomic("up").parent("net").on("drop", "down"))
            .state(StateBuilder.atomic("down").parent("net").on("restore", "up"))
            .build()

    @Test
    fun builderMatchesJsonBehaviour() {
        val ctxJ = Context()
        val ctxB = Context()
        val cj = StateChart(ctxJ, jsonChart())
        val cb = StateChart(ctxB, builtChart())
        assertEquals(cj.configuration(ctxJ), cb.configuration(ctxB))

        // Guard false: rejected on both.
        val notReady = mapOf("ready" to false)
        assertEquals(cj.send(ctxJ, "go", notReady), cb.send(ctxB, "go", notReady))
        assertEquals(cj.configuration(ctxJ), cb.configuration(ctxB))

        // Orthogonal region transition on both.
        assertTrue(cj.send(ctxJ, "drop"))
        assertTrue(cb.send(ctxB, "drop"))

        // Guard true: accepted on both, identical resulting configuration.
        val ready = mapOf("ready" to true)
        assertTrue(cj.send(ctxJ, "go", ready))
        assertTrue(cb.send(ctxB, "go", ready))
        assertEquals(cj.configuration(ctxJ), cb.configuration(ctxB))
        assertTrue(cj.matches(ctxJ, "done") && cj.matches(ctxJ, "down"))
    }

    @Test
    fun threadSafeChartTransitions() {
        val ctx = ThreadSafeContext()
        val chart = ThreadSafeStateChart(builtChart(), ctx)
        assertTrue(chart.matches("idle") && chart.matches("up"))

        // Guard absent -> fail-closed rejection.
        assertFalse(chart.send("go"))
        assertTrue(chart.matches("idle"))

        // Orthogonal transition.
        assertTrue(chart.send("drop"))
        assertTrue(chart.matches("down"))

        // Guarded transition once the guard is supplied.
        assertTrue(chart.send("go", mapOf("ready" to true)))
        assertTrue(chart.matches("done"))
    }
}
