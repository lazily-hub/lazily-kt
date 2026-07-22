package io.github.lazily

// -- Keyed cell collections --------------------------------------------------
//
// Native Kotlin implementation of the lazily-spec keyed cell collections layer
// (`lazily-spec/cell-model.md` § Keyed cell collections). A `CellMap` is a
// *composition of cells*, not a new cell kind: each entry is an ordinary cell,
// a dedicated membership cell tracks the key set, and a dedicated order cell
// tracks the ordered key list. The three reactive planes are independent:
//
//  - writing an entry value invalidates only that entry's value readers;
//  - inserting / removing a key invalidates membership + order readers, never
//    unrelated entry value readers; and
//  - a pure reorder (atomic move) invalidates order readers only — the
//    membership set is structurally unchanged.
//
// A key resolves to a stable `Source` for its lifetime; moves never
// remove + re-mint (atomic move keeps the same handle, dependents, and
// lineage, bumping the order signal once).

/**
 * Where to place an inserted key relative to the current ordered key list.
 *
 * `At(index)` inserts before the element currently at [index] (end if index ==
 * size). `End` appends. `Before(anchor)` / `After(anchor)` resolve against a
 * sibling key.
 */
sealed class InsertAt<out K> {
    data object End : InsertAt<Nothing>()
    data class At<K>(val index: Int) : InsertAt<K>()
    data class Before<K>(val anchor: K) : InsertAt<K>()
    data class After<K>(val anchor: K) : InsertAt<K>()
}

// Read the value of a cell handle via the non-reified internal accessor.
// Declared on [ComputeOps] so it obeys the surface's tracking discipline: a
// read inside an [allocSlot] body (Compute receiver) tracks against the
// recomputing node; a top-level `ctx.cellValue(...)` (Context receiver) does not
// (#lzcellkernel).
private fun ComputeOps.cellValue(id: Int): Any = getCellAny(id)

/** Allocate a cell holding [value] without a reified type parameter. */
private fun Context.allocCell(): Source<Any> = Source(cellAny(UNSET))

/** Allocate a memo/non-memo slot over [compute] without a reified type parameter. */
private fun Context.allocSlot(compute: Compute.() -> Any?): Computed<Any> =
    Computed(slotAny(compute))

private object UnsetSentinel
private val UNSET: Any = UnsetSentinel

/**
 * A keyed cell collection — a composition of cells mapping keys [K] to
 * per-entry value cells, plus a dedicated membership cell (the key set) and a
 * dedicated order cell (the ordered key list).
 *
 * **Reactive independence** is structural:
 * - [value] / [setValue] touch a single entry cell → only that key's value
 *   readers invalidate.
 * - [insert] / [remove] rewrite the membership set and order list → membership
 *   and order readers invalidate; unrelated entry value readers do not.
 * - [moveTo] / [moveBefore] / [moveAfter] rewrite only the order list → order
 *   readers invalidate; membership readers (`len` / `contains`) and value
 *   readers do not. The moved entry keeps its same cell handle.
 *
 * @param K key type (must be stable by `==`/`hashCode`; typically `String`)
 * @param V entry value type
 */
