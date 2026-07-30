package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
        val projection = ReceiptProjection()

        fixture.getValue("assertions").jsonObject
            .consuming("receipts/causal_receipts.json assertions") { a ->
                val currentGeneration = a.long("current_generation")
                    ?: error("current_generation is required")
                receipts.forEach { projection.observe(currentGeneration, it) }

                a.assertInt("receipt_count") { receipts.size }
                // `current_generation` seeded the replay and was then discarded —
                // the fixture could name any generation and the run behaved the
                // same way (#lzconsumednotasserted). Its content is the split it
                // induces: a receipt stamped with it is recorded, one stamped with
                // anything else is stale.
                a.assertKeyWith("current_generation") { want ->
                    val gen = want.jsonPrimitive.long
                    assertEquals(
                        receipts.filter { it.generation != gen }.map { it.receiptId },
                        projection.staleReceiptIds(),
                        "current_generation: receipts off this generation are exactly the stale ones",
                    )
                }
                // `causation_id` selected the projection lookup below and was
                // otherwise unchecked: it is also a claim about the batch, namely
                // that every receipt in it causes the same patch.
                val causationId = a.string("causation_id") ?: error("causation_id is required")
                a.assertKeyWith("causation_id") { want ->
                    assertEquals(
                        listOf(want.jsonPrimitive.content),
                        receipts.map { it.causationId }.distinct(),
                        "causation_id",
                    )
                }
                a.assertString("terminal_outcome") {
                    projection.terminalFor(causationId)?.outcome?.wireName
                }
                a.assertStrings("stale_receipt_ids") { projection.staleReceiptIds() }
                // The non-terminal half of the outcome lattice. Carried by the
                // fixture and read by nothing until #lzassertunknownkeys: a
                // binding that classified `accepted` as terminal would satisfy
                // every other key here and still be wrong.
                a.assertKeyWith("nonterminal_outcomes") { el ->
                    val want = el.jsonArray.map { it.jsonPrimitive.content }
                    val got = receipts
                        .filterNot { it.outcome.isTerminal }
                        .map { it.outcome.wireName }
                        .distinct()
                    assertEquals(want, got, "nonterminal_outcomes")
                    for (name in want) {
                        assertFalse(
                            ReceiptOutcome.fromWire(name).isTerminal,
                            "nonterminal_outcomes: '$name' must not be terminal",
                        )
                    }
                }
            }
        assertNull(projection.terminalFor("missing"))
    }
}
