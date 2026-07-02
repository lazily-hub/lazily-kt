package io.github.lazily

// -- Move-aware sequence CRDT (#lzseqcrdt) ----------------------------------
//
// Native Kotlin port of `lazily-rs::seq_crdt`. [SeqCrdt] gives a **mergeable
// ordered sequence** of keyed elements without a coordinator — the order layer
// above the per-cell value merge. It is the sibling-order substrate for keyed
// reconciliation of a document tree under concurrent edits.
//
// Each element carries a **fractional-index** [Position] (an orderable byte key
// plus the originating peer as a tiebreak). Inserting between two neighbours
// mints a key strictly between their keys, so concurrent inserts into the same
// gap on different replicas both survive and converge to a deterministic order.
//
// Crucially it is **move-aware**: a move is a *single* LWW reassign of the
// element's position (highest stamp wins), **not** a delete + reinsert. So a
// reorder keeps the element's identity and value, and two concurrent moves of
// the same element converge to the later one instead of duplicating it. Value,
// position, and tombstone are independent LWW registers, so a concurrent *move*
// and *value edit* of one element do not conflict.
//
// The clock is caller-driven: every mutator takes `nowMicros` so behaviour is
// deterministic and testable (the embedded [SeqHlc] never reads the system
// clock).

/** Alias for the originating peer of a sequence element. */
private typealias SeqPeer = Long

/**
 * A fractional-index position: an orderable byte key, tiebroken by the peer
 * that minted it so concurrent inserts into the same gap get a deterministic
 * total order. Compared lexicographically by `frac`, then `peer`.
 */
data class Position(val frac: ByteArray, val peer: SeqPeer) : Comparable<Position> {
    override fun compareTo(other: Position): Int {
        val n = minOf(frac.size, other.frac.size)
        for (i in 0 until n) {
            val a = frac[i].toInt() and 0xff
            val b = other.frac[i].toInt() and 0xff
            if (a != b) return a - b
        }
        val c = frac.size.compareTo(other.frac.size)
        if (c != 0) return c
        return peer.compareTo(other.peer)
    }

    override fun equals(other: Any?): Boolean =
        other is Position && frac.contentEquals(other.frac) && peer == other.peer

    override fun hashCode(): Int = frac.contentHashCode() * 31 + peer.hashCode()
}

/** A hybrid logical clock stamp (wall, logical, peer) with a `(wall, logical)` then `peer` total order. */
data class SeqStamp(val wall: Long, val logical: Long, val peer: SeqPeer) : Comparable<SeqStamp> {
    override fun compareTo(other: SeqStamp): Int {
        var c = wall.compareTo(other.wall); if (c != 0) return c
        c = logical.compareTo(other.logical); if (c != 0) return c
        return peer.compareTo(other.peer)
    }
}

/** Caller-driven hybrid logical clock — deterministic (never reads the wall clock). */
private class SeqHlc(private val peer: SeqPeer) {
    private var lastWall: Long = 0L
    private var lastLogical: Long = 0L

    /** Stamp a local event at caller-supplied wall time [nowMicros]. */
    fun send(nowMicros: Long): SeqStamp {
        if (nowMicros > lastWall) { lastWall = nowMicros; lastLogical = 0L }
        else lastLogical += 1
        return SeqStamp(lastWall, lastLogical, peer)
    }

    /** Observe a remote [stamp] at wall time [nowMicros], advancing past it. */
    fun recv(remote: SeqStamp, nowMicros: Long) {
        val wall = maxOf(lastWall, remote.wall, nowMicros)
        lastLogical = when {
            wall == lastWall && wall == remote.wall -> maxOf(lastLogical, remote.logical) + 1
            wall == lastWall -> lastLogical + 1
            wall == remote.wall -> remote.logical + 1
            else -> 0L
        }
        lastWall = wall
    }
}

/** Last-write-wins register over a stamp: the greatest stamp wins; equal never displaces. */
private class SeqLww<T>(var value: T, var stamp: SeqStamp) {
    /** Apply a write, overwriting iff [s] strictly beats the current stamp. */
    fun set(v: T, s: SeqStamp): Boolean =
        if (s > stamp) { value = v; stamp = s; true } else false

    /** Merge another replica's state; returns whether the value changed. */
    fun mergeFrom(other: SeqLww<T>): Boolean = set(other.value, other.stamp)
}

