package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private data class FlavorTopicSubscription(
    var cursor: Long,
    val durability: TopicDurability,
    var connected: Boolean,
)

/** `ThreadSafeContext` flavor of [TopicCell]. */
class ThreadSafeTopicCell<T : Any>(
    private val ctx: ThreadSafeContext,
    initial: TopicSnapshot<T> = TopicSnapshot(),
) {
    private val lock = ReentrantLock()
    private var baseOffset = initial.baseOffset
    private val retained = ArrayDeque<T>(initial.elements.size)
    private val subscriptions = LinkedHashMap<String, FlavorTopicSubscription>()
    private val readers = HashMap<String, ThreadSafeComputed<List<T>>>()

    init {
        require(baseOffset >= 0) { "TopicCell baseOffset must be non-negative" }
        retained.addAll(initial.elements)
        val end = baseOffset + retained.size
        for ((id, sub) in initial.subscriptions) {
            require(sub.cursor in baseOffset..end)
            require(sub.durability != TopicDurability.Ephemeral || sub.connected)
            subscriptions[id] = FlavorTopicSubscription(sub.cursor, sub.durability, sub.connected)
        }
        for (id in subscriptions.keys) ensureReader(id)
    }

    private fun ensureReader(id: String): ThreadSafeComputed<List<T>> =
        lock.withLock { readers[id] } ?: run {
            // Mint off the topic lock. A read holds the context lock before it
            // enters the compute and takes this lock, so the reverse order here
            // would deadlock with a concurrent read.
            val minted =
                ctx.computed {
                    lock.withLock {
                        val sub = subscriptions[id]
                        if (sub == null || !sub.connected) {
                            emptyList()
                        } else {
                            retained.drop((sub.cursor - baseOffset).coerceAtLeast(0).toInt())
                        }
                    }
                }
            lock.withLock { readers.getOrPut(id) { minted } }
        }

    fun subscribe(
        id: String,
        durability: TopicDurability,
    ): TopicSubscribeOutcome {
        val outcome =
            lock.withLock {
                val existing = subscriptions[id]
                if (existing != null) {
                    if (existing.connected) {
                        TopicSubscribeOutcome.AlreadyConnected
                    } else {
                        existing.connected = true
                        TopicSubscribeOutcome.Reconnected
                    }
                } else {
                    subscriptions[id] =
                        FlavorTopicSubscription(baseOffset + retained.size, durability, connected = true)
                    TopicSubscribeOutcome.Created
                }
            }
        val reader = ensureReader(id)
        if (outcome == TopicSubscribeOutcome.Reconnected) {
            ctx.invalidateSlots(intArrayOf(reader.id))
        }
        return outcome
    }

    fun reconnect(id: String): TopicSubscribeOutcome = subscribe(id, TopicDurability.Durable)

    fun disconnect(id: String): Boolean {
        var root: Int? = null
        val changed =
            lock.withLock {
                val sub = subscriptions[id] ?: return false
                if (!sub.connected) return false
                sub.connected = false
                root =
                    if (sub.durability == TopicDurability.Ephemeral) {
                        subscriptions.remove(id)
                        readers.remove(id)?.id
                    } else {
                        readers[id]?.id
                    }
                true
            }
        root?.let { ctx.invalidateSlots(intArrayOf(it)) }
        return changed
    }

    fun publish(value: T): Long {
        val (offset, roots) =
            lock.withLock {
                val offset = baseOffset + retained.size
                retained.addLast(value)
                val roots =
                    subscriptions
                        .filterValues { it.connected && it.cursor <= offset }
                        .keys
                        .mapNotNull { readers[it]?.id }
                        .toIntArray()
                offset to roots
            }
        ctx.invalidateSlots(roots)
        return offset
    }

    fun readStream(id: String): List<T> = readerHandle(id)?.let(ctx::get) ?: emptyList()

    fun read(id: String): T? = readStream(id).firstOrNull()

    fun advance(id: String): T? {
        var root: Int? = null
        val value =
            lock.withLock {
                val sub = subscriptions[id] ?: return null
                val end = baseOffset + retained.size
                if (!sub.connected || sub.cursor >= end) return null
                val value = retained.elementAt((sub.cursor - baseOffset).toInt())
                sub.cursor += 1
                root = readers[id]?.id
                value
            }
        root?.let { ctx.invalidateSlots(intArrayOf(it)) }
        return value
    }

    fun gc(): Int =
        lock.withLock {
            val frontier =
                subscriptions.values
                    .asSequence()
                    .filter { it.durability == TopicDurability.Durable }
                    .map { it.cursor }
                    .minOrNull() ?: (baseOffset + retained.size)
            val remove = (frontier - baseOffset).toInt()
            repeat(remove) { retained.removeFirst() }
            baseOffset = frontier
            remove
        }

    fun baseOffset(): Long = lock.withLock { baseOffset }

    fun endOffset(): Long = lock.withLock { baseOffset + retained.size }

    fun elements(): List<T> = lock.withLock { retained.toList() }

    fun subscription(id: String): TopicSubscriptionSnapshot? =
        lock.withLock {
            subscriptions[id]?.let {
                TopicSubscriptionSnapshot(it.cursor, it.durability, it.connected)
            }
        }

    fun readerHandle(id: String): ThreadSafeComputed<List<T>>? = lock.withLock { readers[id] }

    fun snapshot(): TopicSnapshot<T> =
        lock.withLock {
            TopicSnapshot(
                baseOffset,
                retained.toList(),
                subscriptions.mapValues { (_, sub) ->
                    TopicSubscriptionSnapshot(sub.cursor, sub.durability, sub.connected)
                },
            )
        }
}

