package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortableStdlibConformanceTest {
    private val json = Json

    @Test
    fun canonicalFixturesReplayProductionPrimitives() {
        var visitedBlockCount = 0
        listOf("timer.json", "timeout.json", "revision_barrier.json").forEach { name ->
            val fixturePath = "stdlib/$name"
            val fixture =
                json
                    .parseToJsonElement(
                        ConformanceFixtures.read(fixturePath),
                    ).jsonObject
            assertFixtureBookkeeping(fixture)
            val declaredSites = ConformanceFixtures.blockSitesOf(fixturePath, fixture).keys
            val visitedSites = linkedSetOf<String>()
            ConformanceScenarios.indexed(fixturePath, fixture).forEach { (scenarioIndex, scenario) ->
                when (fixture.string("feature")) {
                    "stdlib_timer_v1" -> replayTimer(fixturePath, scenarioIndex, scenario, visitedSites)
                    "stdlib_timeout_v1" -> replayTimeout(fixturePath, scenarioIndex, scenario, visitedSites)
                    "stdlib_revision_barrier_v1" -> replayBarrier(fixturePath, scenarioIndex, scenario, visitedSites)
                    else -> error("unsupported stdlib feature ${fixture.string("feature")}")
                }
            }
            assertEquals(declaredSites, visitedSites, "$fixturePath: exact assertion-block sites")
            visitedBlockCount += visitedSites.size
        }
        assertEquals(54, visitedBlockCount, "stdlib assertion-block site count")
    }

    @Test
    fun checkedUnsignedDeadlineRejectsOverflow() {
        val failure =
            assertFailsWith<StdlibUnavailableException> {
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
        val completed =
            timeout
                .pollFuture(
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

        timeout
            .pollFuture(
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
        barrier
            .observeFuture(7uL, predicate = false) {
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
            observed
                .observe(10uL, predicate = false) {
                    cancellationCalls += 1
                    TimeoutCancellation.Pending
                }.outcome,
        )
        cancellationCalls = 0
        val regression =
            observed.observe(9uL, predicate = true) {
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
        val reentrantResult =
            reentrant.observe(0uL, predicate = false) {
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

    @Test
    fun timeoutClockRegressionAndCancellationUnavailableLatchBeforeAdaptersRunAgain() {
        val regressed = Timeout<String>(5uL, 10uL)
        var operationCalls = 0
        var cancellationCalls = 0
        assertEquals(
            TimeoutOutcome.Pending,
            regressed
                .poll(
                    8uL,
                    operation = {
                        operationCalls += 1
                        TimeoutOperation.Pending
                    },
                    cancellation = {
                        cancellationCalls += 1
                        TimeoutCancellation.Pending
                    },
                ).outcome,
        )
        operationCalls = 0
        cancellationCalls = 0
        val regression =
            regressed.poll(
                7uL,
                operation = {
                    operationCalls += 1
                    TimeoutOperation.Completed("must not run")
                },
                cancellation = {
                    cancellationCalls += 1
                    TimeoutCancellation.Cancelled
                },
            )
        assertEquals(TimeoutOutcome.Unavailable, regression.outcome)
        assertEquals(StdlibUnavailableReason.ClockRegression, regression.reason)
        assertEquals(0, operationCalls)
        assertEquals(0, cancellationCalls)
        assertEquals(regression, regressed.poll(9uL, { error("terminal operation") }, { error("terminal cancellation") }))

        val unavailable =
            Timeout<String>(0uL, 10uL).poll(
                1uL,
                operation = { TimeoutOperation.Pending },
                cancellation = { TimeoutCancellation.Unavailable },
            )
        assertEquals(TimeoutOutcome.Unavailable, unavailable.outcome)
        assertEquals(StdlibUnavailableReason.CancellationUnavailable, unavailable.reason)
    }

    @Test
    fun registerRecheckDeadlineWinsBeforeRevisionMutation() {
        val barrier = RevisionBarrier(revision = 0uL, requiredRevision = 1uL, deadline = 5uL)
        val timedOut = barrier.registerRecheck(now = 5uL, observedRevision = 1uL, predicate = true)
        assertEquals(RevisionBarrierOutcome.TimedOut, timedOut.outcome)
        assertEquals(0uL, timedOut.revision)
        assertEquals(0uL, timedOut.generation)
        assertEquals(timedOut, barrier.advance(revision = 2uL, predicate = true))
    }

    @Test
    fun futureAdaptersExposeTypedUnavailableAndExceptionalCompletion() {
        val operationUnavailable =
            Timeout<String>(0uL, 10uL)
                .pollFuture(
                    1uL,
                    operation = { CompletableFuture.completedFuture(TimeoutOperation.Unavailable) },
                    cancellation = { CompletableFuture.completedFuture(TimeoutCancellation.Pending) },
                ).join()
        assertEquals(TimeoutOutcome.Unavailable, operationUnavailable.outcome)
        assertEquals(StdlibUnavailableReason.OperationUnavailable, operationUnavailable.reason)

        val cancellationUnavailable =
            Timeout<String>(0uL, 10uL)
                .pollFuture(
                    1uL,
                    operation = { CompletableFuture.completedFuture(TimeoutOperation.Pending) },
                    cancellation = { CompletableFuture.completedFuture(TimeoutCancellation.Unavailable) },
                ).join()
        assertEquals(TimeoutOutcome.Unavailable, cancellationUnavailable.outcome)
        assertEquals(StdlibUnavailableReason.CancellationUnavailable, cancellationUnavailable.reason)

        val barrierUnavailable =
            RevisionBarrier(0uL, 1uL, null)
                .observeFuture(0uL, predicate = false) {
                    CompletableFuture.completedFuture(TimeoutCancellation.Unavailable)
                }.join()
        assertEquals(RevisionBarrierOutcome.Unavailable, barrierUnavailable.outcome)
        assertEquals(StdlibUnavailableReason.CancellationUnavailable, barrierUnavailable.reason)

        var cancellationStarted = false
        val synchronousFailure =
            Timeout<String>(0uL, 10uL).pollFuture(
                1uL,
                operation = { throw IllegalStateException("operation failed") },
                cancellation = {
                    cancellationStarted = true
                    CompletableFuture.completedFuture(TimeoutCancellation.Pending)
                },
            )
        val synchronousThrown = assertFailsWith<CompletionException> { synchronousFailure.join() }
        assertTrue(synchronousThrown.cause is IllegalStateException)
        assertTrue(cancellationStarted, "both future adapters must start before either result is inspected")

        val asynchronousFailure = CompletableFuture<TimeoutCancellation>()
        val failedBarrier =
            RevisionBarrier(0uL, 1uL, null).observeFuture(0uL, predicate = false) { asynchronousFailure }
        asynchronousFailure.completeExceptionally(IllegalArgumentException("cancellation failed"))
        val asynchronousThrown = assertFailsWith<CompletionException> { failedBarrier.join() }
        assertTrue(asynchronousThrown.cause is IllegalArgumentException)
    }

    private fun assertFixtureBookkeeping(fixture: JsonObject) {
        val scenarios = fixture.required("scenarios").jsonArray
        val scenarioIds = scenarios.map { it.jsonObject.string("id") }.toSet()
        val assertionCount =
            scenarios.sumOf { scenario ->
                scenario.jsonObject.required("steps").jsonArray.sumOf { step ->
                    step.jsonObject
                        .required("expect")
                        .jsonObject.size
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

    private fun replayTimer(
        fixturePath: String,
        scenarioIndex: Int,
        scenario: JsonObject,
        visitedSites: MutableSet<String>,
    ) {
        var timer: Timer? = null
        scenario.required("steps").jsonArray.forEachIndexed { index, stepElement ->
            val step = stepElement.jsonObject
            val actual =
                when (step.string("op")) {
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

                    "observe" ->
                        timerObservation(
                            checkNotNull(timer) { "timer observe before start" }.observe(step.ulong("now")),
                        )

                    else -> error("unsupported timer op ${step.string("op")}")
                }
            assertStep(fixturePath, scenarioIndex, index, step, actual, visitedSites)
        }
    }

    private fun replayTimeout(
        fixturePath: String,
        scenarioIndex: Int,
        scenario: JsonObject,
        visitedSites: MutableSet<String>,
    ) {
        var timeout: Timeout<String>? = null
        scenario.required("steps").jsonArray.forEachIndexed { index, stepElement ->
            val step = stepElement.jsonObject
            val actual =
                when (step.string("op")) {
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
                        val observation =
                            checkNotNull(timeout) { "timeout poll before start" }.poll(
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
            assertStep(fixturePath, scenarioIndex, index, step, actual, visitedSites)
        }
    }

    private fun replayBarrier(
        fixturePath: String,
        scenarioIndex: Int,
        scenario: JsonObject,
        visitedSites: MutableSet<String>,
    ) {
        var barrier: RevisionBarrier? = null
        scenario.required("steps").jsonArray.forEachIndexed { index, stepElement ->
            val step = stepElement.jsonObject
            var cancellationCalls = 0
            val observation =
                when (step.string("op")) {
                    "start" ->
                        RevisionBarrier(
                            revision = step.ulong("revision"),
                            requiredRevision = step.ulong("required_revision"),
                            deadline = step.nullableULong("deadline"),
                        ).also { barrier = it }.receipt("")

                    "observe" ->
                        checkNotNull(barrier) { "barrier observe before start" }.observe(
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

                    "advance" ->
                        checkNotNull(barrier) { "barrier advance before start" }.advance(
                            revision = step.ulong("revision"),
                            predicate = step.required("predicate").jsonPrimitive.boolean,
                        )

                    "dispose" -> checkNotNull(barrier) { "barrier dispose before start" }.dispose()
                    "receipt" ->
                        checkNotNull(barrier) { "barrier receipt before start" }
                            .receipt(step.string("key"))

                    else -> error("unsupported barrier op ${step.string("op")}")
                }
            val actual =
                barrierObservation(
                    observation,
                    cancellationCalls.takeIf { step.string("op") == "observe" },
                )
            assertStep(fixturePath, scenarioIndex, index, step, actual, visitedSites)
        }
    }

    private fun assertStep(
        fixturePath: String,
        scenarioIndex: Int,
        stepIndex: Int,
        step: JsonObject,
        actual: JsonObject,
        visitedSites: MutableSet<String>,
    ) {
        val siteId = "$fixturePath|scenarios[$scenarioIndex].steps[$stepIndex].expect"
        check(visitedSites.add(siteId)) { "$siteId: assertion block visited more than once" }
        val expected = AssertionKeys(siteId, step.required("expect").jsonObject, fixturePath)

        // Key-set equality is deliberately bidirectional. AssertionKeys catches a
        // fixture key this runner forgot, while this comparison also catches an
        // observation field the fixture forgot to declare.
        assertEquals(expected.keys, actual.keys, "$siteId: whole-block key set")

        val outcome = actual.string("outcome")
        expected.assertKeyValue("outcome") { actual.required("outcome") }
        when (outcome) {
            "pending" -> expected.assertKeyValue("deadline") { actual.required("deadline") }
            "fired" -> expected.assertKeyValue("fired_at") { actual.required("fired_at") }
            "completed" -> expected.assertKeyValue("value") { actual.required("value") }
            "unavailable" -> {
                expected.assertKeyValue("reason") { actual.required("reason") }
                // Timer clock regression preserves its live deadline; constructor
                // overflow has none. The fixture decides which shape applies.
                expected.assertKeyValue("deadline") { actual.required("deadline") }
            }

            "timed_out", "cancelled", "satisfied", "disposed" -> Unit
            else -> error("$siteId: unsupported observed outcome '$outcome'")
        }

        // These are outcome-independent observables within their respective
        // primitive. Optional AssertionKeys reads do not evaluate the actual
        // accessor when a fixture omits the key.
        expected.assertKeyValue("operation_calls") { actual.required("operation_calls") }
        expected.assertKeyValue("cancellation_calls") { actual.required("cancellation_calls") }
        expected.assertKeyValue("revision") { actual.required("revision") }
        expected.assertKeyValue("generation") { actual.required("generation") }
        expected.requireAllSatisfied()
    }

    private fun timerObservation(observation: TimerObservation): JsonObject =
        buildJsonObject {
            put("outcome", observation.outcome.wireName)
            observation.deadline?.let { putULong("deadline", it) }
            observation.firedAt?.let { putULong("fired_at", it) }
            observation.reason?.let { put("reason", it.wireName) }
        }

    private fun timeoutObservation(
        observation: TimeoutObservation<String>,
        operationCalls: Int,
        cancellationCalls: Int,
    ): JsonObject =
        buildJsonObject {
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
    ): JsonObject =
        buildJsonObject {
            put("outcome", observation.outcome.wireName)
            observation.reason?.let { put("reason", it.wireName) }
            putULong("revision", observation.revision)
            putULong("generation", observation.generation)
            cancellationCalls?.let { put("cancellation_calls", it) }
        }

    private fun JsonObject.required(name: String): JsonElement = this[name] ?: error("missing field $name")

    private fun JsonObject.string(name: String): String = required(name).jsonPrimitive.content

    private fun JsonObject.ulong(name: String): ULong = required(name).jsonPrimitive.content.toULong()

    private fun JsonObject.nullableULong(name: String): ULong? =
        required(name)
            .takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.content
            ?.toULong()

    private fun JsonObject.cancellation(): TimeoutCancellation =
        when (string("cancellation")) {
            "pending" -> TimeoutCancellation.Pending
            "cancelled" -> TimeoutCancellation.Cancelled
            "unavailable" -> TimeoutCancellation.Unavailable
            else -> error("unsupported cancellation ${string("cancellation")}")
        }

    private fun JsonObjectBuilder.putULong(
        name: String,
        value: ULong,
    ) {
        put(name, json.parseToJsonElement(value.toString()))
    }
}
