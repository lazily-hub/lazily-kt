package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Reader-kind handles owned by a [ThreadSafeQueueCell]'s graph. */
data class ThreadSafeQueueReaderHandles(
    val head: ThreadSafeComputed<Any>,
    val len: ThreadSafeComputed<Int>,
    val isEmpty: ThreadSafeComputed<Boolean>,
    val isFull: ThreadSafeComputed<Boolean>,
    val closed: ThreadSafeSource<Boolean>,
)

/**
 * Lock-backed queue flavor. Storage is released before the context is touched:
 * reader computes take the inverse order (context, then storage), so retaining
 * the storage lock through invalidation would admit a lock-order deadlock.
 */
class ThreadSafeQueueCell<T : Any, S : QueueStorage<T>>(
    private val ctx: ThreadSafeContext,
    storage: S,
) {
    private val lock = ReentrantLock()
    internal val storage: S = storage
    private val cap = storage.capacity()
    private val headSlot = ctx.computed<Any> { lock.withLock { storage.peek() ?: NO_QUEUE_HEAD } }
    private val lenSlot = ctx.computed { lock.withLock { storage.len() } }
    private val isEmptySlot = ctx.computed { lock.withLock { storage.len() == 0 } }
    private val isFullSlot =
        ctx.computed { lock.withLock { cap?.let { storage.len() >= it } ?: false } }
    private val closedCell = ctx.source(lock.withLock { storage.isClosed() })

    companion object {
        fun <T : Any> unbounded(ctx: ThreadSafeContext): ThreadSafeQueueCell<T, VecDequeStorage<T>> =
            ThreadSafeQueueCell(ctx, VecDequeStorage())

        fun <T : Any> bounded(
            ctx: ThreadSafeContext,
            capacity: Int,
        ): ThreadSafeQueueCell<T, VecDequeStorage<T>> =
            ThreadSafeQueueCell(ctx, VecDequeStorage(capacity))
    }

    private fun invalidateReaders(lenBefore: Int, lenAfter: Int, headChanged: Boolean) {
        val roots = ArrayList<Int>(4)
        roots += lenSlot.id
        if ((lenBefore == 0) != (lenAfter == 0)) roots += isEmptySlot.id
        if (cap?.let { (lenBefore >= it) != (lenAfter >= it) } == true) roots += isFullSlot.id
        if (headChanged) roots += headSlot.id
        ctx.invalidateSlots(roots.toIntArray())
    }

    fun tryPush(value: T): QueuePushError? {
        val (error, before) =
            lock.withLock {
                val before = storage.len()
                storage.tryPush(value) to before
            }
        if (error == null) invalidateReaders(before, before + 1, before == 0)
        return error
    }

    fun tryPop(): QueuePop<T> {
        val (result, before) =
            lock.withLock {
                val before = storage.len()
                storage.tryPop() to before
            }
        if (result is QueuePop.Value) invalidateReaders(before, before - 1, true)
        return result
    }

    fun close() {
        val changed =
            lock.withLock {
                if (storage.isClosed()) false else {
                    storage.close()
                    true
                }
            }
        if (changed) ctx.set(closedCell, true)
    }

    @Suppress("UNCHECKED_CAST")
    fun head(): T? {
        val value = ctx.get(headSlot)
        return if (value === NO_QUEUE_HEAD) null else value as T
    }

    fun len(): Int = ctx.get(lenSlot)
    fun isEmpty(): Boolean = ctx.get(isEmptySlot)
    fun isFull(): Boolean = ctx.get(isFullSlot)
    fun isClosed(): Boolean = ctx.get(closedCell)
    fun capacity(): Int? = cap

    fun readerHandles(): ThreadSafeQueueReaderHandles =
        ThreadSafeQueueReaderHandles(headSlot, lenSlot, isEmptySlot, isFullSlot, closedCell)

    internal fun <R> withStorage(read: (S) -> R): R = lock.withLock { read(storage) }
}

fun <T : Any> ThreadSafeQueueCell<T, VecDequeStorage<T>>.elements(): List<T> =
    withStorage { it.elements() }

/** Reader-kind handles owned by an [AsyncQueueCell]'s graph. */
data class AsyncQueueReaderHandles(
    val head: AsyncContext.AsyncComputed<Any>,
    val len: AsyncContext.AsyncComputed<Int>,
    val isEmpty: AsyncContext.AsyncComputed<Boolean>,
    val isFull: AsyncContext.AsyncComputed<Boolean>,
    val closed: AsyncContext.AsyncSource<Boolean>,
)