/** `AsyncContext` flavor of [TopicCell], with synchronous in-memory reader derives. */
class AsyncTopicCell<T : Any>(
    private val ctx: AsyncContext,
    initial: TopicSnapshot<T> = TopicSnapshot(),
) {
    private val lock = ReentrantLock()
    private var baseOffset = initial.baseOffset
    private val retained = ArrayDeque<T>(initial.elements.size)
    private val subscriptions = LinkedHashMap<String, FlavorTopicSubscription>()
    private val readers = HashMap<String, AsyncContext.AsyncComputed<List<T>>>()

    init {
        require(baseOffset >= 0) { "TopicCell baseOffset must be non-negative" }
        retained.addAll(initial.elements)
        val end = baseOffset + retained.size
        for ((id, sub) in initial.subscriptions) {
            require(sub.cursor in baseOffset..end)
            require(sub.durability != TopicDurability.Ephemeral || sub.connected)
            subscriptions[id] = FlavorTopicSubscription(sub.cursor, sub.durability, sub.connected)
        }
        for (id in subscriptions.keys) ensureReader(id)
    }

    private fun ensureReader(id: String): AsyncContext.AsyncComputed<List<T>> =
        lock.withLock { readers[id] } ?: run {
            val minted =
                ctx.computed {
                    lock.withLock {
                        val sub = subscriptions[id]
                        if (sub == null || !sub.connected) {
                            emptyList()
                        } else {
                            retained.drop((sub.cursor - baseOffset).coerceAtLeast(0).toInt())
                        }
                    }
                }
            lock.withLock { readers.getOrPut(id) { minted } }
        }

    fun subscribe(
        id: String,
        durability: TopicDurability,
    ): TopicSubscribeOutcome {
        val outcome =
            lock.withLock {
                val existing = subscriptions[id]
                if (existing != null) {
                    if (existing.connected) {
                        TopicSubscribeOutcome.AlreadyConnected
                    } else {
                        existing.connected = true
                        TopicSubscribeOutcome.Reconnected
                    }
                } else {
                    subscriptions[id] =
                        FlavorTopicSubscription(baseOffset + retained.size, durability, connected = true)
                    TopicSubscribeOutcome.Created
                }
            }
        val reader = ensureReader(id)
        if (outcome == TopicSubscribeOutcome.Reconnected) {
            ctx.invalidateSlots(intArrayOf(reader.id))
        }
        return outcome
    }

    fun reconnect(id: String): TopicSubscribeOutcome = subscribe(id, TopicDurability.Durable)

    fun disconnect(id: String): Boolean {
        var root: Int? = null
        val changed =
            lock.withLock {
                val sub = subscriptions[id] ?: return false
                if (!sub.connected) return false
                sub.connected = false
                root =
                    if (sub.durability == TopicDurability.Ephemeral) {
                        subscriptions.remove(id)
                        readers.remove(id)?.id
                    } else {
                        readers[id]?.id
                    }
                true
            }
        root?.let { ctx.invalidateSlots(intArrayOf(it)) }
        return changed
    }

    fun publish(value: T): Long {
        val (offset, roots) =
            lock.withLock {
                val offset = baseOffset + retained.size
                retained.addLast(value)
                val roots =
                    subscriptions
                        .filterValues { it.connected && it.cursor <= offset }
                        .keys
                        .mapNotNull { readers[it]?.id }
                        .toIntArray()
                offset to roots
            }
        ctx.invalidateSlots(roots)
        return offset
    }

    fun readStream(id: String): List<T> = readerHandle(id)?.let { requireNotNull(ctx.get(it)) } ?: emptyList()

    fun readStream(
        id: String,
        compute: AsyncComputeContext,
    ): List<T> = readerHandle(id)?.let { requireNotNull(compute.get(it)) } ?: emptyList()

    fun read(id: String): T? = readStream(id).firstOrNull()

    fun read(
        id: String,
        compute: AsyncComputeContext,
    ): T? = readStream(id, compute).firstOrNull()

    fun advance(id: String): T? {
        var root: Int? = null
        val value =
            lock.withLock {
                val sub = subscriptions[id] ?: return null
                val end = baseOffset + retained.size
                if (!sub.connected || sub.cursor >= end) return null
                val value = retained.elementAt((sub.cursor - baseOffset).toInt())
                sub.cursor += 1
                root = readers[id]?.id
                value
            }
        root?.let { ctx.invalidateSlots(intArrayOf(it)) }
        return value
    }

    fun gc(): Int =
        lock.withLock {
            val frontier =
                subscriptions.values
                    .asSequence()
                    .filter { it.durability == TopicDurability.Durable }
                    .map { it.cursor }
                    .minOrNull() ?: (baseOffset + retained.size)
            val remove = (frontier - baseOffset).toInt()
            repeat(remove) { retained.removeFirst() }
            baseOffset = frontier
            remove
        }

    fun baseOffset(): Long = lock.withLock { baseOffset }

    fun endOffset(): Long = lock.withLock { baseOffset + retained.size }

    fun elements(): List<T> = lock.withLock { retained.toList() }

    fun subscription(id: String): TopicSubscriptionSnapshot? =
        lock.withLock {
            subscriptions[id]?.let {
                TopicSubscriptionSnapshot(it.cursor, it.durability, it.connected)
            }
        }

    fun readerHandle(id: String): AsyncContext.AsyncComputed<List<T>>? = lock.withLock { readers[id] }

    fun snapshot(): TopicSnapshot<T> =
        lock.withLock {
            TopicSnapshot(
                baseOffset,
                retained.toList(),
                subscriptions.mapValues { (_, sub) ->
                    TopicSubscriptionSnapshot(sub.cursor, sub.durability, sub.connected)
                },
            )
        }
}
