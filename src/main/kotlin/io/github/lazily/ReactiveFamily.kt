package io.github.lazily

/**
 * The unified keyed reactive family ([ReactiveFamily]) and its materialization
 * mode (`#lzmatmode`).
 *
 * `lazily-spec/cell-model.md` § "The `ReactiveFamily` vehicle" fixes a **keyed
 * reactive family** that maps keys `K` to per-entry reactive nodes and abstracts
 * over the entry's **handle kind** (the Rust `ReactiveFamily<K, V, H>` type
 * parameter `H`). The handle-kind axis is [EntryKind], carried by the sealed
 * [FamilyHandle]: [CellEntry] wraps a [CellHandle] (input cells) and [SlotEntry]
 * wraps a [SlotHandle] (derived slots) — the two node kinds of the cell model.
 *
 * - **Cell entries** ([EntryKind.Cell]) are **input** nodes. An input has no
 *   derivation to defer, so it is **always materialized** regardless of mode. The
 *   keyed cell collection ([CellFamily]) is this input-cell specialization.
 * - **Slot entries** ([EntryKind.Slot]) are **derived** nodes. These are what
 *   materialization mode governs.
 *
 * ## Materialization mode
 *
 * Materialization mode is **orthogonal** to cell kind: it fixes *when a derived
 * cell's backing node is allocated*, never what it computes or how it converges,
 * and it MUST NOT be observable through any cell's value.
 *
 * - [MaterializationMode.Eager] (**default**) — every derived node is allocated
 *   when the family is built. A read is a direct node access.
 * - [MaterializationMode.Lazy] (opt-in) — a derived node is allocated on its
 *   **first read** ("materialize on pull"), addressed by key. A never-read
 *   derived cell is never allocated. Lazy is a keyed overlay on the eager core,
 *   not a second engine: the first read of key `k` builds the *same* node the
 *   eager build would have, then caches it.
 *
 * Entry kind is orthogonal to mode (proved in `lazily-formal`'s `Materialization`
 * module as `cell_entries_materialized_in_every_mode` /
 * `slot_entries_deferred_under_lazy`): choosing lazy defers only slot entries,
 * never cell entries. Observational transparency
 * (`observe(build(eager, s), id) = observe(build(lazy, s), id) = s.val(id)`)
 * holds: mode changes allocation timing and memory, never observed values.
 *
 * Kotlin cannot infer the entry-handle type the way Rust infers `H`, so the
 * handle kind is chosen by the factory used: [eager] / [lazy] / [new] build a
 * **slot** (derived) family — the case materialization mode is meaningful for —
 * and [cells] builds the **cell** (input) specialization.
 *
 * ```
 * val ctx = Context()
 * // A derived (slot) family of key*3, built lazily: nothing allocated up front.
 * val fam = ReactiveFamily.lazy(ctx, 0 until 1_000_000) { it * 3 }
 * check(fam.presentCount == 0)
 *
 * // First read of a key materializes just that entry ("materialize on pull").
 * check(fam.observe(ctx, 5) == 15)
 * check(fam.presentCount == 1)
 * check(fam.isPresent(5))
 * check(!fam.isPresent(6))
 *
 * // Eager builds the same values up front — observationally identical.
 * val eager = ReactiveFamily.eager(ctx, 0 until 4) { it * 3 }
 * check(eager.mode == MaterializationMode.Eager)
 * check(eager.presentCount == 4)
 * check(eager.observe(ctx, 2) == fam.observe(ctx, 2))
 * ```
 */

/**
 * Which kind of reactive node a [ReactiveFamily] entry is — the handle-kind axis
 * the family abstracts over, kept orthogonal to [MaterializationMode]. Mirrors
 * `EntryKind` in `lazily-formal`'s `Materialization` module.
 */
enum class EntryKind {
    /** An **input** cell ([CellHandle]) — always materialized, any mode. */
    Cell,

    /** A **derived** slot ([SlotHandle]) — materialized eagerly, or lazily on first read. */
    Slot,
}

/**
 * When a [ReactiveFamily]'s derived (slot) entries are allocated. Orthogonal to
 * [EntryKind]; never observable on the value axis. Mirrors `Mode` in
 * `lazily-formal`'s `Materialization` module; the default is [Eager]
 * (`Mode.default = Mode.eager`).
 */
enum class MaterializationMode {
    /** Allocate every derived node up front at build time. The shared core and the required default. */
    Eager,

    /** Allocate a derived node on its first read, keyed rather than handle-addressed. An opt-in overlay. */
    Lazy;

    companion object {
        /** The required default materialization mode. Mirrors formal `Mode.default = eager`. */
        val Default: MaterializationMode = Eager
    }
}

/**
 * The entry-handle axis a [ReactiveFamily] abstracts over (the spec's `H` in
 * `ReactiveFamily<K, V, H>`). Sealed to [CellEntry] (input cells, always
 * materialized) and [SlotEntry] (derived slots, mode-governed) — the two node
 * kinds of the cell model. Bindings do not add new kinds.
 */
sealed interface FamilyHandle<V : Any> {
    /** This entry's kind: [EntryKind.Cell] for input cells, [EntryKind.Slot] for derived slots. */
    val kind: EntryKind