/**
 * Async-graph queue flavor. The queue algebra remains synchronous; its reader
 * kinds use [AsyncContext.computed] because their in-memory values have nothing
 * to await. Callers composing an async derived node use the `AsyncComputeContext`
 * overloads so dependency edges are registered explicitly.
 */
class AsyncQueueCell<T : Any, S : QueueStorage<T>>(
    private val ctx: AsyncContext,
    storage: S,
) {
    private val lock = ReentrantLock()
    internal val storage: S = storage
    private val cap = storage.capacity()
    private val headSlot = ctx.computed<Any> { lock.withLock { storage.peek() ?: NO_QUEUE_HEAD } }
    private val lenSlot = ctx.computed { lock.withLock { storage.len() } }
    private val isEmptySlot = ctx.computed { lock.withLock { storage.len() == 0 } }
    private val isFullSlot =
        ctx.computed { lock.withLock { cap?.let { storage.len() >= it } ?: false } }
    private val closedCell = ctx.source(lock.withLock { storage.isClosed() })

    companion object {
        fun <T : Any> unbounded(ctx: AsyncContext): AsyncQueueCell<T, VecDequeStorage<T>> =
            AsyncQueueCell(ctx, VecDequeStorage())

        fun <T : Any> bounded(
            ctx: AsyncContext,
            capacity: Int,
        ): AsyncQueueCell<T, VecDequeStorage<T>> =
            AsyncQueueCell(ctx, VecDequeStorage(capacity))
    }

    private fun invalidateReaders(lenBefore: Int, lenAfter: Int, headChanged: Boolean) {
        val roots = ArrayList<Int>(4)
        roots += lenSlot.id
        if ((lenBefore == 0) != (lenAfter == 0)) roots += isEmptySlot.id
        if (cap?.let { (lenBefore >= it) != (lenAfter >= it) } == true) roots += isFullSlot.id
        if (headChanged) roots += headSlot.id
        ctx.invalidateSlots(roots.toIntArray())
    }

    fun tryPush(value: T): QueuePushError? {
        val (error, before) =
            lock.withLock {
                val before = storage.len()
                storage.tryPush(value) to before
            }
        if (error == null) invalidateReaders(before, before + 1, before == 0)
        return error
    }

    fun tryPop(): QueuePop<T> {
        val (result, before) =
            lock.withLock {
                val before = storage.len()
                storage.tryPop() to before
            }
        if (result is QueuePop.Value) invalidateReaders(before, before - 1, true)
        return result
    }

    fun close() {
        val changed =
            lock.withLock {
                if (storage.isClosed()) false else {
                    storage.close()
                    true
                }
            }
        if (changed) ctx.set(closedCell, true)
    }

    @Suppress("UNCHECKED_CAST")
    fun head(): T? {
        val value = requireNotNull(ctx.get(headSlot)) { "sync queue head did not resolve inline" }
        return if (value === NO_QUEUE_HEAD) null else value as T
    }

    fun head(compute: AsyncComputeContext): T? {
        val value = requireNotNull(compute.get(headSlot))
        @Suppress("UNCHECKED_CAST")
        return if (value === NO_QUEUE_HEAD) null else value as T
    }

    fun len(): Int = requireNotNull(ctx.get(lenSlot))
    fun len(compute: AsyncComputeContext): Int = requireNotNull(compute.get(lenSlot))
    fun isEmpty(): Boolean = requireNotNull(ctx.get(isEmptySlot))
    fun isEmpty(compute: AsyncComputeContext): Boolean = requireNotNull(compute.get(isEmptySlot))
    fun isFull(): Boolean = requireNotNull(ctx.get(isFullSlot))
    fun isFull(compute: AsyncComputeContext): Boolean = requireNotNull(compute.get(isFullSlot))
    fun isClosed(): Boolean = ctx.get(closedCell)
    fun isClosed(compute: AsyncComputeContext): Boolean = compute.get(closedCell)
    fun capacity(): Int? = cap

    fun readerHandles(): AsyncQueueReaderHandles =
        AsyncQueueReaderHandles(headSlot, lenSlot, isEmptySlot, isFullSlot, closedCell)

    internal fun <R> withStorage(read: (S) -> R): R = lock.withLock { read(storage) }
}

fun <T : Any> AsyncQueueCell<T, VecDequeStorage<T>>.elements(): List<T> =
    withStorage { it.elements() }

private object NO_QUEUE_HEAD
