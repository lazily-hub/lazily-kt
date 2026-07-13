package io.github.lazily

// -- Free-text character sequence CRDT (#lztextcrdt) ------------------------
//
// Native Kotlin port of `lazily-rs::text_crdt`. For arbitrary prose with no
// anchors and **concurrent** edits, the merge unit drops to characters:
// [TextCrdt] merges keystrokes, then you re-parse the merged text and re-derive
// the structural tree. The tree is a *projection* of CRDT-merged text, not the
// merge unit itself.
//
// A Fugue/RGA-style tree CRDT. Each inserted character is an element with a
// unique [OpId] and a **left origin** (the element it was typed after). The
// sequence is the in-order traversal of the origin tree, with same-origin
// siblings ordered by `OpId` descending (newest-after-origin first — the RGA
// tiebreak). Deletes are tombstones. `order` is therefore a pure, deterministic
// function of the element set, so [merge] (a union of elements, tombstones
// sticky) is commutative, associative, and idempotent.

/**
 * A globally-unique, totally-ordered id for one inserted character.
 *
 * Ordered by `(counter, peer)`; the counter is Lamport-style (advances past
 * everything observed on merge), so a causally-later insert sorts higher and a
 * concurrent insert tiebreaks deterministically by peer.
 */
data class OpId(val counter: Long, val peer: Long) : Comparable<OpId> {
    override fun compareTo(other: OpId): Int {
        var c = counter.compareTo(other.counter); if (c != 0) return c
        return peer.compareTo(other.peer)
    }
}

private class Elem(val ch: Char, val origin: OpId?) {
    // `null` while live; `Some(deleteOp)` once tombstoned. Carrying the delete's
    // own OpId (not a bare flag) lets GC test whether the *deletion* is causally
    // stable. Tombstones are sticky; concurrent deletes converge to the min id.
    var deleted: OpId? = null
}

/**
 * One text-CRDT element in transport-ready form (#lztextsync): the wire unit for
 * [TextCrdt.deltaSince] / [TextCrdt.applyDelta]. A whole-state snapshot is
 * `deltaSince(emptyMap())`; rebuilding a replica via [TextCrdt.applyDelta]
 * preserves each character's [OpId] identity so later concurrent edits still merge
 * conflict-free.
 */
data class TextOp(val id: OpId, val ch: Char, val origin: OpId?, val deleted: OpId?)

/**
 * A character-granular, mergeable text buffer for concurrent free-text edits.
 *
 * Merge is commutative, associative, and idempotent; out-of-order, duplicated,
 * or batched delivery all converge.
 */
