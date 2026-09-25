package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent, deterministic mutation interpreter for the portable stdlib corpus. */
class PortableStdlibMutationTest {
    private val json = Json

    @Test
    fun independentModelReproducesTheUnperturbedCorpus() {
        fixtureNames.forEach { name ->
            val fixture = load(name)
            assertTrue(fixture.required("scenarios").jsonArray.isNotEmpty(), "stdlib/$name: no scenarios")
            assertEquals(
                emptySet(),
                independentFailures(fixture, null).failed,
                "stdlib/$name: independent model diverged without a mutation",
            )
        }
    }

    @Test
    fun everyDeclaredMutationKillsEveryRequiredScenario() {
        var pairCount = 0
        fixtureNames.forEach { name ->
            val fixture = load(name)
            val baseline = independentFailures(fixture, null).failed
            assertEquals(emptySet(), baseline, "stdlib/$name: unperturbed replay already fails")
            val mutations = fixture.required("mutations").jsonArray
            assertTrue(mutations.isNotEmpty(), "stdlib/$name: empty mutation ledger")
            var fixturePairCount = 0
            mutations.forEach { element ->
                val mutation = element.jsonObject
                val operator = mutation.string("operator")
                val mustFail = mutation.required("must_fail").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue(mustFail.isNotEmpty(), "stdlib/$name: $operator names no scenario")

                val result = independentFailures(fixture, operator)
                assertTrue(
                    operator in result.consulted,
                    "stdlib/$name: mutation '$operator' has no independent interpreter arm; " +
                        "consulted ${result.consulted.sorted()}",
                )
                assertEquals(
                    emptySet(),
                    mustFail - result.failed,
                    "stdlib/$name: mutation '$operator' did not break every must_fail scenario",
                )
                assertEquals(
                    emptySet(),
                    mustFail intersect baseline,
                    "stdlib/$name: mutation '$operator' claims a scenario that already fails unperturbed",
                )
                fixturePairCount += mustFail.size
            }
            assertTrue(
                fixturePairCount >= fixture.required("mutation_floor").jsonPrimitive.int,
                "stdlib/$name: applied only $fixturePairCount mutation/scenario pairs",
            )
            pairCount += fixturePairCount
        }
        assertTrue(pairCount >= 15, "applied only $pairCount mutation/scenario pairs")
    }

    private fun load(name: String): JsonObject =
        json.parseToJsonElement(ConformanceFixtures.read("stdlib/$name")).jsonObject

    private fun independentFailures(
        fixture: JsonObject,
        operator: String?,
    ): MutationResult {
        val mutation = Mutation(operator)
        val failed = linkedSetOf<String>()
        fixture.required("scenarios").jsonArray.forEach { element ->
            val scenario = element.jsonObject
            val state = ModelState()
            scenario.required("steps").jsonArray.forEach { stepElement ->
                val step = stepElement.jsonObject
                val actual =
                    when (fixture.string("feature")) {
                        "stdlib_timer_v1" -> modelTimer(state, step, mutation)
                        "stdlib_timeout_v1" -> modelTimeout(state, step, mutation)
                        "stdlib_revision_barrier_v1" -> modelBarrier(state, step, mutation)
                        else -> error("unsupported stdlib feature ${fixture.string("feature")}")
                    }
                if (actual != step.required("expect").jsonObject) failed += scenario.string("id")
            }
        }
        return MutationResult(failed, mutation.consulted)
    }

    private fun modelTimer(
        state: ModelState,
        step: JsonObject,
        mutation: Mutation,
    ): JsonObject {
        when (modelOp(step, setOf("start", "observe"))) {
            "start" -> {
                val deadline = checkedModelDeadline(step.ulong("now"), step.ulong("duration"))
                if (deadline == null) {
                    state.status = "unavailable"
                    state.reason = "deadline_overflow"
                    return terminal(state)
                }
                state.status = "pending"
                state.deadline = deadline
                state.lastNow = step.ulong("now")
                return jsonObject("outcome" to "pending", "deadline" to deadline)
            }

            "observe" -> Unit
        }
        if (mutation.applies("fixture_bookkeeping")) {
            return jsonObject("outcome" to "pending", "deadline" to state.deadline)
        }
        val ignoreTerminal = mutation.applies("terminal_not_latched")
        if (state.status != "pending" && !ignoreTerminal) return terminal(state)
        if (ignoreTerminal) state.status = "pending"
        val now = step.ulong("now")
        if (now < checkNotNull(state.lastNow)) {
            return jsonObject(
                "outcome" to "unavailable",
                "reason" to "clock_regression",
                "deadline" to state.deadline,
            )
        }
        state.lastNow = now
        val deadline = checkNotNull(state.deadline)
        val reached = if (mutation.applies("deadline_strict_greater")) now > deadline else now >= deadline
        if (!reached) return jsonObject("outcome" to "pending", "deadline" to deadline)
        state.status = "fired"
        state.firedAt = now
        return terminal(state)
    }

