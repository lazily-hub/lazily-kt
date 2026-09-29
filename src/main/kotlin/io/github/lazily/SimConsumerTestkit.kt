package io.github.lazily

import java.util.Arrays

/** Execution boundary behind a consumer conformance adapter. */
enum class SimConsumerAdapterKind(val wireName: String) {
    IN_MEMORY("in_memory"),
    POSTGRES("postgres"),
    NATS("nats"),
    EXTERNAL_PROCESS("external_process"),
    ;

    val isReal: Boolean get() = this != IN_MEMORY
    internal val isLegacyReal: Boolean get() = this == POSTGRES || this == NATS
}

/** Public integration boundary used to reach an external consumer process. */
enum class SimConsumerExternalPortKind(val wireName: String) {
    CLI("cli"),
    FILESYSTEM("filesystem"),
    LOCAL_SOCKET("local_socket"),
    EDITOR_REPLICA("editor_replica"),
}

enum class SimConsumerPortDeterminism(val wireName: String) {
    DETERMINISTIC("deterministic"),
    NONDETERMINISTIC("nondeterministic"),
}

data class SimConsumerPort(
    val id: String,
    val kind: String,
    val determinism: SimConsumerPortDeterminism,
    val stubbed: Boolean = false,
)

data class SimConsumerExternalProcessSelection(
    val adapterId: String,
    val port: SimConsumerExternalPortKind,
)

/** One trace entry exposed by a deterministic world to prove an action ran there. */
data class SimConsumerTraceEntry(
    val actionId: String,
    val kind: String,
)

/**
 * Narrow evidence seam for a deterministic simulation world.
 *
 * This deliberately does not prescribe a scheduler. The testkit needs only stable
 * identity, a monotonic step count, and append-only action trace evidence.
 */
interface SimConsumerWorldEvidence {
    val identity: Any
    val steps: Long
    val trace: List<SimConsumerTraceEntry>
}

data class SimConsumerAction(
    val id: String,
    val actorId: String,
    val kind: String,
    val version: String,
    val payload: Any?,
    val causeId: String = "",
) : ReplayCanonical {
    override val canonicalTypeName: String get() = "SimConsumerAction"

    override fun canonicalForm(): Any? =
        mapOf(
            "id" to id,
            "actor_id" to actorId,
            "kind" to kind,
            "version" to version,
            "payload" to payload,
            "cause_id" to causeId,
        )
}

data class SimGeneratedAction(
    val command: String,
    val action: SimConsumerAction,
) : ReplayCanonical {
    override val canonicalTypeName: String get() = "SimGeneratedAction"
    override fun canonicalForm(): Any? = mapOf("command" to command, "action" to action)
}

data class SimGeneratedScenario(
    val generatorName: String,
    val generatorVersion: String,
    val seedHex: String,
    val actions: List<SimGeneratedAction>,
) : ReplayCanonical {
    override val canonicalTypeName: String get() = "SimGeneratedScenario"

    override fun canonicalForm(): Any? =
        mapOf(
            "generator_name" to generatorName,
            "generator_version" to generatorVersion,
            "seed_hex" to seedHex,
            "actions" to actions,
        )
}

data class SimConsumerAdapter(
    val id: String,
    val kind: SimConsumerAdapterKind,
    val productionReducerId: String = "",
    val protocolId: String,
    val reducerId: String,
    val serviceId: String = "",
    val externalPort: SimConsumerExternalPortKind? = null,
    val ports: List<SimConsumerPort>,
    val probe: (() -> Unit)? = null,
    val simulationWorld: (() -> SimConsumerWorldEvidence?)? = null,
    val reset: (() -> Unit)?,
    val apply: ((SimConsumerAction) -> Unit)?,
    val observe: (() -> Map<String, Any?>)?,
    val materializedHistory: (() -> List<SimConsumerAction>)? = null,
)

data class SimConsumerTestkitSpec(
    val simulationAdapterId: String,
    val requiredRealAdapters: List<SimConsumerAdapterKind> = emptyList(),
    val requiredExternalProcesses: List<SimConsumerExternalProcessSelection> = emptyList(),
    val adapters: List<SimConsumerAdapter>,
)

data class SimConsumerAdapterEvidence(
    val adapterId: String,
    val kind: SimConsumerAdapterKind,
    val serviceId: String,
    val externalPort: SimConsumerExternalPortKind?,
    val protocolId: String,
    val reducerId: String,
    val productionReducerId: String,
)

