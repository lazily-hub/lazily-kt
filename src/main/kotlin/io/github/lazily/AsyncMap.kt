package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The async keyed reactive collections (`#reactivemap`, async flavor) — the
 * [AsyncContext] analog of [SourceMap] / [ComputedMap]. Keys `K` map to per-entry async
 * reactive nodes allocated on the owning [AsyncContext]; present-set state is
 * guarded by a [ReentrantLock] (the [AsyncContext] is itself shareable), so a map
 * can live in a cross-task owner. Mirrors lazily-rs `AsyncSourceMap` /
 * `AsyncComputedMap` (feature `async`).
 *
 * Input cells are always resolved. A derived slot, however, resolves
 * **eventually**: a non-blocking read is `null` while pending and resolves to the
 * canonical value once driven — the eventual-transparency law proved in
 * `lazily-formal`'s `AsyncMaterialization` module. Drive a slot to resolution
 * with [AsyncComputedMap.observeAsync].
 */

/**
 * An async keyed **input-cell** collection: the [AsyncContext] [SourceMap]
 * specialization. Entries are settable async cells; [entry] mints one on first
 * access (input cells are always resolved) and [set] updates it.
 */
class AsyncSourceMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Source

    private val lock = ReentrantLock()

    /**
     * Present set + key order + the move algebra, shared with every other
     * flavor. Graph-agnostic; the reactivity below is this map's own.
     */
    private val keyed = KeyedOrder<K, AsyncContext.AsyncSource<V>>()

    /** Return the async value cell for [key], minting it with [default] on first access. */
    fun entry(
        ctx: AsyncContext,
        key: K,
        default: (K) -> V,
    ): AsyncContext.AsyncSource<V> {
        lock.withLock { keyed.get(key)?.let { return it } }
        val handle = ctx.source(default(key))
        val (stored, mutation) = lock.withLock { keyed.insert(key, handle) }
        // Bump off the map lock: a set can drive a dependent recompute that
        // re-enters this map.
        if (mutation.changed) bumpMembership(ctx)
        return stored
    }

    /** Eagerly pre-mint an async value cell for every key in [keys] via [default]. */
    fun materializeAll(
        ctx: AsyncContext,
        keys: Iterable<K>,
        default: (K) -> V,
    ) {
        for (key in keys) entry(ctx, key, default)
    }

    /** Set the value at [key], inserting a new entry (via [default]) if it does not exist yet. */
    fun set(
        ctx: AsyncContext,
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

    /** The existing async value-cell handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): AsyncContext.AsyncSource<V>? = lock.withLock { keyed.get(key) }

    /** Observe [key]'s value (input cells are always resolved); throws if [key] is absent. */
    fun observe(
        ctx: AsyncContext,
        key: K,
    ): V {
        val handle = handle(key) ?: error("AsyncSourceMap has no entry for key $key")
        return ctx.get(handle)
    }

    /** Read the value at [key] if present; `null` if absent. */
    fun get(
        ctx: AsyncContext,
        key: K,
        cc: AsyncComputeContext? = null,
    ): V? = handle(key)?.let { if (cc != null) cc.get(it) else ctx.get(it) }

    override fun isPresent(key: K): Boolean = lock.withLock { keyed.contains(key) }

    override fun presentKeys(): List<K> = lock.withLock { keyed.keys() }

    override val presentCount: Int get() = lock.withLock { keyed.length }

    // Membership and order signals minted on THIS flavor's graph, lazily because
    // the context arrives per call. A shared graph-agnostic core cannot supply
    // reactivity — each flavor owns its cells.
    private var membershipCell: AsyncContext.AsyncSource<Int>? = null
    private var orderCell: AsyncContext.AsyncSource<Int>? = null
    private var membershipVersion = 0
    private var orderVersion = 0

    private fun membershipId(ctx: AsyncContext): AsyncContext.AsyncSource<Int> =
        lock.withLock { membershipCell ?: ctx.source(0).also { membershipCell = it } }

    private fun orderId(ctx: AsyncContext): AsyncContext.AsyncSource<Int> =
        lock.withLock { orderCell ?: ctx.source(0).also { orderCell = it } }

    private fun bumpOrder(ctx: AsyncContext) {
        val target = orderId(ctx)
        val next = lock.withLock { ++orderVersion }
        ctx.set(target, next)
    }

    private fun bumpMembership(ctx: AsyncContext) {
        val target = membershipId(ctx)
        val next = lock.withLock { ++membershipVersion }
        ctx.set(target, next)
        bumpOrder(ctx)
    }

    private fun applyMove(
        ctx: AsyncContext,
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
    fun keys(
        ctx: AsyncContext,
        cc: AsyncComputeContext? = null,
    ): List<K> {
        val target = orderId(ctx)
        if (cc != null) cc.get(target) else ctx.get(target)
        return presentKeys()
    }

    /** Reactive entry count. Subscribes the caller to membership changes only. */
    fun len(
        ctx: AsyncContext,
        cc: AsyncComputeContext? = null,
    ): Int {
        val target = membershipId(ctx)
        if (cc != null) cc.get(target) else ctx.get(target)
        return presentCount
    }

    /** Reactive emptiness check. */
    fun isEmpty(
        ctx: AsyncContext,
        cc: AsyncComputeContext? = null,
    ): Boolean = len(ctx, cc) == 0

    /**
     * Reactive membership test for [key]. Subscribes the caller to membership
     * changes (add/remove of any key), not to value changes.
     */
    fun containsKey(
        ctx: AsyncContext,
        key: K,
        cc: AsyncComputeContext? = null,
    ): Boolean {
        val target = membershipId(ctx)
        if (cc != null) cc.get(target) else ctx.get(target)
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
        ctx: AsyncContext,
        key: K,
        index: Int,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveTo(key, index) })

    /** Atomically move [key] to just before [anchor] (`#lzcellmove`). */
    fun moveBefore(
        ctx: AsyncContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveBefore(key, anchor) })

    /** Atomically move [key] to just after [anchor] (`#lzcellmove`). */
    fun moveAfter(
        ctx: AsyncContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveAfter(key, anchor) })

    /**
     * Remove [key]'s entry and bump reactive membership. Returns whether the key
     * was present.
     */
    fun remove(
        ctx: AsyncContext,
        key: K,
    ): Boolean {
        val (_, mutation) = lock.withLock { keyed.remove(key) }
        if (!mutation.changed) return false
        bumpMembership(ctx)
        return true
    }
}