private class SeqEntry<V>(
    var value: SeqLww<V>,
    var position: SeqLww<Position>,
    var deleted: SeqLww<Boolean>,
)

/**
 * A move-aware, mergeable ordered sequence of `Id -> V`.
 *
 * The clock is caller-driven: every mutator takes `nowMicros` so behaviour is
 * deterministic and testable.
 */
class SeqCrdt<Id, V> private constructor(
    private val entries: MutableMap<Id, SeqEntry<V>>,
    private var hlc: SeqHlc,
    private var peer: SeqPeer,
) where Id : Any, V : Any {

    /** Create an empty sequence owned by [peer]. */
    constructor(peer: SeqPeer) : this(LinkedHashMap(), SeqHlc(peer), peer)

    private fun fracOf(id: Id): ByteArray? = entries[id]?.position?.value?.frac

    /**
     * Insert [id]/[value] between the live neighbours [left] and [right] (each
     * `null` for an open end). If [id] already exists this is a no-op.
     */
    fun insertBetween(id: Id, value: V, left: Id?, right: Id?, nowMicros: Long) {
        if (entries.containsKey(id)) return
        val lo = left?.let { fracOf(it) }
        val hi = right?.let { fracOf(it) }
        val pos = Position(keyBetween(lo, hi), peer)
        val stamp = hlc.send(nowMicros)
        entries[id] = SeqEntry(
            SeqLww(value, stamp),
            SeqLww(pos, stamp),
            SeqLww(false, stamp),
        )
    }

    /** Append [id]/[value] after the current last live element. */
    fun insertBack(id: Id, value: V, nowMicros: Long) {
        val last = order().lastOrNull()
        insertBetween(id, value, last, null, nowMicros)
    }

    /** Prepend [id]/[value] before the current first live element. */
    fun insertFront(id: Id, value: V, nowMicros: Long) {
        val first = order().firstOrNull()
        insertBetween(id, value, null, first, nowMicros)
    }

    /** Last-writer-wins update of [id]'s value. Returns whether it applied. */
    fun setValue(id: Id, value: V, nowMicros: Long): Boolean {
        val stamp = hlc.send(nowMicros)
        return entries[id]?.value?.set(value, stamp) ?: false
    }

    /**
     * Atomically move [id] between [left] and [right] (move-aware): a single LWW
     * reassignment of its position, keeping identity and value.
     */
    fun moveBetween(id: Id, left: Id?, right: Id?, nowMicros: Long): Boolean {
        if (!entries.containsKey(id)) return false
        val lo = left?.let { fracOf(it) }
        val hi = right?.let { fracOf(it) }
        val pos = Position(keyBetween(lo, hi), peer)
        val stamp = hlc.send(nowMicros)
        return entries[id]!!.position.set(pos, stamp)
    }

    /** Move [id] to just after [anchor]. */
    fun moveAfter(id: Id, anchor: Id, nowMicros: Long): Boolean {
        val ord = order()
        val i = ord.indexOf(anchor)
        val right = if (i >= 0) ord.getOrNull(i + 1) else null
        return moveBetween(id, anchor, right, nowMicros)
    }

    /** Move [id] to just before [anchor]. */
    fun moveBefore(id: Id, anchor: Id, nowMicros: Long): Boolean {
        val ord = order()
        val i = ord.indexOf(anchor)
        val left = if (i > 0) ord[i - 1] else null
        return moveBetween(id, left, anchor, nowMicros)
    }

    /** Tombstone [id] (LWW). Returns whether it applied. */
    fun remove(id: Id, nowMicros: Long): Boolean {
        val stamp = hlc.send(nowMicros)
        return entries[id]?.deleted?.set(true, stamp) ?: false
    }

    /** Whether [id] is present and live (not tombstoned). */
    fun contains(id: Id): Boolean = entries[id]?.let { !it.deleted.value } ?: false

    /** Read [id]'s value if it is live. */
    fun get(id: Id): V? = entries[id]?.let { e -> if (!e.deleted.value) e.value.value else null }

    /** Live element ids in sequence order. */
    fun order(): List<Id> =
        entries.entries
            .filter { !it.value.deleted.value }
            .map { it.key to it.value.position.value }
            .sortedWith(compareBy { it.second })
            .map { it.first }

    /** Live `(id, value)` pairs in sequence order. */
    fun values(): List<Pair<Id, V>> = order().mapNotNull { id -> get(id)?.let { id to it } }

    /** Number of tombstoned-but-not-yet-collected entries (GC-pressure gauge). */
    fun tombstoneCount(): Int = entries.values.count { it.deleted.value }

    /**
     * Garbage-collect causally-stable tombstones (#lztombgc). [isStable] is the
     * caller-supplied "every replica has observed this deletion" policy.
     * Dropping a stable tombstone is observationally inert: [order] and
     * [contains] already skip tombstoned entries. Returns the count collected.
     */
    fun gcWith(isStable: (SeqStamp) -> Boolean): Int {
        val before = entries.size
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.deleted.value && isStable(e.deleted.stamp)) it.remove()
        }
        return before - entries.size
    }

    /** Convenience: collect every tombstone whose stamp is `<=` [watermark]. */
    fun gc(watermark: SeqStamp): Int = gcWith { it <= watermark }

    /**
     * Merge another replica's state in (commutative, associative, idempotent):
     * per-element LWW of value, position, and tombstone; unknown elements are
     * adopted. Advances the local clock past everything observed. Returns
     * whether anything changed.
     */
    fun merge(other: SeqCrdt<Id, V>, nowMicros: Long): Boolean {
        // Advance the clock past the highest stamp we are about to observe.
        var maxStamp: SeqStamp? = null
        for (e in other.entries.values) {
            for (s in listOf(e.value.stamp, e.position.stamp, e.deleted.stamp)) {
                maxStamp = maxStamp?.let { if (s > it) s else it } ?: s
            }
        }
        maxStamp?.let { hlc.recv(it, nowMicros) }

        var changed = false
        for ((id, oe) in other.entries) {
            val e = entries[id]
            if (e != null) {
                changed = e.value.mergeFrom(oe.value) || changed
                changed = e.position.mergeFrom(oe.position) || changed
                changed = e.deleted.mergeFrom(oe.deleted) || changed
            } else {
                entries[id] = cloneEntry(oe)
                changed = true
            }
        }
        return changed
    }

    /** Fork this replica's state to a new owning [peer] (deep copy, new identity). */
    fun cloneStateAs(peer: SeqPeer): SeqCrdt<Id, V> =
        SeqCrdt(LinkedHashMap(entries.mapValues { cloneEntry(it.value) }), SeqHlc(peer), peer)

    /** Clone state keeping the same peer identity. */
    fun cloneState(): SeqCrdt<Id, V> = cloneStateAs(peer)

    private fun cloneEntry(e: SeqEntry<V>): SeqEntry<V> = SeqEntry(
        SeqLww(e.value.value, e.value.stamp),
        SeqLww(e.position.value, e.position.stamp),
        SeqLww(e.deleted.value, e.deleted.stamp),
    )
}

