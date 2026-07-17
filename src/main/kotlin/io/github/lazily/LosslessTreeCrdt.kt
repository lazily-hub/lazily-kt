package io.github.lazily

// -- Lossless full-document tree CRDT — M1 syntax-agnostic core (#lzlosstree) --
//
// Native Kotlin port of `lazily-rs::lossless_tree_crdt`. Where [TextCrdt] is a
// *flat* lossless floor and `SeqCrdt` orders opaque keyed siblings, this is a
// **single rooted concrete-syntax tree** whose *leaves own every rendered byte*.
// The guiding invariant is losslessness — `render(tree) == source_text` for
// valid, invalid, and unknown source alike — so the tree itself can be the wire
// authority instead of a semantic AST layered over a separate text floor.
// Internal Element nodes own *structure only*; all text lives in Leaf nodes
// tagged Token / Trivia / Raw / Error, so unknown or invalid spans round-trip
// exactly as Raw/Error leaves rather than being discarded.
//
// M1 scope: create / tombstone / intra-parent reorder / leaf-edit / split-leaf /
// merge-adjacent-leaves, plus op-based delta sync over a dotted, non-contiguous
// version frontier ([TreeVersionFrontier]). Positions and seed text travel
// inside ops so both replicas store byte-identical keys and converge.
//
// Substrate reuse mirrors the reference: leaf text embeds [TextCrdt] wholesale;
// child order is a minimal move-aware fractional index ([keyBetween], mirroring
// `SeqCrdt`'s proven generator); the clock is a Lamport [TreeOpId]. Leaf-local
// text offsets on the wire are UTF-8 bytes, converted via [Utf8Offsets].

/**
 * A dotted, totally-ordered operation id (Lamport counter tiebroken by peer),
 * ordered `(counter, peer)`. Distinct from [OpId] (the text-CRDT char id).
 */
data class TreeOpId(val counter: Long, val peer: Long) : Comparable<TreeOpId> {
    override fun compareTo(other: TreeOpId): Int {
        val c = counter.compareTo(other.counter); if (c != 0) return c
        return peer.compareTo(other.peer)
    }
}

/** Stable identity of one tree node: the id of the op that created it. */
data class TreeNodeId(val op: TreeOpId) : Comparable<TreeNodeId> {
    override fun compareTo(other: TreeNodeId): Int = op.compareTo(other.op)

    companion object {
        /** The sentinel id of the document root. */
        val ROOT = TreeNodeId(TreeOpId(0L, 0L))
    }
}

/** Classification of a leaf's exact source span. */
enum class LeafKind { Token, Trivia, Raw, Error }

/** What a `CreateNode` materializes: an element shell or a seeded text leaf. */
sealed class NodeSeed {
    data class Element(val kind: String) : NodeSeed()
    data class Leaf(val kind: LeafKind, val text: String) : NodeSeed()
}

/** A fractional-index child position: orderable bytes tiebroken by minting peer. */
data class SortKey(val frac: List<Int>, val peer: Long) : Comparable<SortKey> {
    override fun compareTo(other: SortKey): Int {
        val n = minOf(frac.size, other.frac.size)
        for (i in 0 until n) {
            val c = frac[i].compareTo(other.frac[i]); if (c != 0) return c
        }
        val c = frac.size.compareTo(other.frac.size); if (c != 0) return c
        return peer.compareTo(other.peer)
    }
}

/** The M1 op vocabulary. Positions/seed text travel inside the op. */
sealed class TreeOpKind {
    data class CreateNode(
        val id: TreeNodeId,
        val parent: TreeNodeId,
        val sort: SortKey,
        val seed: NodeSeed,
    ) : TreeOpKind()

    data class Tombstone(val node: TreeNodeId) : TreeOpKind()

    data class Reorder(val node: TreeNodeId, val sort: SortKey) : TreeOpKind()

    data class LeafEdit(val node: TreeNodeId, val prev: TreeOpId, val ops: List<TextOp>) : TreeOpKind()

