package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Canonical competing-consumer delivery lifecycle (`#lzworkqueue`). */
class WorkQueueConformanceTest {
    private fun loadFixture(name: String): JsonObject {
        val specPath = Path.of("../lazily-spec/conformance/collections/$name")
        val text =
            if (Files.exists(specPath)) {
                Files.readString(specPath)
            } else {
                val resource = javaClass.getResource("/conformance/collections/$name")
                    ?: error("missing conformance fixture: $name")
                resource.readText()
            }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun assertDelivery(actual: WorkQueueDelivery<String>, expected: JsonObject) {
        assertEquals(expected.getValue("delivery_id").jsonPrimitive.long, actual.deliveryId)
        assertEquals(expected.getValue("item_id").jsonPrimitive.long, actual.itemId)
        assertEquals(expected.getValue("value").jsonPrimitive.content, actual.value)
        assertEquals(expected.getValue("worker").jsonPrimitive.content, actual.worker)
        assertEquals(expected.getValue("attempt").jsonPrimitive.int, actual.attempt)
        assertEquals(expected.getValue("deadline").jsonPrimitive.long, actual.deadline)
    }

    private fun assertInvalidations(ctx: Context, queue: WorkQueueCell<String>, expected: JsonObject) {
        val invalidates = expected.getValue("invalidates").jsonObject
        assertEquals(invalidates.getValue("pending_len").jsonPrimitive.boolean, !ctx.isSet(queue.readers.pendingLen))
        assertEquals(invalidates.getValue("is_empty").jsonPrimitive.boolean, !ctx.isSet(queue.readers.isEmpty))
        assertEquals(invalidates.getValue("in_flight_len").jsonPrimitive.boolean, !ctx.isSet(queue.readers.inFlightLen))
        assertEquals(invalidates.getValue("dead_letter_len").jsonPrimitive.boolean, !ctx.isSet(queue.readers.deadLetterLen))
    }

    private fun assertState(queue: WorkQueueCell<String>, expected: JsonObject) {
        val expectedPending = expected.getValue("pending").jsonArray
        assertEquals(expectedPending.size, queue.pendingItems().size)
        queue.pendingItems().zip(expectedPending).forEach { (actual, raw) ->
            val item = raw.jsonObject
            assertEquals(item.getValue("item_id").jsonPrimitive.long, actual.itemId)
            assertEquals(item.getValue("value").jsonPrimitive.content, actual.value)
            assertEquals(item.getValue("attempts").jsonPrimitive.int, actual.attempts)
        }

        val expectedInFlight = expected.getValue("in_flight").jsonArray
        assertEquals(expectedInFlight.size, queue.inFlightDeliveries().size)
        queue.inFlightDeliveries().zip(expectedInFlight).forEach { (actual, raw) ->
            assertDelivery(actual, raw.jsonObject)
        }

        val expectedDeadLetters = expected.getValue("dead_letters").jsonArray
        assertEquals(expectedDeadLetters.size, queue.deadLetterItems().size)
        queue.deadLetterItems().zip(expectedDeadLetters).forEach { (actual, raw) ->
            val dead = raw.jsonObject
            assertEquals(dead.getValue("item_id").jsonPrimitive.long, actual.itemId)
            assertEquals(dead.getValue("value").jsonPrimitive.content, actual.value)
            assertEquals(dead.getValue("attempts").jsonPrimitive.int, actual.attempts)
            val reason = when (actual.reason) {
                WorkQueueDeadLetterReason.Nack -> "nack"
                WorkQueueDeadLetterReason.Expired -> "expired"
            }
            assertEquals(dead.getValue("reason").jsonPrimitive.content, reason)
        }

        val reads = expected.getValue("reads").jsonObject
        assertEquals(reads.getValue("pending_len").jsonPrimitive.int, queue.pendingLen())
        assertEquals(reads.getValue("is_empty").jsonPrimitive.boolean, queue.isEmpty())
        assertEquals(reads.getValue("in_flight_len").jsonPrimitive.int, queue.inFlightLen())
        assertEquals(reads.getValue("dead_letter_len").jsonPrimitive.int, queue.deadLetterLen())
    }

    private fun runFixture(name: String) {
        val fixture = loadFixture(name)
        val config = fixture.getValue("config").jsonObject
        val ctx = Context()
        val queue =
            WorkQueueCell<String>(
                ctx,
                visibilityTimeout = config.getValue("visibility_timeout").jsonPrimitive.long,
                maxDeliveries = config.getValue("max_deliveries").jsonPrimitive.int,
            )

        fixture.getValue("steps").jsonArray.forEach { rawStep ->
            val step = rawStep.jsonObject
            val op = step.getValue("op").jsonObject
            // Materialize every reader before mutation so isSet observes exact invalidation.
            queue.pendingLen()
            queue.isEmpty()
            queue.inFlightLen()
            queue.deadLetterLen()

            when (op.getValue("type").jsonPrimitive.content) {
                "push" -> {
                    val actual = queue.push(op.getValue("value").jsonPrimitive.content)
                    assertEquals(step.getValue("returns").jsonPrimitive.long, actual)
                }
                "claim" -> {
                    val actual =
                        queue.claim(
                            op.getValue("worker").jsonPrimitive.content,
                            op.getValue("now").jsonPrimitive.long,
                        )
                    val expected = step.getValue("returns")
                    if (expected is JsonNull) {
                        assertNull(actual)
                    } else {
                        assertDelivery(requireNotNull(actual), expected.jsonObject)
                    }
                }
                "ack" -> {
                    val actual =
                        queue.ack(
                            op.getValue("worker").jsonPrimitive.content,
                            op.getValue("delivery_id").jsonPrimitive.long,
                        )
                    assertEquals(step.getValue("returns").jsonPrimitive.boolean, actual)
                }
                "nack" -> {
                    val actual =
                        queue.nack(
                            op.getValue("worker").jsonPrimitive.content,
                            op.getValue("delivery_id").jsonPrimitive.long,
                        )
                    assertEquals(step.getValue("returns").jsonPrimitive.boolean, actual)
                }
                "reap_expired" -> {
                    val actual = queue.reapExpired(op.getValue("now").jsonPrimitive.long)
                    assertEquals(step.getValue("returns").jsonPrimitive.int, actual)
                }
                else -> error("unknown WorkQueueCell op")
            }

            val expected = step.getValue("expected").jsonObject
            assertInvalidations(ctx, queue, expected)
            assertState(queue, expected)
        }
    }

    @Test
    fun competingDeliveryFixture() = runFixture("workqueue_competing_delivery.json")

    @Test
    fun leaseDeadLetterFixture() = runFixture("workqueue_lease_deadletter.json")
}
