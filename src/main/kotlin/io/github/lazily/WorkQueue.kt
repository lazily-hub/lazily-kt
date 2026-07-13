package io.github.lazily

/** A stable queued item. [attempts] counts leases already issued for it. */
data class WorkQueueItem<T : Any>(
    val itemId: Long,
    val value: T,
    val attempts: Int,
)

/** One exclusive worker-owned delivery lease. */
data class WorkQueueDelivery<T : Any>(
    val deliveryId: Long,
    val itemId: Long,
    val value: T,
    val worker: String,
    val attempt: Int,
    val deadline: Long,
)

enum class WorkQueueDeadLetterReason { Nack, Expired }

/** A terminal poison-message record. */
data class WorkQueueDeadLetter<T : Any>(
    val itemId: Long,
    val value: T,
    val attempts: Int,
    val reason: WorkQueueDeadLetterReason,
)

/** Independent reactive reader kinds for lifecycle state. */
data class WorkQueueReaderHandles(
    val pendingLen: SlotHandle<Int>,
    val isEmpty: SlotHandle<Boolean>,
    val inFlightLen: SlotHandle<Int>,
    val deadLetterLen: SlotHandle<Int>,
)

/**
 * Pull-based competing-consumer work queue (`#lzworkqueue`).
 *
 * This class is the portable local-authority lifecycle. Its owner serializes
 * [claim]; distributed/HA deployments put that decision behind consensus while
 * retaining the same item/delivery identities and settlement rules.
 */
class WorkQueueCell<T : Any>(
    private val ctx: Context,
    val visibilityTimeout: Long,
    val maxDeliveries: Int,
) {
    private val pending = ArrayDeque<WorkQueueItem<T>>()
    private val inFlight = LinkedHashMap<Long, WorkQueueDelivery<T>>()
    private val deadLetters = ArrayList<WorkQueueDeadLetter<T>>()
    private var nextItemId = 0L
    private var nextDeliveryId = 0L

    val readers: WorkQueueReaderHandles

    init {
        require(visibilityTimeout > 0) { "visibilityTimeout must be positive" }
        require(maxDeliveries >= 1) { "maxDeliveries must be at least one" }
        readers =
            WorkQueueReaderHandles(
                pendingLen = SlotHandle(ctx.slotAny(memo = true) { pending.size }),
                isEmpty = SlotHandle(ctx.slotAny(memo = true) { pending.isEmpty() }),
                inFlightLen = SlotHandle(ctx.slotAny(memo = true) { inFlight.size }),
                deadLetterLen = SlotHandle(ctx.slotAny(memo = true) { deadLetters.size }),
            )
    }

    private data class Counts(
        val pending: Int,
        val inFlight: Int,
        val deadLetters: Int,
    )

    private fun counts() = Counts(pending.size, inFlight.size, deadLetters.size)

    private fun invalidate(before: Counts, after: Counts) {
        val roots = ArrayList<Int>(4)
        if (before.pending != after.pending) roots += readers.pendingLen.id
        if ((before.pending == 0) != (after.pending == 0)) roots += readers.isEmpty.id
        if (before.inFlight != after.inFlight) roots += readers.inFlightLen.id
        if (before.deadLetters != after.deadLetters) roots += readers.deadLetterLen.id
        ctx.invalidateSlots(roots.toIntArray())
    }

    private fun deadline(now: Long): Long {
        require(now >= 0) { "now must be non-negative" }
        return if (Long.MAX_VALUE - now < visibilityTimeout) Long.MAX_VALUE else now + visibilityTimeout
    }

    private fun fail(delivery: WorkQueueDelivery<T>, reason: WorkQueueDeadLetterReason) {
        if (delivery.attempt < maxDeliveries) {
            pending.addLast(WorkQueueItem(delivery.itemId, delivery.value, delivery.attempt))
        } else {
            deadLetters +=
                WorkQueueDeadLetter(
                    delivery.itemId,
                    delivery.value,
                    delivery.attempt,
                    reason,
                )
        }
    }

    /** Append one item to the pending FIFO and return its stable identity. */
    fun push(value: T): Long {
        val before = counts()
        check(nextItemId < Long.MAX_VALUE) { "item id exhausted" }
        val itemId = nextItemId
        nextItemId++
        pending.addLast(WorkQueueItem(itemId, value, 0))
        invalidate(before, counts())
        return itemId
    }

    /** Claim the oldest pending item, minting a fresh delivery identity. */
    fun claim(worker: String, now: Long): WorkQueueDelivery<T>? {
        require(now >= 0) { "now must be non-negative" }
        if (pending.isEmpty()) return null
        check(nextDeliveryId < Long.MAX_VALUE) { "delivery id exhausted" }
        val item = pending.removeFirstOrNull() ?: return null
        val before = Counts(pending.size + 1, inFlight.size, deadLetters.size)
        val deliveryId = nextDeliveryId
        nextDeliveryId++
        val delivery =
            WorkQueueDelivery(
                deliveryId = deliveryId,
                itemId = item.itemId,
                value = item.value,
                worker = worker,
                attempt = item.attempts + 1,
                deadline = deadline(now),
            )
        inFlight[deliveryId] = delivery
        invalidate(before, counts())
        return delivery
    }

    /** Ack only the matching current delivery owned by [worker]. */
    fun ack(worker: String, deliveryId: Long): Boolean {
        val delivery = inFlight[deliveryId] ?: return false
        if (delivery.worker != worker) return false
        val before = counts()
        inFlight.remove(deliveryId)
        invalidate(before, counts())
        return true
    }

    /** Nack a matching delivery, requeueing or dead-lettering at the attempt limit. */
    fun nack(worker: String, deliveryId: Long): Boolean {
        val delivery = inFlight[deliveryId] ?: return false
        if (delivery.worker != worker) return false
        val before = counts()
        inFlight.remove(deliveryId)
        fail(delivery, WorkQueueDeadLetterReason.Nack)
        invalidate(before, counts())
        return true
    }

    /** Expire leases whose `deadline < now`, in delivery-id order. */
    fun reapExpired(now: Long): Int {
        require(now >= 0) { "now must be non-negative" }
        val expired = inFlight.values.filter { it.deadline < now }.sortedBy { it.deliveryId }
        if (expired.isEmpty()) return 0
        val before = counts()
        for (delivery in expired) {
            inFlight.remove(delivery.deliveryId)
            fail(delivery, WorkQueueDeadLetterReason.Expired)
        }
        invalidate(before, counts())
        return expired.size
    }

    fun pendingLen(): Int = ctx.get(readers.pendingLen)

    fun isEmpty(): Boolean = ctx.get(readers.isEmpty)

    fun inFlightLen(): Int = ctx.get(readers.inFlightLen)

    fun deadLetterLen(): Int = ctx.get(readers.deadLetterLen)

    fun pendingItems(): List<WorkQueueItem<T>> = pending.toList()

    fun inFlightDeliveries(): List<WorkQueueDelivery<T>> = inFlight.values.sortedBy { it.deliveryId }

    fun deadLetterItems(): List<WorkQueueDeadLetter<T>> = deadLetters.toList()
}
