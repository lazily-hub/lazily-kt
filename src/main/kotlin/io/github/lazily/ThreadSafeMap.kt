package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The thread-safe keyed reactive collections (`#reactivemap`, thread-safe flavor)
 * — the `Send + Sync` analog of [SourceMap] / [ComputedMap]. Where the single-threaded
 * maps allocate on a [Context], these allocate on a [ThreadSafeContext] and guard
 * their own present-set state behind a [ReentrantLock], so a keyed map can live in
 * a shareable owner reached from more than one thread. Mirrors lazily-rs
 * `ThreadSafeSourceMap` / `ThreadSafeComputedMap` (feature `thread-safe`).
 *
 * The [ThreadSafeComputedMap] specialization also obeys **materialization confluence**
 * (proved in `lazily-formal`'s `Materialization` module as
 * `materialize_present_comm` / `materialize_observe_comm`): the present set and
 * every observed value are independent of the order in which concurrent threads
 * first touch keys. Serializing materialization behind the map lock is what
 * justifies that — a lost race for a key keeps the **first** writer's handle
 * (cell-identity); the loser's freshly-allocated node is orphaned in the context
 * (unreferenced, never observed).
 */

/**
 * A thread-safe keyed **input-cell** collection: the `Send + Sync` [SourceMap]
 * specialization. Entries are settable [ThreadSafeSource]s; [entry] mints a
 * value cell on first access (input cells are always materialized) and [set]
 * updates it.
 */
class ThreadSafeSourceMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Source

    private val lock = ReentrantLock()

    /**
     * Present set + key order + the move algebra, shared with every other
     * flavor. Graph-agnostic; the reactivity below is this map's own.
     */
    private val keyed = KeyedOrder<K, ThreadSafeSource<V>>()

    /**
     * Return the value cell for [key], minting it with [default] on first access.
     * Subsequent calls return the cached handle. Safe to call concurrently: the
     * first writer wins a race so the key keeps a stable handle (cell-identity).
     */
    fun entry(
        ctx: ThreadSafeContext,
        key: K,
        default: (K) -> V,
    ): ThreadSafeSource<V> {
        lock.withLock { keyed.get(key)?.let { return it } }
        val handle = ThreadSafeSource<V>(ctx.cellAny(default(key)))
        val (stored, mutation) = lock.withLock { keyed.insert(key, handle) }
        // Bump off the map lock: a set can drive a dependent recompute that
        // re-enters this map.
        if (mutation.changed) bumpMembership(ctx)
        return stored
    }

    /**
     * Eagerly pre-mint a value cell for every key in [keys] via [default]. Input
     * cells are always materialized, so this is the eager build for a cell map.
     */
    fun materializeAll(
        ctx: ThreadSafeContext,
        keys: Iterable<K>,
        default: (K) -> V,
    ) {
        for (key in keys) entry(ctx, key, default)
    }

    /** Set the value at [key], inserting a new entry (via [default]) if it does not exist yet. */
    fun set(
        ctx: ThreadSafeContext,
        key: K,
        value: V,
    ) {
        val existing = lock.withLock { keyed.get(key) }
        if (existing != null) {
            ctx.set(existing, value)
            return
        }
        entry(ctx, key) { value }
    }

    /** The existing value-cell handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): ThreadSafeSource<V>? = lock.withLock { keyed.get(key) }

    /** Observe [key]'s value (subscribes the reader); throws if [key] is absent. */
    fun observe(
        ctx: ThreadSafeContext,
        key: K,
    ): V {
        val handle = handle(key) ?: error("ThreadSafeSourceMap has no entry for key $key")
        @Suppress("UNCHECKED_CAST")
        return ctx.getCellAny(handle.id) as V
    }

    /** Read the value at [key] if present; `null` if absent. */
    fun get(
        ctx: ThreadSafeContext,
        key: K,
    ): V? {
        val handle = handle(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return ctx.getCellAny(handle.id) as V
    }

    override fun isPresent(key: K): Boolean = lock.withLock { keyed.contains(key) }

    override fun presentKeys(): List<K> = lock.withLock { keyed.keys() }

    override val presentCount: Int get() = lock.withLock { keyed.length }

    // Membership and order signals minted on THIS flavor's graph, lazily because
    // the context arrives per call. A shared graph-agnostic core cannot supply
    // reactivity — each flavor owns its cells.
    private var membershipCell: Int? = null
    private var orderCell: Int? = null
    private var membershipVersion = 0
    private var orderVersion = 0

    private fun membershipId(ctx: ThreadSafeContext): Int = lock.withLock { membershipCell ?: ctx.cellAny(0).also { membershipCell = it } }

    private fun orderId(ctx: ThreadSafeContext): Int = lock.withLock { orderCell ?: ctx.cellAny(0).also { orderCell = it } }

    private fun bumpOrder(ctx: ThreadSafeContext) {
        val target = orderId(ctx)
        val next = lock.withLock { ++orderVersion }
        ctx.setCellAny(target, next)
    }

    private fun bumpMembership(ctx: ThreadSafeContext) {
        val target = membershipId(ctx)
        val next = lock.withLock { ++membershipVersion }
        ctx.setCellAny(target, next)
        bumpOrder(ctx)
    }

    private fun applyMove(
        ctx: ThreadSafeContext,
        outcome: MapMove,
    ): Boolean {
        if (!outcome.applied) return false
        if (outcome.changed) bumpOrder(ctx)
        return true
    }

    // -- Core surface: ordering, atomic move, reactive membership ---------
    //
    // These bind every flavor. The move algebra touches no entry handle and
    // awaits nothing, so it is neither thread- nor async-coloured. Before this,
    // ordering existed only on the single-threaded SourceMap.

    /**
     * Reactive snapshot of the keys in their current order. Subscribes the
     * caller to **order** changes (add/remove **and** move/reorder), not to
     * per-entry value changes.
     */
    fun keys(ctx: ThreadSafeContext): List<K> {
        val target = orderId(ctx)
        ctx.getCellAny(target)
        return presentKeys()
    }

    /** Reactive entry count. Subscribes the caller to membership changes only. */
    fun len(ctx: ThreadSafeContext): Int {
        val target = membershipId(ctx)
        ctx.getCellAny(target)
        return presentCount
    }

    /** Reactive emptiness check. */
    fun isEmpty(ctx: ThreadSafeContext): Boolean = len(ctx) == 0

    /**
     * Reactive membership test for [key]. Subscribes the caller to membership
     * changes (add/remove of any key), not to value changes.
     */
    fun containsKey(
        ctx: ThreadSafeContext,
        key: K,
    ): Boolean {
        val target = membershipId(ctx)
        ctx.getCellAny(target)
        return isPresent(key)
    }

    /** Non-reactive count. */
    val lenUntracked: Int get() = presentCount

    /** Current 0-based position of [key] in the order, or `null`. Non-reactive. */
    fun position(key: K): Int? = lock.withLock { keyed.position(key) }

    /**
     * Atomically move [key] to [index] (`#lzcellmove`). The entry keeps the
     * **same** node, its dependents, and its lineage — unlike a remove +
     * re-mint, which re-allocates and bumps membership twice. Only the order
     * signal is bumped. [index] is clamped to `[0, len)`.
     */
    fun moveTo(
        ctx: ThreadSafeContext,
        key: K,
        index: Int,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveTo(key, index) })

    /** Atomically move [key] to just before [anchor] (`#lzcellmove`). */
    fun moveBefore(
        ctx: ThreadSafeContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveBefore(key, anchor) })

    /** Atomically move [key] to just after [anchor] (`#lzcellmove`). */
    fun moveAfter(
        ctx: ThreadSafeContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveAfter(key, anchor) })

    /**
     * Remove [key]'s entry and bump reactive membership. Returns whether the key
     * was present.
     */
    fun remove(
        ctx: ThreadSafeContext,
        key: K,
    ): Boolean {
        val (_, mutation) = lock.withLock { keyed.remove(key) }
        if (!mutation.changed) return false
        bumpMembership(ctx)
        return true
    }
}

