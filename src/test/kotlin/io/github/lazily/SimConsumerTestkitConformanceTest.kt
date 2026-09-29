package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimConsumerTestkitConformanceTest {
    private class TestWorld : SimConsumerWorldEvidence {
        override val identity: Any = this
        override var steps: Long = 0
        private val entries = mutableListOf<SimConsumerTraceEntry>()
        override val trace: List<SimConsumerTraceEntry> get() = entries.toList()

        fun execute(action: SimConsumerAction, reducer: () -> Unit) {
            reducer()
            steps++
            entries += SimConsumerTraceEntry(action.id, "action_accepted")
        }
    }

    private class AdapterState {
        var value = 0
        var probes = 0
        var resets = 0
        var applications = 0
        var world: TestWorld? = null
        val history = mutableListOf<SimConsumerAction>()
    }

    @Test
    fun `canonical consumer simulation testkit corpus`() {
        val path = "simulation/consumer_testkit.json"
        val fixture = Json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject
        assertEquals("ConsumerSimulationTestkit", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("CounterReducerV1", fixture.getValue("model").jsonPrimitive.content)
        val actions = fixture.getValue("actions").jsonArray.map(::action)
        val generator = fixture.getValue("generator").jsonObject
        val scenario =
            SimGeneratedScenario(
                generatorName = generator.getValue("path").jsonPrimitive.content,
                generatorVersion = generator.getValue("version").jsonPrimitive.content,
                seedHex = fixture.getValue("seed").jsonPrimitive.content,
                actions = actions.map { SimGeneratedAction("increment", it) },
            )

        var replayed = 0
        for ((index, booked) in ConformanceScenarios.indexed(path, fixture)) {
            val scenarioFixture = booked
            val id = ConformanceScenarios.idOf(scenarioFixture, index).value
            val states = linkedMapOf<String, AdapterState>()
            val adapters =
                scenarioFixture.getValue("adapters").jsonArray.map { element ->
                    adapter(element.jsonObject, fixture, states)
                }
            val spec =
                SimConsumerTestkitSpec(
                    simulationAdapterId = scenarioFixture.string("simulation_adapter_id"),
                    requiredRealAdapters =
                    scenarioFixture.getValue("required_real_adapters").jsonArray.map {
                        adapterKind(it.jsonPrimitive.content)
                    },
                    requiredExternalProcesses =
                    scenarioFixture.getValue("required_external_processes").jsonArray.map {
                        val selection = it.jsonObject
                        SimConsumerExternalProcessSelection(
                            selection.string("adapter_id"),
                            externalPort(selection.string("port")),
                        )
                    },
                    adapters = adapters,
                )
            val expected = scenarioFixture.getValue("expected").jsonObject
            val where = "$path scenario $id"

            when (expected.string("outcome")) {
                "success" -> assertSuccess(where, expected, SimConsumerTestkit(spec).run(scenario), states)
                "observation_divergence" -> {
                    val error = assertFailsWith<SimConsumerDivergenceException> { SimConsumerTestkit(spec).run(scenario) }
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { "observation_divergence" }
                        a.assertLong("step") { error.step }
                        a.assertString("action_id") { error.actionId }
                        a.assertString("adapter_id") { error.adapterId }
                        a.assertString("observation_id") { error.observationId }
                    }
                }
                "materialized_history_mismatch" -> {
                    val error = assertFailsWith<SimConsumerHistoryMismatchException> { SimConsumerTestkit(spec).run(scenario) }
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { "materialized_history_mismatch" }
                        a.assertLong("step") { error.step }
                        a.assertString("action_id") { error.actionId }
                        a.assertString("adapter_id") { error.adapterId }
                        a.assertInt("expected_prefix_length") { error.expectedPrefixLength }
                        a.assertInt("actual_prefix_length") { error.actualPrefixLength }
                    }
                }
                "simulation_world_bypass" -> {
                    val error = assertFailsWith<SimConsumerWorldBypassException> { SimConsumerTestkit(spec).run(scenario) }
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { "simulation_world_bypass" }
                        a.assertLong("step") { error.step }
                        a.assertString("action_id") { error.actionId }
                        a.assertString("adapter_id") { error.adapterId }
                    }
                }
                else -> error("$where: unknown outcome")
            }
            replayed++
        }
        assertEquals(fixture.getValue("scenarios").jsonArray.size, replayed)
    }

    @Test
    fun `construction rejects invalid topology before callbacks run`() {
        var callbacks = 0
        val world = TestWorld()
        val ports =
            listOf(
                SimConsumerPort("state.store", "storage", SimConsumerPortDeterminism.DETERMINISTIC),
            )
        val memory =
            SimConsumerAdapter(
                id = "memory",
                kind = SimConsumerAdapterKind.IN_MEMORY,
                productionReducerId = "counter.reducer.v1",
                protocolId = "counter.protocol.v1",
                reducerId = "counter.reducer.v1",
                ports = ports,
                simulationWorld = { world },
                reset = { callbacks++ },
                apply = { callbacks++ },
                observe = {
                    callbacks++
                    mapOf("consumer.value" to 0)
                },
            )
        val real =
            SimConsumerAdapter(
                id = "postgres.integration",
                kind = SimConsumerAdapterKind.POSTGRES,
                productionReducerId = "counter.reducer.v1",
                protocolId = "counter.protocol.v1",
                reducerId = "counter.reducer.v1",
                serviceId = "postgres.integration.service",
                ports = ports.map { it.copy(stubbed = true) },
                probe = { callbacks++ },
                reset = { callbacks++ },
                apply = { callbacks++ },
                observe = {
                    callbacks++
                    mapOf("consumer.value" to 0)
                },
                materializedHistory = {
                    callbacks++
                    emptyList()
                },
            )
        assertFailsWith<SimConsumerConformanceException> {
            SimConsumerTestkit(
                SimConsumerTestkitSpec(
                    simulationAdapterId = "memory",
                    requiredRealAdapters = listOf(SimConsumerAdapterKind.POSTGRES),
                    adapters = listOf(memory, real),
                ),
            )
        }
        assertEquals(0, callbacks, "construction must validate before invoking callbacks")
    }

    private fun assertSuccess(
        where: String,
        expected: JsonObject,
        result: SimConsumerRunResult,
        states: Map<String, AdapterState>,
    ) {
        assertTrue(result.scenarioDigest.isNotEmpty())
        expected.consuming(where) { a ->
            a.assertString("outcome") { "success" }
            a.assertKeyWith("adapter_ids") { want ->
                assertEquals(want.jsonArray.strings(), result.adapterIds, "$where adapter ids")
            }
            a.assertKeyWith("checkpoint_steps") { want ->
                assertEquals(want.jsonArray.map { it.jsonPrimitive.long }, result.checkpoints.map { it.step })
            }
            if (a.has("checkpoint_action_ids")) {
                a.assertKeyWith("checkpoint_action_ids") { want ->
                    assertEquals(want.jsonArray.strings(), result.checkpoints.map { it.actionId })
                }
            }
            a.assertKeyWith("checkpoint_values") { want ->
                val values = want.jsonArray.map { it.jsonPrimitive.int }
                assertEquals(values.size, result.checkpoints.size)
                for ((checkpoint, value) in result.checkpoints.zip(values)) {
                    val digest = canonicalDigest(mapOf("consumer.value" to value))
                    assertTrue(checkpoint.observationDigests.values.all { it == digest })
                }
            }
            if (a.has("observation_relation")) {
                a.assertString("observation_relation") { "all_equal_at_every_checkpoint" }
                a.assertString("materialized_history_relation") { "exact_prefix_at_every_checkpoint" }
                a.assertString("probe_relation") {
                    assertTrue(states.filterKeys { it != "memory" }.values.all { it.probes == 1 })
                    "every_real_adapter_once"
                }
            }
            if (a.has("external_adapter_id")) {
                var selectedEvidence: SimConsumerAdapterEvidence? = null
                a.assertKeyWith("external_adapter_id") { expectedId ->
                    val evidence = result.adapterEvidence.single { it.adapterId == expectedId.jsonPrimitive.content }
                    selectedEvidence = evidence
                    assertEquals(expectedId.jsonPrimitive.content, evidence.adapterId)
                }
                val evidence = requireNotNull(selectedEvidence)
                a.assertKeyWith("external_port") { assertEquals(it.jsonPrimitive.content, evidence.externalPort?.wireName) }
                a.assertKeyWith("external_protocol_id") { assertEquals(it.jsonPrimitive.content, evidence.protocolId) }
                a.assertKeyWith("external_reducer_id") { assertEquals(it.jsonPrimitive.content, evidence.reducerId) }
                a.assertKeyWith("external_production_reducer_id") {
                    assertEquals(it.jsonPrimitive.content, evidence.productionReducerId)
                }
            }
        }
    }

    private fun adapter(
        value: JsonObject,
        fixture: JsonObject,
        states: MutableMap<String, AdapterState>,
    ): SimConsumerAdapter {
        val id = value.string("id")
        val kind = adapterKind(value.string("kind"))
        val state = AdapterState()
        states[id] = state
        val executionMode = value.string("execution_mode")
        val historyMode = value.string("history_mode")
        val bias = value.getValue("delta_bias").jsonPrimitive.int
        val ports =
            fixture.getValue("ports").jsonArray.map { element ->
                val p = element.jsonObject
                SimConsumerPort(
                    id = p.string("id"),
                    kind = p.string("kind"),
                    determinism =
                    if (p.string("determinism") == "deterministic") {
                        SimConsumerPortDeterminism.DETERMINISTIC
                    } else {
                        SimConsumerPortDeterminism.NONDETERMINISTIC
                    },
                    stubbed = kind == SimConsumerAdapterKind.IN_MEMORY && value.string("clock_stub") == "stubbed" && p.string("kind") == "clock",
                )
            }
        fun reduce(action: SimConsumerAction) {
            state.value += action.payload as Int + bias
            state.applications++
        }
        return SimConsumerAdapter(
            id = id,
            kind = kind,
            productionReducerId = value.string("production_reducer_id"),
            protocolId = value.string("protocol_id"),
            reducerId = value.string("reducer_id"),
            serviceId = value.string("service_id"),
            externalPort = value["external_port"]?.jsonPrimitive?.contentOrNull?.let(::externalPort),
            ports = ports,
            probe = if (kind.isReal) ({ state.probes++ }) else null,
            simulationWorld = if (kind == SimConsumerAdapterKind.IN_MEMORY) ({ state.world }) else null,
            reset = {
                state.value = 0
                state.history.clear()
                state.resets++
                if (kind == SimConsumerAdapterKind.IN_MEMORY) state.world = TestWorld()
            },
            apply = { action ->
                if (kind == SimConsumerAdapterKind.IN_MEMORY && executionMode == "sim_world") {
                    requireNotNull(state.world).execute(action) { reduce(action) }
                } else {
                    reduce(action)
                }
                if (kind.isReal && historyMode == "exact") state.history += action.copy()
            },
            observe = { mapOf("consumer.value" to state.value) },
            materializedHistory =
            if (kind.isReal) {
                ({ if (historyMode == "empty") emptyList() else state.history.map { it.copy() } })
            } else {
                null
            },
        )
    }

    private fun action(value: JsonElement): SimConsumerAction {
        val obj = value.jsonObject
        return SimConsumerAction(
            id = obj.string("id"),
            actorId = obj.string("actor_id"),
            kind = obj.string("kind"),
            version = obj.string("version"),
            payload = obj.getValue("payload").toHostValue(),
        )
    }

    private fun adapterKind(value: String): SimConsumerAdapterKind =
        SimConsumerAdapterKind.entries.single { it.wireName == value }

    private fun externalPort(value: String): SimConsumerExternalPortKind =
        SimConsumerExternalPortKind.entries.single { it.wireName == value }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private fun JsonElement.toHostValue(): Any? =
        when (this) {
            JsonNull -> null
            is JsonArray -> map { it.toHostValue() }
            is JsonObject -> entries.associate { it.key to it.value.toHostValue() }
            is JsonPrimitive ->
                when {
                    isString -> content
                    booleanOrNull != null -> boolean
                    else -> int
                }
        }
}
