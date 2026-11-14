package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical egress corpus (`#lzegress`, `lazily-spec/conformance/egress/`)
 * replayed against **every egress flavor lazily-kt ships**: [EgressCell],
 * [ThreadSafeEgressCell] and [AsyncEgressCell] — the `reactive-egress`,
 * `egress-thread-safe` and `egress-async` coverage rows.
 *
 * Mirrors [IngressFamilyConformanceTest]: the flavor axis lives in the runner, one
 * [EgressModel] replays the same JSON against each shell, and nothing in it is
 * async-coloured because delivery authority has nothing to await.
 *
 * What keeps it from reporting green while testing nothing:
 *
 * * Every step's returned outcome is compared as a whole JSON element against
 *   `returns`, and the full projected state after the step against `expected` —
 *   the key set of `expected` itself is pinned, so a key added upstream fails.
 * * `invalidates` is asserted in BOTH directions through a per-reader cache-validity
 *   probe ([Context.isSet] and its siblings); a step expecting `false` fails if the
 *   shell over-invalidated. [`the invalidation probe discriminates on every flavor`]
 *   proves the probe can fail.
 * * Steps are counted inside the dispatch loop and every flavor must execute the
 *   corpus total, which is itself required to be non-zero; an unknown op is a hard
 *   failure, never a skip; a missing fixture throws from [ConformanceFixtures.read].
 * * [unshippedFlavorsAreReallyAbsent] keeps the flavor ledger honest against
 *   `src/main/kotlin` in both directions.
 */
class EgressFamilyConformanceTest {
    private enum class Flavor { Sync, ThreadSafe, Async }

    /** Named explicitly: a corpus fixture missing here is caught by the coverage guard. */
    private val fixtures =
        listOf(
            "egress_ordered_ack.json",
            "egress_inflight_window.json",
            "egress_retry_budget.json",
            "egress_generation_fence.json",
        )

    private data class LedgerRow(
        val flavor: String,
        val typeName: String,
        val shipped: Boolean,
    )

    private val ledger =
        listOf(
            LedgerRow("single-threaded", "EgressCell", shipped = true),
            LedgerRow("thread-safe", "ThreadSafeEgressCell", shipped = true),
            LedgerRow("async", "AsyncEgressCell", shipped = true),
        )

    private val stateKeys =
        setOf("generation", "next_sequence", "pending", "inflight", "acked_through", "retry", "invalidates")
    private val readerKinds = listOf("pending", "inflight", "acked_through", "retry")

    // -- The flavor-neutral model ------------------------------------------

    private interface EgressModel : AutoCloseable {
        fun enqueue(payload: Long): Long

        fun claim(generation: Long): EgressClaim<Long>

        fun ack(
            generation: Long,
            through: Long,
        ): EgressAck

        fun fail(
            generation: Long,
            sequence: Long,
        ): EgressFailure

        fun retryNow(
            generation: Long,
            sequence: Long,
        ): EgressRetryAction

        fun reconnect(generation: Long): EgressReconnect

        fun generation(): Long

        fun nextSequence(): Long

        /** Reactive reads; each also re-materializes its reader's cache. */
        fun pending(): List<EgressEnvelope<Long>>

        fun inflight(): List<EgressEnvelope<Long>>

        fun ackedThrough(): Long?

        fun retry(): EgressRetry?

        /** Per-reader cache validity, in [readerKinds] order. */
        fun validity(): List<Boolean>
    }

    private class SyncModel(
        generation: Long,
        policy: EgressPolicy,
    ) : EgressModel {
        private val ctx = Context()
        private val cell = EgressCell<Long>(ctx, generation, policy)

        override fun enqueue(payload: Long) = cell.enqueue(payload)

        override fun claim(generation: Long) = cell.claim(generation)

        override fun ack(
            generation: Long,
            through: Long,
        ) = cell.ack(generation, through)

        override fun fail(
            generation: Long,
            sequence: Long,
        ) = cell.fail(generation, sequence)

        override fun retryNow(
            generation: Long,
            sequence: Long,
        ) = cell.retryNow(generation, sequence)

        override fun reconnect(generation: Long) = cell.reconnect(generation)

        override fun generation() = cell.generation

        override fun nextSequence() = cell.nextSequence

        override fun pending() = cell.pending()

        override fun inflight() = cell.inflight()

        override fun ackedThrough() = cell.ackedThrough()

        override fun retry() = cell.retry()

        override fun validity() =
            listOf(
                ctx.isSet(cell.pendingHandle()),
                ctx.isSet(cell.inflightHandle()),
                ctx.isSet(cell.ackedThroughHandle()),
                ctx.isSet(cell.retryHandle()),
            )

        override fun close() = Unit
    }

