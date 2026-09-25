package io.github.lazily

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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canonical competing-consumer delivery lifecycle (`#lzworkqueue`). */
class WorkQueueConformanceTest {
    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("collections/$name")
        return Json.parseToJsonElement(text).jsonObject
    }

    /**
     * Every field the corpus spells for a delivery, so an added one cannot be
     * ignored (`#lzsiblingrunnermasking`).
     *
     * Six `getValue` reads catch a field the corpus DROPS and are blind to one it
     * ADDS. QueueFamilyConformanceTest compares the whole JSON object against a
     * `deliveryJson(got)` projection, which catches both — so over these same two
     * fixtures the extra-field half of the contract was asserted only by the
     * family runner. The key-set equality is this runner's half of that, without
     * duplicating the projection.
     */
    private val deliveryFields =
        setOf("delivery_id", "item_id", "value", "worker", "attempt", "deadline")

    private fun assertDelivery(
        actual: WorkQueueDelivery<String>,
        expected: JsonObject,
    ) {
        assertEquals(deliveryFields, expected.keys, "delivery field set")
        assertEquals(expected.getValue("delivery_id").jsonPrimitive.long, actual.deliveryId)
        assertEquals(expected.getValue("item_id").jsonPrimitive.long, actual.itemId)
        assertEquals(expected.getValue("value").jsonPrimitive.content, actual.value)
        assertEquals(expected.getValue("worker").jsonPrimitive.content, actual.worker)
        assertEquals(expected.getValue("attempt").jsonPrimitive.int, actual.attempt)
        assertEquals(expected.getValue("deadline").jsonPrimitive.long, actual.deadline)
    }

    /**
     * The reader kinds the WorkQueueCell matrix is made of.
     *
     * Asserted as a SET before the four reads below (`#lzsiblingrunnermasking`).
     * `getValue` per known name catches a kind the corpus drops and silently
     * ignores one it adds or renames; QueueFamilyConformanceTest iterates the
     * fixture's own keys and resolves each through a reader map that THROWS on an
     * unknown kind, so it catches the add and is blind to the drop. Each runner
     * covered exactly the half the other missed, over the same two fixtures —
     * which is coverage by accident of which runners exist, not by assertion.
     * Both halves now hold in both runners.
     */
    private val invalidationKinds =
        setOf("pending_len", "is_empty", "in_flight_len", "dead_letter_len")

    private fun assertInvalidations(
        ctx: Context,
        queue: WorkQueueCell<String>,
        invalidates: AssertionKeys,
    ) {
        assertEquals(invalidationKinds, invalidates.keys, "expected.invalidates reader kinds")
        invalidates.assertBoolean("pending_len") { !ctx.isSet(queue.readers.pendingLen) }
        invalidates.assertBoolean("is_empty") { !ctx.isSet(queue.readers.isEmpty) }
        invalidates.assertBoolean("in_flight_len") { !ctx.isSet(queue.readers.inFlightLen) }
        invalidates.assertBoolean("dead_letter_len") { !ctx.isSet(queue.readers.deadLetterLen) }
    }

    private fun assertState(
        queue: WorkQueueCell<String>,
        expected: AssertionKeys,
    ) {
        expected.assertKeyWith("pending") { rawPending ->
            val expectedPending = rawPending.jsonArray
            assertEquals(expectedPending.size, queue.pendingItems().size)
            queue.pendingItems().zip(expectedPending).forEach { (actual, raw) ->
                val item = raw.jsonObject
                assertEquals(setOf("item_id", "value", "attempts"), item.keys)
                assertEquals(item.getValue("item_id").jsonPrimitive.long, actual.itemId)
                assertEquals(item.getValue("value").jsonPrimitive.content, actual.value)
                assertEquals(item.getValue("attempts").jsonPrimitive.int, actual.attempts)
            }
        }

        expected.assertKeyWith("in_flight") { rawInFlight ->
            val expectedInFlight = rawInFlight.jsonArray
            assertEquals(expectedInFlight.size, queue.inFlightDeliveries().size)
            queue.inFlightDeliveries().zip(expectedInFlight).forEach { (actual, raw) ->
                assertDelivery(actual, raw.jsonObject)
            }
        }

        expected.assertKeyWith("dead_letters") { rawDeadLetters ->
            val expectedDeadLetters = rawDeadLetters.jsonArray
            assertEquals(expectedDeadLetters.size, queue.deadLetterItems().size)
            queue.deadLetterItems().zip(expectedDeadLetters).forEach { (actual, raw) ->
                val dead = raw.jsonObject
                assertEquals(setOf("item_id", "value", "attempts", "reason"), dead.keys)
                assertEquals(dead.getValue("item_id").jsonPrimitive.long, actual.itemId)
                assertEquals(dead.getValue("value").jsonPrimitive.content, actual.value)
                assertEquals(dead.getValue("attempts").jsonPrimitive.int, actual.attempts)
                val reason =
                    when (actual.reason) {
                        WorkQueueDeadLetterReason.Nack -> "nack"
                        WorkQueueDeadLetterReason.Expired -> "expired"
                    }
                assertEquals(dead.getValue("reason").jsonPrimitive.content, reason)
            }
        }

        expected.sub("reads") { reads ->
            reads.assertInt("pending_len") { queue.pendingLen() }
            reads.assertBoolean("is_empty") { queue.isEmpty() }
            reads.assertInt("in_flight_len") { queue.inFlightLen() }
            reads.assertInt("dead_letter_len") { queue.deadLetterLen() }
        }
    }

    private fun runFixture(name: String) {
        val fixturePath = "collections/$name"
        val fixture = loadFixture(name)
        val declaredSites = ConformanceFixtures.blockSitesOf(fixturePath, fixture).keys
        val visitedSites = linkedSetOf<String>()
        val initial = fixture.getValue("initial").jsonObject
        val ctx = Context()
        val queue =
            WorkQueueCell<String>(
                ctx,
                visibilityTimeout = initial.getValue("visibility_timeout").jsonPrimitive.long,
                maxDeliveries = initial.getValue("max_deliveries").jsonPrimitive.int,
            )

        val steps = fixture.getValue("steps").jsonArray
        assertTrue(steps.isNotEmpty(), "$name declares no steps — a zero-step replay is not a pass")
        var executed = 0
        steps.forEachIndexed { index, rawStep ->
            val step = rawStep.jsonObject
            // The matrix lives under `expected.invalidates`. lazily-rs read it off
            // the STEP, so its assertion never ran once; QueueFamilyConformanceTest
            // carries this guard for the same fixtures and this runner did not
            // (`#lzsiblingrunnermasking`).
            assertFalse(
                step.containsKey("invalidates"),
                "step $index spells `invalidates` on the STEP, not under `expected`",
            )
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
            val siteId = "$fixturePath|steps[$index].expected"
            check(visitedSites.add(siteId)) { "$siteId: sibling assertion block visited more than once" }
            val keys = AssertionKeys(siteId, expected, fixturePath, rungZeroBind = false)
            keys.sub("invalidates") { assertInvalidations(ctx, queue, it) }
            assertState(queue, keys)
            keys.requireAllSatisfied()
            executed++
        }
        assertEquals(steps.size, executed, "$name: loaded ${steps.size} steps but executed $executed")
        assertEquals(declaredSites, visitedSites, "$fixturePath: exact sibling assertion-block sites")
        assertEquals(
            if (name == "workqueue_competing_delivery.json") 10 else 8,
            visitedSites.size,
            "$fixturePath: sibling assertion-block site pin",
        )
    }

    @Test
    fun competingDeliveryFixture() = runFixture("workqueue_competing_delivery.json")

    @Test
    fun leaseDeadLetterFixture() = runFixture("workqueue_lease_deadletter.json")
}