class CellMap<K : Any, V : Any>(
    private val ctx: Context,
    entries: List<Pair<K, V>> = emptyList(),
) : ReactiveMap<K, V> {
    /** [CellMap] is the input-cell specialization of [ReactiveMap]. */
    override val entryKind: EntryKind get() = EntryKind.Cell
    private val entryCells: MutableMap<K, Source<Any>> = LinkedHashMap()
    private val membershipCell: Source<Any> = ctx.allocCell().also { cell ->
        ctx.setCellAny(cell.id, entries.map { it.first }.toLinkedSet())
    }
    private val orderCell: Source<Any> = ctx.allocCell().also { cell ->
        ctx.setCellAny(cell.id, entries.map { it.first })
    }

    init {
        for ((key, value) in entries) {
            val handle = ctx.allocCell()
            ctx.setCellAny(handle.id, value)
            entryCells[key] = handle
        }
    }

    // -- Value plane ------------------------------------------------------

    /** The stable value-cell handle for [key]; throws if [key] is absent. */
    @Suppress("UNCHECKED_CAST")
    fun value(key: K): Source<V> =
        (entryCells[key] ?: error("CellMap has no entry for key $key")) as Source<V>

    /** Read the current value of [key]; auto-subscribes the reading node. */
    @Suppress("UNCHECKED_CAST")
    fun get(key: K, ops: ComputeOps = ctx): V = ops.getCellAny(value(key).id) as V

    /** Write [value] to [key]'s entry cell. Membership and order are untouched. */
    fun setValue(key: K, value: V) {
        require(containsNow(key)) { "cannot setValue on absent key $key" }
        ctx.setCellAny(value(key).id, value)
    }

    // -- Membership plane -------------------------------------------------

    /** Slot over the membership-set size — a membership reader (`len`). */
    @Suppress("UNCHECKED_CAST")
    fun len(): Computed<Int> =
        ctx.allocSlot { (cellValue(membershipCell.id) as Set<*>).size } as Computed<Int>

    /** Slot over membership of [key] — a membership reader (`contains`). */
    @Suppress("UNCHECKED_CAST")
    fun contains(key: K): Computed<Boolean> =
        ctx.allocSlot { key in (cellValue(membershipCell.id) as Set<*>) } as Computed<Boolean>

    /** Whether [key] is currently a member (non-reactive snapshot). */
    fun containsNow(key: K): Boolean = key in (ctx.cellValue(membershipCell.id) as Set<*>)

    // -- ReactiveMap present-set surface ----------------------------------
    //
    // A CellMap materializes an input cell per member key, so the "present"
    // set is exactly the current membership / order (input cells are always
    // materialized).

    /** Whether [key] is currently materialized (present). Non-reactive; alias of [containsNow]. */
    override fun isPresent(key: K): Boolean = containsNow(key)

    /** The currently-materialized keys, in order. Non-reactive; alias of [keysNow]. */
    override fun presentKeys(): List<K> = keysNow()

    /** Number of currently-materialized entries. Non-reactive. */
    override val presentCount: Int get() = keysNow().size

    // -- Order plane ------------------------------------------------------

    /** Slot over the ordered key list — an order reader (`keys`). */
    @Suppress("UNCHECKED_CAST")
    fun keys(): Computed<List<K>> =
        ctx.allocSlot { cellValue(orderCell.id) as List<*> } as Computed<List<K>>

    /** Current ordered key list (non-reactive snapshot). */
    @Suppress("UNCHECKED_CAST")
    fun keysNow(): List<K> = ctx.cellValue(orderCell.id) as List<K>

    /** Snapshot of the map as ordered (key, value) pairs. */
    fun entriesNow(): List<Pair<K, V>> = keysNow().map { it to get(it) }

    // -- Mutation: insert / remove ---------------------------------------

    /** Insert [key] → [value] at [at]; updates membership + order, mints the entry cell. */
    fun insert(key: K, value: V, at: InsertAt<K> = InsertAt.End): Boolean {
        if (containsNow(key)) return false
        val handle = ctx.allocCell()
        ctx.setCellAny(handle.id, value)
        entryCells[key] = handle
        ctx.batch {
            setCellAny(membershipCell.id, (cellValue(membershipCell.id) as Set<K>) + key)
            setCellAny(orderCell.id, (cellValue(orderCell.id) as List<K>).insertedAt(at, key))
        }
        return true
    }

    /** Remove [key]; updates membership + order (entry handle is retired — lifetime ended). */
    fun remove(key: K): Boolean {
        if (!containsNow(key)) return false
        entryCells.remove(key)
        ctx.batch {
            setCellAny(membershipCell.id, (cellValue(membershipCell.id) as Set<K>) - key)
            setCellAny(orderCell.id, (cellValue(orderCell.id) as List<K>) - key)
        }
        return true
    }

    // -- Mutation: atomic ordered move -----------------------------------
    //
    // A move rewrites only the order cell. The membership set is unchanged, and
    // the entry's cell handle / dependents / lineage are preserved (not a
    // remove + re-mint). Value readers of the moved key are untouched.

    /** Move [key] to absolute index [index] in the order list. */
    fun moveTo(key: K, index: Int) = moveOnly(key) { order -> order.movedTo(key, index) }

    /** Move [key] to immediately before sibling [anchor]. */
    fun moveBefore(key: K, anchor: K) = moveOnly(key) { order -> order.movedBefore(key, anchor) }

    /** Move [key] to immediately after sibling [anchor]. */
    fun moveAfter(key: K, anchor: K) = moveOnly(key) { order -> order.movedAfter(key, anchor) }

    private inline fun moveOnly(key: K, reorder: (List<K>) -> List<K>) {
        require(containsNow(key)) { "cannot move absent key $key" }
        val current = ctx.cellValue(orderCell.id) as List<K>
        ctx.setCellAny(orderCell.id, reorder(current))
    }

    // -- Keyed reconciliation --------------------------------------------

    /**
     * Reconcile this map against [targetOrder] / [targetValues] by applying the
     * minimal move-minimized op set (see [reconcile]). Stable entries (in the
     * LIS, unchanged value) keep their cell handles and are NOT invalidated:
     * removes touch membership/order only, moves touch order only, and updates
     * fire only when a value actually differs.
     */
    fun reconcile(targetOrder: List<K>, targetValues: Map<K, V>) {
        val priorOrder = keysNow()
        val targetSet = targetOrder.toSet()

        // removes: keys in prior not in target (membership/order only).
        for (key in priorOrder) {
            if (key !in targetSet) remove(key)
        }

        // inserts: keys in target not yet present (minted at end, ordered below).
        for (key in targetOrder) {
            if (!containsNow(key)) insert(key, targetValues.getValue(key))
        }

        // updates: changed values only (stable entries' value cells stay untouched).
        for (key in targetOrder) {
            if (get(key) != targetValues.getValue(key)) setValue(key, targetValues.getValue(key))
        }

        // order: LIS-stable keys anchor the rest; only non-stable keys move (atomic).
        val priorIndex = priorOrder.withIndex().associate { (i, k) -> k to i }
        val kept = targetOrder.filter { it in priorIndex }
        val keptPriorIndices = kept.map { priorIndex.getValue(it) }
        val stableIndexSet = longestIncreasingSubsequenceIndices(keptPriorIndices).toSet()
        val stableKeys = kept.mapIndexedNotNull { i, k -> if (i in stableIndexSet) k else null }.toSet()

        val order = keysNow().toMutableList()
        for ((targetIdx, key) in targetOrder.withIndex()) {
            val curIdx = order.indexOf(key)
            if (curIdx == targetIdx) continue
            // Only emit a move for non-stable keys; stable keys stay in place.
            if (key in stableKeys) continue
            order.removeAt(curIdx)
            order.add(targetIdx, key)
            moveTo(key, targetIdx)
        }
    }

    override fun toString(): String =
        "CellMap(order=${keysNow()})"
}

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
private inline fun <K> List<K>.insertedAt(at: InsertAt<K>, key: K): List<K> = when (at) {
    InsertAt.End -> this + key
    is InsertAt.At -> {
        val idx = at.index.coerceIn(0, size)
        toMutableList().apply { add(idx, key) }
    }
    is InsertAt.Before -> {
        val idx = indexOf(at.anchor)
        require(idx >= 0) { "insert Before unknown anchor ${at.anchor}" }
        toMutableList().apply { add(idx, key) }
    }
    is InsertAt.After -> {
        val idx = indexOf(at.anchor)
        require(idx >= 0) { "insert After unknown anchor ${at.anchor}" }
        toMutableList().apply { add(idx + 1, key) }
    }
}

