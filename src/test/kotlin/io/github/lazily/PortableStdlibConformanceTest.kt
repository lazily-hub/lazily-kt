package io.github.lazily

import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableStdlibConformanceTest {
    private val json = Json

    @Test
    fun canonicalFixturesReplayProductionPrimitives() {
        listOf("timer.json", "timeout.json", "revision_barrier.json").forEach { name ->
            val fixture = json.parseToJsonElement(
                ConformanceFixtures.read("stdlib/$name"),
            ).jsonObject
            assertFixtureBookkeeping(fixture)
            ConformanceScenarios.of("stdlib/$name", fixture).forEach { scenario ->
                when (fixture.string("feature")) {
                    "stdlib_timer_v1" -> replayTimer(scenario)
                    "stdlib_timeout_v1" -> replayTimeout(scenario)
                    "stdlib_revision_barrier_v1" -> replayBarrier(scenario)
                    else -> error("unsupported stdlib feature ${fixture.string("feature")}")
                }
            }
        }
    }

    @Test
    fun checkedUnsignedDeadlineRejectsOverflow() {
        val failure = assertFailsWith<StdlibUnavailableException> {
            checkedDeadline(ULong.MAX_VALUE - 1uL, 2uL)
        }
        assertEquals(StdlibUnavailableReason.DeadlineOverflow, failure.reason)
    }

    @Test
    fun completableFutureAdaptersUseTheSameCallerDrivenStateMachines() {
        val timer = Timer(0uL, 2uL)
        assertEquals(TimerOutcome.Pending, timer.observeFuture(1uL).join().outcome)
        assertEquals(TimerOutcome.Fired, timer.observeFuture(2uL).join().outcome)

        var operationCalls = 0
        var cancellationCalls = 0
        val timeout = Timeout<String>(0uL, 10uL)
        val completed = timeout.pollFuture(
            3uL,
            operation = {
                operationCalls += 1
                CompletableFuture.completedFuture(TimeoutOperation.Completed("future"))
            },
            cancellation = {
                cancellationCalls += 1
                CompletableFuture.completedFuture(TimeoutCancellation.Cancelled)
            },
        ).join()
        assertEquals(TimeoutOutcome.Completed, completed.outcome)
        assertEquals("future", completed.value)
        assertEquals(1, operationCalls)
        assertEquals(1, cancellationCalls)

        timeout.pollFuture(
            9uL,
            operation = {
                operationCalls += 1
                CompletableFuture.completedFuture(TimeoutOperation.Unavailable)
            },
            cancellation = {
                cancellationCalls += 1
                CompletableFuture.completedFuture(TimeoutCancellation.Cancelled)
            },
        ).join()
        assertEquals(1, operationCalls, "terminal future read must not call operation")
        assertEquals(1, cancellationCalls, "terminal future read must not call cancellation")

        val barrier = RevisionBarrier(0uL, 1uL, null)
        var barrierCancellationCalls = 0
        val satisfied = barrier.advance(1uL, predicate = true)
        assertEquals(RevisionBarrierOutcome.Satisfied, satisfied.outcome)
        barrier.observeFuture(7uL, predicate = false) {
            barrierCancellationCalls += 1
            CompletableFuture.completedFuture(TimeoutCancellation.Cancelled)
        }.join()
        assertEquals(0, barrierCancellationCalls, "terminal barrier read must not call cancellation")
    }

    @Test
    fun revisionBarrierRejectsClockRegressionBeforeMutationOrCancellation() {
        val observed = RevisionBarrier(0uL, 1uL, null)
        var cancellationCalls = 0
        assertEquals(
            RevisionBarrierOutcome.Pending,
            observed.observe(10uL, predicate = false) {
                cancellationCalls += 1
                TimeoutCancellation.Pending
            }.outcome,
        )
        cancellationCalls = 0
        val regression = observed.observe(9uL, predicate = true) {
            cancellationCalls += 1
            TimeoutCancellation.Cancelled
        }
        assertEquals(RevisionBarrierOutcome.Unavailable, regression.outcome)
        assertEquals(StdlibUnavailableReason.ClockRegression, regression.reason)
        assertEquals(0uL, regression.revision)
        assertEquals(0uL, regression.generation)
        assertEquals(0, cancellationCalls)

        val registered = RevisionBarrier(0uL, 1uL, null)
        registered.registerRecheck(10uL, observedRevision = 0uL, predicate = false)
        val registerRegression =
            registered.registerRecheck(9uL, observedRevision = 7uL, predicate = true)
        assertEquals(RevisionBarrierOutcome.Unavailable, registerRegression.outcome)
        assertEquals(StdlibUnavailableReason.ClockRegression, registerRegression.reason)
        assertEquals(0uL, registerRegression.revision)
        assertEquals(0uL, registerRegression.generation)
    }

    @Test
    fun revisionBarrierPreservesFirstTerminalAcrossReentrantAndFutureCancellation() {
        val reentrant = RevisionBarrier(0uL, 1uL, null)
        val reentrantResult = reentrant.observe(0uL, predicate = false) {
            reentrant.dispose()
            TimeoutCancellation.Cancelled
        }
        assertEquals(RevisionBarrierOutcome.Disposed, reentrantResult.outcome)

        val asynchronous = RevisionBarrier(0uL, 1uL, null)
        val cancellation = CompletableFuture<TimeoutCancellation>()
        val observation = asynchronous.observeFuture(0uL, predicate = false) { cancellation }
        asynchronous.dispose()
        cancellation.complete(TimeoutCancellation.Cancelled)
        assertEquals(RevisionBarrierOutcome.Disposed, observation.join().outcome)
    }

    private fun assertFixtureBookkeeping(fixture: JsonObject) {
        val scenarios = fixture.required("scenarios").jsonArray
        val scenarioIds = scenarios.map { it.jsonObject.string("id") }.toSet()
        val assertionCount = scenarios.sumOf { scenario ->
            scenario.jsonObject.required("steps").jsonArray.sumOf { step ->
                step.jsonObject.required("expect").jsonObject.size
            }
        }
        val mutations = fixture.required("mutations").jsonArray

        assertTrue(scenarios.size >= fixture.required("scenario_floor").jsonPrimitive.int)
        assertTrue(assertionCount >= fixture.required("assertion_floor").jsonPrimitive.int)
        assertTrue(mutations.size >= fixture.required("mutation_floor").jsonPrimitive.int)
        mutations.forEach { mutationElement ->
            val mutation = mutationElement.jsonObject
            val kills = mutation.required("must_fail").jsonArray
            assertTrue(kills.isNotEmpty(), "${mutation.string("operator")} has no required kill")
            kills.forEach { kill ->
                assertTrue(
                    kill.jsonPrimitive.content in scenarioIds,
                    "${mutation.string("operator")} references a missing scenario",
                )
            }
        }
    }

    private fun replayTimer(scenario: JsonObject) {
        var timer: Timer? = null
        scenario.required("steps").jsonArray.forEachIndexed { index, stepElement ->
            val step = stepElement.jsonObject
            val actual = when (step.string("op")) {
                "start" -> {
                    try {
                        Timer(step.ulong("now"), step.ulong("duration")).also { timer = it }
                        buildJsonObject {
                            put("outcome", "pending")
                            putULong("deadline", timer!!.deadline)
                        }
                    } catch (failure: StdlibUnavailableException) {
                        buildJsonObject {
                            put("outcome", "unavailable")
                            put("reason", failure.reason.wireName)
                        }
                    }
                }

                "observe" -> timerObservation(
                    checkNotNull(timer) { "timer observe before start" }.observe(step.ulong("now")),
                )

                else -> error("unsupported timer op ${step.string("op")}")
            }
            assertStep(scenario, index, step, actual)
        }
    }

    private fun replayTimeout(scenario: JsonObject) {
        var timeout: Timeout<String>? = null
        scenario.required("steps").jsonArray.forEachIndexed { index, stepElement ->
            val step = stepElement.jsonObject
            val actual = when (step.string("op")) {
                "start" -> {
                    try {
                        Timeout<String>(step.ulong("now"), step.ulong("duration")).also {
                            timeout = it
                        }
                        buildJsonObject {
                            put("outcome", "pending")
                            putULong("deadline", timeout!!.deadline)
                        }
                    } catch (failure: StdlibUnavailableException) {
                        buildJsonObject {
                            put("outcome", "unavailable")
                            put("reason", failure.reason.wireName)
                        }
                    }
                }

                "poll" -> {
                    var operationCalls = 0
                    var cancellationCalls = 0
                    val observation = checkNotNull(timeout) { "timeout poll before start" }.poll(
                        step.ulong("now"),
                        operation = {
                            operationCalls += 1
                            when (step.string("operation")) {
                                "pending" -> TimeoutOperation.Pending
                                "completed" -> TimeoutOperation.Completed(step.string("value"))
                                "unavailable" -> TimeoutOperation.Unavailable
                                else -> error("unsupported operation ${step.string("operation")}")
                            }
                        },
                        cancellation = {
                            cancellationCalls += 1
                            step.cancellation()
                        },
                    )
                    timeoutObservation(observation, operationCalls, cancellationCalls)
                }

                else -> error("unsupported timeout op ${step.string("op")}")
            }
            assertStep(scenario, index, step, actual)
        }
    }

    private fun replayBarrier(scenario: JsonObject) {
        var barrier: RevisionBarrier? = null
        scenario.required("steps").jsonArray.forEachIndexed { index, stepElement ->
            val step = stepElement.jsonObject
            var cancellationCalls = 0
            val observation = when (step.string("op")) {
                "start" -> RevisionBarrier(
                    revision = step.ulong("revision"),
                    requiredRevision = step.ulong("required_revision"),
                    deadline = step.nullableULong("deadline"),
                ).also { barrier = it }.receipt("")

                "observe" -> checkNotNull(barrier) { "barrier observe before start" }.observe(
                    now = step.ulong("now"),
                    predicate = step.required("predicate").jsonPrimitive.boolean,
                    cancellation = {
                        cancellationCalls += 1
                        step.cancellation()
                    },
                )

                "register_recheck" ->
                    checkNotNull(barrier) { "barrier register before start" }.registerRecheck(
                        now = step.ulong("now"),
                        observedRevision = step.ulong("observed_revision"),
                        predicate = step.required("predicate").jsonPrimitive.boolean,
                    )

                "advance" -> checkNotNull(barrier) { "barrier advance before start" }.advance(
                    revision = step.ulong("revision"),
                    predicate = step.required("predicate").jsonPrimitive.boolean,
                )

                "dispose" -> checkNotNull(barrier) { "barrier dispose before start" }.dispose()
                "receipt" -> checkNotNull(barrier) { "barrier receipt before start" }
                    .receipt(step.string("key"))

                else -> error("unsupported barrier op ${step.string("op")}")
            }
            val actual = barrierObservation(
                observation,
                cancellationCalls.takeIf { step.string("op") == "observe" },
            )
            assertStep(scenario, index, step, actual)
        }
    }

    private fun assertStep(
        scenario: JsonObject,
        index: Int,
        step: JsonObject,
        actual: JsonObject,
    ) {
        assertEquals(
            step.required("expect").jsonObject,
            actual,
            "${scenario.string("id")} step $index",
        )
    }

    private fun timerObservation(observation: TimerObservation): JsonObject = buildJsonObject {
        put("outcome", observation.outcome.wireName)
        observation.deadline?.let { putULong("deadline", it) }
        observation.firedAt?.let { putULong("fired_at", it) }
        observation.reason?.let { put("reason", it.wireName) }
    }

    private fun timeoutObservation(
        observation: TimeoutObservation<String>,
        operationCalls: Int,
        cancellationCalls: Int,
    ): JsonObject = buildJsonObject {
        put("outcome", observation.outcome.wireName)
        observation.deadline?.let { putULong("deadline", it) }
        if (observation.outcome == TimeoutOutcome.Completed) {
            put("value", observation.value)
        }
        observation.reason?.let { put("reason", it.wireName) }
        put("operation_calls", operationCalls)
        put("cancellation_calls", cancellationCalls)
    }

    private fun barrierObservation(
        observation: RevisionBarrierObservation,
        cancellationCalls: Int?,
    ): JsonObject = buildJsonObject {
        put("outcome", observation.outcome.wireName)
        observation.reason?.let { put("reason", it.wireName) }
        putULong("revision", observation.revision)
        putULong("generation", observation.generation)
        cancellationCalls?.let { put("cancellation_calls", it) }
    }

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: error("missing field $name")

    private fun JsonObject.string(name: String): String =
        required(name).jsonPrimitive.content

    private fun JsonObject.ulong(name: String): ULong =
        required(name).jsonPrimitive.content.toULong()

    private fun JsonObject.nullableULong(name: String): ULong? =
        required(name).takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toULong()

    private fun JsonObject.cancellation(): TimeoutCancellation =
        when (string("cancellation")) {
            "pending" -> TimeoutCancellation.Pending
            "cancelled" -> TimeoutCancellation.Cancelled
            "unavailable" -> TimeoutCancellation.Unavailable
            else -> error("unsupported cancellation ${string("cancellation")}")
        }

    private fun JsonObjectBuilder.putULong(name: String, value: ULong) {
        put(name, json.parseToJsonElement(value.toString()))
    }
}
