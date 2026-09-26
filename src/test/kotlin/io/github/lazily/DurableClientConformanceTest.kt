package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableClientConformanceTest {
    @Test
    fun `replays canonical durable client corpus`() {
        val root = Json.parseToJsonElement(ConformanceFixtures.read("durable-client/envelope_v1.json")).jsonObject
        assertFalse(root.getValue("owner_authority").jsonPrimitive.boolean)

        root.getValue("envelope_vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val envelope = envelopeFrom(vector.getValue("envelope").jsonObject)
            val validation = envelope.validate()
            vector.getValue("expected").jsonObject.consuming(
                "durable-client/envelope_v1.json envelope expected",
            ) { expected ->
                expected.assertString("reason") { validationName(validation) }
                expected.assertBoolean("accepted") { validation == EnvelopeValidation.Accepted }
                expected.assertBoolean("payload_decoded") { validation == EnvelopeValidation.Accepted }
            }
        }

        root.getValue("ordering_vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val order = DurableObservationOrder()
            vector.getValue("observed_message_ids").jsonArray.forEach {
                order.observe(
                    DurableEnvelope(
                        messageId = it.jsonPrimitive.content,
                        schemaVersion = 1,
                        codecVersion = 1,
                        payload = byteArrayOf(),
                    ),
                )
            }
            assertContentEquals(vector.getValue("expected_delivery_order").jsonArray.map { it.jsonPrimitive.content }, order.messageIds)
            assertEquals(vector.getValue("owner_order_inferred").jsonPrimitive.boolean, order.ownerOrderInferred)
        }

        root.getValue("projection_ordering_vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val order = AdvisoryProjectionOrder()
            val classifications = vector.getValue("observed_source_positions").jsonArray.map {
                val position = it.jsonPrimitive.long
                order.observe(position, "source-$position").name.lowercase()
            }
            assertContentEquals(vector.getValue("expected_delivery_classification").jsonArray.map { it.jsonPrimitive.content }, classifications)
            assertContentEquals(vector.getValue("expected_applied_positions").jsonArray.map { it.jsonPrimitive.long }, order.appliedPositions)
            assertEquals(vector.getValue("broker_order_authoritative").jsonPrimitive.boolean, order.brokerOrderAuthoritative)
            assertEquals(vector.getValue("may_authorize_transition").jsonPrimitive.boolean, order.mayAuthorizeTransition)
        }

        root.getValue("dedup_vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val dedup = DurableDeduplicator()
            val actual = vector.getValue("deliveries").jsonArray.map {
                dedup.classify(envelopeFrom(it.jsonObject)).name.lowercase()
            }
            assertContentEquals(vector.getValue("expected_classification").jsonArray.map { it.jsonPrimitive.content }, actual)
        }

        root.getValue("receipt_vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val receipt = receiptFrom(vector.getValue("receipt").jsonObject)
            assertEquals(receiptFrom(vector.getValue("expected_round_trip").jsonObject), receipt)
            assertEquals(
                vector.getValue("transport_ack_equivalent").jsonPrimitive.boolean,
                receipt.transportAckEquivalent,
            )
        }

        root.getValue("projection_fingerprint_vectors").jsonArray.forEach { element ->
            val vector = element.jsonObject
            val left = fingerprintFrom(vector.getValue("left").jsonObject)
            val right = fingerprintFrom(vector.getValue("right").jsonObject)
            vector.getValue("expected").jsonObject.consuming(
                "durable-client/envelope_v1.json projection fingerprint expected",
            ) { expected ->
                expected.assertBoolean("same_source") { left.sourcePosition == right.sourcePosition }
                expected.assertBoolean("same_fingerprint") { left.fingerprint == right.fingerprint }
                expected.assertBoolean("same_completeness") { left.completeness == right.completeness }
                expected.assertBoolean("equivalent") { left.equivalentTo(right) }
            }
            assertFalse(left.mayAuthorizeTransition || right.mayAuthorizeTransition)
        }
    }

    @Test
    fun `envelope order dedup receipt fingerprint and tiers`() {
        val tiers = DurableTierDeclaration()
        assertTrue(tiers.core && tiers.client)
        assertFalse(tiers.durableHost || tiers.distributedHost || tiers.acceleratedHost)
        val first = envelope(byteArrayOf(65))
        assertEquals(EnvelopeValidation.Accepted, first.validate())
        assertEquals(EnvelopeValidation.UnsupportedProtocolVersion, first.copy(protocolVersion = 2).validate())
        assertEquals(EnvelopeValidation.InvalidMessageId, first.copy(messageId = "").validate())

        val dedup = DurableDeduplicator()
        assertEquals(DeliveryClassification.First, dedup.classify(first))
        assertEquals(DeliveryClassification.Duplicate, dedup.classify(envelope(byteArrayOf(65))))
        assertEquals(DeliveryClassification.Conflict, dedup.classify(envelope(byteArrayOf(66))))

        val order = DurableObservationOrder()
        listOf("message-2", "message-1", "message-2").forEach {
            order.observe(envelope(byteArrayOf(), "sample-owner/$it"))
        }
        assertContentEquals(listOf("sample-owner/message-2", "sample-owner/message-1", "sample-owner/message-2"), order.messageIds)
        assertFalse(order.ownerOrderInferred)

        val receipt = DurableHostReceipt(
            receiptId = "receipt-1",
            messageId = first.messageId,
            outcome = DurableReceiptOutcome.Committed,
            ownerPosition = 42,
        )
        assertFalse(receipt.transportAckEquivalent)
        val left = ProjectionFingerprint("orders", 42, "aabbccdd")
        assertTrue(left.equivalentTo(ProjectionFingerprint("orders", 42, "aabbccdd")))
        assertFalse(left.equivalentTo(ProjectionFingerprint("orders", 41, "aabbccdd")))
        assertFalse(left.equivalentTo(ProjectionFingerprint("orders", 42, "aabbccdd", ProjectionCompleteness.LatestStateOnly)))
        assertFalse(left.mayAuthorizeTransition)

        val projections = AdvisoryProjectionOrder()
        assertEquals(ProjectionDeliveryClassification.Buffered, projections.observe(2, "two"))
        assertEquals(ProjectionDeliveryClassification.Applied, projections.observe(1, "one"))
        assertEquals(ProjectionDeliveryClassification.Duplicate, projections.observe(2, "two"))
        assertContentEquals(listOf(1L, 2L), projections.appliedPositions)
        assertFalse(projections.brokerOrderAuthoritative)
        assertFalse(projections.mayAuthorizeTransition)
    }

    @Test
    fun `unknown protocol fails before decode and PubAck stays separate`() {
        var decoded = false
        val client = DurableClient<String, String>(FakeTransport(), String::encodeToByteArray) {
            decoded = true
            it.decodeToString()
        }
        assertEquals(
            EnvelopeValidation.UnsupportedProtocolVersion,
            client.decode(envelope(byteArrayOf(255.toByte())).copy(protocolVersion = 2)).validation,
        )
        assertFalse(decoded)
        assertEquals(9, client.publish("owners.commands", "message-1", 7, 11, "go").sequence)
        assertFalse(client.mayAuthorizeTransition)
    }

    private fun envelope(payload: ByteArray, messageId: String = "sample-owner/message-4") =
        DurableEnvelope(messageId = messageId, schemaVersion = 7, codecVersion = 11, payload = payload)

    private fun envelopeFrom(value: kotlinx.serialization.json.JsonObject) = DurableEnvelope(
        protocolVersion = value.getValue("protocol_version").jsonPrimitive.long,
        messageId = value.getValue("message_id").jsonPrimitive.content,
        schemaVersion = value.getValue("schema_version").jsonPrimitive.long,
        codecVersion = value.getValue("codec_version").jsonPrimitive.long,
        payload = value.getValue("payload").jsonArray.map { it.jsonPrimitive.long.toByte() }.toByteArray(),
    )

    private fun validationName(value: EnvelopeValidation) =
        when (value) {
            EnvelopeValidation.Accepted -> "accepted"
            EnvelopeValidation.UnsupportedProtocolVersion -> "unsupported_protocol_version"
            EnvelopeValidation.InvalidMessageId -> "invalid_message_id"
            EnvelopeValidation.InvalidSchemaVersion -> "invalid_schema_version"
            EnvelopeValidation.InvalidCodecVersion -> "invalid_codec_version"
        }

    private fun receiptFrom(value: kotlinx.serialization.json.JsonObject) = DurableHostReceipt(
        protocolVersion = value.getValue("protocol_version").jsonPrimitive.long,
        receiptId = value.getValue("receipt_id").jsonPrimitive.content,
        messageId = value.getValue("message_id").jsonPrimitive.content,
        outcome = DurableReceiptOutcome.valueOf(value.getValue("outcome").jsonPrimitive.content.replaceFirstChar(Char::uppercase)),
        ownerPosition = value.getValue("owner_position").jsonPrimitive.long,
    )

    private fun fingerprintFrom(value: kotlinx.serialization.json.JsonObject) = ProjectionFingerprint(
        projectionId = value.getValue("projection_id").jsonPrimitive.content,
        sourcePosition = value.getValue("source_position").jsonPrimitive.long,
        fingerprint = value.getValue("fingerprint").jsonPrimitive.content,
        completeness = if (value.getValue("completeness").jsonPrimitive.content == "complete_history") {
            ProjectionCompleteness.CompleteHistory
        } else {
            ProjectionCompleteness.LatestStateOnly
        },
    )

    private class FakeTransport : NatsDurableClientTransport {
        override fun publish(subject: String, envelope: DurableEnvelope) = BrokerPubAck("OWNER", 9)
        override fun subscribe(subject: String, handler: (DurableEnvelope) -> Unit) = AutoCloseable {}
    }
}