data class SimConsumerCheckpoint(
    val step: Long,
    val actionId: String,
    val observationDigests: Map<String, String>,
)

data class SimConsumerRunResult(
    val scenarioDigest: String,
    val adapterIds: List<String>,
    val adapterEvidence: List<SimConsumerAdapterEvidence>,
    val checkpoints: List<SimConsumerCheckpoint>,
)

open class SimConsumerConformanceException(message: String, cause: Throwable? = null) : RuntimeException("consumer simulation conformance failed: $message", cause)

class SimConsumerDivergenceException(
    val step: Long,
    val actionId: String,
    val baselineAdapterId: String,
    val adapterId: String,
    val observationId: String,
    val divergenceKind: String,
) : SimConsumerConformanceException(
    "step $step action '$actionId' adapter '$adapterId' differs from " +
        "'$baselineAdapterId': $divergenceKind" +
        if (observationId.isEmpty()) "" else " observation '$observationId'",
)

class SimConsumerHistoryMismatchException(
    val step: Long,
    val actionId: String,
    val adapterId: String,
    val expectedPrefixLength: Int,
    val actualPrefixLength: Int,
) : SimConsumerConformanceException(
    "step $step action '$actionId' adapter '$adapterId' materialized history contains " +
        "$actualPrefixLength actions, want exact prefix of $expectedPrefixLength",
)

class SimConsumerWorldBypassException(
    val step: Long,
    val actionId: String,
    val adapterId: String,
) : SimConsumerConformanceException(
    "step $step action '$actionId' adapter '$adapterId' did not execute through its simulation world",
)

/** Cross-adapter consumer simulation conformance runner. */
class SimConsumerTestkit(spec: SimConsumerTestkitSpec) {
    private val spec: SimConsumerTestkitSpec
    private val baselineIndex: Int

    init {
        val normalized = validateAndNormalize(spec)
        this.spec = normalized.first
        baselineIndex = normalized.second
    }

    fun run(scenario: SimGeneratedScenario): SimConsumerRunResult {
        validateScenario(scenario)
        val scenarioDigest = canonicalDigest(scenario)

        for (adapter in spec.adapters) {
            try {
                if (adapter.kind.isReal) requireNotNull(adapter.probe).invoke()
                requireNotNull(adapter.reset).invoke()
            } catch (error: SimConsumerConformanceException) {
                throw error
            } catch (error: Throwable) {
                throw SimConsumerConformanceException("prepare adapter '${adapter.id}'", error)
            }
            if (adapter.kind == SimConsumerAdapterKind.IN_MEMORY && adapter.simulationWorld?.invoke() == null) {
                throw SimConsumerConformanceException("reset adapter '${adapter.id}' did not create its simulation world")
            }
            if (adapter.kind.isReal) validateHistory(adapter, emptyList(), 0, "")
        }

        val checkpoints = mutableListOf<SimConsumerCheckpoint>()
        for ((actionIndex, generated) in scenario.actions.withIndex()) {
            val step = actionIndex.toLong() + 1
            val action = generated.action
            val observed = MutableList<Map<String, Any?>>(spec.adapters.size) { emptyMap() }
            val digests = linkedMapOf<String, String>()

            for ((adapterIndex, adapter) in spec.adapters.withIndex()) {
                val beforeWorld =
                    if (adapter.kind == SimConsumerAdapterKind.IN_MEMORY) {
                        requireNotNull(adapter.simulationWorld?.invoke())
                    } else {
                        null
                    }
                val beforeSteps = beforeWorld?.steps ?: 0
                val beforeTrace = beforeWorld?.trace?.size ?: 0

                try {
                    requireNotNull(adapter.apply).invoke(cloneAction(action))
                } catch (error: Throwable) {
                    throw SimConsumerConformanceException(
                        "step $step action '${action.id}' apply adapter '${adapter.id}'",
                        error,
                    )
                }

                if (beforeWorld != null) {
                    val afterWorld = adapter.simulationWorld?.invoke()
                    val executed =
                        afterWorld != null &&
                            afterWorld.identity === beforeWorld.identity &&
                            afterWorld.steps > beforeSteps &&
                            afterWorld.trace.drop(beforeTrace).any {
                                it.actionId == action.id && it.kind.startsWith("action_")
                            }
                    if (!executed) throw SimConsumerWorldBypassException(step, action.id, adapter.id)
                }
                if (adapter.kind.isReal) {
                    validateHistory(adapter, scenario.actions.take(actionIndex + 1), step, action.id)
                }

                val values =
                    try {
                        requireNotNull(adapter.observe).invoke()
                    } catch (error: Throwable) {
                        throw SimConsumerConformanceException(
                            "step $step action '${action.id}' observe adapter '${adapter.id}'",
                            error,
                        )
                    }
                if (values.isEmpty()) {
                    throw SimConsumerConformanceException(
                        "step $step action '${action.id}' adapter '${adapter.id}' returned no observations",
                    )
                }
                val frozen = freezeObservation(values)
                observed[adapterIndex] = frozen
                digests[adapter.id] = canonicalDigest(frozen)
            }

            val baseline = observed[baselineIndex]
            val baselineId = spec.adapters[baselineIndex].id
            for ((adapterIndex, adapter) in spec.adapters.withIndex()) {
                if (adapterIndex != baselineIndex) {
                    compareObservations(step, action.id, baselineId, adapter.id, baseline, observed[adapterIndex])
                }
            }
            checkpoints += SimConsumerCheckpoint(step, action.id, digests.toMap())
        }

        return SimConsumerRunResult(
            scenarioDigest = scenarioDigest,
            adapterIds = spec.adapters.map { it.id },
            adapterEvidence =
            spec.adapters.map {
                SimConsumerAdapterEvidence(
                    adapterId = it.id,
                    kind = it.kind,
                    serviceId = it.serviceId,
                    externalPort = it.externalPort,
                    protocolId = it.protocolId,
                    reducerId = it.reducerId,
                    productionReducerId = it.productionReducerId,
                )
            },
            checkpoints = checkpoints.toList(),
        )
    }

