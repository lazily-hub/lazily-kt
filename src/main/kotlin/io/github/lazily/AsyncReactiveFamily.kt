package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The async keyed reactive family ([AsyncReactiveFamily]) — the [AsyncContext]
 * analog of [ReactiveFamily] (`#lzmatmode`, async flavor).
 *
 * Keys `K` map to per-entry async reactive nodes ([AsyncCellEntry] input cells,
 * [AsyncSlotEntry] derived slots) allocated per the family's
 * [MaterializationMode] on the owning [AsyncContext]. Like
 * [ThreadSafeReactiveFamily] it guards its present-set state with a
 * [ReentrantLock] (the [AsyncContext] is itself shareable), so the family can
 * live in a cross-task owner. Mirrors lazily-rs `AsyncReactiveFamily<K, V, H>`
 * (feature `async`).
 *
 * The eager/lazy contract and present-set monotonicity are identical to the
 * single-threaded family. The transparency law, however, is **eventual**: an
 * async derived slot read is `null` while pending and resolves to the canonical
 * value once driven — so [observe] returns a **nullable** `V?`. Input cells are
 * always resolved ([observe] returns non-null). Drive a slot to resolution with
 * [observeAsync] (or [AsyncContext.getAsync] on the handle from [get]). This is
 * the eventual-transparency law proved in `lazily-formal`'s
 * `AsyncMaterialization` module.
 *
 * To keep the three families API-parallel the per-key factory is the same sync
 * `(K) -> V` as the sync/thread-safe families; a derived slot wraps it as a
 * ready async recomputation.
 *
 * Kotlin cannot infer the entry-handle type the way Rust infers `H`, so the
 * handle kind is chosen by the factory used: [eager] / [lazy] / [new] build a
 * **slot** (derived) family and [cells] builds the **cell** (input)
 * specialization.
 */

/**
 * The entry-handle axis an [AsyncReactiveFamily] abstracts over — the async
 * analog of [FamilyHandle]. Sealed to [AsyncCellEntry] (input cells, always
 * resolved) and [AsyncSlotEntry] (derived slots, resolved asynchronously).
 */
sealed interface AsyncFamilyHandle<V : Any> {
    /** This entry's kind: [EntryKind.Cell] for input cells, [EntryKind.Slot] for derived slots. */
    val kind: EntryKind

    /**
     * Non-blocking read: the value for a materialized cell or a resolved slot,
     * `null` for a slot still pending. Drive a pending slot with [observeAsync].
     */
    fun observe(ctx: AsyncContext): V?

    /** Await this entry's value, driving a pending derived slot to resolution. */
    suspend fun observeAsync(ctx: AsyncContext): V
}

/** A materialized **input cell** entry — always resolved regardless of mode; writable via [handle]. */
class AsyncCellEntry<V : Any> @PublishedApi internal constructor(
    val handle: AsyncContext.AsyncCellHandle<V>,
) : AsyncFamilyHandle<V> {
    override val kind: EntryKind get() = EntryKind.Cell

    override fun observe(ctx: AsyncContext): V = ctx.getCell(handle)

    override suspend fun observeAsync(ctx: AsyncContext): V = ctx.getCell(handle)
}

/** A materialized **derived slot** entry — resolved asynchronously; `observe` is `null` while pending. */
class AsyncSlotEntry<V : Any> @PublishedApi internal constructor(
    val handle: AsyncContext.AsyncSlotHandle<V>,
) : AsyncFamilyHandle<V> {
    override val kind: EntryKind get() = EntryKind.Slot

    override fun observe(ctx: AsyncContext): V? = ctx.get(handle)

    override suspend fun observeAsync(ctx: AsyncContext): V = ctx.getAsync(handle)
}

/**
 * The async unified keyed reactive family (`#lzmatmode`): keys `K` map to
 * per-entry async reactive nodes ([AsyncCellEntry] input cells, [AsyncSlotEntry]
 * derived slots), allocated per its [MaterializationMode] on the owning
 * [AsyncContext].
 *
 * Its present-set state is guarded by a [ReentrantLock]. See the file docs for
 * the eager/lazy contract and the eventual-transparency law.
 */
