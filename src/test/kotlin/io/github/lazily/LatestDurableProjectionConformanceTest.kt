package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Replays the canonical latest-durable projection trace from lazily-spec v0.38.0. */
class LatestDurableProjectionConformanceTest {
    private val fixture: JsonObject =
        Json.parseToJsonElement(
            ConformanceFixtures.read("egress/latest_durable_projection.json"),
        ).jsonObject

    @Test
    fun `canonical latest durable projection trace replays exactly`() {
        assertEquals("LatestDurableProjection", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("LatestDurableProjectionCore", fixture.getValue("model").jsonPrimitive.content)
        var replayed = 0
        for (scenarioObject in ConformanceScenarios.of("egress/latest_durable_projection.json", fixture)) {
            val core = LatestDurableProjectionCore<String, String>(scenarioObject.getValue("generation").jsonPrimitive.long)
            for (step in scenarioObject.getValue("steps").jsonArray) {
                val stepObject = step.jsonObject
                val op = stepObject.getValue("op").jsonObject
                val returned = stepObject.getValue("returns").jsonObject
                assertOutcome(core, op, returned)
                assertState(core, stepObject.getValue("expected").jsonObject)
                replayed++
            }
        }
        assertEquals(22, replayed, "the canonical corpus grew; extend the replay instead of silently skipping steps")
    }

    private fun assertOutcome(
        core: LatestDurableProjectionCore<String, String>,
        op: JsonObject,
        expected: JsonObject,
    ) {
        val key = op["key"]?.jsonPrimitive?.content
        val epoch = op["epoch"]?.jsonPrimitive?.long
        val generation = op["generation"]?.jsonPrimitive?.long
        when (op.getValue("type").jsonPrimitive.content) {
            "upsert_desired" -> {
                val actual = core.upsertDesired(key!!, epoch!!, op.getValue("value").jsonPrimitive.content)
                assertEquals(expected.getValue("upsert").jsonPrimitive.content, upsertName(actual))
                when (actual) {
                    is LatestDurableUpsert.AlreadyDurable -> assertEquals(expected.getValue("durable_through").jsonPrimitive.long, actual.durableThrough)
                    is LatestDurableUpsert.StaleEpoch -> assertEquals(expected.getValue("current").jsonPrimitive.long, actual.current)
                    else -> Unit
                }
            }
            "claim" -> {
                val actual = core.claim(key!!, generation!!)
                assertEquals(expected.getValue("claim").jsonPrimitive.content, claimName(actual))
                if (actual is LatestDurableClaim.Claimed) assertEnvelope(actual.envelope, expected.getValue("envelope").jsonObject)
                if (actual is LatestDurableClaim.StaleGeneration) assertEquals(expected.getValue("current").jsonPrimitive.long, actual.current)
            }
            "ack_applied" -> {
                val actual = core.ackApplied(key!!, generation!!, epoch!!)
                assertEquals(expected.getValue("ack").jsonPrimitive.content, ackName(actual))
                when (actual) {
                    is LatestDurableAck.Advanced -> assertEquals(expected.getValue("durable_through").jsonPrimitive.long, actual.durableThrough)
                    is LatestDurableAck.Unchanged -> assertEquals(expected.getValue("durable_through").jsonPrimitive.long, actual.durableThrough)
                    is LatestDurableAck.StaleGeneration -> assertEquals(expected.getValue("current").jsonPrimitive.long, actual.current)
                    LatestDurableAck.UnknownEpoch -> Unit
                }
            }
            "fail_retryable" -> {
                val actual = core.failRetryable(key!!, generation!!, epoch!!)
                assertEquals(expected.getValue("failure").jsonPrimitive.content, failureName(actual))
                if (actual is LatestDurableFailure.StaleGeneration) assertEquals(expected.getValue("current").jsonPrimitive.long, actual.current)
            }
            "reconnect" -> {
                val actual = core.reconnect(generation!!)
                assertEquals(expected.getValue("reconnect").jsonPrimitive.content, reconnectName(actual))
                when (actual) {
                    is LatestDurableReconnect.Advanced -> {
                        assertEquals(expected.getValue("generation").jsonPrimitive.long, actual.generation)
                        assertEquals(expected.getValue("requeued").jsonPrimitive.long.toInt(), actual.requeued)
                        assertEquals(expected.getValue("superseded").jsonPrimitive.long.toInt(), actual.superseded)
                    }
                    is LatestDurableReconnect.Unchanged -> assertEquals(expected.getValue("generation").jsonPrimitive.long, actual.generation)
                    is LatestDurableReconnect.StaleGeneration -> assertEquals(expected.getValue("current").jsonPrimitive.long, actual.current)
                }
            }
            else -> error("unknown latest-durable operation: ${op.getValue("type")}")
        }
    }

    private fun assertState(
        core: LatestDurableProjectionCore<String, String>,
        expected: JsonObject,
    ) {
        assertEquals(expected.getValue("generation").jsonPrimitive.long, core.generation)
        val entries = expected.getValue("entries").jsonArray
        assertEquals(entries.size, core.knownKeys().size)
        for (element in entries) {
            val want = element.jsonObject
            val key = want.getValue("key").jsonPrimitive.content
            val got = core.snapshot(key)
            val desired = want.getValue("desired")
            if (desired is JsonNull) {
                assertNull(got.desired)
            } else {
                val desiredObject = desired.jsonObject
                assertEquals(desiredObject.getValue("epoch").jsonPrimitive.long, got.desired?.epoch)
                assertEquals(desiredObject.getValue("value").jsonPrimitive.content, got.desired?.value)
            }
            val inflight = want.getValue("inflight")
            if (inflight is JsonNull) {
                assertNull(got.inflight)
            } else {
                assertEnvelope(requireNotNull(got.inflight), inflight.jsonObject)
            }
            assertEquals(want.getValue("durable_through").jsonPrimitive.contentOrNull?.toLong(), got.durableThrough)
        }
    }

    private fun assertEnvelope(got: LatestDurableEnvelope<String, String>, want: JsonObject) {
        assertEquals(want.getValue("generation").jsonPrimitive.long, got.generation)
        assertEquals(want.getValue("key").jsonPrimitive.content, got.key)
        assertEquals(want.getValue("epoch").jsonPrimitive.long, got.epoch)
        assertEquals(want.getValue("value").jsonPrimitive.content, got.value)
    }

    private fun upsertName(value: LatestDurableUpsert) = when (value) {
        LatestDurableUpsert.Accepted -> "accepted"
        LatestDurableUpsert.Unchanged -> "unchanged"
        is LatestDurableUpsert.AlreadyDurable -> "already_durable"
        is LatestDurableUpsert.StaleEpoch -> "stale_epoch"
        LatestDurableUpsert.EpochConflict -> "epoch_conflict"
    }

    private fun claimName(value: LatestDurableClaim<*, *>) = when (value) {
        is LatestDurableClaim.Claimed -> "claimed"
        LatestDurableClaim.Empty -> "empty"
        LatestDurableClaim.Busy -> "busy"
        is LatestDurableClaim.StaleGeneration -> "stale_generation"
    }

    private fun ackName(value: LatestDurableAck) = when (value) {
        is LatestDurableAck.Advanced -> "advanced"
        is LatestDurableAck.Unchanged -> "unchanged"
        LatestDurableAck.UnknownEpoch -> "unknown_epoch"
        is LatestDurableAck.StaleGeneration -> "stale_generation"
    }

    private fun failureName(value: LatestDurableFailure) = when (value) {
        LatestDurableFailure.Pending -> "pending"
        LatestDurableFailure.Superseded -> "superseded"
        LatestDurableFailure.UnknownEpoch -> "unknown_epoch"
        is LatestDurableFailure.StaleGeneration -> "stale_generation"
    }

    private fun reconnectName(value: LatestDurableReconnect) = when (value) {
        is LatestDurableReconnect.Advanced -> "advanced"
        is LatestDurableReconnect.Unchanged -> "unchanged"
        is LatestDurableReconnect.StaleGeneration -> "stale_generation"
    }

    @Test
    fun `all three reactive families expose the core lifecycle`() {
        val syncContext = Context()
        val sync = LatestDurableProjection<String, String>(syncContext, 1)
        val syncEntry = sync.entry("doc")
        assertNull(syncContext.get(syncEntry).desired)
        sync.upsertDesired("doc", 1, "A")
        assertEquals("A", syncContext.get(syncEntry).desired?.value)

        val threadContext = ThreadSafeContext()
        val threadSafe = ThreadSafeLatestDurableProjection<String, String>(threadContext, 1)
        val threadEntry = threadSafe.entry("doc")
        threadSafe.upsertDesired("doc", 1, "A")
        assertEquals("A", threadContext.get(threadEntry).desired?.value)

        val asyncContext = AsyncContext()
        val async = AsyncLatestDurableProjection<String, String>(asyncContext, 1)
        val asyncEntry = async.entry("doc")
        async.upsertDesired("doc", 1, "A")
        assertEquals("A", asyncContext.get(asyncEntry)?.desired?.value)
        asyncContext.close()
    }
}
