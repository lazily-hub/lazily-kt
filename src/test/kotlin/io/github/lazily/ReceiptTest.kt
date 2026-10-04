package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val message =
            ReceiptMessage.ofCausalReceipts(
                CausalReceipts(
                    listOf(
                        CausalReceipt.observed("receipt-observed", "patch-123", "editor", 7u),
                        CausalReceipt.applied(
                            "receipt-applied",
                            "patch-123",
                            "editor",
                            7u,
                            payloadHash = "sha256:abc",
                        ),
                    ),
                ),
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
                7u,
                CausalReceipt.observed("receipt-observed", "patch-123", "editor", 7u),
            ),
        )
        assertEquals(
            ReceiptApplyStatus.StaleGeneration(expected = 7u, actual = 6u),
            projection.observe(
                7u,
                CausalReceipt.rejected(
                    "receipt-stale",
                    "patch-123",
                    "editor",
                    6u,
                    reason = "stale generation",
                ),
            ),
        )
        assertEquals(
            ReceiptApplyStatus.Recorded,
            projection.observe(
                7u,
                CausalReceipt.applied(
                    "receipt-applied",
                    "patch-123",
                    "editor",
                    7u,
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
        val applied = CausalReceipt.applied("receipt-applied", "patch-123", "editor", 7u)

        assertEquals(ReceiptApplyStatus.Recorded, projection.observe(7u, applied))
        assertEquals(ReceiptApplyStatus.Duplicate, projection.observe(7u, applied))
        assertEquals(
            ReceiptApplyStatus.TerminalConflict(
                causationId = "patch-123",
                existing = ReceiptOutcome.Applied,
                incoming = ReceiptOutcome.Rejected,
            ),
            projection.observe(
                7u,
                CausalReceipt.rejected("receipt-rejected", "patch-123", "editor", 7u),
            ),
        )
        assertFalse(projection.containsReceipt("receipt-rejected"))
    }

    @Test
    fun `shared causal receipt conformance fixture replays`() {
        val fixture =
            Json
                .parseToJsonElement(
                    ConformanceFixtures.read("receipts/causal_receipts.json"),
                ).jsonObject
        val message = ReceiptMessage.fromJson(fixture.getValue("wire"))
        val receipts = assertIs<ReceiptMessage.CausalReceiptsMessage>(message).batch.receipts
        val projection = ReceiptProjection()

        fixture
            .getValue("assertions")
            .jsonObject
            .consuming("receipts/causal_receipts.json assertions") { a ->
                val currentGeneration =
                    a.long("current_generation")?.toULong()
                        ?: error("current_generation is required")
                receipts.forEach { projection.observe(currentGeneration, it) }

                a.assertInt("receipt_count") { receipts.size }
                // `current_generation` seeded the replay and was then discarded —
                // the fixture could name any generation and the run behaved the
                // same way (#lzconsumednotasserted). Its content is the split it
                // induces: a receipt stamped with it is recorded, one stamped with
                // anything else is stale.
                a.assertKeyWith("current_generation") { want ->
                    val gen = want.jsonPrimitive.long.toULong()
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
                    val got =
                        receipts
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

    private val validReceipt =
        """{"receipt_id":"r-1","causation_id":"c-1","observer":"editor","generation":7,""" +
            """"outcome":"applied","reason":null,"payload_hash":"sha256:abc"}"""

    private fun frame(receipt: String): String = """{"CausalReceipts":{"receipts":[$receipt]}}"""

    @Test
    fun `canonical fixture re-encodes byte for byte`() {
        val wire =
            Json
                .parseToJsonElement(ConformanceFixtures.read("receipts/causal_receipts.json"))
                .jsonObject
                .getValue("wire")
        assertEquals(wire.toString(), ReceiptMessage.fromJson(wire).toJson().toString())
    }

    @Test
    fun `generation spans the full u64 range`() {
        val max = frame(validReceipt.replace("\"generation\":7", "\"generation\":18446744073709551615"))
        val decoded = assertIs<ReceiptMessage.CausalReceiptsMessage>(ReceiptMessage.decodeJson(max))
        assertEquals(ULong.MAX_VALUE, decoded.batch.receipts.single().generation)
        assertEquals(max, decoded.encodeJson().decodeToString())
    }

    @Test
    fun `strict decoder rejects off-schema receipts`() {
        val bad =
            mapOf(
                "negative generation" to validReceipt.replace("\"generation\":7", "\"generation\":-1"),
                "generation past u64" to validReceipt.replace("\"generation\":7", "\"generation\":18446744073709551616"),
                "fractional generation" to validReceipt.replace("\"generation\":7", "\"generation\":7.0"),
                "string generation" to validReceipt.replace("\"generation\":7", "\"generation\":\"7\""),
                "unknown outcome" to validReceipt.replace("\"applied\"", "\"merged\""),
                "unknown key" to validReceipt.replace("}", ",\"extra\":1}"),
                "missing reason" to validReceipt.replace("\"reason\":null,", ""),
                "empty receipt_id" to validReceipt.replace("\"r-1\"", "\"\""),
                "numeric observer" to validReceipt.replace("\"editor\"", "1"),
            )
        bad.forEach { (label, receipt) ->
            assertFailsWith<IllegalArgumentException>(label) { ReceiptMessage.decodeJson(frame(receipt)) }
        }
        assertFailsWith<IllegalArgumentException>("missing receipts") {
            ReceiptMessage.decodeJson("""{"CausalReceipts":{}}""")
        }
        assertFailsWith<IllegalArgumentException>("two variant tags") {
            ReceiptMessage.decodeJson("""{"CausalReceipts":{"receipts":[]},"Other":{}}""")
        }
        assertFailsWith<IllegalArgumentException>("unknown variant") {
            ReceiptMessage.decodeJson("""{"Other":{"receipts":[]}}""")
        }
    }

    @Test
    fun `empty batch encodes as an empty array`() {
        assertEquals(
            """{"CausalReceipts":{"receipts":[]}}""",
            ReceiptMessage.ofCausalReceipts(CausalReceipts()).encodeJson().decodeToString(),
        )
    }

    @Test
    fun `command generation comparison never wraps`() {
        assertTrue(receiptGenerationMatches(7, 7u))
        assertFalse(receiptGenerationMatches(-1, ULong.MAX_VALUE))
        assertFalse(receiptGenerationMatches(Long.MAX_VALUE, ULong.MAX_VALUE))
        assertTrue(receiptGenerationMatches(Long.MAX_VALUE, Long.MAX_VALUE.toULong()))
        assertEquals(Long.MAX_VALUE, receiptGenerationAsLong(ULong.MAX_VALUE))
        assertEquals(7L, receiptGenerationAsLong(7u))
    }
}
