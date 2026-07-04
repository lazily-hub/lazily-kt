package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private val receiptJson = Json { prettyPrint = false }

private fun JsonElement.receiptObject(name: String): JsonObject =
    this as? JsonObject ?: error("$name must be a JSON object")

private fun JsonObject.receiptRequired(name: String): JsonElement =
    this[name] ?: error("missing required field: $name")

private fun JsonObject.receiptString(name: String): String =
    receiptRequired(name).jsonPrimitive.content

private fun JsonObject.receiptLong(name: String): Long =
    receiptRequired(name).jsonPrimitive.long

enum class ReceiptOutcome(val wireName: String) {
    Observed("observed"),
    Accepted("accepted"),
    Applied("applied"),
    Rejected("rejected"),
    ;

    val isTerminal: Boolean
        get() = this == Applied || this == Rejected

    companion object {
        fun fromWire(value: String): ReceiptOutcome =
            entries.firstOrNull { it.wireName == value }
                ?: error("unknown receipt outcome: $value")
    }
}

data class CausalReceipt(
    val receiptId: String,
    val causationId: String,
    val observer: String,
    val generation: Long,
    val outcome: ReceiptOutcome,
    val reason: String? = null,
    val payloadHash: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("receipt_id", receiptId)
        put("causation_id", causationId)
        put("observer", observer)
        put("generation", generation)
        put("outcome", outcome.wireName)
        put("reason", reason?.let { JsonPrimitive(it) } ?: JsonNull)
        put("payload_hash", payloadHash?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        fun observed(
            receiptId: String,
            causationId: String,
            observer: String,
            generation: Long,
        ): CausalReceipt =
            CausalReceipt(receiptId, causationId, observer, generation, ReceiptOutcome.Observed)

        fun accepted(
            receiptId: String,
            causationId: String,
            observer: String,
            generation: Long,
        ): CausalReceipt =
            CausalReceipt(receiptId, causationId, observer, generation, ReceiptOutcome.Accepted)

        fun applied(
            receiptId: String,
            causationId: String,
            observer: String,
            generation: Long,
            payloadHash: String? = null,
        ): CausalReceipt =
            CausalReceipt(
                receiptId,
                causationId,
                observer,
                generation,
                ReceiptOutcome.Applied,
                payloadHash = payloadHash,
            )

        fun rejected(
            receiptId: String,
            causationId: String,
            observer: String,
            generation: Long,
            reason: String? = null,
        ): CausalReceipt =
            CausalReceipt(
                receiptId,
                causationId,
                observer,
                generation,
                ReceiptOutcome.Rejected,
                reason = reason,
            )

        fun fromJson(element: JsonElement): CausalReceipt {
            val obj = element.receiptObject("CausalReceipt")
            return CausalReceipt(
                receiptId = obj.receiptString("receipt_id"),
                causationId = obj.receiptString("causation_id"),
                observer = obj.receiptString("observer"),
                generation = obj.receiptLong("generation"),
                outcome = ReceiptOutcome.fromWire(obj.receiptString("outcome")),
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
                payloadHash = obj["payload_hash"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

data class CausalReceipts(val receipts: List<CausalReceipt>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("receipts", buildJsonArray { receipts.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(element: JsonElement): CausalReceipts {
            val obj = element.receiptObject("CausalReceipts")
            return CausalReceipts(
                obj.receiptRequired("receipts").jsonArray.map { CausalReceipt.fromJson(it) }
            )
        }
    }
}

sealed interface ReceiptMessage {
    fun toJson(): JsonObject

    data class CausalReceiptsMessage(val batch: CausalReceipts) : ReceiptMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("CausalReceipts", batch.toJson())
        }
    }

    fun encodeJson(): ByteArray =
        receiptJson.encodeToString(JsonElement.serializer(), toJson()).encodeToByteArray()

    companion object {
        fun ofCausalReceipts(batch: CausalReceipts): ReceiptMessage =
            CausalReceiptsMessage(batch)

        fun decodeJson(data: ByteArray): ReceiptMessage =
            fromJson(receiptJson.parseToJsonElement(data.decodeToString()))

        fun decodeJson(data: String): ReceiptMessage =
            fromJson(receiptJson.parseToJsonElement(data))

        fun fromJson(element: JsonElement): ReceiptMessage {
            val obj = element.receiptObject("ReceiptMessage")
            require(obj.size == 1) { "ReceiptMessage must be externally tagged" }
            val (tag, body) = obj.entries.single()
            return when (tag) {
                "CausalReceipts" -> CausalReceiptsMessage(CausalReceipts.fromJson(body))
                else -> error("unknown ReceiptMessage variant: $tag")
            }
        }
    }
}

sealed interface ReceiptApplyStatus {
    data object Recorded : ReceiptApplyStatus
    data object Duplicate : ReceiptApplyStatus
    data class StaleGeneration(val expected: Long, val actual: Long) : ReceiptApplyStatus
    data class TerminalConflict(
        val causationId: String,
        val existing: ReceiptOutcome,
        val incoming: ReceiptOutcome,
    ) : ReceiptApplyStatus
}

class ReceiptProjection {
    private val receiptsById: MutableMap<String, CausalReceipt> = linkedMapOf()
    private val latestByCausation: MutableMap<String, CausalReceipt> = linkedMapOf()
    private val terminalByCausation: MutableMap<String, CausalReceipt> = linkedMapOf()
    private val staleIds: MutableSet<String> = linkedSetOf()

    fun observe(currentGeneration: Long?, receipt: CausalReceipt): ReceiptApplyStatus {
        if (receipt.receiptId in receiptsById || receipt.receiptId in staleIds) {
            return ReceiptApplyStatus.Duplicate
        }

        if (currentGeneration != null && receipt.generation != currentGeneration) {
            staleIds.add(receipt.receiptId)
            return ReceiptApplyStatus.StaleGeneration(
                expected = currentGeneration,
                actual = receipt.generation,
            )
        }

        if (receipt.outcome.isTerminal) {
            val existing = terminalByCausation[receipt.causationId]
            if (existing != null && existing.outcome != receipt.outcome) {
                return ReceiptApplyStatus.TerminalConflict(
                    causationId = receipt.causationId,
                    existing = existing.outcome,
                    incoming = receipt.outcome,
                )
            }
            terminalByCausation.putIfAbsent(receipt.causationId, receipt)
        }

        latestByCausation[receipt.causationId] = receipt
        receiptsById[receipt.receiptId] = receipt
        return ReceiptApplyStatus.Recorded
    }

    fun latestFor(causationId: String): CausalReceipt? =
        latestByCausation[causationId]

    fun terminalFor(causationId: String): CausalReceipt? =
        terminalByCausation[causationId]

    fun containsReceipt(receiptId: String): Boolean =
        receiptId in receiptsById || receiptId in staleIds

    fun staleReceiptIds(): List<String> = staleIds.toList()
}
