package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The async keyed reactive collections (`#reactivemap`, async flavor) — the
 * [AsyncContext] analog of [CellMap] / [SlotMap]. Keys `K` map to per-entry async
 * reactive nodes allocated on the owning [AsyncContext]; present-set state is
 * guarded by a [ReentrantLock] (the [AsyncContext] is itself shareable), so a map
 * can live in a cross-task owner. Mirrors lazily-rs `AsyncCellMap` /
 * `AsyncSlotMap` (feature `async`).
 *
 * Input cells are always resolved. A derived slot, however, resolves
 * **eventually**: a non-blocking read is `null` while pending and resolves to the
 * canonical value once driven — the eventual-transparency law proved in
 * `lazily-formal`'s `AsyncMaterialization` module. Drive a slot to resolution
 * with [AsyncSlotMap.observeAsync].
 */

/**
 * An async keyed **input-cell** collection: the [AsyncContext] [CellMap]
 * specialization. Entries are settable async cells; [entry] mints one on first
 * access (input cells are always resolved) and [set] updates it.
 */
class AsyncCellMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Cell

    private val lock = ReentrantLock()
    private val materialized = LinkedHashMap<K, AsyncContext.AsyncCellHandle<V>>()

    /** Return the async value cell for [key], minting it with [default] on first access. */
    fun entry(ctx: AsyncContext, key: K, default: (K) -> V): AsyncContext.AsyncCellHandle<V> {
        lock.withLock { materialized[key]?.let { return it } }
        val handle = ctx.cell(default(key))
        return lock.withLock {
            materialized[key]?.let { return@withLock it }
            materialized[key] = handle
            handle
        }
    }

    /** Eagerly pre-mint an async value cell for every key in [keys] via [default]. */
    fun materializeAll(ctx: AsyncContext, keys: Iterable<K>, default: (K) -> V) {
        for (key in keys) entry(ctx, key, default)
    }

    /** Set the value at [key], inserting a new entry (via [default]) if it does not exist yet. */
    fun set(ctx: AsyncContext, key: K, value: V) {
        val existing = lock.withLock { materialized[key] }
        if (existing != null) {
            ctx.setCell(existing, value)
            return
        }
        entry(ctx, key) { value }
    }

    /** The existing async value-cell handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): AsyncContext.AsyncCellHandle<V>? = lock.withLock { materialized[key] }

    /** Observe [key]'s value (input cells are always resolved); throws if [key] is absent. */
    fun observe(ctx: AsyncContext, key: K): V {
        val handle = handle(key) ?: error("AsyncCellMap has no entry for key $key")
        return ctx.getCell(handle)
    }

    /** Read the value at [key] if present; `null` if absent. */
    fun get(ctx: AsyncContext, key: K): V? = handle(key)?.let { ctx.getCell(it) }

    override fun isPresent(key: K): Boolean = lock.withLock { materialized.containsKey(key) }

    override fun presentKeys(): List<K> = lock.withLock { materialized.keys.toList() }

    override val presentCount: Int get() = lock.withLock { materialized.size }
}

/**
 * An async keyed **derived-slot** collection: the [AsyncContext] [SlotMap]
 * specialization. [getOrInsertWith] mints a derived slot on first access (**lazy
 * materialization**); [materializeAll] pre-mints the keyset (**eager**). A derived
 * slot resolves asynchronously, so [observe] returns a **nullable** `V?` (`null`
 * while pending); drive it with [observeAsync]. No `set`.
 */
class AsyncSlotMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Slot

    private val lock = ReentrantLock()
    private val materialized = LinkedHashMap<K, AsyncContext.AsyncSlotHandle<V>>()

    private fun mint(ctx: AsyncContext, key: K, factory: (K) -> V): AsyncContext.AsyncSlotHandle<V> {
        lock.withLock { materialized[key]?.let { return it } }
        val handle = ctx.computedAsync { factory(key) }
        return lock.withLock {
            materialized[key]?.let { return@withLock it }
            materialized[key] = handle
            handle
        }
    }

    /**
     * Lazy materialization: mint the derived slot for [key] on first access (via
     * [factory]) and return its handle. Drive it to a value with [observeAsync] or
     * [AsyncContext.getAsync].
     */
    fun getOrInsertWith(ctx: AsyncContext, key: K, factory: (K) -> V): AsyncContext.AsyncSlotHandle<V> =
        mint(ctx, key, factory)

    /** Eager materialization: pre-mint a derived slot for every key in [keys] via [factory]. */
    fun materializeAll(ctx: AsyncContext, keys: Iterable<K>, factory: (K) -> V) {
        for (key in keys) mint(ctx, key, factory)
    }

    /** The existing derived-slot handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): AsyncContext.AsyncSlotHandle<V>? = lock.withLock { materialized[key] }

    /**
     * Non-blocking read: the resolved value for [key], or `null` if absent or still
     * pending. Once resolved this equals the canonical value (eventual transparency).
     */
    fun observe(ctx: AsyncContext, key: K): V? = handle(key)?.let { ctx.get(it) }

    /** Await [key]'s value, driving a pending derived slot to resolution; throws if absent. */
    suspend fun observeAsync(ctx: AsyncContext, key: K): V =
        ctx.getAsync(handle(key) ?: error("AsyncSlotMap has no entry for key $key"))

    override fun isPresent(key: K): Boolean = lock.withLock { materialized.containsKey(key) }

    override fun presentKeys(): List<K> = lock.withLock { materialized.keys.toList() }

    override val presentCount: Int get() = lock.withLock { materialized.size }
}