/**
 * A thread-safe keyed **derived-slot** collection: the `Send + Sync` [ComputedMap]
 * specialization. [getOrInsertWith] mints a derived slot on first access (**lazy
 * materialization**); [materializeAll] pre-mints the keyset (**eager**). No `set`
 * (a slot's value is derived). Present-set state is guarded by a [ReentrantLock].
 */
class ThreadSafeComputedMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Computed

    private val lock = ReentrantLock()

    /**
     * Present set + key order + the move algebra, shared with every other
     * flavor. Graph-agnostic; the reactivity below is this map's own.
     */
    private val keyed = KeyedOrder<K, ThreadSafeComputed<V>>()

    private fun mint(
        ctx: ThreadSafeContext,
        key: K,
        factory: (K) -> V,
    ): ThreadSafeComputed<V> {
        // Fast path: already allocated. Release the map lock before touching `ctx`
        // so a context operation can never re-enter this lock.
        lock.withLock { keyed.get(key)?.let { return it } }
        val handle = ThreadSafeComputed<V>(ctx.slotAny(memo = false) { factory(key) })
        // Lost a race: first writer wins so the key keeps a stable handle; our
        // freshly-allocated node is orphaned in `ctx` (never observed).
        val (stored, mutation) = lock.withLock { keyed.insert(key, handle) }
        // Bump off the map lock: a set can drive a dependent recompute that
        // re-enters this map.
        if (mutation.changed) bumpMembership(ctx)
        return stored
    }

    /** Lazy materialization: read [key], minting the derived slot via [factory] if absent. */
    fun getOrInsertWith(
        ctx: ThreadSafeContext,
        key: K,
        factory: (K) -> V,
    ): V {
        @Suppress("UNCHECKED_CAST")
        return ctx.getSlotAny(mint(ctx, key, factory).id) as V
    }

    /** Eager materialization: pre-mint a derived slot for every key in [keys] via [factory]. */
    fun materializeAll(
        ctx: ThreadSafeContext,
        keys: Iterable<K>,
        factory: (K) -> V,
    ) {
        for (key in keys) mint(ctx, key, factory)
    }

    /** The existing derived-slot handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): ThreadSafeComputed<V>? = lock.withLock { keyed.get(key) }

    /** Read the value at [key] if present (does not mint); `null` if absent. */
    fun get(
        ctx: ThreadSafeContext,
        key: K,
    ): V? {
        val handle = handle(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return ctx.getSlotAny(handle.id) as V
    }

    override fun isPresent(key: K): Boolean = lock.withLock { keyed.contains(key) }

    override fun presentKeys(): List<K> = lock.withLock { keyed.keys() }

    override val presentCount: Int get() = lock.withLock { keyed.length }

    // Membership and order signals minted on THIS flavor's graph, lazily because
    // the context arrives per call. A shared graph-agnostic core cannot supply
    // reactivity — each flavor owns its cells.
    private var membershipCell: Int? = null
    private var orderCell: Int? = null
    private var membershipVersion = 0
    private var orderVersion = 0

    private fun membershipId(ctx: ThreadSafeContext): Int = lock.withLock { membershipCell ?: ctx.cellAny(0).also { membershipCell = it } }

    private fun orderId(ctx: ThreadSafeContext): Int = lock.withLock { orderCell ?: ctx.cellAny(0).also { orderCell = it } }

    private fun bumpOrder(ctx: ThreadSafeContext) {
        val target = orderId(ctx)
        val next = lock.withLock { ++orderVersion }
        ctx.setCellAny(target, next)
    }

    private fun bumpMembership(ctx: ThreadSafeContext) {
        val target = membershipId(ctx)
        val next = lock.withLock { ++membershipVersion }
        ctx.setCellAny(target, next)
        bumpOrder(ctx)
    }

    private fun applyMove(
        ctx: ThreadSafeContext,
        outcome: MapMove,
    ): Boolean {
        if (!outcome.applied) return false
        if (outcome.changed) bumpOrder(ctx)
        return true
    }

    // -- Core surface: ordering, atomic move, reactive membership ---------
    //
    // These bind every flavor. The move algebra touches no entry handle and
    // awaits nothing, so it is neither thread- nor async-coloured. Before this,
    // ordering existed only on the single-threaded SourceMap.

    /**
     * Reactive snapshot of the keys in their current order. Subscribes the
     * caller to **order** changes (add/remove **and** move/reorder), not to
     * per-entry value changes.
     */
    fun keys(ctx: ThreadSafeContext): List<K> {
        val target = orderId(ctx)
        ctx.getCellAny(target)
        return presentKeys()
    }

    /** Reactive entry count. Subscribes the caller to membership changes only. */
    fun len(ctx: ThreadSafeContext): Int {
        val target = membershipId(ctx)
        ctx.getCellAny(target)
        return presentCount
    }

    /** Reactive emptiness check. */
    fun isEmpty(ctx: ThreadSafeContext): Boolean = len(ctx) == 0

    /**
     * Reactive membership test for [key]. Subscribes the caller to membership
     * changes (add/remove of any key), not to value changes.
     */
    fun containsKey(
        ctx: ThreadSafeContext,
        key: K,
    ): Boolean {
        val target = membershipId(ctx)
        ctx.getCellAny(target)
        return isPresent(key)
    }

    /** Non-reactive count. */
    val lenUntracked: Int get() = presentCount

    /** Current 0-based position of [key] in the order, or `null`. Non-reactive. */
    fun position(key: K): Int? = lock.withLock { keyed.position(key) }

    /**
     * Atomically move [key] to [index] (`#lzcellmove`). The entry keeps the
     * **same** node, its dependents, and its lineage — unlike a remove +
     * re-mint, which re-allocates and bumps membership twice. Only the order
     * signal is bumped. [index] is clamped to `[0, len)`.
     */
    fun moveTo(
        ctx: ThreadSafeContext,
        key: K,
        index: Int,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveTo(key, index) })

    /** Atomically move [key] to just before [anchor] (`#lzcellmove`). */
    fun moveBefore(
        ctx: ThreadSafeContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveBefore(key, anchor) })

    /** Atomically move [key] to just after [anchor] (`#lzcellmove`). */
    fun moveAfter(
        ctx: ThreadSafeContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveAfter(key, anchor) })

    /**
     * Remove [key]'s entry and bump reactive membership. Returns whether the key
     * was present.
     */
    fun remove(
        ctx: ThreadSafeContext,
        key: K,
    ): Boolean {
        val (_, mutation) = lock.withLock { keyed.remove(key) }
        if (!mutation.changed) return false
        bumpMembership(ctx)
        return true
    }
}

/** Deprecated pre-v2 spelling of [ThreadSafeSourceMap] (`#lzcellkernel`). */
@Deprecated("renamed to ThreadSafeSourceMap", ReplaceWith("ThreadSafeSourceMap<K, V>"))
typealias ThreadSafeCellMap<K, V> = ThreadSafeSourceMap<K, V>

/** Deprecated pre-v2 spelling of [ThreadSafeComputedMap] (`#lzcellkernel`). */
@Deprecated("renamed to ThreadSafeComputedMap", ReplaceWith("ThreadSafeComputedMap<K, V>"))
typealias ThreadSafeSlotMap<K, V> = ThreadSafeComputedMap<K, V>