private fun <K> List<K>.movedTo(key: K, index: Int): List<K> {
    val cur = indexOf(key)
    require(cur >= 0) { "moveTo unknown key $key" }
    if (cur == index) return this
    val without = filter { it != key }
    return without.toMutableList().apply { add(index.coerceIn(0, without.size), key) }
}

private fun <K> List<K>.movedBefore(key: K, anchor: K): List<K> {
    require(indexOf(key) >= 0) { "moveBefore unknown key $key" }
    val a = indexOf(anchor)
    require(a >= 0) { "moveBefore unknown anchor $anchor" }
    if (key == anchor) return this
    val without = filter { it != key }
    val newAnchor = without.indexOf(anchor)
    return without.toMutableList().apply { add(newAnchor, key) }
}

private fun <K> List<K>.movedAfter(key: K, anchor: K): List<K> {
    require(indexOf(key) >= 0) { "moveAfter unknown key $key" }
    val a = indexOf(anchor)
    require(a >= 0) { "moveAfter unknown anchor $anchor" }
    if (key == anchor) return this
    val without = filter { it != key }
    val newAnchor = without.indexOf(anchor)
    return without.toMutableList().apply { add(newAnchor + 1, key) }
}

private fun <K> Iterable<K>.toLinkedSet(): Set<K> = linkedSetOf<K>().apply { addAll(this@toLinkedSet) }

