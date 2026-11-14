package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canonical generic effect-CAS corpus (`#lzeffectcas`,
 * `lazily-spec/conformance/egress/effect_cas.json`) replayed against
 * [EffectCasCore] — the `effect-cas` coverage row.
 *
 * Every step's outcome is compared as a whole JSON element against `returns`, and
 * each scenario's `final` per-key image as a whole element (key set and values,
 * both directions). Scenarios iterate through [ConformanceScenarios.of], so the
 * rung-4 ledger books exactly the scenarios whose payload was replayed; steps are
 * counted inside the dispatch loop and must equal the corpus total.
 */
class EffectCasConformanceTest {
    private val path = "egress/effect_cas.json"

    private fun load(): JsonObject = Json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject

    private fun upsertJson(outcome: EffectCasUpsert) =
        when (outcome) {
            EffectCasUpsert.Accepted -> buildJsonObject { put("upsert", "accepted") }
            EffectCasUpsert.Unchanged -> buildJsonObject { put("upsert", "unchanged") }
            is EffectCasUpsert.AlreadyApplied ->
                buildJsonObject {
                    put("upsert", "already_applied")
                    put("applied_through", outcome.appliedThrough)
                }
            is EffectCasUpsert.StaleEpoch ->
                buildJsonObject {
                    put("upsert", "stale_epoch")
                    put("current", outcome.current)
                }
            EffectCasUpsert.EpochConflict -> buildJsonObject { put("upsert", "epoch_conflict") }
        }

    private fun appliedJson(outcome: EffectCasApplied) =
        when (outcome) {
            is EffectCasApplied.StepApplied ->
                buildJsonObject {
                    put("applied", "step_applied")
                    put("next_step", outcome.nextStep)
                }
            is EffectCasApplied.Completed ->
                buildJsonObject {
                    put("applied", "completed")
                    put("applied_through", outcome.appliedThrough)
                }
            EffectCasApplied.Superseded -> buildJsonObject { put("applied", "superseded") }
            EffectCasApplied.Duplicate -> buildJsonObject { put("applied", "duplicate") }
            EffectCasApplied.UnknownAttempt -> buildJsonObject { put("applied", "unknown_attempt") }
            is EffectCasApplied.StaleGeneration ->
                buildJsonObject {
                    put("applied", "stale_generation")
                    put("current", outcome.current)
                }
        }

    private fun failureJson(outcome: EffectCasFailure) =
        when (outcome) {
            EffectCasFailure.Superseded -> buildJsonObject { put("failure", "superseded") }
            is EffectCasFailure.Retrying ->
                buildJsonObject {
                    put("failure", "retrying")
                    put("retry_at", outcome.retryAt)
                }
            EffectCasFailure.Rejected -> buildJsonObject { put("failure", "rejected") }
            EffectCasFailure.UnknownAttempt -> buildJsonObject { put("failure", "unknown_attempt") }
            is EffectCasFailure.StaleGeneration ->
                buildJsonObject {
                    put("failure", "stale_generation")
                    put("current", outcome.current)
                }
        }

    private fun reconnectJson(outcome: EffectCasReconnect) =
        when (outcome) {
            is EffectCasReconnect.Advanced ->
                buildJsonObject {
                    put("reconnect", "advanced")
                    put("generation", outcome.generation)
                    put("requeued", outcome.requeued)
                    put("superseded", outcome.superseded)
                }
            is EffectCasReconnect.Unchanged ->
                buildJsonObject {
                    put("reconnect", "unchanged")
                    put("generation", outcome.generation)
                }
            is EffectCasReconnect.StaleGeneration ->
                buildJsonObject {
                    put("reconnect", "stale_generation")
                    put("current", outcome.current)
                }
        }

    private fun claimJson(outcome: EffectCasClaim<String, Long, String, String>): Pair<JsonObject, EffectAttemptIdentity?> =
        when (outcome) {
            is EffectCasClaim.Claimed ->
                buildJsonObject {
                    put("claim", "claimed")
                    put("identity", outcome.attempt.identity.effect.value)
                    put("step", outcome.attempt.identity.step)
                    put("attempt", outcome.attempt.identity.attempt)
                    put("expected", outcome.attempt.expected)
                    put("payload", outcome.attempt.payload)
                } to outcome.attempt.identity
            EffectCasClaim.Empty -> buildJsonObject { put("claim", "empty") } to null
            EffectCasClaim.Busy -> buildJsonObject { put("claim", "busy") } to null
            is EffectCasClaim.Waiting ->
                buildJsonObject {
                    put("claim", "waiting")
                    put("retry_at", outcome.retryAt)
                } to null
            is EffectCasClaim.StaleGeneration ->
                buildJsonObject {
                    put("claim", "stale_generation")
                    put("current", outcome.current)
                } to null
        }