    private fun modelTimeout(
        state: ModelState,
        step: JsonObject,
        mutation: Mutation,
    ): JsonObject {
        when (modelOp(step, setOf("start", "poll"))) {
            "start" -> {
                val deadline = checkedModelDeadline(step.ulong("now"), step.ulong("duration"))
                if (deadline == null) {
                    state.status = "unavailable"
                    state.reason = "deadline_overflow"
                    return terminal(state)
                }
                state.status = "pending"
                state.deadline = deadline
                state.lastNow = step.ulong("now")
                return jsonObject("outcome" to "pending", "deadline" to deadline)
            }

            "poll" -> Unit
        }
        if (mutation.applies("fixture_bookkeeping")) {
            return jsonObject(
                "outcome" to "pending",
                "deadline" to state.deadline,
                "operation_calls" to 0,
                "cancellation_calls" to 0,
            )
        }
        val ignoreTerminal = mutation.applies("terminal_not_latched")
        if (state.status != "pending" && !ignoreTerminal) return terminal(state, adapterCounts = true)
        if (ignoreTerminal) state.status = "pending"
        val now = step.ulong("now")
        if (now < checkNotNull(state.lastNow)) {
            state.status = "unavailable"
            state.reason = "clock_regression"
            return terminal(state, adapterCounts = true)
        }
        state.lastNow = now
        val deadline = checkNotNull(state.deadline)
        val reached = if (mutation.applies("deadline_strict_greater")) now > deadline else now >= deadline
        if (reached) {
            state.status = "timed_out"
            return terminal(state, adapterCounts = true)
        }

        val operation = step.string("operation").also { require(it in operationNames) }
        val cancellation = step.string("cancellation").also { require(it in cancellationNames) }
        if (mutation.applies("cancellation_before_completion") && cancellation == "cancelled") {
            state.status = "cancelled"
            return terminal(state, adapterCounts = true, adapterCalls = 1)
        }
        when (operation) {
            "completed" -> {
                state.status = "completed"
                state.value = step.string("value")
            }

            "unavailable" -> {
                state.status = "unavailable"
                state.reason = "operation_unavailable"
            }

            "pending" ->
                when (cancellation) {
                    "cancelled" -> state.status = "cancelled"
                    "unavailable" -> {
                        state.status = "unavailable"
                        state.reason = "cancellation_unavailable"
                    }

                    "pending" ->
                        return jsonObject(
                            "outcome" to "pending",
                            "deadline" to deadline,
                            "operation_calls" to 1,
                            "cancellation_calls" to 1,
                        )
                }
        }
        return terminal(state, adapterCounts = true, adapterCalls = 1)
    }