// -- Ordered keyed tree ------------------------------------------------------

/**
 * An ordered keyed tree — each node is `(stable id [K], value cell, ordered
 * keyed child collection)`. Per-node value reactivity holds (editing a node
 * invalidates only that node's value readers); per-level membership/order
 * reactivity holds (a sibling-subtree change does not invalidate an unrelated
 * level's child readers); and child reorder inherits the atomic-move
 * guarantee (node ids, value cells, and dependents are preserved).
 *
 * The tree is a composition of cells — not a new cell kind — so per-cell merge
 * applies node-by-node.
 */
class CellTree<K : Any, V : Any>(private val ctx: Context) {
    private val values: MutableMap<K, Source<Any>> = LinkedHashMap()
    private val children: MutableMap<K, CellMap<K, K>> = LinkedHashMap()
    private val parentOf: MutableMap<K, K> = HashMap()
    private val roots: CellMap<K, K> = CellMap(ctx)

    /** The value-cell handle for [node] (stable for the node's lifetime). */
    @Suppress("UNCHECKED_CAST")
    fun value(node: K): Source<V> =
        (values[node] ?: error("CellTree has no node $node")) as Source<V>

    /** Read [node]'s value. */
    @Suppress("UNCHECKED_CAST")
    fun get(node: K, ops: ComputeOps = ctx): V = ops.getCellAny(value(node).id) as V

    /** Write [node]'s value — invalidates only [node]'s value readers. */
    fun setValue(node: K, v: V) {
        require(values.containsKey(node)) { "unknown node $node" }
        ctx.setCellAny(value(node).id, v)
    }

    /** Insert [node] as a root (top-level node). */
    fun addRoot(node: K, v: V): Boolean {
        if (values.containsKey(node)) return false
        val handle = ctx.allocCell()
        ctx.setCellAny(handle.id, v)
        values[node] = handle
        children[node] = CellMap(ctx)
        roots.insert(node, node, InsertAt.End)
        return true
    }

    /** Insert [child] under [parent] with value [v]. */
    fun insertChild(parent: K, child: K, v: V): Boolean {
        require(values.containsKey(parent)) { "unknown parent $parent" }
        if (values.containsKey(child)) return false
        val handle = ctx.allocCell()
        ctx.setCellAny(handle.id, v)
        values[child] = handle
        children[child] = CellMap(ctx)
        parentOf[child] = parent
        children.getValue(parent).insert(child, child, InsertAt.End)
        return true
    }

    /** The ordered child-collection handle for [parent] (membership/order readers). */
    fun children(parent: K): CellMap<K, K> =
        children[parent] ?: error("CellTree has no node $parent")

    /** Move [child] to absolute [index] within [parent]'s child order (atomic). */
    fun moveChildTo(parent: K, child: K, index: Int) =
        children.getValue(parent).moveTo(child, index)

    /** Move [child] before [anchor] within [parent]'s child order (atomic). */
    fun moveChildBefore(parent: K, child: K, anchor: K) =
        children.getValue(parent).moveBefore(child, anchor)

    /** Move [child] after [anchor] within [parent]'s child order (atomic). */
    fun moveChildAfter(parent: K, child: K, anchor: K) =
        children.getValue(parent).moveAfter(child, anchor)

    /** Remove [child] (and detach from its parent). */
    fun remove(node: K): Boolean {
        val parent = parentOf.remove(node)
        if (parent != null) children[parent]?.remove(node)
        else roots.remove(node)
        values.remove(node)
        children.remove(node)
        return true
    }