    private fun runStep(
        core: EffectCasCore<String, Long, String, String, String>,
        attempts: MutableMap<String, EffectAttemptIdentity>,
        step: JsonObject,
        where: String,
    ): JsonObject {
        val op = step.getValue("op").jsonObject
        fun str(key: String) = op.getValue(key).jsonPrimitive.content
        fun long(key: String) = op.getValue(key).jsonPrimitive.long
        fun attempt() = assertNotNull(attempts[str("attempt")], "$where: unsaved attempt label ${str("attempt")}")
        return when (val type = str("type")) {
            "upsert" ->
                upsertJson(
                    core.upsertDesired(
                        EffectIntent(
                            identity = EffectIdentity(str("identity")),
                            epoch = long("epoch"),
                            key = str("key"),
                            expected = long("expected"),
                            plan = EffectPlan(op.getValue("plan").jsonArray.map { it.jsonPrimitive.content }),
                        ),
                    ).result,
                )
            "claim" -> {
                val (returned, claimed) = claimJson(core.claim(str("key"), long("generation"), long("now")).result)
                step["save"]?.let { label ->
                    attempts[label.jsonPrimitive.content] = assertNotNull(claimed, "$where: a saved claim must succeed")
                }
                returned
            }
            "applied" -> appliedJson(core.recordApplied(str("key"), attempt(), str("receipt"), long("authority")).result)
            "superseded" -> {
                // A compare mismatch mutates no backend state (invariant 4).
                assertEquals(op.getValue("backend_before"), op.getValue("backend_after"), "$where: zero backend mutation")
                failureJson(core.recordSuperseded(str("key"), attempt(), long("observed")).result)
            }
            "retryable" -> failureJson(core.recordRetryable(str("key"), attempt(), str("error"), long("retry_at")).result)
            "rejected" -> failureJson(core.recordRejected(str("key"), attempt(), str("error")).result)
            "reconnect" -> reconnectJson(core.reconnect(long("generation")).result)
            else -> error("$where: unknown effect-CAS op `$type`")
        }
    }

    private fun nullable(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun nullable(value: Long?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun finalJson(core: EffectCasCore<String, Long, String, String, String>): JsonObject =
        buildJsonObject {
            for ((key, state) in core.snapshot().keys) {
                val terminal = state.terminal
                put(
                    key,
                    buildJsonObject {
                        put("pending_identity", nullable(state.pending?.intent?.identity?.value))
                        put("inflight_identity", nullable(state.inflight?.identity?.effect?.value))
                        put("applied_through", nullable(state.appliedThrough))
                        put(
                            "terminal",
                            nullable(
                                when (terminal) {
                                    null -> null
                                    is EffectTerminal.Applied -> "applied"
                                    is EffectTerminal.Superseded -> "superseded"
                                    is EffectTerminal.Rejected -> "rejected"
                                },
                            ),
                        )
                        put("receipt_count", terminal?.receipts?.size ?: 0)
                        put("retry_at", nullable(state.retry?.retryAt))
                    },
                )
            }
        }

    @Test
    fun `effect CAS corpus replays exactly`() {
        val fixture = load()
        assertEquals("EffectCas", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("EffectCasCore", fixture.getValue("model").jsonPrimitive.content)
        val loadedScenarios = fixture.getValue("scenarios").jsonArray
        val loadedSteps = loadedScenarios.sumOf { it.jsonObject.getValue("steps").jsonArray.size }
        assertTrue(loadedScenarios.isNotEmpty() && loadedSteps > 0, "$path declares no scenarios or no steps")

        var scenarios = 0
        var executed = 0
        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val id = scenario.getValue("id").jsonPrimitive.content
            val core = EffectCasCore<String, Long, String, String, String>(scenario.getValue("generation").jsonPrimitive.long)
            val attempts = HashMap<String, EffectAttemptIdentity>()
            scenario.getValue("steps").jsonArray.forEachIndexed { index, raw ->
                val step = raw.jsonObject
                val where = "$path $id step $index"
                assertEquals(step.getValue("returns"), runStep(core, attempts, step, where), "$where: returned outcome")
                executed++
            }
            assertEquals(scenario.getValue("final"), finalJson(core), "$path $id: final per-key state")
            scenarios++
        }
        assertEquals(loadedScenarios.size, scenarios, "every scenario must replay")
        assertEquals(loadedSteps, executed, "every step must execute")
    }

    /** Invariant 2: a stale or unknown receipt changes no state. */
    @Test
    fun `stale and unknown receipts are zero-mutation`() {
        val core = EffectCasCore<String, Long, String, String, String>(1)
        core.upsertDesired(EffectIntent(EffectIdentity("e"), 1, "k", 0, EffectPlan.single("w")))
        val attempt = (core.claim("k", 1, 0).result as EffectCasClaim.Claimed).attempt.identity
        val before = core.snapshot()
        val unknown = attempt.copy(attempt = 9)
        val stale = attempt.copy(generation = 0)
        assertEquals(
            EffectCasTransition(EffectCasChange.NONE, EffectCasApplied.UnknownAttempt),
            core.recordApplied("k", unknown, "r", 1),
        )
        assertEquals(
            EffectCasTransition(EffectCasChange.NONE, EffectCasFailure.StaleGeneration(1)),
            core.recordRejected("k", stale, "x"),
        )
        assertEquals(before, core.snapshot())
        assertNull(core.state("k")?.appliedThrough)
    }

    /** The adapter outcome router maps every [EffectApply] arm to its record op. */
    @Test
    fun `record outcome routes adapter results`() {
        val core = EffectCasCore<String, Long, String, String, String>(1)
        core.upsertDesired(EffectIntent(EffectIdentity("e"), 1, "k", 0, EffectPlan.single("w")))
        val first = (core.claim("k", 1, 0).result as EffectCasClaim.Claimed).attempt.identity
        assertEquals(
            EffectCasOutcome.Failure(EffectCasFailure.Retrying(5)),
            core.recordOutcome("k", first, EffectApply.Retryable("busy"), 5).result,
        )
        assertEquals(EffectCasClaim.Waiting(5), core.claim("k", 1, 4).result)
        assertEquals(listOf<String>(), core.pendingKeys(4))
        assertEquals(listOf("k"), core.pendingKeys(5))
        val second = (core.claim("k", 1, 5).result as EffectCasClaim.Claimed).attempt.identity
        assertEquals(2, second.attempt)
        assertEquals(
            EffectCasOutcome.Applied(EffectCasApplied.Completed(1)),
            core.recordOutcome("k", second, EffectApply.Applied("r", 1L), 0).result,
        )
    }
}