    /** Read this entry's value through [ctx] (subscribes the caller as any cell/slot read does). */
    fun observe(ctx: Context): V
}

/** A materialized **input cell** entry — always present regardless of mode; writable via [handle]. */
class CellEntry<V : Any> @PublishedApi internal constructor(val handle: CellHandle<V>) : FamilyHandle<V> {
    override val kind: EntryKind get() = EntryKind.Cell

    @Suppress("UNCHECKED_CAST")
    override fun observe(ctx: Context): V = ctx.getCellAny(handle.id) as V
}

/** A materialized **derived slot** entry — allocated eagerly at build or lazily on first read. */
class SlotEntry<V : Any> @PublishedApi internal constructor(val handle: SlotHandle<V>) : FamilyHandle<V> {
    override val kind: EntryKind get() = EntryKind.Slot

    @Suppress("UNCHECKED_CAST")
    override fun observe(ctx: Context): V = ctx.getSlotAny(handle.id) as V
}

/**
 * The unified keyed reactive family (`#lzmatmode`): keys `K` map to per-entry
 * reactive nodes ([CellEntry] for input cells, [SlotEntry] for derived slots),
 * allocated per its [MaterializationMode].
 *
 * Operations run against the owning [Context], like the rest of `lazily`. See the
 * file docs for the eager/lazy contract and the [CellFamily] input-cell
 * specialization.
 */
class ReactiveFamily<K : Any, V : Any> private constructor(
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
    /**
     * Currently-allocated entries (the "present" set), in first-materialization
     * order. Grows on materialize, never shrinks silently — deferral, not
     * de-allocation. [LinkedHashMap] preserves insertion order for [presentKeys].
     */
    private val materialized = LinkedHashMap<K, FamilyHandle<V>>()

    private fun materializeKey(ctx: Context, key: K): FamilyHandle<V> {
        materialized[key]?.let { return it } // warm: already allocated.
        val handle: FamilyHandle<V> = when (entryKind) {
            // An input has no derivation: materialize by setting its value directly.
            EntryKind.Cell -> CellEntry(CellHandle(ctx.cellAny(factory(key))))
            // A derived node: the same node an eager build would allocate.
            EntryKind.Slot -> SlotEntry(SlotHandle(ctx.slotAny(memo = false) { factory(key) }))
        }
        materialized[key] = handle
        return handle
    }

    /**
     * Get the entry handle for [key], materializing it on first access (the lazy
     * pull) and caching it. Under eager mode an entry is already present, so this
     * returns the cached handle.
     */
    fun get(ctx: Context, key: K): FamilyHandle<V> = materializeKey(ctx, key)

    /**
     * Observe [key]'s value — the headline transparency law: the returned value is
     * identical under either mode. Materializes the entry if absent.
     */
    fun observe(ctx: Context, key: K): V = materializeKey(ctx, key).observe(ctx)

    /** Whether [key] is currently materialized (present in the allocated set). Non-reactive. */
    fun isPresent(key: K): Boolean = materialized.containsKey(key)

    /** The currently-materialized keys, in first-materialization order. The present set only grows. */
    fun presentKeys(): List<K> = materialized.keys.toList()

    /** Number of currently-materialized entries. */
    val presentCount: Int get() = materialized.size

    companion object {
        private fun <K : Any, V : Any> build(
            ctx: Context,
            entryKind: EntryKind,
            mode: MaterializationMode,
            keys: Iterable<K>,
            factory: (K) -> V,
        ): ReactiveFamily<K, V> {
            val fam = ReactiveFamily(entryKind, mode, factory)
            for (key in keys) {
                // Eager materializes every node; lazy materializes only input
                // cells (a cell entry is always materialized regardless of mode;
                // a slot entry only under eager).
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
        fun <K : Any, V : Any> eager(ctx: Context, keys: Iterable<K>, factory: (K) -> V): ReactiveFamily<K, V> =
            build(ctx, EntryKind.Slot, MaterializationMode.Eager, keys, factory)

        /**
         * Build a **lazy** slot (derived) family: each derived entry is deferred to
         * first read. Pass empty [keys] for a purely on-demand family.
         */
        fun <K : Any, V : Any> lazy(ctx: Context, keys: Iterable<K>, factory: (K) -> V): ReactiveFamily<K, V> =
            build(ctx, EntryKind.Slot, MaterializationMode.Lazy, keys, factory)

        /** Build a slot family in the **default** mode (eager). Alias for [eager]. */
        fun <K : Any, V : Any> new(ctx: Context, keys: Iterable<K>, factory: (K) -> V): ReactiveFamily<K, V> =
            eager(ctx, keys, factory)

        /**
         * Build a **cell** (input) family — the [CellFamily] specialization. Entries
         * are input cells, so every declared key is materialized at build under
         * **either** mode (cells are always materialized); [mode] is accepted only
         * for symmetry and defaults to eager. Entries are writable via
         * [CellEntry.handle].
         */
        fun <K : Any, V : Any> cells(
            ctx: Context,
            keys: Iterable<K>,
            mode: MaterializationMode = MaterializationMode.Default,
            factory: (K) -> V,
        ): ReactiveFamily<K, V> = build(ctx, EntryKind.Cell, mode, keys, factory)
    }
}
