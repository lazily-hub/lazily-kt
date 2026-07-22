package io.github.lazily

// -- Memoized semantic tree over a CellTree (#lzsemtree) --------------------
//
// Native Kotlin port of `lazily-rs::sem_tree`. The syntactic document tree
// ([CellTree]) holds *input* cells; the **semantic** tree (e.g. "unresolved
// prompts", "drainable heads", "section summaries") is a layer of **memoized
// slots** derived from it. [SemTree] builds one memoized slot per node that
// folds `(node value, child derived values) -> D`.
//
// Because each node has its own memo slot and a parent reads its *children's*
// derived slots (not their raw cells), the derivation is **incremental and
// glitch-free**: editing one node recomputes only that node's **ancestor chain**
// — a sibling subtree's derived value stays cached. And the memo guard means a
// node edit that doesn't change the folded result stops the recompute from
// propagating to the parent at all. Incrementality covers value edits, removals,
// and reorders of children; after structural growth call [build] again.

/**
 * A shared fold `(node value, children derived) -> derived`.
 */
fun interface SemFold<V, D> {
    fun fold(nodeValue: V, childDeriveds: List<D>): D
}

/** Read a slot via the non-reified internal accessor (returns [Any]). */
// Declared on [ComputeOps] so a read inside an [allocSemSlot] body (Compute
// receiver) tracks against the recomputing memo slot; a top-level
// `ctx.slotValue(...)` (Context receiver) is an untracked snapshot (#lzcellkernel).
private fun ComputeOps.slotValue(id: Int): Any = getSlotAny(id)

/** Allocate a memo slot over [compute] without a reified type parameter. */
private fun Context.allocSemSlot(compute: Compute.() -> Any?): Computed<Any> =
    Computed(slotAny(compute))

/**
 * A memoized semantic derivation over a [CellTree]: one `memo` slot per node,
 * each folding `(node value, child derived values) -> D`.
 *
 * @param K node-id type
 * @param D derived value type
 */
class SemTree<K : Any, D : Any> private constructor(
    private val rootId: Int,
    private val nodes: Map<K, Int>,
) {
    /** The root derived slot. */
    fun root(): Computed<@UnsafeVariance D> = Computed(rootId)

    /** Read the derived value at the root (reactive). */
    fun value(ops: ComputeOps): D {
        @Suppress("UNCHECKED_CAST")
        return ops.slotValue(rootId) as D
    }

    /** The derived slot for a node id, if it was present at build time. */
    fun node(id: K): Computed<@UnsafeVariance D>? = nodes[id]?.let { Computed(it) }

    /** Read the derived value at a node id, if present (reactive). */
    fun nodeValue(ops: ComputeOps, id: K): D? {
        val slotId = nodes[id] ?: return null
        @Suppress("UNCHECKED_CAST")
        return ops.slotValue(slotId) as D
    }

    companion object {
        /**
         * Build the semantic tree from [rootId], folding each node with [fold]
         * (`fold(node_value, children_derived) -> derived`). Children are folded
         * in the tree's current order.
         */
        @Suppress("UNCHECKED_CAST")
        fun <K : Any, V : Any, D : Any> build(
            ctx: Context,
            tree: CellTree<K, V>,
            rootId: K,
            fold: SemFold<V, D>,
        ): SemTree<K, D> {
            val nodes = LinkedHashMap<K, Int>()
            val rootSlot = derive(ctx, tree, rootId, fold, nodes)
            nodes[rootId] = rootSlot
            return SemTree(rootSlot, nodes)
        }

        /**
         * Recursively build derived memo slots. Child slots are built first
         * (current structure; no tracking frame is active here, so reading
         * children does not create a spurious subscription).
         *
         * Returns the slot id (so the generic [D] never crosses a reified
         * `memo`/`get` boundary — slots are read back as `Any` and cast at the
         * public API).
         */
        private fun <K : Any, V : Any, D : Any> derive(
            ctx: Context,
            tree: CellTree<K, V>,
            nodeId: K,
            fold: SemFold<V, D>,
            nodes: MutableMap<K, Int>,
        ): Int {
            val childIds = tree.children(nodeId).keysNow()
            val childSlots = LinkedHashMap<K, Int>()
            for (c in childIds) {
                val s = derive(ctx, tree, c, fold, nodes)
                nodes[c] = s
                childSlots[c] = s
            }
            val slot = ctx.allocSemSlot {
                // Value-threaded tracked reads (#lzcellkernel): every read below
                // goes through the [Compute] receiver, so the memo slot subscribes
                // to this node's value cell, its child order slot, and each child
                // memo slot — attributed to the memo slot being recomputed, not an
                // ambient frame.
                @Suppress("UNCHECKED_CAST")
                val v = getCellAny(tree.value(nodeId).id) as V // subscribe to this node's value cell
                val orderSlot = tree.children(nodeId).keys()
                val ids = slotValue(orderSlot.id) as List<*>
                @Suppress("UNCHECKED_CAST")
                val ds = ArrayList<Any?>(ids.size)
                for (id in ids) childSlots[id]?.let { ds.add(slotValue(it)) }
                fold.fold(v, ds as List<D>) as Any
            }
            return slot.id
        }
    }
}
