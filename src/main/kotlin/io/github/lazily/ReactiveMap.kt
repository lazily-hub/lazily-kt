package io.github.lazily

/**
 * The unified keyed reactive collection layer ([ReactiveMap]) and its
 * [ComputedMap] derived-slot specialization (`#reactivemap`).
 *
 * `lazily-spec/cell-model.md` § "Keyed cell collections" unifies keyed
 * collections on ONE generic primitive — the Rust `ReactiveMap<K, V, H>` over a
 * `MapHandle` trait (`Source` input cells / `Computed` derived slots) — with
 * two specializations:
 *
 * - **[SourceMap]** (input cells) — the settable, membership/order/move collection
 *   in `Collections.kt`; adds cell-only `setValue`/`insert` and eager
 *   value-minting.
 * - **[ComputedMap]** (derived slots) — [getOrInsertWith] mints a slot on first
 *   access (**lazy materialization**); [materializeAll] pre-mints the keyset
 *   (**eager**). A slot's value is derived, so `ComputedMap` has **no `set`**. There
 *   is **no eager/lazy mode flag** — eager is a pre-mint loop, lazy is
 *   mint-on-access.
 *
 * Kotlin cannot carry the `H` handle-kind type parameter the way Rust does, so
 * the two specializations are concrete classes ([SourceMap] / [ComputedMap]) that share
 * the [ReactiveMap] present-set surface and the [EntryKind] handle-kind axis
 * rather than a single generic over `H`. The concurrency flavors follow the same
 * split: [ThreadSafeSourceMap] / [ThreadSafeComputedMap] and [AsyncSourceMap] /
 * [AsyncComputedMap].
 */

/**
 * Which kind of reactive node a [ReactiveMap] entry is — the handle-kind axis the
 * map abstracts over (the Rust `MapHandle` trait / `EntryKind`). Mirrors
 * `EntryKind` in `lazily-formal`'s `Materialization` module.
 *
 * The entry *names* follow the v2 cell kernel (`Source` / `Computed`); the *wire*
 * tags do not. [wireName] pins the cross-binding serialized spelling to the
 * historical `"cell"` / `"slot"`, so the rename cannot leak into fixture or
 * protocol data through Kotlin's default `.name` encoding.
 */
enum class EntryKind(val wireName: String) {
    /** An **input** cell ([Source]) — always materialized on read. Wire tag: `"cell"`. */
    Source("cell"),

    /**
     * A **derived** slot ([Computed]) — materialized eagerly (pre-mint) or lazily
     * on first read. Wire tag: `"slot"`.
     */
    Computed("slot");

    companion object {
        /**
         * Deprecated pre-v2 spelling of [Source]. Enum *entries* cannot be
         * typealiased, so the old spelling survives as a companion property —
         * `EntryKind.Cell` still resolves and still denotes the same constant.
         */
        @Deprecated("renamed to EntryKind.Source", ReplaceWith("EntryKind.Source"))
        val Cell: EntryKind get() = Source

        /** Deprecated pre-v2 spelling of [Computed]. See [Cell]. */
        @Deprecated("renamed to EntryKind.Computed", ReplaceWith("EntryKind.Computed"))
        val Slot: EntryKind get() = Computed

        /**
         * Parse a serialized entry-kind tag, accepting both the historical
         * `"cell"` / `"slot"` wire spellings and the v2 `"source"` / `"computed"`
         * spellings the canonical corpus will flip to. Returns `null` for any
         * other tag — callers must treat that as an error, never as a default.
         */
        fun fromWire(tag: String): EntryKind? = when (tag) {
            "cell", "source" -> Source
            "slot", "computed" -> Computed
            else -> null
        }
    }
}

/**
 * The shared surface of a keyed reactive collection generic over the entry handle
 * kind (the Rust `ReactiveMap<K, V, H>`): the present-set / membership view plus
 * the [entryKind] handle-kind tag. Implemented by the [SourceMap] (input-cell) and
 * [ComputedMap] (derived-slot) specializations and their thread-safe / async flavors.
 */
