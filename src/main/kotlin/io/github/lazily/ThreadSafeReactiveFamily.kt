package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The thread-safe keyed reactive family ([ThreadSafeReactiveFamily]) — the
 * `Send + Sync` analog of [ReactiveFamily] (`#lzmatmode`, thread-safe flavor).
 *
 * Where [ReactiveFamily] maps keys `K` to per-entry reactive nodes on a
 * single-threaded [Context], this family allocates its nodes on a
 * [ThreadSafeContext] and keeps its own present-set state behind a
 * [ReentrantLock], so a keyed family can live in a shareable owner reached from
 * more than one thread (for example a relay hub stored behind a global lock,
 * where the `RefCell`-backed single-threaded family cannot go). Mirrors
 * lazily-rs `ThreadSafeReactiveFamily<K, V, H>` (feature `thread-safe`).
 *
 * It obeys the same three laws as the single-threaded family (see
 * [ReactiveFamily]):
 * - **Eager/lazy contract:** [eager] materializes every declared node at build;
 *   [lazy] defers derived (slot) nodes to first read. Cell entries
 *   ([EntryKind.Cell]) are always materialized regardless of mode.
 * - **Observational transparency:** [observe] returns an identical value under
 *   either mode.
 * - **Present-set monotonicity:** the materialized set only grows (deferral,
 *   never de-allocation).
 *
 * Plus **materialization confluence** (proved in `lazily-formal`'s
 * `Materialization` module as `materialize_present_comm` /
 * `materialize_observe_comm`): the present set and every observed value are
 * independent of the order in which concurrent threads first touch keys. That
 * order-independence is what justifies serializing materialization behind the
 * family lock: a lost race for a key keeps the **first** writer's handle
 * (cell-identity), and the loser's freshly-allocated node is simply orphaned in
 * the context (unreferenced, never observed).
 *
 * Kotlin cannot infer the entry-handle type the way Rust infers `H`, so the
 * handle kind is chosen by the factory used: [eager] / [lazy] / [new] build a
 * **slot** (derived) family — the case materialization mode is meaningful for —
 * and [cells] builds the **cell** (input) specialization.
 */

/**
 * The entry-handle axis a [ThreadSafeReactiveFamily] abstracts over — the
 * thread-safe analog of [FamilyHandle]. Sealed to [ThreadSafeCellEntry] (input
 * cells, always materialized) and [ThreadSafeSlotEntry] (derived slots,
 * mode-governed).
 */
sealed interface ThreadSafeFamilyHandle<V : Any> {
    /** This entry's kind: [EntryKind.Cell] for input cells, [EntryKind.Slot] for derived slots. */
    val kind: EntryKind

    /** Read this entry's value through [ctx] (subscribes the caller as any cell/slot read does). */
    fun observe(ctx: ThreadSafeContext): V
}

/** A materialized **input cell** entry — always present regardless of mode; writable via [handle]. */
class ThreadSafeCellEntry<V : Any> @PublishedApi internal constructor(
    val handle: ThreadSafeCellHandle<V>,
) : ThreadSafeFamilyHandle<V> {
    override val kind: EntryKind get() = EntryKind.Cell

    @Suppress("UNCHECKED_CAST")
    override fun observe(ctx: ThreadSafeContext): V = ctx.getCellAny(handle.id) as V
}

/** A materialized **derived slot** entry — allocated eagerly at build or lazily on first read. */
class ThreadSafeSlotEntry<V : Any> @PublishedApi internal constructor(
    val handle: ThreadSafeSlotHandle<V>,
) : ThreadSafeFamilyHandle<V> {
    override val kind: EntryKind get() = EntryKind.Slot

    @Suppress("UNCHECKED_CAST")
    override fun observe(ctx: ThreadSafeContext): V = ctx.getSlotAny(handle.id) as V
}

/**
 * The thread-safe unified keyed reactive family (`#lzmatmode`): keys `K` map to
 * per-entry reactive nodes ([ThreadSafeCellEntry] for input cells,
 * [ThreadSafeSlotEntry] for derived slots), allocated per its
 * [MaterializationMode] on the owning [ThreadSafeContext].
 *
 * Its present-set state is guarded by a [ReentrantLock], so the family is safe
 * to share across threads; the reactive nodes themselves live in the
 * [ThreadSafeContext], which has its own lock. See the file docs for the
 * eager/lazy contract, transparency, and materialization confluence.
 */
