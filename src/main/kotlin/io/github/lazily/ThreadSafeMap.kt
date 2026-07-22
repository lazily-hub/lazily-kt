package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The thread-safe keyed reactive collections (`#reactivemap`, thread-safe flavor)
 * — the `Send + Sync` analog of [CellMap] / [SlotMap]. Where the single-threaded
 * maps allocate on a [Context], these allocate on a [ThreadSafeContext] and guard
 * their own present-set state behind a [ReentrantLock], so a keyed map can live in
 * a shareable owner reached from more than one thread. Mirrors lazily-rs
 * `ThreadSafeCellMap` / `ThreadSafeSlotMap` (feature `thread-safe`).
 *
 * The [ThreadSafeSlotMap] specialization also obeys **materialization confluence**
 * (proved in `lazily-formal`'s `Materialization` module as
 * `materialize_present_comm` / `materialize_observe_comm`): the present set and
 * every observed value are independent of the order in which concurrent threads
 * first touch keys. Serializing materialization behind the map lock is what
 * justifies that — a lost race for a key keeps the **first** writer's handle
 * (cell-identity); the loser's freshly-allocated node is orphaned in the context
 * (unreferenced, never observed).
 */

/**
 * A thread-safe keyed **input-cell** collection: the `Send + Sync` [CellMap]
 * specialization. Entries are settable [ThreadSafeSource]s; [entry] mints a
 * value cell on first access (input cells are always materialized) and [set]
 * updates it.
 */
class ThreadSafeCellMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Cell

    private val lock = ReentrantLock()
    private val materialized = LinkedHashMap<K, ThreadSafeSource<V>>()

    /**
     * Return the value cell for [key], minting it with [default] on first access.
     * Subsequent calls return the cached handle. Safe to call concurrently: the
     * first writer wins a race so the key keeps a stable handle (cell-identity).
     */
    fun entry(ctx: ThreadSafeContext, key: K, default: (K) -> V): ThreadSafeSource<V> {
        lock.withLock { materialized[key]?.let { return it } }
        val handle = ThreadSafeSource<V>(ctx.cellAny(default(key)))
        return lock.withLock {
            materialized[key]?.let { return@withLock it }
            materialized[key] = handle
            handle
        }
    }

    /**
     * Eagerly pre-mint a value cell for every key in [keys] via [default]. Input
     * cells are always materialized, so this is the eager build for a cell map.
     */
    fun materializeAll(ctx: ThreadSafeContext, keys: Iterable<K>, default: (K) -> V) {
        for (key in keys) entry(ctx, key, default)
    }

    /** Set the value at [key], inserting a new entry (via [default]) if it does not exist yet. */
    fun set(ctx: ThreadSafeContext, key: K, value: V) {
        val existing = lock.withLock { materialized[key] }
        if (existing != null) {
            ctx.set(existing, value)
            return
        }
        entry(ctx, key) { value }
    }

    /** The existing value-cell handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): ThreadSafeSource<V>? = lock.withLock { materialized[key] }

    /** Observe [key]'s value (subscribes the reader); throws if [key] is absent. */
    fun observe(ctx: ThreadSafeContext, key: K): V {
        val handle = handle(key) ?: error("ThreadSafeCellMap has no entry for key $key")
        @Suppress("UNCHECKED_CAST")
        return ctx.getCellAny(handle.id) as V
    }

    /** Read the value at [key] if present; `null` if absent. */
    fun get(ctx: ThreadSafeContext, key: K): V? {
        val handle = handle(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return ctx.getCellAny(handle.id) as V
    }

    override fun isPresent(key: K): Boolean = lock.withLock { materialized.containsKey(key) }

    override fun presentKeys(): List<K> = lock.withLock { materialized.keys.toList() }

    override val presentCount: Int get() = lock.withLock { materialized.size }
}

/**
 * A thread-safe keyed **derived-slot** collection: the `Send + Sync` [SlotMap]
 * specialization. [getOrInsertWith] mints a derived slot on first access (**lazy
 * materialization**); [materializeAll] pre-mints the keyset (**eager**). No `set`
 * (a slot's value is derived). Present-set state is guarded by a [ReentrantLock].
 */
class ThreadSafeSlotMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Slot

    private val lock = ReentrantLock()
    private val materialized = LinkedHashMap<K, ThreadSafeComputed<V>>()

    private fun mint(ctx: ThreadSafeContext, key: K, factory: (K) -> V): ThreadSafeComputed<V> {
        // Fast path: already allocated. Release the map lock before touching `ctx`
        // so a context operation can never re-enter this lock.
        lock.withLock { materialized[key]?.let { return it } }
        val handle = ThreadSafeComputed<V>(ctx.slotAny(memo = false) { factory(key) })
        return lock.withLock {
            // Lost a race: first writer wins so the key keeps a stable handle; our
            // freshly-allocated node is orphaned in `ctx` (never observed).
            materialized[key]?.let { return@withLock it }
            materialized[key] = handle
            handle
        }
    }

    /** Lazy materialization: read [key], minting the derived slot via [factory] if absent. */
    fun getOrInsertWith(ctx: ThreadSafeContext, key: K, factory: (K) -> V): V {
        @Suppress("UNCHECKED_CAST")
        return ctx.getSlotAny(mint(ctx, key, factory).id) as V
    }

    /** Eager materialization: pre-mint a derived slot for every key in [keys] via [factory]. */
    fun materializeAll(ctx: ThreadSafeContext, keys: Iterable<K>, factory: (K) -> V) {
        for (key in keys) mint(ctx, key, factory)
    }

    /** The existing derived-slot handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): ThreadSafeComputed<V>? = lock.withLock { materialized[key] }

    /** Read the value at [key] if present (does not mint); `null` if absent. */
    fun get(ctx: ThreadSafeContext, key: K): V? {
        val handle = handle(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return ctx.getSlotAny(handle.id) as V
    }

    override fun isPresent(key: K): Boolean = lock.withLock { materialized.containsKey(key) }

    override fun presentKeys(): List<K> = lock.withLock { materialized.keys.toList() }

    override val presentCount: Int get() = lock.withLock { materialized.size }
}