interface ReactiveMap<K : Any, V : Any> {
    /** This map's entry kind ([EntryKind.Source] for a [SourceMap], [EntryKind.Computed] for a [ComputedMap]). */
    val entryKind: EntryKind

    /** Whether [key] is currently materialized (present in the allocated set). Non-reactive. */
    fun isPresent(key: K): Boolean

    /** The currently-materialized (present) keys, in first-materialization order. Non-reactive. */
    fun presentKeys(): List<K>

    /** Number of currently-materialized (present) entries. Non-reactive. */
    val presentCount: Int
}

/**
 * A keyed **derived-slot** collection (`#reactivemap`): the [ReactiveMap]
 * specialization over the [Computed] handle kind. Every entry is a derived
 * slot; [getOrInsertWith] mints one on first access (**lazy materialization**)
 * and [materializeAll] pre-mints the keyset (**eager**). A slot's value is
 * derived, so `ComputedMap` has **no `set`**.
 *
 * There is no eager/lazy mode flag — eager materialization is the pre-mint loop
 * [materializeAll], lazy is mint-on-access [getOrInsertWith]. Both build the
 * *same* node for a key; strategy changes only *when* the node is allocated,
 * never the observed value (observational transparency). Mirrors lazily-rs
 * `ComputedMap<K, V> = ReactiveMap<K, V, Computed<V>>`.
 *
 * Operations run against the owning [Context], like the rest of `lazily`; the
 * present set only grows (deferral, not de-allocation). [LinkedHashMap] preserves
 * first-materialization order for [presentKeys].
 */
class ComputedMap<K : Any, V : Any> : ReactiveMap<K, V> {
    override val entryKind: EntryKind get() = EntryKind.Computed

    private val materialized = LinkedHashMap<K, Computed<V>>()

    /**
     * Mint (or return the cached) derived-slot node for [key], caching the handle.
     * [factory] is a [ComputeOps] receiver so upstream reads it performs are
     * value-threaded against the minted slot's recompute (`#lzcellkernel`).
     */
    private fun mint(ctx: Context, key: K, factory: ComputeOps.(K) -> V): Computed<V> {
        materialized[key]?.let { return it } // warm: already allocated.
        val handle = Computed<V>(ctx.slotAny { factory(key) })
        materialized[key] = handle
        return handle
    }

    /**
     * Read the value at [key], minting the derived slot via [factory] first if the
     * key is absent — the **lazy materialization** pull (`get_or_insert_with`).
     * Re-reading an existing key returns its current value without re-running
     * [factory].
     */
    fun getOrInsertWith(ops: ComputeOps, key: K, factory: ComputeOps.(K) -> V): V {
        @Suppress("UNCHECKED_CAST")
        return ops.getSlotAny(mint(ops.computeContext, key, factory).id) as V
    }

    /**
     * **Eager materialization**: pre-mint a derived slot for every key in [keys]
     * via [factory], up front. Observationally identical to minting each key
     * lazily on first read — it only changes *when* the nodes are allocated.
     */
    fun materializeAll(ctx: Context, keys: Iterable<K>, factory: ComputeOps.(K) -> V) {
        for (key in keys) mint(ctx, key, factory)
    }

    /** The existing derived-slot handle for [key], or `null`. Non-reactive. */
    fun handle(key: K): Computed<V>? = materialized[key]

    /** Read the value at [key] if present (does not mint); `null` if absent. Reactive on that entry. */
    fun get(ops: ComputeOps, key: K): V? {
        val handle = materialized[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return ops.getSlotAny(handle.id) as V
    }

    override fun isPresent(key: K): Boolean = materialized.containsKey(key)

    override fun presentKeys(): List<K> = materialized.keys.toList()

    override val presentCount: Int get() = materialized.size
}

/**
 * Deprecated pre-v2 spelling of [ComputedMap]. The v2 cell kernel renamed the node
 * kinds to `Source` / `Computed` (`#lzcellkernel`), so the keyed-collection names
 * followed; kept as an alias so existing callers still compile.
 */
@Deprecated("renamed to ComputedMap", ReplaceWith("ComputedMap<K, V>"))
typealias SlotMap<K, V> = ComputedMap<K, V>