    data class SplitLeaf(
        val node: TreeNodeId,
        val new: TreeNodeId,
        val sort: SortKey,
        val atChar: Int,
        val prev: TreeOpId,
    ) : TreeOpKind()

    data class MergeLeaves(
        val left: TreeNodeId,
        val right: TreeNodeId,
        val prevLeft: TreeOpId,
        val prevRight: TreeOpId,
    ) : TreeOpKind()
}

/** A transport-ready tree operation: its dotted id plus the change it encodes. */
data class TreeOp(val id: TreeOpId, val kind: TreeOpKind)

/** A batch of ops — the output of [LosslessTreeCrdt.diff], input to [LosslessTreeCrdt.applyUpdate]. */
data class TreeUpdate(val ops: List<TreeOp>)

/** Errors from tree mutations. Text preservation wins, so these reject rather than drop bytes. */
class TreeException(message: String) : RuntimeException(message)

/** The observed dots for one peer: a contiguous prefix plus out-of-order holes. */
internal class DotRange(var contiguous: Long = 0L, val sparse: java.util.TreeSet<Long> = java.util.TreeSet()) {
    fun contains(counter: Long): Boolean = counter <= contiguous || sparse.contains(counter)

    fun observe(counter: Long) {
        if (counter <= contiguous) return
        sparse.add(counter)
        while (sparse.remove(contiguous + 1)) contiguous += 1
    }
}

/**
 * A dotted version frontier: per peer, exactly which op dots are held. Unlike a
 * version vector (per-peer max), this represents non-contiguous delivery so
 * [LosslessTreeCrdt.diff] never omits a missing interior op.
 */
class TreeVersionFrontier internal constructor(private val dots: MutableMap<Long, DotRange>) {
    constructor() : this(HashMap())

    /** Whether the op with dotted [id] is held. */
    fun contains(id: TreeOpId): Boolean = dots[id.peer]?.contains(id.counter) ?: false

    internal fun observe(id: TreeOpId) {
        dots.getOrPut(id.peer) { DotRange() }.observe(id.counter)
    }

    internal fun deepCopy(): TreeVersionFrontier {
        val out = HashMap<Long, DotRange>()
        for ((peer, r) in dots) out[peer] = DotRange(r.contiguous, java.util.TreeSet(r.sparse))
        return TreeVersionFrontier(out)
    }
}

private sealed class NodeBody {
    class Element(val kind: String) : NodeBody()
    class Leaf(val kind: LeafKind, var text: TextCrdt) : NodeBody()
}

private class NodeRecord(
    var parent: TreeNodeId?,
    var sort: SortKey,
    var sortStamp: TreeOpId,
    var body: NodeBody,
    var tomb: TreeOpId?,
    var textHead: TreeOpId,
)

