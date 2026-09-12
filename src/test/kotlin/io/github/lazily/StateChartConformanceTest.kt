package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Cross-language conformance tests for the full Harel state-chart spec
 * (`lazily-spec/docs/state-charts.md`), replaying the canonical fixtures every
 * binding replays. Each test loads a chart, asserts `initial_active`
 * (and `initial_actions` when present), replays the `steps`, and asserts
 * `accepted`, `active`, `matches`, and `actions` after each step.
 */
class StateChartConformanceTest {
    private val json = Json

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

    private fun assertActive(
        ctx: Context,
        chart: StateChart,
        expected: JsonElement,
        msg: String,
    ) {
        val want = activeExpected(expected)
        val got = chart.activeLeaves(ctx)
        assertEquals(want, got, "$msg: active leaves $got ≠ expected $want")
    }

    private fun assertMatches(
        ctx: Context,
        chart: StateChart,
        step: JsonObject,
    ) {
        val raw = step["matches"] ?: return
        val obj =
            raw as? JsonObject
                ?: error("`matches` must be a JSON object, got $raw (#lzflagcoercion)")
        for ((id, expected) in obj) {
            val want = expected.jsonPrimitive.boolean
            assertEquals(want, chart.matches(ctx, id), "matches($id) mismatch")
        }
    }

    /**
     * Absence is an empty list; a PRESENT value that is not an array is a named
     * failure, not an empty list (`#lzflagcoercion`). `as? JsonArray ?: emptyList()`
     * made a wrong-typed `actions` key read exactly like a missing one.
     */
    private fun actionsOf(element: JsonElement?): List<String> =
        when (element) {
            null -> emptyList()
            is JsonArray -> element.map { it.jsonPrimitive.content }
            else -> error("`actions` must be a JSON array, got $element (#lzflagcoercion)")
        }

    private fun runFixture(name: String) {
        val fixture = loadFixture(name)
        val (ctx, chart) = buildChart(fixture)

        // initial_active (asserted once before any step).
        assertActive(ctx, chart, fixture.getValue("initial_active"), "initial_active")

        // initial_actions (optional). Keyed on PRESENCE, not on emptiness: an
        // explicit `"initial_actions": []` is the claim that entering the initial
        // configuration ran NO actions, and `isNotEmpty()` dropped exactly that
        // claim — the one a chart running entry actions it should not would break
        // (`#lzflagcoercion`). The per-step arm below already keys on presence.
        fixture["initial_actions"]?.let {
            assertEquals(actionsOf(it), chart.lastActions(), "initial_actions")
        }

        val steps = fixture.getValue("steps").jsonArray
        for ((i, stepElement) in steps.withIndex()) {
            val step = stepElement.jsonObject
            val event = step.getValue("event").jsonPrimitive.content
            // A guard is an INPUT, and `booleanOrNull ?: false` silently replayed a
            // DIFFERENT fixture than the one on disk: `0`, `"yes"`, an object all
            // became `false`, and the quoted `"true"` became `true` — a spelling
            // StateChart.kt itself refuses for `parallel` and `internal`, with the
            // `!isString` guard called load-bearing there for exactly this reason.
            // Same rule here (`#lzflagcoercion`): present means it has to BE a
            // JSON boolean.
            val guardBlock =
                step["guards"]?.let {
                    it as? JsonObject
                        ?: error("step $i `$event`: `guards` must be a JSON object, got $it")
                }
            // Absence spelled out, never `?: emptyMap()` (`#lzsiblingrunnermasking`).
            // A step with no `guards` block passes no guards; the `?:` form said the
            // same thing in the spelling this binding's flag/presence guard refuses,
            // because it is indistinguishable from a defaulted EXPECTATION.
            val guards: Map<String, Boolean> =
                if (guardBlock == null) {
                    emptyMap()
                } else {
                    guardBlock.entries.associate { (k, v) ->
                        k to (
                            (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                                ?: error("step $i `$event`: guard '$k' must be a JSON boolean, got $v")
                            )
                    }
                }

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

    @Test
    fun malformed_statechart_corpus_is_rejected() {
        val fixture = loadFixture("malformed_rejected.json")
        val cases = fixture.getValue("cases").jsonArray
        check(cases.isNotEmpty()) { "malformed corpus must contain cases" }
        for (caseElement in cases) {
            val case = caseElement.jsonObject
            assertFails("malformed statechart case ${case.getValue("name")} was accepted") {
                ChartDef.fromJson(case.getValue("chart"))
            }
        }
    }

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