/**
 * An async keyed **derived-slot** collection: the [AsyncContext] [ComputedMap]
 * specialization. [getOrInsertWith] mints a derived slot on first access (**lazy
 * materialization**); [materializeAll] pre-mints the keyset (**eager**). A derived
 * slot resolves asynchronously, so [observe] returns a **nullable** `V?` (`null`
 * while pending); drive it with [observeAsync]. No `set`.
 */
class AsyncComputedMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Computed

    private val lock = ReentrantLock()

    /**
     * Present set + key order + the move algebra, shared with every other
     * flavor. Graph-agnostic; the reactivity below is this map's own.
     */
    private val keyed = KeyedOrder<K, AsyncContext.AsyncComputed<V>>()

    private fun mint(
        ctx: AsyncContext,
        key: K,
        factory: (K) -> V,
    ): AsyncContext.AsyncComputed<V> {
        lock.withLock { keyed.get(key)?.let { return it } }
        val handle = ctx.computedAsync { factory(key) }
        val (stored, mutation) = lock.withLock { keyed.insert(key, handle) }
        // Bump off the map lock: a set can drive a dependent recompute that
        // re-enters this map.
        if (mutation.changed) bumpMembership(ctx)
        return stored
    }

    /**
     * Lazy materialization: mint the derived slot for [key] on first access (via
     * [factory]) and return its handle. Drive it to a value with [observeAsync] or
     * [AsyncContext.getAsync].
     */
    fun getOrInsertWith(
        ctx: AsyncContext,
        key: K,
        factory: (K) -> V,
    ): AsyncContext.AsyncComputed<V> = mint(ctx, key, factory)

    /** Eager materialization: pre-mint a derived slot for every key in [keys] via [factory]. */
    fun materializeAll(
        ctx: AsyncContext,
        keys: Iterable<K>,
        factory: (K) -> V,
    ) {
        for (key in keys) mint(ctx, key, factory)
    }

    /** The existing derived-slot handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): AsyncContext.AsyncComputed<V>? = lock.withLock { keyed.get(key) }

    /**
     * Non-blocking read: the resolved value for [key], or `null` if absent or still
     * pending. Once resolved this equals the canonical value (eventual transparency).
     */
    fun observe(
        ctx: AsyncContext,
        key: K,
    ): V? = handle(key)?.let { ctx.get(it) }

    /** Await [key]'s value, driving a pending derived slot to resolution; throws if absent. */
    suspend fun observeAsync(
        ctx: AsyncContext,
        key: K,
    ): V = ctx.getAsync(handle(key) ?: error("AsyncComputedMap has no entry for key $key"))

    override fun isPresent(key: K): Boolean = lock.withLock { keyed.contains(key) }

    override fun presentKeys(): List<K> = lock.withLock { keyed.keys() }

    override val presentCount: Int get() = lock.withLock { keyed.length }

    // Membership and order signals minted on THIS flavor's graph, lazily because
    // the context arrives per call. A shared graph-agnostic core cannot supply
    // reactivity — each flavor owns its cells.
    private var membershipCell: AsyncContext.AsyncSource<Int>? = null
    private var orderCell: AsyncContext.AsyncSource<Int>? = null
    private var membershipVersion = 0
    private var orderVersion = 0

    private fun membershipId(ctx: AsyncContext): AsyncContext.AsyncSource<Int> =
        lock.withLock { membershipCell ?: ctx.source(0).also { membershipCell = it } }

    private fun orderId(ctx: AsyncContext): AsyncContext.AsyncSource<Int> =
        lock.withLock { orderCell ?: ctx.source(0).also { orderCell = it } }

    private fun bumpOrder(ctx: AsyncContext) {
        val target = orderId(ctx)
        val next = lock.withLock { ++orderVersion }
        ctx.set(target, next)
    }

    private fun bumpMembership(ctx: AsyncContext) {
        val target = membershipId(ctx)
        val next = lock.withLock { ++membershipVersion }
        ctx.set(target, next)
        bumpOrder(ctx)
    }

    private fun applyMove(
        ctx: AsyncContext,
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
    fun keys(
        ctx: AsyncContext,
        cc: AsyncComputeContext? = null,
    ): List<K> {
        val target = orderId(ctx)
        if (cc != null) cc.get(target) else ctx.get(target)
        return presentKeys()
    }

    /** Reactive entry count. Subscribes the caller to membership changes only. */
    fun len(
        ctx: AsyncContext,
        cc: AsyncComputeContext? = null,
    ): Int {
        val target = membershipId(ctx)
        if (cc != null) cc.get(target) else ctx.get(target)
        return presentCount
    }

    /** Reactive emptiness check. */
    fun isEmpty(
        ctx: AsyncContext,
        cc: AsyncComputeContext? = null,
    ): Boolean = len(ctx, cc) == 0

    /**
     * Reactive membership test for [key]. Subscribes the caller to membership
     * changes (add/remove of any key), not to value changes.
     */
    fun containsKey(
        ctx: AsyncContext,
        key: K,
        cc: AsyncComputeContext? = null,
    ): Boolean {
        val target = membershipId(ctx)
        if (cc != null) cc.get(target) else ctx.get(target)
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
        ctx: AsyncContext,
        key: K,
        index: Int,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveTo(key, index) })

    /** Atomically move [key] to just before [anchor] (`#lzcellmove`). */
    fun moveBefore(
        ctx: AsyncContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveBefore(key, anchor) })

    /** Atomically move [key] to just after [anchor] (`#lzcellmove`). */
    fun moveAfter(
        ctx: AsyncContext,
        key: K,
        anchor: K,
    ): Boolean = applyMove(ctx, lock.withLock { keyed.moveAfter(key, anchor) })

    /**
     * Remove [key]'s entry and bump reactive membership. Returns whether the key
     * was present.
     */
    fun remove(
        ctx: AsyncContext,
        key: K,
    ): Boolean {
        val (_, mutation) = lock.withLock { keyed.remove(key) }
        if (!mutation.changed) return false
        bumpMembership(ctx)
        return true
    }
}

/** Deprecated pre-v2 spelling of [AsyncSourceMap] (`#lzcellkernel`). */
@Deprecated("renamed to AsyncSourceMap", ReplaceWith("AsyncSourceMap<K, V>"))
typealias AsyncCellMap<K, V> = AsyncSourceMap<K, V>

/** Deprecated pre-v2 spelling of [AsyncComputedMap] (`#lzcellkernel`). */
@Deprecated("renamed to AsyncComputedMap", ReplaceWith("AsyncComputedMap<K, V>"))
typealias AsyncSlotMap<K, V> = AsyncComputedMap<K, V>
