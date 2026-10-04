package io.github.lazily

// Wire declarations and codecs (ReceiptOutcome, CausalReceipt, CausalReceipts,
// ReceiptMessage) are generated into ReceiptsWireGen.kt from the shared wire
// model in lazily-spec (#lzwiremodel). This file keeps the receipt semantics.

/** `applied` and `rejected` close a causation; `observed` and `accepted` do not. */
val ReceiptOutcome.isTerminal: Boolean
    get() = this == ReceiptOutcome.Applied || this == ReceiptOutcome.Rejected

fun CausalReceipt.Companion.observed(
    receiptId: String,
    causationId: String,
    observer: String,
    generation: ULong,
): CausalReceipt = CausalReceipt(receiptId, causationId, observer, generation, ReceiptOutcome.Observed)

fun CausalReceipt.Companion.accepted(
    receiptId: String,
    causationId: String,
    observer: String,
    generation: ULong,
): CausalReceipt = CausalReceipt(receiptId, causationId, observer, generation, ReceiptOutcome.Accepted)

fun CausalReceipt.Companion.applied(
    receiptId: String,
    causationId: String,
    observer: String,
    generation: ULong,
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

fun CausalReceipt.Companion.rejected(
    receiptId: String,
    causationId: String,
    observer: String,
    generation: ULong,
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

fun ReceiptMessage.Companion.ofCausalReceipts(batch: CausalReceipts): ReceiptMessage = ReceiptMessage.CausalReceiptsMessage(batch)

sealed interface ReceiptApplyStatus {
    data object Recorded : ReceiptApplyStatus

    data object Duplicate : ReceiptApplyStatus

    data class StaleGeneration(
        val expected: ULong,
        val actual: ULong,
    ) : ReceiptApplyStatus

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

    fun observe(
        currentGeneration: ULong?,
        receipt: CausalReceipt,
    ): ReceiptApplyStatus {
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

    fun latestFor(causationId: String): CausalReceipt? = latestByCausation[causationId]

    fun terminalFor(causationId: String): CausalReceipt? = terminalByCausation[causationId]

    fun containsReceipt(receiptId: String): Boolean = receiptId in receiptsById || receiptId in staleIds

    fun staleReceiptIds(): List<String> = staleIds.toList()
}
