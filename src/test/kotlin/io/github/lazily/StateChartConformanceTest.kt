package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language conformance tests for the full Harel state-chart spec
 * (`lazily-spec/docs/state-charts.md`), replaying the canonical fixtures every
 * binding replays. Each test loads a chart, asserts `initial_active`
 * (and `initial_actions` when present), replays the `steps`, and asserts
 * `accepted`, `active`, `matches`, and `actions` after each step.
 */
class StateChartConformanceTest {
    private val json = Json

    private val specDir: Path = Path.of("../lazily-spec/conformance/statechart")

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("statechart/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun buildChart(fixture: JsonObject): Pair<Context, StateChart> {
        val ctx = Context()
        val def = ChartDef.fromJson(fixture.getValue("chart"))
        return ctx to StateChart(ctx, def)
    }

    private fun activeExpected(expected: JsonElement): List<String> =
        when (expected) {
            is JsonPrimitive -> listOf(expected.content)
            is JsonArray -> expected.map { it.jsonPrimitive.content }
            else -> error("active must be a string or array")
        }.sorted()

    private fun assertActive(ctx: Context, chart: StateChart, expected: JsonElement, msg: String) {
        val want = activeExpected(expected)
        val got = chart.activeLeaves(ctx)
        assertEquals(want, got, "$msg: active leaves $got ≠ expected $want")
    }

    private fun assertMatches(ctx: Context, chart: StateChart, step: JsonObject) {
        val obj = step["matches"] as? JsonObject ?: return
        for ((id, expected) in obj) {
            val want = expected.jsonPrimitive.boolean
            assertEquals(want, chart.matches(ctx, id), "matches($id) mismatch")
        }
    }

    private fun actionsOf(element: JsonElement?): List<String> =
        (element as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun runFixture(name: String) {
        val fixture = loadFixture(name)
        val (ctx, chart) = buildChart(fixture)

        // initial_active (asserted once before any step).
        assertActive(ctx, chart, fixture.getValue("initial_active"), "initial_active")

        // initial_actions (optional).
        val initialActions = actionsOf(fixture["initial_actions"])
        if (initialActions.isNotEmpty()) {
            assertEquals(initialActions, chart.lastActions(), "initial_actions")
        }

        val steps = fixture.getValue("steps").jsonArray
        for ((i, stepElement) in steps.withIndex()) {
            val step = stepElement.jsonObject
            val event = step.getValue("event").jsonPrimitive.content
            val guards: Map<String, Boolean> =
                (step["guards"] as? JsonObject)?.entries?.associate { (k, v) ->
                    k to (v.jsonPrimitive.booleanOrNull ?: false)
                } ?: emptyMap()

            val accepted = chart.send(ctx, event, guards)
            val wantAccepted = step.getValue("accepted").jsonPrimitive.boolean
            assertEquals(wantAccepted, accepted, "step $i `$event` accepted")

            assertActive(ctx, chart, step.getValue("active"), "step $i `$event` active")
            assertMatches(ctx, chart, step)

            val wantActions = actionsOf(step["actions"])
            if (step["actions"] != null) {
                assertEquals(wantActions, chart.lastActions(), "step $i `$event` actions")
            }
        }
    }

    @Test fun conformance_flat_cycle() = runFixture("flat_cycle.json")
    @Test fun conformance_hierarchical_player() = runFixture("hierarchical_player.json")
    @Test fun conformance_guarded_door() = runFixture("guarded_door.json")
    @Test fun conformance_parallel_regions() = runFixture("parallel_regions.json")
    @Test fun conformance_history_shallow() = runFixture("history_shallow.json")
    @Test fun conformance_history_deep() = runFixture("history_deep.json")
    @Test fun conformance_entry_exit_actions() = runFixture("entry_exit_actions.json")

    @Test fun `all statechart fixtures replay identically`() {
        listOf(
            "flat_cycle.json",
            "hierarchical_player.json",
            "guarded_door.json",
            "parallel_regions.json",
            "history_shallow.json",
            "history_deep.json",
            "entry_exit_actions.json",
        ).forEach { runFixture(it) }
    }
}