/**
 * Generate a fractional key strictly between [lo] and [hi] (each `null` for an
 * open end), as a byte sequence compared lexicographically.
 */
internal fun keyBetween(lo: ByteArray?, hi: ByteArray?): ByteArray {
    val result = ArrayList<Byte>()
    var i = 0
    // Safety bound: the shared prefix can be at most lo.len()+hi.len() long.
    val cap = (lo?.size ?: 0) + (hi?.size ?: 0) + 2
    while (i <= cap) {
        val a: Int = lo?.getOrNull(i)?.let { it.toInt() and 0xff } ?: 0
        val b: Int = when {
            hi != null -> hi.getOrNull(i)?.let { it.toInt() and 0xff } ?: 0
            else -> 256
        }
        if (a + 1 < b) {
            // Gap of >= 2 at this digit: a midpoint digit lands strictly between.
            result.add(((a + b) / 2).toByte())
            return result.toByteArray()
        }
        // Gap < 2: commit the lower digit and descend.
        result.add(a.toByte())
        i += 1
        if (a < b) {
            // We dropped strictly below `hi` at this digit, so deeper digits are
            // bounded only by `lo`'s tail; recurse with an open top.
            val loTail = lo?.let { it.copyOfRange(i, it.size) }
            result.addAll(keyBetween(loTail, null).toList())
            return result.toByteArray()
        }
        // a == b: shared prefix digit; continue.
    }
    // Degenerate (lo not < hi): append a midpoint and stop.
    result.add(128.toByte())
    return result.toByteArray()
}
