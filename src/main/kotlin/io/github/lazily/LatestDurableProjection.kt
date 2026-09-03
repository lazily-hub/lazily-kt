package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Single-threaded reactive projection over [LatestDurableProjectionCore]. */
class LatestDurableProjection<K : Any, V : Any>(
    private val ctx: Context,
    initialGeneration: Long,
) {
    private data class Reader<K : Any, V : Any>(
        val computed: Computed<LatestDurableEntry<K, V>>,
    )

    private val core = LatestDurableProjectionCore<K, V>(initialGeneration)
    private val readers = HashMap<K, Reader<K, V>>()
    private val generationReader = ctx.computed { core.generation }

    fun entry(key: K): Computed<LatestDurableEntry<K, V>> =
        readers.getOrPut(key) { Reader(ctx.computed { core.snapshot(key) }) }.computed

    fun generation(): Computed<Long> = generationReader

    private inline fun <R> mutateKey(
        key: K,
        op: () -> R,
    ): R {
        val before = core.snapshot(key)
        val result = op()
        if (before != core.snapshot(key)) readers[key]?.let { ctx.invalidateSlots(intArrayOf(it.computed.id)) }
        return result
    }

    fun upsertDesired(key: K, epoch: Long, value: V) = mutateKey(key) { core.upsertDesired(key, epoch, value) }

    fun claim(key: K, generation: Long) = mutateKey(key) { core.claim(key, generation) }

    fun ackApplied(key: K, generation: Long, epoch: Long) =
        mutateKey(key) { core.ackApplied(key, generation, epoch) }

    fun failRetryable(key: K, generation: Long, epoch: Long) =
        mutateKey(key) { core.failRetryable(key, generation, epoch) }

    fun reconnect(newGeneration: Long): LatestDurableReconnect {
        val before = core.knownKeys().associateWith(core::snapshot)
        val result = core.reconnect(newGeneration)
        val roots = core.knownKeys().mapNotNull { key -> readers[key]?.takeIf { before[key] != core.snapshot(key) }?.computed?.id }
        val generationChanged = result is LatestDurableReconnect.Advanced
        val allRoots = if (generationChanged) roots + generationReader.id else roots
        if (allRoots.isNotEmpty()) ctx.invalidateSlots(allRoots.toIntArray())
        return result
    }
}

/** Lock-backed reactive projection; core mutation and graph invalidation never hold both locks. */
class ThreadSafeLatestDurableProjection<K : Any, V : Any>(
    private val ctx: ThreadSafeContext,
    initialGeneration: Long,
) {
    private val lock = ReentrantLock()
    private val core = LatestDurableProjectionCore<K, V>(initialGeneration)
    private val readers = HashMap<K, ThreadSafeComputed<LatestDurableEntry<K, V>>>()
    private val generationReader = ctx.computed { lock.withLock { core.generation } }

    fun entry(key: K): ThreadSafeComputed<LatestDurableEntry<K, V>> {
        lock.withLock { readers[key] }?.let { return it }
        val minted = ctx.computed { lock.withLock { core.snapshot(key) } }
        return lock.withLock { readers.getOrPut(key) { minted } }
    }

    fun generation(): ThreadSafeComputed<Long> = generationReader

    private inline fun <R> mutateKey(key: K, op: () -> R): R {
        val (result, changed) = lock.withLock {
            val before = core.snapshot(key)
            val result = op()
            result to (before != core.snapshot(key))
        }
        if (changed) lock.withLock { readers[key] }?.let { ctx.invalidateSlots(intArrayOf(it.id)) }
        return result
    }

    fun upsertDesired(key: K, epoch: Long, value: V) = mutateKey(key) { core.upsertDesired(key, epoch, value) }

    fun claim(key: K, generation: Long) = mutateKey(key) { core.claim(key, generation) }

    fun ackApplied(key: K, generation: Long, epoch: Long) = mutateKey(key) { core.ackApplied(key, generation, epoch) }

    fun failRetryable(key: K, generation: Long, epoch: Long) = mutateKey(key) { core.failRetryable(key, generation, epoch) }

    fun reconnect(newGeneration: Long): LatestDurableReconnect {
        val (result, changedKeys) = lock.withLock {
            val before = core.knownKeys().associateWith(core::snapshot)
            val result = core.reconnect(newGeneration)
            result to core.knownKeys().filter { before[it] != core.snapshot(it) }
        }
        val roots = lock.withLock { changedKeys.mapNotNull { readers[it]?.id } }.toMutableList()
        if (result is LatestDurableReconnect.Advanced) roots += generationReader.id
        if (roots.isNotEmpty()) ctx.invalidateSlots(roots.toIntArray())
        return result
    }
}

/** Async-graph flavor. The in-memory admission algebra remains synchronous. */
class AsyncLatestDurableProjection<K : Any, V : Any>(
    private val ctx: AsyncContext,
    initialGeneration: Long,
) {
    private val lock = ReentrantLock()
    private val core = LatestDurableProjectionCore<K, V>(initialGeneration)
    private val readers = HashMap<K, AsyncContext.AsyncComputed<LatestDurableEntry<K, V>>>()
    private val generationReader = ctx.computed { lock.withLock { core.generation } }

    fun entry(key: K): AsyncContext.AsyncComputed<LatestDurableEntry<K, V>> {
        lock.withLock { readers[key] }?.let { return it }
        val minted = ctx.computed { lock.withLock { core.snapshot(key) } }
        return lock.withLock { readers.getOrPut(key) { minted } }
    }

    fun generation(): AsyncContext.AsyncComputed<Long> = generationReader

    private inline fun <R> mutateKey(key: K, op: () -> R): R {
        val (result, changed) = lock.withLock {
            val before = core.snapshot(key)
            val result = op()
            result to (before != core.snapshot(key))
        }
        if (changed) lock.withLock { readers[key] }?.let { ctx.invalidateSlots(intArrayOf(it.id)) }
        return result
    }

    fun upsertDesired(key: K, epoch: Long, value: V) = mutateKey(key) { core.upsertDesired(key, epoch, value) }

    fun claim(key: K, generation: Long) = mutateKey(key) { core.claim(key, generation) }

    fun ackApplied(key: K, generation: Long, epoch: Long) = mutateKey(key) { core.ackApplied(key, generation, epoch) }

    fun failRetryable(key: K, generation: Long, epoch: Long) = mutateKey(key) { core.failRetryable(key, generation, epoch) }

    fun reconnect(newGeneration: Long): LatestDurableReconnect {
        val (result, changedKeys) = lock.withLock {
            val before = core.knownKeys().associateWith(core::snapshot)
            val result = core.reconnect(newGeneration)
            result to core.knownKeys().filter { before[it] != core.snapshot(it) }
        }
        val roots = lock.withLock { changedKeys.mapNotNull { readers[it]?.id } }.toMutableList()
        if (result is LatestDurableReconnect.Advanced) roots += generationReader.id
        if (roots.isNotEmpty()) ctx.invalidateSlots(roots.toIntArray())
        return result
    }
}
