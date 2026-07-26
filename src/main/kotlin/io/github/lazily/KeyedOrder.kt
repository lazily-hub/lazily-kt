package io.github.lazily

/**
 * What a present-set mutation did, so the caller knows which signals to bump.
 *
 * A no-op must bump nothing: bumping on a warm insert would invalidate every
 * `len` / `contains` reader on a pure cache hit.
 */
enum class MapMutation {
    None,
    Inserted,
    Removed;

    /** Whether anything changed. */
    val changed: Boolean get() = this != None
}

/**
 * What an ordering move did.
 *
 * [Missing] and [Unchanged] are distinct because the public `move*` methods
 * report `false` for a missing key but `true` for a no-op move — while neither
 * may bump the order signal.
 */
enum class MapMove {
    Missing,
    Unchanged,
    Reordered;

    /** Whether the move applied at all (the `Boolean` the public API returns). */
    val applied: Boolean get() = this != Missing

    /** Whether the order actually changed, i.e. whether to bump. */
    val changed: Boolean get() = this == Reordered
}

/**
 * The present set plus its authoritative key order, with the atomic-move algebra
 * (`#lzcellmove`).
 *
 * This is the **graph-agnostic** half of every keyed-collection flavor. It holds
 * no context, no factory, and no closure: only `K -> handle` bookkeeping and the
 * key list. That is exactly why ordering and atomic move bind the
 * single-threaded, thread-safe, and async flavors alike — a move touches no entry
 * handle and awaits nothing, so it is neither thread- nor async-coloured.
 *
 * What is deliberately **not** here is reactivity. Membership and order
 * *invalidation* is a graph write, and each flavor must mint its own version
 * cells on its own graph; a shared core cannot supply them. Each flavor keeps a
 * thin shell holding this core, its own lock, its own signals, and its own
 * materialize/observe.
 *
 * `entries` and `order` stay in lockstep: every key in one appears exactly once
 * in the other, including on every failure path. Reordering cannot fail — it is a
 * remove + add with both ends clamped — so there is no error path to desync on.
 *
 * Rust reference: `lazily-rs/src/keyed_order.rs`.
 */
class KeyedOrder<K : Any, H : Any> {
    private val entries = LinkedHashMap<K, H>()
    private val order = ArrayList<K>()

    // -- reads (no graph involvement) -------------------------------------

    fun get(key: K): H? = entries[key]

    fun contains(key: K): Boolean = entries.containsKey(key)

    /** A copy of the authoritative key list; the internal list never escapes. */
    fun keys(): List<K> = order.toList()

    val length: Int get() = order.size

    fun position(key: K): Int? = order.indexOf(key).takeIf { it >= 0 }

    // -- present-set mutations --------------------------------------------

    /**
     * Insert [handle] under [key], appending to the order. A warm key keeps its
     * existing handle (cell-identity: a key's node is stable for its lifetime)
     * and reports [MapMutation.None] so the caller bumps nothing.
     */
    fun insert(key: K, handle: H): Pair<H, MapMutation> {
        val existing = entries[key]
        if (existing != null) return existing to MapMutation.None
        entries[key] = handle
        order.add(key)
        return handle to MapMutation.Inserted
    }

    /**
     * Remove [key], returning its handle so the caller can dispose the node on
     * its own graph. The core never touches a handle.
     */
    fun remove(key: K): Pair<H?, MapMutation> {
        val handle = entries.remove(key) ?: return null to MapMutation.None
        order.remove(key)
        return handle to MapMutation.Removed
    }

    // -- the move algebra --------------------------------------------------

    /**
     * Move [key] to [index], clamped to `[0, len)`.
     *
     * The entry keeps the same handle, its dependents, and its CRDT lineage —
     * that is what separates a reorder from a remove + re-mint. Both ends are
     * clamped; an unclamped negative index is the defect lazily-js shipped.
     */
    fun moveTo(key: K, index: Int): MapMove {
        val from = order.indexOf(key)
        if (from < 0) return MapMove.Missing
        val to = index.coerceIn(0, order.size - 1)
        if (from == to) return MapMove.Unchanged
        order.removeAt(from)
        order.add(to, key)
        return MapMove.Reordered
    }

    /**
     * Move [key] to just before [anchor].
     *
     * The target is computed on the **pre-removal** list: when [key] currently
     * precedes [anchor], lifting it out shifts [anchor] one slot left, so the
     * insertion point is `anchor - 1`. Getting this wrong lands the key on the
     * far side of its anchor — the defect found in lazily-zig, where
     * `moveBefore("a", "d")` on `[a,b,c,d]` produced `[b,c,d,a]`.
     */
    fun moveBefore(key: K, anchor: K): MapMove {
        val anchorIdx = position(anchor) ?: return MapMove.Missing
        val from = position(key) ?: return MapMove.Missing
        return moveTo(key, if (from < anchorIdx) anchorIdx - 1 else anchorIdx)
    }

    /** Move [key] to just after [anchor]. Same pre-removal reasoning. */
    fun moveAfter(key: K, anchor: K): MapMove {
        val anchorIdx = position(anchor) ?: return MapMove.Missing
        val from = position(key) ?: return MapMove.Missing
        return moveTo(key, if (from <= anchorIdx) anchorIdx else anchorIdx + 1)
    }
}