class AsyncReactiveFamily<K : Any, V : Any> private constructor(
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
     * order. Grows on materialize, never shrinks silently. [LinkedHashMap]
     * preserves insertion order for [presentKeys].
     */
    private val materialized = LinkedHashMap<K, AsyncFamilyHandle<V>>()

    private fun materializeKey(ctx: AsyncContext, key: K): AsyncFamilyHandle<V> {
        // Fast path: already allocated. Release the lock before touching `ctx`.
        lock.withLock { materialized[key]?.let { return it } }
        val handle: AsyncFamilyHandle<V> = when (entryKind) {
            // An input has no derivation: materialize by setting its value directly.
            EntryKind.Cell -> AsyncCellEntry(ctx.cell(factory(key)))
            // A derived node whose async recompute is a ready future of the sync value.
            EntryKind.Slot -> AsyncSlotEntry(ctx.computedAsync { factory(key) })
        }
        return lock.withLock {
            // First writer wins on a race so the key keeps a stable handle.
            materialized[key]?.let { return@withLock it }
            materialized[key] = handle
            handle
        }
    }

    /**
     * Get the entry handle for [key], materializing it on first access. For a slot
     * family this is the [AsyncSlotEntry] whose [AsyncSlotEntry.handle] can be
     * driven with [AsyncContext.getAsync].
     */
    fun get(ctx: AsyncContext, key: K): AsyncFamilyHandle<V> = materializeKey(ctx, key)

    /**
     * Non-blocking observe: the value for a cell or resolved slot, `null` for a
     * pending slot. The eventual-transparency law: once resolved, this equals the
     * canonical value under either mode.
     */
    fun observe(ctx: AsyncContext, key: K): V? = materializeKey(ctx, key).observe(ctx)

    /** Await [key]'s value, driving a pending derived slot to resolution. */
    suspend fun observeAsync(ctx: AsyncContext, key: K): V = materializeKey(ctx, key).observeAsync(ctx)

    /** Whether [key] is currently materialized (present). Non-reactive. */
    fun isPresent(key: K): Boolean = lock.withLock { materialized.containsKey(key) }

    /** The currently-materialized keys, in first-materialization order. */
    fun presentKeys(): List<K> = lock.withLock { materialized.keys.toList() }

    /** Number of currently-materialized entries. */
    val presentCount: Int get() = lock.withLock { materialized.size }

    companion object {
        private fun <K : Any, V : Any> build(
            ctx: AsyncContext,
            entryKind: EntryKind,
            mode: MaterializationMode,
            keys: Iterable<K>,
            factory: (K) -> V,
        ): AsyncReactiveFamily<K, V> {
            val fam = AsyncReactiveFamily(entryKind, mode, factory)
            for (key in keys) {
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
        fun <K : Any, V : Any> eager(ctx: AsyncContext, keys: Iterable<K>, factory: (K) -> V): AsyncReactiveFamily<K, V> =
            build(ctx, EntryKind.Slot, MaterializationMode.Eager, keys, factory)

        /**
         * Build a **lazy** slot (derived) family: each derived entry is deferred to
         * first read. Pass empty [keys] for a purely on-demand family.
         */
        fun <K : Any, V : Any> lazy(ctx: AsyncContext, keys: Iterable<K>, factory: (K) -> V): AsyncReactiveFamily<K, V> =
            build(ctx, EntryKind.Slot, MaterializationMode.Lazy, keys, factory)

        /** Build a slot family in the **default** mode (eager). Alias for [eager]. */
        fun <K : Any, V : Any> new(ctx: AsyncContext, keys: Iterable<K>, factory: (K) -> V): AsyncReactiveFamily<K, V> =
            eager(ctx, keys, factory)

        /**
         * Build a **cell** (input) family — the input-cell specialization. Entries
         * are input cells, so every declared key is materialized at build under
         * **either** mode; [mode] is accepted only for symmetry and defaults to
         * eager. Entries are writable via [AsyncCellEntry.handle].
         */
        fun <K : Any, V : Any> cells(
            ctx: AsyncContext,
            keys: Iterable<K>,
            mode: MaterializationMode = MaterializationMode.Default,
            factory: (K) -> V,
        ): AsyncReactiveFamily<K, V> = build(ctx, EntryKind.Cell, mode, keys, factory)
    }
}