class TextCrdt private constructor(
    private val elems: MutableMap<OpId, Elem>,
    private var peer: Long,
    private var counter: Long,
) : CrdtTree<TextCrdt, Map<Long, Long>, List<TextOp>, String> {
    /** An empty buffer owned by [peer]. */
    constructor(peer: Long) : this(LinkedHashMap(), peer, 0L)

    /** A buffer owned by [peer] seeded with [s] (a linear chain of characters). */
    constructor(peer: Long, s: String) : this(peer) { insertString(0, s) }

    /** Fork this buffer's state to a new replica [peer] (deep copy, new identity). */
    fun fork(peer: Long): TextCrdt = TextCrdt(copyElems(), peer, counter)

    /** Clone this buffer's state keeping the same peer identity (deep copy). */
    fun clone(): TextCrdt = TextCrdt(copyElems(), peer, counter)

    private fun copyElems(): MutableMap<OpId, Elem> {
        val out = LinkedHashMap<OpId, Elem>()
        for ((id, e) in elems) out[id] = Elem(e.ch, e.origin).apply { deleted = e.deleted }
        return out
    }

    private fun nextId(): OpId {
        counter += 1
        return OpId(counter, peer)
    }

    /** This replica's current Lamport position (`(counter, peer)`). */
    fun clock(): OpId = OpId(counter, peer)

    /** Insert [ch] at visible index [index] (0 = start, `len` = end). */
    fun insert(index: Int, ch: Char) {
        val visible = orderedIds(includeDeleted = false)
        val origin = if (index == 0) null else visible.getOrNull(index - 1)
        val id = nextId()
        elems[id] = Elem(ch, origin)
    }

    /** Insert all of [s] starting at visible index [index]. */
    fun insertString(index: Int, s: String) {
        for ((i, ch) in s.withIndex()) insert(index + i, ch)
    }

    /** Tombstone the visible character at [index]. No-op if out of range. */
    fun delete(index: Int) {
        val visible = orderedIds(includeDeleted = false)
        val id = visible.getOrNull(index) ?: return
        val del = nextId()
        val e = elems[id] ?: return
        if (e.deleted == null) e.deleted = del
    }

    /** The current visible text in sequence order. */
    fun text(): String {
        val ids = orderedIds(includeDeleted = false)
        val sb = StringBuilder(ids.size)
        for (id in ids) elems[id]?.let { sb.append(it.ch) }
        return sb.toString()
    }

    /** Number of visible characters. */
    fun len(): Int = elems.values.count { it.deleted == null }

    /** Number of tombstoned-but-not-yet-collected characters (GC-pressure gauge). */
    fun tombstoneCount(): Int = elems.values.count { it.deleted != null }

    /** Whether there is any visible text. */
    fun isEmpty(): Boolean = len() == 0

    /**
     * Ordered element ids via the origin tree (pre-order DFS; same-origin
     * siblings newest-first). [includeDeleted] keeps tombstones in the order
     * (needed so later origins still resolve), else they are filtered.
     */
    private fun orderedIds(includeDeleted: Boolean): List<OpId> {
        // children[origin] = ids inserted directly after `origin`.
        val children = HashMap<OpId?, MutableList<OpId>>()
        for ((id, e) in elems) {
            children.getOrPut(e.origin) { mutableListOf() }.add(id)
        }
        for (list in children.values) {
            // Descending OpId: the most recent insert-after-origin comes first.
            list.sortWith(reverseOrder())
        }
        val out = ArrayList<OpId>(elems.size)
        // Iterative pre-order DFS; stack holds ids to visit (reversed so the
        // first child is processed first).
        val stack = ArrayDeque<OpId>()
        (children[null] ?: emptyList()).reversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            val e = elems[id] ?: continue
            if (includeDeleted || e.deleted == null) out.add(id)
            val kids = children[OpId(id.counter, id.peer)]
            if (kids != null) {
                // Push reversed so the first (highest-OpId) child pops first.
                kids.reversed().forEach { stack.addLast(it) }
            }
        }
        return out
    }

    /**
     * Merge another replica's edits (commutative, associative, idempotent):
     * union of elements by id, with tombstones sticky (a delete on either side
     * wins). Advances the local counter past everything observed. Returns
     * whether the visible text changed.
     */
    fun merge(other: TextCrdt): Boolean {
        val before = text()
        for ((id, oe) in other.elems) {
            counter = maxOf(counter, id.counter)
            // Delete OpIds advance the clock too, so a local insert after a
            // merge can never collide with an observed deletion's id.
            oe.deleted?.let { counter = maxOf(counter, it.counter) }
            val e = elems[id]
            if (e != null) {
                // Tombstone is sticky and order-independent: keep whichever
                // delete id is smaller so concurrent deletes converge
                // (commutative/associative) instead of depending on merge order.
                e.deleted = when {
                    e.deleted != null && oe.deleted != null -> minOf(e.deleted!!, oe.deleted!!)
                    e.deleted != null -> e.deleted
                    else -> oe.deleted
                }
            } else {
                elems[id] = Elem(oe.ch, oe.origin).apply { deleted = oe.deleted }
            }
        }
        return text() != before
    }

    // -- Delta sync (#lztextsync) -------------------------------------------

    /**
     * This replica's version vector: for each peer that authored an insert or a
     * deletion this replica holds, the greatest counter seen from that peer. An op
     * `(c, p)` is unknown to a partner iff `c > theirVv[p]` (0 when absent).
     */
    override fun versionVector(): Map<Long, Long> {
        val vv = HashMap<Long, Long>()
        fun bump(id: OpId) { vv[id.peer] = maxOf(vv[id.peer] ?: 0L, id.counter) }
        for ((id, e) in elems) {
            bump(id)
            e.deleted?.let { bump(it) }
        }
        return vv
    }

    /**
     * The ops this replica holds that [theirVv] has not observed — new inserts and
     * newly-observed deletions of older elements. [applyDelta]-ing this list into
     * the partner converges the two replicas. A whole-state snapshot is
     * `deltaSince(emptyMap())`.
     */
    override fun deltaSince(theirVv: Map<Long, Long>): List<TextOp> {
        fun seen(id: OpId) = id.counter <= (theirVv[id.peer] ?: 0L)
        val out = ArrayList<TextOp>()
        for ((id, e) in elems) {
            val insertNew = !seen(id)
            val deleteNew = e.deleted?.let { !seen(it) } ?: false
            if (insertNew || deleteNew) out.add(TextOp(id, e.ch, e.origin, e.deleted))
        }
        return out
    }

    /**
     * Apply a delta op list (from [deltaSince]). Commutative, associative, and
     * idempotent — the same convergence contract as [merge], from the transport
     * form. Returns whether the visible text changed.
     */
    override fun applyDelta(ops: List<TextOp>): Boolean {
        val before = text()
        for (op in ops) {
            counter = maxOf(counter, op.id.counter)
            op.deleted?.let { counter = maxOf(counter, it.counter) }
            val e = elems[op.id]
            if (e != null) {
                e.deleted = when {
                    e.deleted != null && op.deleted != null -> minOf(e.deleted!!, op.deleted!!)
                    e.deleted != null -> e.deleted
                    else -> op.deleted
                }
            } else {
                elems[op.id] = Elem(op.ch, op.origin).apply { deleted = op.deleted }
            }
        }
        return text() != before
    }

    override fun value(): String = text()

    override fun mergeFrom(other: TextCrdt): Boolean = merge(other)

    /**
     * Garbage-collect causally-stable deletion tombstones (#lztombgc).
     * [isStable] is the caller-supplied "every replica has observed this
     * deletion" policy. Deliberately conservative: a tombstoned element is
     * collected only when it is **not referenced as any element's left origin**,
     * so removing it can never orphan a surviving character. Interior
     * tombstones are reclaimed bottom-up as their descendants are themselves
     * collected. Returns the number of elements collected.
     */
    fun gcWith(isStable: (OpId) -> Boolean): Int {
        var removed = 0
        while (true) {
            val referenced = HashSet<OpId>()
            for (e in elems.values) e.origin?.let { referenced.add(it) }
            val collectable = elems.entries
                .filter { (id, e) -> e.deleted != null && isStable(e.deleted!!) && id !in referenced }
                .map { it.key }
            if (collectable.isEmpty()) break
            for (id in collectable) {
                elems.remove(id)
                removed += 1
            }
        }
        return removed
    }
}

/**
 * Re-parse merged text into paragraph [Block]s (split on blank lines). Feed the
 * result through [assignStableKeys] + [reconcile] to project merged text onto
 * the keyed tree.
 */
fun parseBlocks(text: String): List<Block> =
    text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.map { Block.text(it) }