    private fun modelBarrier(
        state: ModelState,
        step: JsonObject,
        mutation: Mutation,
    ): JsonObject {
        val op = modelOp(step, setOf("start", "register_recheck", "advance", "observe", "dispose", "receipt"))
        if (op == "start") {
            state.status = "pending"
            state.revision = step.ulong("revision")
            state.generation = 0uL
            state.required = step.ulong("required_revision")
            state.deadline = step.nullableULong("deadline")
            state.lastNow = null
            return barrierObservation(state)
        }
        if (mutation.applies("fixture_bookkeeping")) {
            state.status = "pending"
            return barrierObservation(state)
        }
        val ignoreTerminal = mutation.applies("terminal_not_latched")
        if (state.status != "pending" && !ignoreTerminal) {
            return barrierObservation(state, cancellationCalls = 0.takeIf { op == "observe" })
        }
        if (ignoreTerminal) state.status = "pending"
        if (op == "dispose") {
            state.status = "disposed"
            return barrierObservation(state)
        }
        if (op == "receipt") {
            if (mutation.applies("receipt_is_authority")) {
                state.revision = state.required
                state.generation += 1uL
                state.status = "satisfied"
            }
            return barrierObservation(state)
        }
        if (op == "advance") {
            state.acceptRevision(step.ulong("revision"))
            if (state.revision >= state.required && step.required("predicate").jsonPrimitive.boolean) {
                state.status = "satisfied"
            }
            return barrierObservation(state)
        }

        val now = step.ulong("now")
        val previousNow = state.lastNow
        val regressed = previousNow != null && now < previousNow
        if (regressed && !mutation.applies("barrier_accept_clock_regression")) {
            state.status = "unavailable"
            state.reason = "clock_regression"
            return barrierObservation(state, cancellationCalls = 0.takeIf { op == "observe" })
        }
        state.lastNow = now
        if (op == "register_recheck") {
            state.generation += 1uL
            if (!mutation.applies("barrier_skip_post_registration_recheck")) {
                state.revision = maxOf(state.revision, step.ulong("observed_revision"))
                if (state.revision >= state.required && step.required("predicate").jsonPrimitive.boolean) {
                    state.status = "satisfied"
                }
            }
            return barrierObservation(state)
        }

        val deadline = state.deadline
        val reached =
            if (deadline == null) {
                false
            } else if (mutation.applies("deadline_strict_greater")) {
                now > deadline
            } else {
                now >= deadline
            }
        if (reached) {
            state.status = "timed_out"
            return barrierObservation(state, cancellationCalls = 0)
        }
        if (state.revision >= state.required && step.required("predicate").jsonPrimitive.boolean) {
            state.status = "satisfied"
            return barrierObservation(state, cancellationCalls = 0)
        }
        when (val cancellation = step.string("cancellation")) {
            "cancelled" -> state.status = "cancelled"
            "unavailable" -> {
                state.status = "unavailable"
                state.reason = "cancellation_unavailable"
            }

            "pending" -> Unit
            else -> error("unknown model cancellation '$cancellation'")
        }
        return barrierObservation(state, cancellationCalls = 1)
    }

    private fun terminal(
        state: ModelState,
        adapterCounts: Boolean = false,
        adapterCalls: Int = 0,
    ): JsonObject =
        buildJsonObject {
            put("outcome", state.status)
            state.firedAt?.let { putULong("fired_at", it) }
            state.value?.let { put("value", it) }
            state.reason?.let { put("reason", it) }
            if (adapterCounts) {
                put("operation_calls", adapterCalls)
                put("cancellation_calls", adapterCalls)
            }
        }

    private fun barrierObservation(
        state: ModelState,
        cancellationCalls: Int? = null,
    ): JsonObject =
        buildJsonObject {
            put("outcome", state.status)
            state.reason?.let { put("reason", it) }
            putULong("revision", state.revision)
            putULong("generation", state.generation)
            cancellationCalls?.let { put("cancellation_calls", it) }
        }

    private fun jsonObject(vararg fields: Pair<String, Any?>): JsonObject =
        buildJsonObject {
            fields.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is ULong -> putULong(key, value)
                    else -> error("unsupported model JSON value $value")
                }
            }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putULong(
        key: String,
        value: ULong,
    ) {
        put(key, json.parseToJsonElement(value.toString()))
    }

    private fun JsonObject.required(key: String): JsonElement = this[key] ?: error("missing field $key")

    private fun JsonObject.string(key: String): String = required(key).jsonPrimitive.content

    private fun JsonObject.ulong(key: String): ULong = required(key).jsonPrimitive.content.toULong()

    private fun JsonObject.nullableULong(key: String): ULong? =
        required(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toULong()

    private fun modelOp(
        step: JsonObject,
        known: Set<String>,
    ): String = step.string("op").also { require(it in known) { "unknown model op '$it'; known $known" } }

    private fun checkedModelDeadline(
        now: ULong,
        duration: ULong,
    ): ULong? = if (duration > ULong.MAX_VALUE - now) null else now + duration

    private data class MutationResult(
        val failed: Set<String>,
        val consulted: Set<String>,
    )

    private class Mutation(private val operator: String?) {
        val consulted = linkedSetOf<String>()

        fun applies(name: String): Boolean {
            consulted += name
            return operator == name
        }
    }

    private data class ModelState(
        var status: String = "pending",
        var deadline: ULong? = null,
        var lastNow: ULong? = null,
        var firedAt: ULong? = null,
        var value: String? = null,
        var reason: String? = null,
        var revision: ULong = 0uL,
        var generation: ULong = 0uL,
        var required: ULong = 0uL,
    ) {
        fun acceptRevision(candidate: ULong) {
            if (candidate > revision) {
                revision = candidate
                generation += 1uL
            }
        }
    }

    private companion object {
        val fixtureNames = listOf("timer.json", "timeout.json", "revision_barrier.json")
        val operationNames = setOf("completed", "pending", "unavailable")
        val cancellationNames = setOf("cancelled", "pending", "unavailable")
    }
}
