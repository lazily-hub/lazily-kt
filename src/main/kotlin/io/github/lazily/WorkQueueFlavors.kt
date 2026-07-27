package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private data class FlavorWorkQueueCounts(
    val pending: Int,
    val inFlight: Int,
    val deadLetters: Int,
)

private data class FlavorWorkQueueMutation<R>(
    val result: R,
    val before: FlavorWorkQueueCounts,
    val after: FlavorWorkQueueCounts,
)

/** Graph-agnostic competing-consumer authority shared by the two new flavors. */
private class FlavorWorkQueueCore<T : Any>(
    private val visibilityTimeout: Long,
    private val maxDeliveries: Int,
) {
    val pending = ArrayDeque<WorkQueueItem<T>>()
    val inFlight = LinkedHashMap<Long, WorkQueueDelivery<T>>()
    val deadLetters = ArrayList<WorkQueueDeadLetter<T>>()
    private var nextItemId = 0L
    private var nextDeliveryId = 0L

    fun counts() = FlavorWorkQueueCounts(pending.size, inFlight.size, deadLetters.size)

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

    fun push(value: T): Long {
        check(nextItemId < Long.MAX_VALUE) { "item id exhausted" }
        val id = nextItemId++
        pending.addLast(WorkQueueItem(id, value, 0))
        return id
    }

    fun claim(worker: String, now: Long): WorkQueueDelivery<T>? {
        require(now >= 0) { "now must be non-negative" }
        val item = pending.removeFirstOrNull() ?: return null
        check(nextDeliveryId < Long.MAX_VALUE) { "delivery id exhausted" }
        val delivery =
            WorkQueueDelivery(
                deliveryId = nextDeliveryId++,
                itemId = item.itemId,
                value = item.value,
                worker = worker,
                attempt = item.attempts + 1,
                deadline = deadline(now),
            )
        inFlight[delivery.deliveryId] = delivery
        return delivery
    }

    fun ack(worker: String, deliveryId: Long): Boolean {
        val delivery = inFlight[deliveryId] ?: return false
        if (delivery.worker != worker) return false
        inFlight.remove(deliveryId)
        return true
    }

    fun nack(worker: String, deliveryId: Long): Boolean {
        val delivery = inFlight[deliveryId] ?: return false
        if (delivery.worker != worker) return false
        inFlight.remove(deliveryId)
        fail(delivery, WorkQueueDeadLetterReason.Nack)
        return true
    }

    fun reapExpired(now: Long): Int {
        require(now >= 0) { "now must be non-negative" }
        val expired = inFlight.values.filter { it.deadline < now }.sortedBy { it.deliveryId }
        for (delivery in expired) {
            inFlight.remove(delivery.deliveryId)
            fail(delivery, WorkQueueDeadLetterReason.Expired)
        }
        return expired.size
    }
}

data class ThreadSafeWorkQueueReaderHandles(
    val pendingLen: ThreadSafeComputed<Int>,
    val isEmpty: ThreadSafeComputed<Boolean>,
    val inFlightLen: ThreadSafeComputed<Int>,
    val deadLetterLen: ThreadSafeComputed<Int>,
)

/** `ThreadSafeContext` competing-consumer work queue. */
class ThreadSafeWorkQueueCell<T : Any>(
    private val ctx: ThreadSafeContext,
    val visibilityTimeout: Long,
    val maxDeliveries: Int,
) {
    private val lock = ReentrantLock()
    private val core: FlavorWorkQueueCore<T>
    val readers: ThreadSafeWorkQueueReaderHandles

    init {
        require(visibilityTimeout > 0) { "visibilityTimeout must be positive" }
        require(maxDeliveries >= 1) { "maxDeliveries must be at least one" }
        core = FlavorWorkQueueCore(visibilityTimeout, maxDeliveries)
        readers =
            ThreadSafeWorkQueueReaderHandles(
                ctx.computed { lock.withLock { core.pending.size } },
                ctx.computed { lock.withLock { core.pending.isEmpty() } },
                ctx.computed { lock.withLock { core.inFlight.size } },
                ctx.computed { lock.withLock { core.deadLetters.size } },
            )
    }

    private fun invalidate(before: FlavorWorkQueueCounts, after: FlavorWorkQueueCounts) {
        val roots = ArrayList<Int>(4)
        if (before.pending != after.pending) roots += readers.pendingLen.id
        if ((before.pending == 0) != (after.pending == 0)) roots += readers.isEmpty.id
        if (before.inFlight != after.inFlight) roots += readers.inFlightLen.id
        if (before.deadLetters != after.deadLetters) roots += readers.deadLetterLen.id
        ctx.invalidateSlots(roots.toIntArray())
    }

    private fun <R> mutate(run: FlavorWorkQueueCore<T>.() -> R): R {
        val mutation =
            lock.withLock {
                val before = core.counts()
                val result = core.run()
                FlavorWorkQueueMutation(result, before, core.counts())
            }
        invalidate(mutation.before, mutation.after)
        return mutation.result
    }

    fun push(value: T): Long = mutate { push(value) }
    fun claim(worker: String, now: Long): WorkQueueDelivery<T>? = mutate { claim(worker, now) }
    fun ack(worker: String, deliveryId: Long): Boolean = mutate { ack(worker, deliveryId) }
    fun nack(worker: String, deliveryId: Long): Boolean = mutate { nack(worker, deliveryId) }
    fun reapExpired(now: Long): Int = mutate { reapExpired(now) }

    fun pendingLen(): Int = ctx.get(readers.pendingLen)
    fun isEmpty(): Boolean = ctx.get(readers.isEmpty)
    fun inFlightLen(): Int = ctx.get(readers.inFlightLen)
    fun deadLetterLen(): Int = ctx.get(readers.deadLetterLen)
    fun pendingItems(): List<WorkQueueItem<T>> = lock.withLock { core.pending.toList() }
    fun inFlightDeliveries(): List<WorkQueueDelivery<T>> =
        lock.withLock { core.inFlight.values.sortedBy { it.deliveryId } }
    fun deadLetterItems(): List<WorkQueueDeadLetter<T>> =
        lock.withLock { core.deadLetters.toList() }
}