    private class ThreadSafeModel(
        generation: Long,
        policy: EgressPolicy,
    ) : EgressModel {
        private val ctx = ThreadSafeContext()
        private val cell = ThreadSafeEgressCell<Long>(ctx, generation, policy)

        override fun enqueue(payload: Long) = cell.enqueue(payload)

        override fun claim(generation: Long) = cell.claim(generation)

        override fun ack(
            generation: Long,
            through: Long,
        ) = cell.ack(generation, through)

        override fun fail(
            generation: Long,
            sequence: Long,
        ) = cell.fail(generation, sequence)

        override fun retryNow(
            generation: Long,
            sequence: Long,
        ) = cell.retryNow(generation, sequence)

        override fun reconnect(generation: Long) = cell.reconnect(generation)

        override fun generation() = cell.generation

        override fun nextSequence() = cell.nextSequence

        override fun pending() = cell.pending()

        override fun inflight() = cell.inflight()

        override fun ackedThrough() = cell.ackedThrough()

        override fun retry() = cell.retry()

        override fun validity() =
            listOf(
                ctx.isSet(cell.pendingHandle()),
                ctx.isSet(cell.inflightHandle()),
                ctx.isSet(cell.ackedThroughHandle()),
                ctx.isSet(cell.retryHandle()),
            )

        override fun close() = Unit
    }

    private class AsyncModel(
        generation: Long,
        policy: EgressPolicy,
    ) : EgressModel {
        private val ctx = AsyncContext()
        private val cell = AsyncEgressCell<Long>(ctx, generation, policy)

        override fun enqueue(payload: Long) = cell.enqueue(payload)

        override fun claim(generation: Long) = cell.claim(generation)

        override fun ack(
            generation: Long,
            through: Long,
        ) = cell.ack(generation, through)

        override fun fail(
            generation: Long,
            sequence: Long,
        ) = cell.fail(generation, sequence)

        override fun retryNow(
            generation: Long,
            sequence: Long,
        ) = cell.retryNow(generation, sequence)

        override fun reconnect(generation: Long) = cell.reconnect(generation)

        override fun generation() = cell.generation

        override fun nextSequence() = cell.nextSequence

        override fun pending() = cell.pending()

        override fun inflight() = cell.inflight()

        override fun ackedThrough() = cell.ackedThrough()

        override fun retry() = cell.retry()

        override fun validity() =
            listOf(
                ctx.isSet(cell.pendingHandle()),
                ctx.isSet(cell.inflightHandle()),
                ctx.isSet(cell.ackedThroughHandle()),
                ctx.isSet(cell.retryHandle()),
            )

        override fun close() = ctx.close()
    }

    private fun model(
        flavor: Flavor,
        generation: Long,
        policy: EgressPolicy,
    ): EgressModel =
        when (flavor) {
            Flavor.Sync -> SyncModel(generation, policy)
            Flavor.ThreadSafe -> ThreadSafeModel(generation, policy)
            Flavor.Async -> AsyncModel(generation, policy)
        }

    // -- Fixture decoding and outcome encoding -------------------------------

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(ConformanceFixtures.read("egress/$name")).jsonObject

    private fun policyOf(raw: JsonObject): EgressPolicy {
        assertEquals(
            setOf("inflight_limit", "retry_budget", "retry_base", "retry_ceiling"),
            raw.keys,
            "egress policy key set",
        )
        return EgressPolicy(
            inflightLimit = raw.getValue("inflight_limit").jsonPrimitive.int,
            retryBudget = raw.getValue("retry_budget").jsonPrimitive.int,
            retryBase = raw.getValue("retry_base").jsonPrimitive.long,
            retryCeiling = raw.getValue("retry_ceiling").jsonPrimitive.long,
        )
    }

    private fun envelopeJson(envelope: EgressEnvelope<Long>) =
        buildJsonObject {
            put("generation", envelope.generation)
            put("sequence", envelope.sequence)
            put("attempt", envelope.attempt)
            put("payload", envelope.payload)
        }

    private fun retryJson(retry: EgressRetry?): JsonElement =
        if (retry == null) {
            JsonNull
        } else {
            buildJsonObject {
                put("sequence", retry.sequence)
                put("attempt", retry.attempt)
                put("backoff", retry.backoff)
                put("exhausted", retry.exhausted)
            }
        }