    private fun validateHistory(
        adapter: SimConsumerAdapter,
        expected: List<SimGeneratedAction>,
        step: Long,
        actionId: String,
    ) {
        val history =
            try {
                requireNotNull(adapter.materializedHistory).invoke()
            } catch (error: Throwable) {
                throw SimConsumerConformanceException("read materialized history for '${adapter.id}'", error)
            }
        if (history.size != expected.size) {
            throw SimConsumerHistoryMismatchException(step, actionId, adapter.id, expected.size, history.size)
        }
        for (index in expected.indices) {
            if (!Arrays.equals(canonicalBytes(expected[index].action), canonicalBytes(history[index]))) {
                throw SimConsumerHistoryMismatchException(step, actionId, adapter.id, expected.size, history.size)
            }
        }
    }

    companion object {
        private val SEED = Regex("[0-9a-f]{64}")

        private fun validateAndNormalize(spec: SimConsumerTestkitSpec): Pair<SimConsumerTestkitSpec, Int> {
            if (!validId(spec.simulationAdapterId)) fail("simulation adapter needs a stable id")
            if (spec.requiredRealAdapters.isEmpty() && spec.requiredExternalProcesses.isEmpty()) {
                fail("select at least one real Postgres/NATS adapter or external process")
            }
            if (spec.adapters.size < 2) fail("testkit needs an in-memory adapter and at least one real adapter")

            val required = linkedSetOf<SimConsumerAdapterKind>()
            for (kind in spec.requiredRealAdapters) {
                if (!kind.isLegacyReal) fail("required adapter kind '${kind.wireName}' is not Postgres or NATS")
                if (!required.add(kind)) fail("duplicate required real adapter kind '${kind.wireName}'")
            }
            val requiredExternal = linkedMapOf<String, SimConsumerExternalPortKind>()
            for (selection in spec.requiredExternalProcesses) {
                if (!validId(selection.adapterId)) fail("external-process selection needs a stable adapter id")
                if (requiredExternal.put(selection.adapterId, selection.port) != null) {
                    fail("duplicate required external-process adapter '${selection.adapterId}'")
                }
            }

            val adapters = spec.adapters.map { it.copy(ports = it.ports.toList()) }.sortedBy { it.id }
            val seenIds = mutableSetOf<String>()
            val presentKinds = mutableSetOf<SimConsumerAdapterKind>()
            var productionReducerId = ""
            var protocolId = ""
            var portContract: List<String>? = null
            for (adapter in adapters) {
                validateAdapter(adapter)
                if (!seenIds.add(adapter.id)) fail("duplicate adapter id '${adapter.id}'")
                presentKinds += adapter.kind
                val selectedExternal = requiredExternal[adapter.id]
                if (selectedExternal != null && adapter.kind != SimConsumerAdapterKind.EXTERNAL_PROCESS) {
                    fail("external-process selection '${adapter.id}' refers to adapter kind '${adapter.kind.wireName}'")
                }
                when {
                    adapter.kind.isLegacyReal && adapter.kind !in required ->
                        fail("real adapter '${adapter.id}' kind '${adapter.kind.wireName}' was not explicitly selected")
                    adapter.kind == SimConsumerAdapterKind.EXTERNAL_PROCESS && selectedExternal == null ->
                        fail("external-process adapter '${adapter.id}' was not explicitly selected")
                    adapter.kind == SimConsumerAdapterKind.EXTERNAL_PROCESS && adapter.externalPort != selectedExternal ->
                        fail(
                            "external-process adapter '${adapter.id}' uses port '${adapter.externalPort?.wireName}', " +
                                "selected '${selectedExternal?.wireName}'",
                        )
                }
                if (adapter.kind != SimConsumerAdapterKind.EXTERNAL_PROCESS) {
                    if (productionReducerId.isEmpty()) productionReducerId = adapter.productionReducerId
                    if (adapter.productionReducerId != productionReducerId) {
                        fail("adapter '${adapter.id}' does not use the shared production reducer '$productionReducerId'")
                    }
                }
                if (protocolId.isEmpty()) protocolId = adapter.protocolId
                if (adapter.protocolId != protocolId) fail("adapter '${adapter.id}' does not use shared protocol '$protocolId'")
                val contract = adapter.ports.map { "${it.id}\u0000${it.kind}\u0000${it.determinism.wireName}" }.sorted()
                if (portContract == null) {
                    portContract = contract
                } else if (portContract != contract) {
                    fail("adapter '${adapter.id}' does not expose the shared narrow-port contract")
                }
            }
            if (portContract.isNullOrEmpty()) fail("consumer adapters must declare at least one narrow port")
            for (kind in required) if (kind !in presentKinds) fail("required real adapter kind '${kind.wireName}' is missing")
            for (adapterId in requiredExternal.keys) {
                if (adapterId !in seenIds) {
                    fail("required external-process adapter '$adapterId' is missing")
                }
            }
            val baseline = adapters.indexOfFirst { it.id == spec.simulationAdapterId }
            if (baseline < 0) fail("simulation adapter '${spec.simulationAdapterId}' is missing")
            if (adapters[baseline].kind != SimConsumerAdapterKind.IN_MEMORY) {
                fail("simulation adapter '${spec.simulationAdapterId}' must have kind 'in_memory'")
            }
            return spec.copy(adapters = adapters) to baseline
        }

        private fun validateAdapter(adapter: SimConsumerAdapter) {
            if (!validId(adapter.id) || !validId(adapter.protocolId) || !validId(adapter.reducerId)) {
                fail("adapter needs stable adapter, protocol, and reducer ids")
            }
            if (adapter.reset == null || adapter.apply == null || adapter.observe == null) {
                fail("adapter '${adapter.id}' needs Reset, Apply, and Observe callbacks")
            }
            when (adapter.kind) {
                SimConsumerAdapterKind.IN_MEMORY -> {
                    if (!validId(adapter.productionReducerId)) fail("in-memory adapter needs a production reducer id")
                    if (adapter.serviceId.isNotEmpty() || adapter.probe != null || adapter.externalPort != null || adapter.materializedHistory != null) {
                        fail("in-memory adapter '${adapter.id}' cannot claim a real service")
                    }
                    if (adapter.simulationWorld == null) fail("in-memory adapter '${adapter.id}' must expose its simulation world")
                }
                SimConsumerAdapterKind.POSTGRES, SimConsumerAdapterKind.NATS -> {
                    if (!validId(adapter.productionReducerId)) fail("real adapter '${adapter.id}' needs a production reducer id")
                    if (adapter.reducerId != adapter.productionReducerId) fail("real adapter reducer evidence must match production reducer")
                    if (adapter.externalPort != null) fail("real adapter '${adapter.id}' cannot claim an external-process port")
                    validateRealAdapter(adapter)
                }
                SimConsumerAdapterKind.EXTERNAL_PROCESS -> {
                    validateRealAdapter(adapter)
                    if (adapter.externalPort == null) fail("external-process adapter '${adapter.id}' needs a supported port")
                    if (adapter.productionReducerId.isNotEmpty()) {
                        fail("external-process adapter '${adapter.id}' must not claim the in-memory production reducer")
                    }
                }
            }
            val ports = mutableSetOf<String>()
            for (port in adapter.ports) {
                if (!validId(port.id) || !validId(port.kind)) fail("adapter '${adapter.id}' has a port without stable id and kind")
                if (!ports.add(port.id)) fail("adapter '${adapter.id}' has duplicate port '${port.id}'")
                if (port.stubbed && port.determinism != SimConsumerPortDeterminism.NONDETERMINISTIC) {
                    fail("adapter '${adapter.id}' stubs deterministic port '${port.id}'")
                }
                if (adapter.kind.isReal && port.stubbed) fail("real adapter '${adapter.id}' cannot stub port '${port.id}'")
            }
        }

        private fun validateRealAdapter(adapter: SimConsumerAdapter) {
            if (!validId(adapter.serviceId) || adapter.probe == null || adapter.materializedHistory == null) {
                fail("real adapter '${adapter.id}' needs a stable service id, Probe, and MaterializedHistory")
            }
            if (adapter.simulationWorld != null) fail("real adapter '${adapter.id}' cannot expose a simulation world")
        }

        private fun validateScenario(scenario: SimGeneratedScenario) {
            if (scenario.generatorName.isBlank() || scenario.generatorVersion.isEmpty()) {
                fail("scenario needs a stable generator name and version")
            }
            if (!SEED.matches(scenario.seedHex)) fail("scenario seed must be exactly 32 bytes of lowercase hexadecimal")
            if (scenario.actions.isEmpty()) fail("scenario must contain at least one generated action")
            val seen = mutableSetOf<String>()
            for (generated in scenario.actions) {
                if (!validId(generated.command)) fail("scenario action '${generated.action.id}' has no stable generator command")
                val action = generated.action
                if (!validId(action.id) || !validId(action.actorId) || !validId(action.kind) || action.version.isEmpty()) {
                    fail("scenario contains an invalid action")
                }
                canonicalBytes(action)
                if (!seen.add(action.id)) fail("scenario has duplicate action id '${action.id}'")
                if (action.causeId.isNotEmpty() && action.causeId !in seen) {
                    fail("scenario action '${action.id}' has unresolved cause '${action.causeId}'")
                }
            }
        }

        private fun compareObservations(
            step: Long,
            actionId: String,
            baselineId: String,
            adapterId: String,
            baseline: Map<String, Any?>,
            actual: Map<String, Any?>,
        ) {
            if (baseline.size != actual.size) {
                throw SimConsumerDivergenceException(step, actionId, baselineId, adapterId, "", "observation count")
            }
            for (key in baseline.keys.sorted()) {
                if (key !in actual) {
                    throw SimConsumerDivergenceException(step, actionId, baselineId, adapterId, key, "missing")
                }
                if (!Arrays.equals(canonicalBytes(baseline[key]), canonicalBytes(actual[key]))) {
                    throw SimConsumerDivergenceException(step, actionId, baselineId, adapterId, key, "value mismatch")
                }
            }
        }

        private fun cloneAction(action: SimConsumerAction): SimConsumerAction = action.copy(payload = deepCopy(action.payload))

        private fun freezeObservation(values: Map<String, Any?>): Map<String, Any?> =
            values.entries.sortedBy { it.key }.associateTo(linkedMapOf()) { it.key to deepCopy(it.value) }

        private fun deepCopy(value: Any?): Any? =
            when (value) {
                is ByteArray -> value.copyOf()
                is Map<*, *> -> value.entries.associate { deepCopy(it.key) to deepCopy(it.value) }
                is Set<*> -> value.mapTo(linkedSetOf()) { deepCopy(it) }
                is Collection<*> -> value.map(::deepCopy)
                is Array<*> -> value.map(::deepCopy)
                else -> value
            }

        private fun validId(id: String): Boolean =
            id.length in 1..128 &&
                id.first() in 'a'..'z' &&
                id.drop(1).all { it in 'a'..'z' || it in '0'..'9' || it in "._:-" }

        private fun fail(message: String): Nothing = throw SimConsumerConformanceException(message)
    }
}