    /** Root-level ordered key list (non-reactive snapshot). */
    fun rootsNow(): List<K> = roots.keysNow()
}

// -- Keyed reconciliation (move-minimized LIS) -------------------------------

/** Anchor for a keyed-reconciliation [ReconOp.Move]. */
sealed class ReconOp {
    sealed class Anchor {
        data class Before(val sibling: String) : Anchor()
        data class After(val sibling: String) : Anchor()
    }

    data class Insert(val key: String, val after: Anchor? = null) : ReconOp()
    data class Remove(val key: String) : ReconOp()
    data class Move(val key: String, val anchor: Anchor) : ReconOp()
    data class Update(val key: String) : ReconOp()
}

/** Prior/target keyed-sequence state for [reconcile]. */
data class ReconcileState(val order: List<String>, val values: Map<String, Int>)

/**
 * Reconcile a keyed sequence [prior] → [target] **by stable key, not position**,
 * emitting the minimal `{insert, remove, move, update}` op set.
 *
 * Move-minimization: keys already in relative order — the longest-increasing-
 * subsequence (LIS) over their prior indices — MUST NOT move; only the
 * remainder emit a `move`. Applied to a reactive collection, a stable entry
 * (unchanged value, in the LIS) is NOT invalidated by a sibling reorder.
 *
 * Emission order matches the canonical fixture: `remove` (prior-only keys)
 * first, then ops in target order (insert / move / update). Moves anchor to the
 * nearest preceding kept sibling (`After`) or, for a move to the front, `Before`
 * the new first sibling.
 */
fun reconcile(prior: ReconcileState, target: ReconcileState): List<ReconOp> {
    val priorIndex: Map<String, Int> = prior.order.withIndex().associate { (i, k) -> k to i }
    val targetKeys = target.order
    val targetSet = targetKeys.toSet()

    // LIS over prior indices of kept keys, in target order — these stay put.
    val keptInTarget = targetKeys.filter { it in priorIndex }
    val keptPriorIndices = keptInTarget.map { priorIndex.getValue(it) }
    val lisIndexSet = longestIncreasingSubsequenceIndices(keptPriorIndices).toSet()
    val stableKeys = keptInTarget.mapIndexedNotNull { i, k -> if (i in lisIndexSet) k else null }.toSet()
    val targetValues = target.values

    return buildList {
        // removes: keys in prior not in target.
        for (key in prior.order) {
            if (key !in targetSet) add(ReconOp.Remove(key))
        }
        // Walk target, emitting insert / move / update anchored to the last kept sibling.
        var lastKeptSibling: String? = null
        for (key in targetKeys) {
            if (key !in priorIndex) {
                val after = lastKeptSibling?.let { ReconOp.Anchor.After(it) }
                add(ReconOp.Insert(key, after))
            } else if (key !in stableKeys) {
                val anchor: ReconOp.Anchor = if (lastKeptSibling != null) {
                    ReconOp.Anchor.After(lastKeptSibling!!)
                } else {
                    val nextSibling = targetKeys.firstOrNull { it != key }
                    ReconOp.Anchor.Before(nextSibling ?: key)
                }
                add(ReconOp.Move(key, anchor))
            }
            if (targetValues[key] != prior.values[key]) {
                add(ReconOp.Update(key))
            }
            lastKeptSibling = key
        }
    }
}

/** Indices (into [seq]) of a longest strictly-increasing subsequence. */
internal fun longestIncreasingSubsequenceIndices(seq: List<Int>): List<Int> {
    if (seq.isEmpty()) return emptyList()
    // patience-style LIS keeping the index path; O(n log n).
    val tails = mutableListOf<Int>()      // indices into seq, min-tail per pile
    val prev = IntArray(seq.size) { -1 }
    for (i in seq.indices) {
        val v = seq[i]
        var lo = 0
        var hi = tails.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (seq[tails[mid]] < v) lo = mid + 1 else hi = mid
        }
        if (lo > 0) prev[i] = tails[lo - 1]
        if (lo == tails.size) tails.add(i) else tails[lo] = i
    }
    val out = mutableListOf<Int>()
    var k = tails.last()
    while (k >= 0) {
        out.add(k)
        k = prev[k]
    }
    return out.reversed()
}