/** A lossless concrete-syntax tree CRDT (M1 core). */
class LosslessTreeCrdt private constructor(
    private var peer: Long,
    private var counter: Long,
    private val nodes: MutableMap<TreeNodeId, NodeRecord>,
    private val frontier: TreeVersionFrontier,
    private val log: MutableList<TreeOp>,
    private val buffered: MutableList<TreeOp>,
    // Secondary parent->children index (#lzlivelchildidx). Replaces the O(N)
    // full-scan in liveChildren that made render() O(N^2). Stores child ids
    // (not records) so the index survives record-body replacement on
    // LeafEdit / SplitLeaf; tombstone/reorder mutate the underlying record
    // in place, so the index only needs parent->child membership.
    private val childrenByParent: MutableMap<TreeNodeId, MutableList<TreeNodeId>>,
) {
    /** A fresh document owned by [peer]: just the root element. */
    constructor(peer: Long) : this(
        peer,
        0L,
        hashMapOf(
            TreeNodeId.ROOT to NodeRecord(
                parent = null,
                sort = SortKey(emptyList(), 0L),
                sortStamp = TreeOpId(0L, 0L),
                body = NodeBody.Element("root"),
                tomb = null,
                textHead = TreeOpId(0L, 0L),
            ),
        ),
        TreeVersionFrontier(),
        mutableListOf(),
        mutableListOf(),
        HashMap(),  // root has no parent → empty index
    )

    /** Fork this replica's full state under a new owning [peer] (deep copy, new identity). */
    fun fork(peer: Long): LosslessTreeCrdt {
        val copiedNodes = copyNodes()
        val index = HashMap<TreeNodeId, MutableList<TreeNodeId>>()
        for ((id, r) in copiedNodes) {
            val parent = r.parent ?: continue
            index.getOrPut(parent) { mutableListOf() }.add(id)
        }
        return LosslessTreeCrdt(
            peer,
            counter,
            copiedNodes,
            frontier.deepCopy(),
            ArrayList(log),
            ArrayList(buffered),
            index,
        )
    }

    private fun copyNodes(): MutableMap<TreeNodeId, NodeRecord> {
        val out = HashMap<TreeNodeId, NodeRecord>(nodes.size)
        for ((id, r) in nodes) {
            val body = when (val b = r.body) {
                is NodeBody.Element -> NodeBody.Element(b.kind)
                is NodeBody.Leaf -> NodeBody.Leaf(b.kind, b.text.clone())
            }
            out[id] = NodeRecord(r.parent, r.sort, r.sortStamp, body, r.tomb, r.textHead)
        }
        return out
    }

    /** Insert child [id] under [parent] in this crdt's index (idempotent under apply replay). */
    private fun indexAdd(parent: TreeNodeId, id: TreeNodeId) {
        childrenByParent.getOrPut(parent) { mutableListOf() }.let { bucket ->
            if (id !in bucket) bucket.add(id)
        }
    }

    private fun nextOpId(): TreeOpId {
        counter += 1
        return TreeOpId(counter, peer)
    }

    /** The live children of [parent], in rendered (SortKey) order. */
    private fun liveChildren(parent: TreeNodeId): List<TreeNodeId> {
        val bucket = childrenByParent[parent] ?: return emptyList()
        // Tombstones stay in the bucket (logical delete); filter + sort at read.
        return bucket
            .mapNotNull { id -> nodes[id]?.let { id to it } }
            .filter { (_, r) -> r.tomb == null }
            .sortedBy { (_, r) -> r.sort }
            .map { (id, _) -> id }
    }

    /** Render the whole document by concatenating live-leaf text in tree order. */
    fun render(): String {
        val sb = StringBuilder()
        renderInto(TreeNodeId.ROOT, sb)
        return sb.toString()
    }

    private fun renderInto(id: TreeNodeId, sb: StringBuilder) {
        when (val body = nodes[id]?.body ?: return) {
            is NodeBody.Leaf -> sb.append(body.text.text())
            is NodeBody.Element -> for (child in liveChildren(id)) renderInto(child, sb)
        }
    }

    /** Live nodes excluding the root — grows by one on split, restored on merge. */
    fun liveNodeCount(): Int = nodes.count { (id, r) -> id != TreeNodeId.ROOT && r.tomb == null }

    /** This replica's dotted version frontier (what to advertise to a partner). */
    fun frontier(): TreeVersionFrontier = frontier.deepCopy()

    /** The kind of an element node, or `null` if [node] is absent or a leaf. */
    fun elementKind(node: TreeNodeId): String? = (nodes[node]?.body as? NodeBody.Element)?.kind

    /** The kind of a leaf node, or `null` if [node] is absent or an element. */
    fun leafKind(node: TreeNodeId): LeafKind? = (nodes[node]?.body as? NodeBody.Leaf)?.kind

    /** The live children of [parent] in rendered order. */
    fun children(parent: TreeNodeId): List<TreeNodeId> = liveChildren(parent)

    /** A leaf's current text; throws if [node] is absent or an element. */
    fun leafText(node: TreeNodeId): String = when (val body = nodes[node]?.body) {
        is NodeBody.Leaf -> body.text.text()
        is NodeBody.Element -> throw TreeException("node is not a leaf")
        null -> throw TreeException("node not found")
    }

    private fun leafBody(node: TreeNodeId): NodeBody.Leaf = when (val body = nodes[node]?.body) {
        is NodeBody.Leaf -> body
        is NodeBody.Element -> throw TreeException("node is not a leaf")
        null -> throw TreeException("node not found")
    }

    /**
     * The fractional key placing a new/moved child of [parent] immediately after
     * [after] (front when `null`). Mirrors `SeqCrdt`'s `key_between`, with the
     * local peer as the tiebreak.
     */
    private fun keyAfter(parent: TreeNodeId, after: TreeNodeId?): SortKey {
        val order = liveChildren(parent)
        val lo: TreeNodeId?
        val hi: TreeNodeId?
        if (after == null) {
            lo = null
            hi = order.firstOrNull()
        } else {
            val idx = order.indexOf(after)
            if (idx >= 0) {
                lo = after
                hi = order.getOrNull(idx + 1)
            } else {
                // Anchor not a live child: append at the end.
                lo = order.lastOrNull()
                hi = null
            }
        }
        val loFrac = lo?.let { nodes.getValue(it).sort.frac }
        val hiFrac = hi?.let { nodes.getValue(it).sort.frac }
        return SortKey(keyBetween(loFrac, hiFrac), peer)
    }

    /** Create a node under [parent], positioned after [after] (front when `null`). */
    fun createNode(parent: TreeNodeId, after: TreeNodeId?, seed: NodeSeed): TreeNodeId {
        if (!nodes.containsKey(parent)) throw TreeException("node not found")
        val sort = keyAfter(parent, after)
        val opId = nextOpId()
        val node = TreeNodeId(opId)
        commitLocal(TreeOp(opId, TreeOpKind.CreateNode(node, parent, sort, seed)))
        return node
    }

    /** Tombstone [node] (its subtree renders away once the ancestor is gone). */
    fun tombstoneNode(node: TreeNodeId) {
        if (!nodes.containsKey(node) || node == TreeNodeId.ROOT) throw TreeException("node not found")
        val opId = nextOpId()
        commitLocal(TreeOp(opId, TreeOpKind.Tombstone(node)))
    }

    /** Reorder [node] within its parent to just after [after] (front when `null`). */
    fun reorderChild(node: TreeNodeId, after: TreeNodeId?) {
        val parent = nodes[node]?.parent ?: throw TreeException("node not found")
        val sort = keyAfter(parent, after)
        val opId = nextOpId()
        commitLocal(TreeOp(opId, TreeOpKind.Reorder(node, sort)))
    }

    /**
     * Edit a leaf's text: delete [deleteBytes] and insert [insert] at UTF-8 byte
     * offset [atByte] (leaf-local). Offsets must land on char boundaries.
     */
    fun editLeaf(node: TreeNodeId, atByte: Int, deleteBytes: Int, insert: String) {
        val s = leafText(node)
        val start = Utf8Offsets.byteToUtf16(s, atByte) ?: throw TreeException("offset not on a char boundary")
        val end = Utf8Offsets.byteToUtf16(s, atByte + deleteBytes)
            ?: throw TreeException("offset not on a char boundary")
        val deleteCount = end - start

        // Re-own the leaf's text under this replica so concurrent edits from
        // different peers mint distinct char ids (no collision on merge).
        val editor = peer
        val leaf = leafBody(node)
        leaf.text = leaf.text.fork(editor)
        val vv = leaf.text.versionVector()
        repeat(deleteCount) { leaf.text.delete(start) }
        leaf.text.insertString(start, insert)
        val ops = leaf.text.deltaSince(vv)

        val prev = nodes.getValue(node).textHead
        val opId = nextOpId()
        commitLocal(TreeOp(opId, TreeOpKind.LeafEdit(node, prev, ops)))
    }

    /**
     * Split a leaf at UTF-8 byte offset [atByte] into two adjacent leaves of the
     * same kind (head keeps [node], tail is a fresh node returned here).
     */
    fun splitLeaf(node: TreeNodeId, atByte: Int): TreeNodeId {
        val s = leafText(node)
        val atChar = Utf8Offsets.byteToCodePoint(s, atByte) ?: throw TreeException("offset not on a char boundary")
        val parent = nodes.getValue(node).parent ?: throw TreeException("node not found")
        val sort = keyAfter(parent, node)
        val prev = nodes.getValue(node).textHead
        val opId = nextOpId()
        val new = TreeNodeId(opId)
        commitLocal(TreeOp(opId, TreeOpKind.SplitLeaf(node, new, sort, atChar, prev)))
        return new
    }

    /** Merge [right] into [left] when they are adjacent live leaf siblings. */
    fun mergeAdjacentLeaves(left: TreeNodeId, right: TreeNodeId) {
        leafText(left) // validate leaf-ness
        leafText(right)
        val parent = nodes.getValue(left).parent ?: throw TreeException("node not found")
        val order = liveChildren(parent)
        val li = order.indexOf(left)
        val adjacent = li >= 0 && order.getOrNull(li + 1) == right
        if (!adjacent) throw TreeException("leaves are not adjacent live siblings")
        val prevLeft = nodes.getValue(left).textHead
        val prevRight = nodes.getValue(right).textHead
        val opId = nextOpId()
        commitLocal(TreeOp(opId, TreeOpKind.MergeLeaves(left, right, prevLeft, prevRight)))
    }

    /** Ops this replica holds that [their] frontier lacks, ordered by dotted id. */
    fun diff(their: TreeVersionFrontier): TreeUpdate {
        val ops = log.filter { !their.contains(it.id) }
            .sortedWith(compareBy({ it.id.counter }, { it.id.peer }))
        return TreeUpdate(ops)
    }

    /**
     * Apply a batch of remote ops. Idempotent (already-held ops skipped) and
     * order-tolerant (an op whose target/parent has not arrived is buffered and
     * retried). Advances the Lamport counter past every observed op.
     */
    fun applyUpdate(update: TreeUpdate) {
        for (op in update.ops) {
            counter = maxOf(counter, op.id.counter)
            if (frontier.contains(op.id)) continue
            buffered.add(op)
        }
        drainBuffered()
    }

    private fun drainBuffered() {
        while (true) {
            var progressed = false
            val pending = ArrayList(buffered)
            buffered.clear()
            for (op in pending) {
                if (frontier.contains(op.id)) continue
                if (dependenciesReady(op)) {
                    applyOp(op)
                    record(op)
                    progressed = true
                } else {
                    buffered.add(op)
                }
            }
            if (!progressed) break
        }
    }

    private fun dependenciesReady(op: TreeOp): Boolean = when (val k = op.kind) {
        is TreeOpKind.CreateNode -> nodes.containsKey(k.parent)
        is TreeOpKind.Tombstone -> nodes.containsKey(k.node)
        is TreeOpKind.Reorder -> nodes.containsKey(k.node)
        is TreeOpKind.LeafEdit -> nodes.containsKey(k.node) && frontier.contains(k.prev)
        is TreeOpKind.SplitLeaf -> nodes.containsKey(k.node) && frontier.contains(k.prev)
        is TreeOpKind.MergeLeaves ->
            nodes.containsKey(k.left) && nodes.containsKey(k.right) &&
                frontier.contains(k.prevLeft) && frontier.contains(k.prevRight)
    }

    private fun commitLocal(op: TreeOp) {
        applyOp(op)
        record(op)
    }

    private fun record(op: TreeOp) {
        frontier.observe(op.id)
        log.add(op)
    }

    private fun applyOp(op: TreeOp) {
        when (val k = op.kind) {
            is TreeOpKind.CreateNode -> {
                if (nodes.containsKey(k.id)) return
                val body = when (val seed = k.seed) {
                    is NodeSeed.Element -> NodeBody.Element(seed.kind)
                    is NodeSeed.Leaf -> NodeBody.Leaf(seed.kind, TextCrdt(k.id.op.peer, seed.text))
                }
                nodes[k.id] = NodeRecord(
                    parent = k.parent,
                    sort = k.sort,
                    sortStamp = op.id,
                    body = body,
                    tomb = null,
                    textHead = op.id,
                )
                indexAdd(k.parent, k.id)
            }
            is TreeOpKind.Tombstone -> {
                val rec = nodes[k.node] ?: return
                rec.tomb = rec.tomb?.let { minOf(it, op.id) } ?: op.id
            }
            is TreeOpKind.Reorder -> {
                val rec = nodes[k.node] ?: return
                if (op.id > rec.sortStamp) {
                    rec.sort = k.sort
                    rec.sortStamp = op.id
                }
            }
            is TreeOpKind.LeafEdit -> {
                val rec = nodes[k.node] ?: return
                val body = rec.body
                if (body is NodeBody.Leaf) {
                    body.text.applyDelta(k.ops)
                    rec.textHead = op.id
                }
            }
            is TreeOpKind.SplitLeaf -> applySplit(k.node, k.new, k.sort, k.atChar, op.id)
            is TreeOpKind.MergeLeaves -> applyMerge(k.left, k.right, op.id)
        }
    }

    private fun applySplit(node: TreeNodeId, new: TreeNodeId, sort: SortKey, atChar: Int, opId: TreeOpId) {
        val rec = nodes[node] ?: return
        val leaf = rec.body as? NodeBody.Leaf ?: return
        val kind = leaf.kind
        val parent = rec.parent
        val s = leaf.text.text()
        val cut = Utf8Offsets.codePointToUtf16(s, atChar)
        val head = s.substring(0, cut)
        val tail = s.substring(cut)
        // Reseed head under the original node's create peer so both replicas
        // rebuild byte-identical leaf state.
        rec.body = NodeBody.Leaf(kind, TextCrdt(node.op.peer, head))
        rec.textHead = opId
        val existedBefore = nodes.containsKey(new)
        nodes.putIfAbsent(
            new,
            NodeRecord(
                parent = parent,
                sort = sort,
                sortStamp = opId,
                body = NodeBody.Leaf(kind, TextCrdt(new.op.peer, tail)),
                tomb = null,
                textHead = opId,
            ),
        )
        if (!existedBefore && parent != null) indexAdd(parent, new)
    }

    private fun applyMerge(left: TreeNodeId, right: TreeNodeId, opId: TreeOpId) {
        val l = nodes[left] ?: return
        val r = nodes[right] ?: return
        val lb = l.body as? NodeBody.Leaf ?: return
        val rb = r.body as? NodeBody.Leaf ?: return
        val combined = lb.text.text() + rb.text.text()
        l.body = NodeBody.Leaf(lb.kind, TextCrdt(left.op.peer, combined))
        l.textHead = opId
        r.tomb = r.tomb?.let { minOf(it, opId) } ?: opId
    }
}

/**
 * Generate a fractional key strictly between [lo] and [hi] (each `null` = open
 * end), compared lexicographically. Mirrors `SeqCrdt`'s `key_between`; bytes are
 * held as `Int` in `0..255`.
 */
internal fun keyBetween(lo: List<Int>?, hi: List<Int>?): List<Int> {
    val result = ArrayList<Int>()
    var i = 0
    val cap = (lo?.size ?: 0) + (hi?.size ?: 0) + 2
    while (i <= cap) {
        val a = lo?.getOrNull(i) ?: 0
        val b = if (hi != null) (hi.getOrNull(i) ?: 0) else 256
        if (a + 1 < b) {
            result.add((a + b) / 2)
            return result
        }
        result.add(a)
        i += 1
        if (a < b) {
            val loTail = if (lo != null && i <= lo.size) lo.subList(i, lo.size).toList() else emptyList()
            result.addAll(keyBetween(loTail, null))
            return result
        }
    }
    result.add(128)
    return result
}
