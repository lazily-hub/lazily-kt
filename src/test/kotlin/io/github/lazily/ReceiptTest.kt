package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptTest {
    @Test
    fun `outcome terminality is explicit`() {
        assertFalse(ReceiptOutcome.Observed.isTerminal)
        assertFalse(ReceiptOutcome.Accepted.isTerminal)
        assertTrue(ReceiptOutcome.Applied.isTerminal)
        assertTrue(ReceiptOutcome.Rejected.isTerminal)
    }

    @Test
    fun `receipt message round trips through JSON`() {
        val message = ReceiptMessage.ofCausalReceipts(
            CausalReceipts(
                listOf(
                    CausalReceipt.observed("receipt-observed", "patch-123", "editor", 7),
                    CausalReceipt.applied(
                        "receipt-applied",
                        "patch-123",
                        "editor",
                        7,
                        payloadHash = "sha256:abc",
                    ),
                )
            )
        )

        val decoded = ReceiptMessage.decodeJson(message.encodeJson())
        assertEquals(message, decoded)
        val batch = assertIs<ReceiptMessage.CausalReceiptsMessage>(decoded).batch
        assertEquals(ReceiptOutcome.Applied, batch.receipts.last().outcome)
    }

    @Test
    fun `projection records terminal and ignores stale generation`() {
        val projection = ReceiptProjection()

        assertEquals(
            ReceiptApplyStatus.Recorded,
            projection.observe(
                7,
                CausalReceipt.observed("receipt-observed", "patch-123", "editor", 7),
            ),
        )
        assertEquals(
            ReceiptApplyStatus.StaleGeneration(expected = 7, actual = 6),
            projection.observe(
                7,
                CausalReceipt.rejected(
                    "receipt-stale",
                    "patch-123",
                    "editor",
                    6,
                    reason = "stale generation",
                ),
            ),
        )
        assertEquals(
            ReceiptApplyStatus.Recorded,
            projection.observe(
                7,
                CausalReceipt.applied(
                    "receipt-applied",
                    "patch-123",
                    "editor",
                    7,
                    payloadHash = "sha256:abc",
                ),
            ),
        )

        assertEquals(ReceiptOutcome.Applied, projection.terminalFor("patch-123")?.outcome)
        assertEquals(listOf("receipt-stale"), projection.staleReceiptIds())
        assertTrue(projection.containsReceipt("receipt-stale"))
    }

    @Test
    fun `duplicate and terminal conflict are no-ops`() {
        val projection = ReceiptProjection()
        val applied = CausalReceipt.applied("receipt-applied", "patch-123", "editor", 7)

        assertEquals(ReceiptApplyStatus.Recorded, projection.observe(7, applied))
        assertEquals(ReceiptApplyStatus.Duplicate, projection.observe(7, applied))
        assertEquals(
            ReceiptApplyStatus.TerminalConflict(
                causationId = "patch-123",
                existing = ReceiptOutcome.Applied,
                incoming = ReceiptOutcome.Rejected,
            ),
            projection.observe(
                7,
                CausalReceipt.rejected("receipt-rejected", "patch-123", "editor", 7),
            ),
        )
        assertFalse(projection.containsReceipt("receipt-rejected"))
    }

    @Test
    fun `shared causal receipt conformance fixture replays`() {
        val fixture = Json.parseToJsonElement(
            ConformanceFixtures.read("receipts/causal_receipts.json"),
        ).jsonObject
        val message = ReceiptMessage.fromJson(fixture.getValue("wire"))
        val receipts = assertIs<ReceiptMessage.CausalReceiptsMessage>(message).batch.receipts
        val assertions = fixture.getValue("assertions").jsonObject
        val currentGeneration = assertions.getValue("current_generation").jsonPrimitive.content.toLong()
        val projection = ReceiptProjection()

        receipts.forEach { projection.observe(currentGeneration, it) }

        assertEquals(
            assertions.getValue("receipt_count").jsonPrimitive.content.toInt(),
            receipts.size,
        )
        val causationId = assertions.getValue("causation_id").jsonPrimitive.content
        assertEquals(
            assertions.getValue("terminal_outcome").jsonPrimitive.content,
            projection.terminalFor(causationId)?.outcome?.wireName,
        )
        assertEquals(
            assertions.getValue("stale_receipt_ids").jsonArray.map { it.jsonPrimitive.content },
            projection.staleReceiptIds(),
        )
        assertNull(projection.terminalFor("missing"))
    }
}