    private fun nullableLong(value: Long?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    private fun claimJson(claim: EgressClaim<Long>) =
        when (claim) {
            is EgressClaim.Claimed ->
                buildJsonObject {
                    put("claim", "claimed")
                    put("envelope", envelopeJson(claim.envelope))
                }
            EgressClaim.Empty -> buildJsonObject { put("claim", "empty") }
            EgressClaim.WindowFull -> buildJsonObject { put("claim", "window_full") }
            is EgressClaim.StaleGeneration ->
                buildJsonObject {
                    put("claim", "stale_generation")
                    put("current", claim.current)
                }
        }

    private fun ackJson(ack: EgressAck) =
        when (ack) {
            is EgressAck.Advanced ->
                buildJsonObject {
                    put("ack", "advanced")
                    put("through", ack.through)
                }
            is EgressAck.Unchanged ->
                buildJsonObject {
                    put("ack", "unchanged")
                    put("through", nullableLong(ack.through))
                }
            is EgressAck.BeyondSent ->
                buildJsonObject {
                    put("ack", "beyond_sent")
                    put("next_sequence", ack.nextSequence)
                }
            is EgressAck.StaleGeneration ->
                buildJsonObject {
                    put("ack", "stale_generation")
                    put("current", ack.current)
                }
        }

    private fun failureJson(failure: EgressFailure) =
        when (failure) {
            is EgressFailure.Retrying ->
                buildJsonObject {
                    put("failure", "retrying")
                    put("retry", retryJson(failure.retry))
                }
            is EgressFailure.Exhausted ->
                buildJsonObject {
                    put("failure", "exhausted")
                    put("retry", retryJson(failure.retry))
                }
            EgressFailure.UnknownSequence -> buildJsonObject { put("failure", "unknown_sequence") }
            is EgressFailure.StaleGeneration ->
                buildJsonObject {
                    put("failure", "stale_generation")
                    put("current", failure.current)
                }
        }

    private fun retryActionJson(action: EgressRetryAction) =
        when (action) {
            is EgressRetryAction.Scheduled ->
                buildJsonObject {
                    put("retry_action", "scheduled")
                    put("sequence", action.sequence)
                }
            EgressRetryAction.UnknownSequence -> buildJsonObject { put("retry_action", "unknown_sequence") }
            is EgressRetryAction.StaleGeneration ->
                buildJsonObject {
                    put("retry_action", "stale_generation")
                    put("current", action.current)
                }
        }

    private fun reconnectJson(reconnect: EgressReconnect) =
        when (reconnect) {
            is EgressReconnect.Advanced ->
                buildJsonObject {
                    put("reconnect", "advanced")
                    put("generation", reconnect.generation)
                    put("replayed", reconnect.replayed)
                }
            is EgressReconnect.Unchanged ->
                buildJsonObject {
                    put("reconnect", "unchanged")
                    put("generation", reconnect.generation)
                }
            is EgressReconnect.StaleGeneration ->
                buildJsonObject {
                    put("reconnect", "stale_generation")
                    put("current", reconnect.current)
                }
        }

    private fun runOp(
        model: EgressModel,
        op: JsonObject,
        where: String,
    ): JsonObject {
        fun long(key: String) = op.getValue(key).jsonPrimitive.long
        return when (val type = op.getValue("type").jsonPrimitive.content) {
            "enqueue" -> buildJsonObject { put("sequence", model.enqueue(long("payload"))) }
            "claim" -> claimJson(model.claim(long("generation")))
            "ack" -> ackJson(model.ack(long("generation"), long("through")))
            "fail" -> failureJson(model.fail(long("generation"), long("sequence")))
            "retry_now" -> retryActionJson(model.retryNow(long("generation"), long("sequence")))
            "reconnect" -> reconnectJson(model.reconnect(long("generation")))
            else -> error("$where: unknown egress op `$type`")
        }
    }

    /** Read every reader so the next step's validity probe measures that step alone. */
    private fun materialize(model: EgressModel) {
        model.pending()
        model.inflight()
        model.ackedThrough()
        model.retry()
    }

    /** Replay one fixture against one flavor; returns the steps actually executed. */
    private fun replay(
        name: String,
        flavor: Flavor,
    ): Int {
        val json = fixture(name)
        assertEquals("Egress", json.getValue("kind").jsonPrimitive.content, "$name: fixture kind")
        assertEquals("EgressCore", json.getValue("model").jsonPrimitive.content, "$name: fixture model")
        val steps = json.getValue("steps").jsonArray
        assertTrue(steps.isNotEmpty(), "$flavor $name has no steps")
        model(
            flavor,
            json.getValue("generation").jsonPrimitive.long,
            policyOf(json.getValue("policy").jsonObject),
        ).use { model ->
            materialize(model)
            var executed = 0
            steps.forEachIndexed { index, raw ->
                val step = raw.jsonObject
                val op = step.getValue("op").jsonObject
                val opType = op.getValue("type").jsonPrimitive.content
                val where = "$flavor $name step $index ($opType)"

                val before = model.validity()
                val returned = runOp(model, op, where)
                assertEquals(step.getValue("returns"), returned, "$where: returned outcome")
                val after = model.validity()

                val expectedObject = step.getValue("expected").jsonObject
                assertEquals(stateKeys, expectedObject.keys, "$where: expected-state key set")
                expectedObject.consuming("egress/$name steps[$index].expected ($flavor $opType)") { expected ->
                    expected.assertLong("generation") { model.generation() }
                    expected.assertLong("next_sequence") { model.nextSequence() }
                    expected.assertKeyValue("pending") { JsonArray(model.pending().map(::envelopeJson)) }
                    expected.assertKeyValue("inflight") { JsonArray(model.inflight().map(::envelopeJson)) }
                    expected.assertKeyValue("acked_through") { nullableLong(model.ackedThrough()) }
                    expected.assertKeyValue("retry") { retryJson(model.retry()) }
                    expected.assertKeySet("invalidates") { readerKinds }
                    expected.sub("invalidates") { invalidates ->
                        readerKinds.forEachIndexed { slot, kind ->
                            val invalidated = before[slot] && !after[slot]
                            invalidates.assertBoolean(kind) { invalidated }
                        }
                    }
                }
                materialize(model)
                executed++
            }
            check(executed == steps.size) {
                "egress/$name ($flavor): loaded ${steps.size} steps but executed $executed"
            }
            return executed
        }
    }

    private fun expectedStepTotal(): Int = fixtures.sumOf { fixture(it).getValue("steps").jsonArray.size }

    // -- The gates ---------------------------------------------------------

    @Test
    fun `corpus is present and non-trivial`() {
        ConformanceFixtures.requireRoot()
        for (name in fixtures) {
            assertTrue(Files.exists(ConformanceFixtures.path("egress/$name")), "missing canonical egress fixture $name")
        }
        // No step-count floor (#lzcorpusfloorguard): growth is closed by the
        // every-step-executed equality below, shrinkage by lazily-spec's
        // corpus-counts.json + check-corpus-floors.mjs.
        assertTrue(expectedStepTotal() > 0, "the egress corpus declares no steps at all")
    }

    @Test
    fun `every flavor replays the whole egress corpus`() {
        val total = expectedStepTotal()
        for (flavor in Flavor.entries) {
            val steps = fixtures.sumOf { replay(it, flavor) }
            assertTrue(steps > 0, "$flavor replayed zero egress steps")
            assertEquals(total, steps, "every corpus step must run against $flavor")
        }
    }

    /** The ledger cannot rot: the filesystem enforces it, in both directions. */
    @Test
    fun unshippedFlavorsAreReallyAbsent() {
        val sources = StringBuilder()
        Files.walk(Path.of("src/main/kotlin")).use { stream ->
            stream
                .filter { it.toString().endsWith(".kt") }
                .forEach { sources.append(Files.readString(it)).append('\n') }
        }
        val text = sources.toString()
        for (row in ledger) {
            val defined = text.contains("class ${row.typeName}<")
            assertEquals(
                row.shipped,
                defined,
                "ledger row `${row.flavor}` claims shipped=${row.shipped} but `class ${row.typeName}` " +
                    "defined=$defined; fix the ledger or extend EgressFamilyConformanceTest",
            )
        }
        assertEquals(Flavor.entries.size, ledger.size, "one runner flavor per ledger row")
    }

    /**
     * The corpus asserts negative invalidation, so the probe itself must be able to
     * fail: reading warms every reader, an enqueue clears `pending` only.
     */
    @Test
    fun `the invalidation probe discriminates on every flavor`() {
        for (flavor in Flavor.entries) {
            model(flavor, 1, EgressPolicy()).use {
                materialize(it)
                assertEquals(listOf(true, true, true, true), it.validity(), "$flavor: reading warms every reader")
                it.enqueue(1)
                assertEquals(listOf(false, true, true, true), it.validity(), "$flavor: enqueue clears pending only")
                materialize(it)
                it.claim(99)
                assertEquals(
                    listOf(true, true, true, true),
                    it.validity(),
                    "$flavor: a stale-generation claim invalidates nothing",
                )
                assertFalse(it.pending().isEmpty(), "$flavor: the stale claim left pending intact")
            }
        }
    }
}