data class AsyncWorkQueueReaderHandles(
    val pendingLen: AsyncContext.AsyncComputed<Int>,
    val isEmpty: AsyncContext.AsyncComputed<Boolean>,
    val inFlightLen: AsyncContext.AsyncComputed<Int>,
    val deadLetterLen: AsyncContext.AsyncComputed<Int>,
)

/** `AsyncContext` competing-consumer work queue with caller-driven lease time. */
class AsyncWorkQueueCell<T : Any>(
    private val ctx: AsyncContext,
    val visibilityTimeout: Long,
    val maxDeliveries: Int,
) {
    private val lock = ReentrantLock()
    private val core: FlavorWorkQueueCore<T>
    val readers: AsyncWorkQueueReaderHandles

    init {
        require(visibilityTimeout > 0) { "visibilityTimeout must be positive" }
        require(maxDeliveries >= 1) { "maxDeliveries must be at least one" }
        core = FlavorWorkQueueCore(visibilityTimeout, maxDeliveries)
        readers =
            AsyncWorkQueueReaderHandles(
                ctx.computed { lock.withLock { core.pending.size } },
                ctx.computed { lock.withLock { core.pending.isEmpty() } },
                ctx.computed { lock.withLock { core.inFlight.size } },
                ctx.computed { lock.withLock { core.deadLetters.size } },
            )
    }

    private fun invalidate(before: FlavorWorkQueueCounts, after: FlavorWorkQueueCounts) {
        val roots = ArrayList<Int>(4)
        if (before.pending != after.pending) roots += readers.pendingLen.id
        if ((before.pending == 0) != (after.pending == 0)) roots += readers.isEmpty.id
        if (before.inFlight != after.inFlight) roots += readers.inFlightLen.id
        if (before.deadLetters != after.deadLetters) roots += readers.deadLetterLen.id
        ctx.invalidateSlots(roots.toIntArray())
    }

    private fun <R> mutate(run: FlavorWorkQueueCore<T>.() -> R): R {
        val mutation =
            lock.withLock {
                val before = core.counts()
                val result = core.run()
                FlavorWorkQueueMutation(result, before, core.counts())
            }
        invalidate(mutation.before, mutation.after)
        return mutation.result
    }

    fun push(value: T): Long = mutate { push(value) }
    fun claim(worker: String, now: Long): WorkQueueDelivery<T>? = mutate { claim(worker, now) }
    fun ack(worker: String, deliveryId: Long): Boolean = mutate { ack(worker, deliveryId) }
    fun nack(worker: String, deliveryId: Long): Boolean = mutate { nack(worker, deliveryId) }
    fun reapExpired(now: Long): Int = mutate { reapExpired(now) }

    fun pendingLen(): Int = requireNotNull(ctx.get(readers.pendingLen))
    fun pendingLen(compute: AsyncComputeContext): Int = requireNotNull(compute.get(readers.pendingLen))
    fun isEmpty(): Boolean = requireNotNull(ctx.get(readers.isEmpty))
    fun isEmpty(compute: AsyncComputeContext): Boolean = requireNotNull(compute.get(readers.isEmpty))
    fun inFlightLen(): Int = requireNotNull(ctx.get(readers.inFlightLen))
    fun inFlightLen(compute: AsyncComputeContext): Int =
        requireNotNull(compute.get(readers.inFlightLen))
    fun deadLetterLen(): Int = requireNotNull(ctx.get(readers.deadLetterLen))
    fun deadLetterLen(compute: AsyncComputeContext): Int =
        requireNotNull(compute.get(readers.deadLetterLen))

    fun pendingItems(): List<WorkQueueItem<T>> = lock.withLock { core.pending.toList() }
    fun inFlightDeliveries(): List<WorkQueueDelivery<T>> =
        lock.withLock { core.inFlight.values.sortedBy { it.deliveryId } }
    fun deadLetterItems(): List<WorkQueueDeadLetter<T>> =
        lock.withLock { core.deadLetters.toList() }
}