class ThreadSafeReactiveFamily<K : Any, V : Any> private constructor(
    /** This family's entry kind ([EntryKind.Cell] for a cell family, [EntryKind.Slot] for a slot family). */
    val entryKind: EntryKind,
    /** This family's materialization mode. */
    val mode: MaterializationMode,
    /**
     * Canonical per-key value producer: a derived slot's recompute, or an input
     * cell's initial value.
     */
    private val factory: (K) -> V,
) {
    /** Guards [materialized]; never held while allocating a node on the context. */
    private val lock = ReentrantLock()

    /**
     * Currently-allocated entries (the "present" set), in first-materialization
     * order. Grows on materialize, never shrinks silently — deferral, not
     * de-allocation. [LinkedHashMap] preserves insertion order for [presentKeys].
     */
    private val materialized = LinkedHashMap<K, ThreadSafeFamilyHandle<V>>()

    private fun materializeKey(ctx: ThreadSafeContext, key: K): ThreadSafeFamilyHandle<V> {
        // Fast path: already allocated. Release the family lock before touching
        // `ctx` so a context operation can never re-enter this lock.
        lock.withLock { materialized[key]?.let { return it } }
        val handle: ThreadSafeFamilyHandle<V> = when (entryKind) {
            // An input has no derivation: materialize by setting its value directly.
            EntryKind.Cell -> ThreadSafeCellEntry(ThreadSafeCellHandle(ctx.cellAny(factory(key))))
            // A derived node: the same node an eager build would allocate.
            EntryKind.Slot -> ThreadSafeSlotEntry(ThreadSafeSlotHandle(ctx.slotAny(memo = false) { factory(key) }))
        }
        return lock.withLock {
            // Lost a materialization race for this key: first writer wins so the key
            // keeps a stable handle (cell-identity). Our freshly-allocated node is
            // orphaned in `ctx` (unreferenced, never observed) — a rare, harmless cost.
            materialized[key]?.let { return@withLock it }
            materialized[key] = handle
            handle
        }
    }

    /**
     * Get the entry handle for [key], materializing it on first access (the lazy
     * pull) and caching it. Under eager mode an entry is already present, so this
     * returns the cached handle.
     */
    fun get(ctx: ThreadSafeContext, key: K): ThreadSafeFamilyHandle<V> = materializeKey(ctx, key)

    /**
     * Observe [key]'s value — the transparency law: the returned value is
     * identical under either mode. Materializes the entry if absent.
     */
    fun observe(ctx: ThreadSafeContext, key: K): V = materializeKey(ctx, key).observe(ctx)

    /** Whether [key] is currently materialized (present in the allocated set). Non-reactive. */
    fun isPresent(key: K): Boolean = lock.withLock { materialized.containsKey(key) }

    /** The currently-materialized keys, in first-materialization order. The present set only grows. */
    fun presentKeys(): List<K> = lock.withLock { materialized.keys.toList() }

    /** Number of currently-materialized entries. */
    val presentCount: Int get() = lock.withLock { materialized.size }

    companion object {
        private fun <K : Any, V : Any> build(
            ctx: ThreadSafeContext,
            entryKind: EntryKind,
            mode: MaterializationMode,
            keys: Iterable<K>,
            factory: (K) -> V,
        ): ThreadSafeReactiveFamily<K, V> {
            val fam = ThreadSafeReactiveFamily(entryKind, mode, factory)
            for (key in keys) {
                // Eager materializes every node; lazy materializes only input cells
                // (a cell entry is always materialized regardless of mode; a slot
                // entry only under eager).
                if (entryKind == EntryKind.Cell || mode == MaterializationMode.Eager) {
                    fam.materializeKey(ctx, key)
                }
            }
            return fam
        }

        /**
         * Build an **eager** slot (derived) family: every declared key's node is
         * allocated now. This is the default mode ([MaterializationMode.Eager]).
         */
        fun <K : Any, V : Any> eager(ctx: ThreadSafeContext, keys: Iterable<K>, factory: (K) -> V): ThreadSafeReactiveFamily<K, V> =
            build(ctx, EntryKind.Slot, MaterializationMode.Eager, keys, factory)

        /**
         * Build a **lazy** slot (derived) family: each derived entry is deferred to
         * first read. Pass empty [keys] for a purely on-demand family.
         */
        fun <K : Any, V : Any> lazy(ctx: ThreadSafeContext, keys: Iterable<K>, factory: (K) -> V): ThreadSafeReactiveFamily<K, V> =
            build(ctx, EntryKind.Slot, MaterializationMode.Lazy, keys, factory)

        /** Build a slot family in the **default** mode (eager). Alias for [eager]. */
        fun <K : Any, V : Any> new(ctx: ThreadSafeContext, keys: Iterable<K>, factory: (K) -> V): ThreadSafeReactiveFamily<K, V> =
            eager(ctx, keys, factory)

        /**
         * Build a **cell** (input) family — the input-cell specialization. Entries
         * are input cells, so every declared key is materialized at build under
         * **either** mode; [mode] is accepted only for symmetry and defaults to
         * eager. Entries are writable via [ThreadSafeCellEntry.handle].
         */
        fun <K : Any, V : Any> cells(
            ctx: ThreadSafeContext,
            keys: Iterable<K>,
            mode: MaterializationMode = MaterializationMode.Default,
            factory: (K) -> V,
        ): ThreadSafeReactiveFamily<K, V> = build(ctx, EntryKind.Cell, mode, keys, factory)
    }
}
