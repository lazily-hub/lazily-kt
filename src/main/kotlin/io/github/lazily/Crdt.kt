package io.github.lazily

// -- Distributed CRDT cell plane (runtime) -----------------------------------
//
// Native Kotlin runtime for the `merge: crdt` mechanism — the ingress step at
// the boundary where remote `CrdtOp`s enter a replica (`lazily-spec/protocol.md`
// § Distributed: CRDT Cell Plane, `lazily-spec/cell-model.md` § Merge is an
// ingress operation on root cells only).
//
// This is the runtime integration slice (`#lzcrdtplane5b`): local edits mint
// `CrdtOp`s; remote `CrdtOp`s merge into a `ReplicatedCell` and the converged
// value is fed into the reactive graph as an ordinary cell update, after which
// propagation is identical to a single-writer cell. The wire types
// (`WireStamp`/`CrdtOp`/`CrdtSync`) live in [Ipc.kt]; this module supplies the
// HLC clock, the per-peer stamp frontier, the causal-stability watermark / GC
// contract, and the LWW / MV / PN-counter register types.

/**
 * Total order over [WireStamp]: `(wall_time, logical, peer)`. Returns a
 * negative, zero, or positive integer per [Comparable] convention.
 */
fun WireStamp.compareTo(other: WireStamp): Int {
    var c = wallTime.compareTo(other.wallTime); if (c != 0) return c
    c = logical.compareTo(other.logical); if (c != 0) return c
    return peer.compareTo(other.peer)
}

/** `true` when this stamp is strictly greater than [other] in the total order. */
fun WireStamp.isAfter(other: WireStamp): Boolean = compareTo(other) > 0

/** `true` when this stamp is less than or equal to [other] (dominated by it). */
fun WireStamp.isAtOrBefore(other: WireStamp): Boolean = compareTo(other) <= 0

/**
 * `true` when this stamp *happens-before* [other] by the `(wall_time, logical)`
 * projection — i.e. [other] causally dominates this stamp. The peer tiebreak is
 * excluded: two stamps with the same `(wall, logical)` from different peers are
 * **concurrent** (independent writers), so neither happens-before the other.
 * Used by the MV-register to keep concurrent writes.
 */
fun WireStamp.happensBefore(other: WireStamp): Boolean =
    when {
        wallTime != other.wallTime -> wallTime < other.wallTime
        else -> logical < other.logical
    }

/** `true` when this stamp causally dominates [other] (`other.happensBefore(this)`). */
fun WireStamp.dominates(other: WireStamp): Boolean = other.happensBefore(this)

/**
 * Per-peer stamp frontier — the highest [WireStamp] observed from each peer.
 *
 * `merge` is per-peer `max`; the **causal-stability watermark** over a
 * membership is the `min` of the frontier across that membership — the causal
 * point every replica has provably passed. A tombstone whose delete stamp is
 * `≤` the watermark is collectable on *every* replica, so dropping it cannot
 * lose an edit (the GC safety property the spec pins to the version-vector
 * minimum, never a single replica's clock).
 */
class StampFrontier(entries: Map<PeerId, WireStamp> = emptyMap()) {
    private val stamps: MutableMap<PeerId, WireStamp> = LinkedHashMap(entries)

    /** The current per-peer stamp map (a defensive copy). */
    fun entries(): Map<PeerId, WireStamp> = stamps.toMap()

    /** Observe [peer] → [stamp], keeping the per-peer maximum. Idempotent. */
    fun observe(peer: PeerId, stamp: WireStamp): Boolean {
        val prev = stamps[peer]
        if (prev != null && !stamp.isAfter(prev)) return false
        stamps[peer] = stamp
        return true
    }

    /** Merge [other] into this frontier (per-peer `max`); returns whether anything advanced. */
    fun merge(other: StampFrontier): Boolean {
        var advanced = false
        for ((peer, stamp) in other.stamps) advanced = observe(peer, stamp) || advanced
        return advanced
    }

    /** The frontier entry for [peer], if any. */
    fun of(peer: PeerId): WireStamp? = stamps[peer]

    /**
     * Causal-stability watermark over [membership]: the `min` frontier stamp
     * across the membership, or `null` if any member is unobserved (a missing
     * member means the watermark is undefined — fail closed rather than using a
     * single replica's clock).
     */
    fun watermark(membership: Collection<PeerId>): WireStamp? {
        if (membership.isEmpty()) return null
        var min: WireStamp? = null
        for (peer in membership) {
            val s = stamps[peer] ?: return null
            if (min == null || min!!.isAfter(s)) min = s
        }
        return min
    }
}

// -- Codec -------------------------------------------------------------------

/**
 * Encodes/decodes a CRDT register value to/from the inline byte payload carried
 * by a [CrdtOp] (`IpcValue.Inline`). Register state crosses the wire as bytes so
 * the runtime is value-type-agnostic.
 */
fun interface CrdtCodec<V> {
    fun encode(value: V): ByteArray

    companion object {
        /** UTF-8 string codec. */
        val string: CrdtCodec<String> = CrdtCodec { it.encodeToByteArray() }

        /** Big-endian Int codec. */
        val int: CrdtCodec<Int> = CrdtCodec {
            byteArrayOf(
                (it shr 24).toByte(), (it shr 16).toByte(),
                (it shr 8).toByte(), it.toByte(),
            )
        }

        /** Big-endian Long codec. */
        val long: CrdtCodec<Long> = CrdtCodec {
            ByteArray(8) { i -> (it shr (56 - 8 * i)).toByte() }
        }
    }
}

/** Decode [bytes] via the [CrdtCodec.string] codec. */
fun CrdtCodec<String>.decode(bytes: ByteArray): String = String(bytes)

/** Decode [bytes] via the [CrdtCodec.int] codec. */
fun CrdtCodec<Int>.decode(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xff shl 24) or (bytes[1].toInt() and 0xff shl 16) or
        (bytes[2].toInt() and 0xff shl 8) or (bytes[3].toInt() and 0xff))

/** Decode [bytes] via the [CrdtCodec.long] codec. */
fun CrdtCodec<Long>.decode(bytes: ByteArray): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (bytes[i].toLong() and 0xff)
    return v
}

private fun decodeInline(state: IpcValue, codec: CrdtCodec<*>): ByteArray =
    when (state) {
        is IpcValue.Inline -> state.toByteArray()
        is IpcValue.SharedBlob ->
            error("SharedBlob CRDT payloads require a ShmBlobArena; pass Inline bytes to the runtime")
    }

// -- Register types ----------------------------------------------------------

/**
 * A CvRDT register — the value shape available *within* `merge: crdt`. Merge is
 * commutative, associative, and idempotent; out-of-order, duplicated, or
 * batched delivery all converge. The register tracks the stamps it has observed
 * so it can drive the causal-stability watermark / GC contract.
 */
sealed class CrdtRegister<V> {
    /** The current converged value (or `null` if never written). */
    abstract fun value(): V?

    /** The stamp of the most recent observation, for frontier exchange. */
    abstract fun lastStamp(): WireStamp?

    /**
     * Merge a remote observation `(value, stamp)` into this register.
     * @return `true` if the converged value changed (callers feed it into the
     *   reactive cell, guarded by `==`).
     */
    abstract fun merge(value: V, stamp: WireStamp): Boolean

    /** Collect tombstones stable at or before [watermark]; returns whether any were dropped. */
    open fun gc(watermark: WireStamp): Boolean = false
}

/**
 * Last-write-wins register (default). The stamp with the greatest total order
 * wins; an equal stamp never displaces a value (idempotent re-delivery).
 */
class LwwRegister<V>(
    private val codec: CrdtCodec<V>,
) : CrdtRegister<V>() {
    @Volatile private var current: V? = null
    @Volatile private var stamp: WireStamp? = null

    override fun value(): V? = current
    override fun lastStamp(): WireStamp? = stamp

    override fun merge(value: V, stamp: WireStamp): Boolean {
        val prev = this.stamp
        if (prev != null && !stamp.isAfter(prev)) return false // dominated/equal → idempotent
        val changed = current != value
        current = value
        this.stamp = stamp
        return changed
    }

    /** Encode the current value to inline wire bytes (for emitting a local [CrdtOp]). */
    fun encode(value: V): ByteArray = codec.encode(value)
}

/**
 * Multi-value register: concurrent writes (incomparable stamps) are surfaced as
 * a set of values; a strictly-newer stamp dominates (clears) earlier values.
 * The converged [value] is the single resolved value, or `null` while two or
 * more concurrent writes are in conflict (an application resolves it with a
 * fresh write that causally supersedes the set).
 */
class MvRegister<V>(
    private val codec: CrdtCodec<V>,
) : CrdtRegister<V>() {
    private val entries: MutableMap<WireStamp, V> = LinkedHashMap()

    override fun value(): V? = if (entries.size == 1) entries.values.first() else null

    /** The concurrent-value set (empty until written; single value when unconflicted). */
    fun values(): Set<V> = entries.values.toSet()

    override fun lastStamp(): WireStamp? = entries.keys.maxWithOrNull { a, b -> a.compareTo(b) }

    override fun merge(value: V, stamp: WireStamp): Boolean {
        // Drop entries this stamp causally dominates (wall/logical only — the
        // peer tiebreak would wrongly merge concurrent same-clock writes);
        // keep concurrent ones so they surface as a multi-value set.
        val dominated = entries.keys.filter { stamp.dominates(it) }
        var changed = false
        for (d in dominated) if (entries.remove(d) != null) changed = true
        val prev = entries[stamp]
        if (prev != value) {
            entries[stamp] = value
            changed = true
        }
        return changed
    }

    fun encode(value: V): ByteArray = codec.encode(value)
}

/**
 * Positive-negative counter. Each peer owns a `(p, n)` pair; merge is per-peer
 * `max` of `p` and `n`; the value is `sum(p) - sum(n)`.
 */
class PnCounter : CrdtRegister<Long>() {
    private val p: MutableMap<PeerId, Long> = HashMap()
    private val n: MutableMap<PeerId, Long> = HashMap()

    /** Increment peer [peer]'s positive counter by [delta] (local edit). */
    fun increment(peer: PeerId, delta: Long = 1L) {
        require(delta >= 0) { "increment delta must be non-negative" }
        p.merge(peer, delta, Long::plus)
    }

    /** Decrement peer [peer]'s negative counter by [delta] (local edit). */
    fun decrement(peer: PeerId, delta: Long = 1L) {
        require(delta >= 0) { "decrement delta must be non-negative" }
        n.merge(peer, delta, Long::plus)
    }

    override fun value(): Long = p.values.sum() - n.values.sum()
    override fun lastStamp(): WireStamp? = null // counters are per-peer state, not stamp-keyed

    /**
     * Merge a remote `(value, peer)` observation. [value] encodes the peer's
     * full `(p, n)` state as a packed long (high 32 = p, low 32 = n).
     */
    fun mergeRemote(peer: PeerId, packedState: Long): Boolean {
        val rp = (packedState ushr 32).toInt().toLong() and 0xffffffffL
        val rn = packedState.toInt().toLong() and 0xffffffffL
        var changed = false
        if (rp > p.getOrDefault(peer, 0L)) { p[peer] = rp; changed = true }
        if (rn > n.getOrDefault(peer, 0L)) { n[peer] = rn; changed = true }
        return changed
    }

    override fun merge(value: Long, stamp: WireStamp): Boolean = mergeRemote(stamp.peer, value)

    /** Pack this peer's local `(p, n)` state for emission. */
    fun packLocal(peer: PeerId): Long {
        val rp = p.getOrDefault(peer, 0L)
        val rn = n.getOrDefault(peer, 0L)
        return ((rp and 0xffffffffL) shl 32) or (rn and 0xffffffffL)
    }
}

// -- HLC clock ---------------------------------------------------------------

/**
 * Hybrid logical clock for a local peer — wall-clock for human-meaningful
 * ordering, a logical counter for causal tiebreaks. Monotonic across calls on a
 * single replica; thread-safe via a coarse monitor (the reactive [Context] is
 * single-threaded; the clock only needs monotonicity, not lock-freedom).
 */
class CrdtClock(private val peer: PeerId) {
    private var wall: Long = 0L
    private var logical: Long = 0L

    /** Advance the clock and return a fresh, strictly-increasing [WireStamp]. */
    @Synchronized
    fun tick(): WireStamp {
        val now = System.currentTimeMillis()
        if (now > wall) { wall = now; logical = 0L } else { logical++ }
        return WireStamp(wallTime = wall, logical = logical, peer = peer)
    }

    /** Observe a remote [stamp] so future local stamps causally succeed it. */
    @Synchronized
    fun observe(stamp: WireStamp) {
        val now = System.currentTimeMillis()
        logical = when {
            now > wall && now > stamp.wallTime -> { wall = now; 0L }
            stamp.wallTime > wall -> { wall = stamp.wallTime; stamp.logical + 1 }
            wall > stamp.wallTime -> logical + 1
            else -> maxOf(logical, stamp.logical) + 1
        }
    }
}

// -- Replicated cell (ingress into the reactive graph) -----------------------

/**
 * A multi-write root cell under `merge: crdt`. Remote [CrdtOp]s are merged
 * through [register]; the converged value is fed into [backing] as an ordinary
 * cell update (guarded by `==`, so an equal merge invalidates nothing).
 *
 * Per the cell model, merge is an **ingress** step on **root** cells only;
 * derived cells and effects are never merged. The merged value flows downstream
 * through the ordinary reactive graph after the cell update.
 *
 * @param V value type
 * @param backing the reactive cell that holds the converged value
 * @param register the CRDT register that reconciles concurrent writes
 */
class ReplicatedCell<V : Any>(
    val ctx: Context,
    val backing: CellHandle<V>,
    val register: CrdtRegister<V>,
    private val codec: CrdtCodec<V>,
    private val clock: CrdtClock,
) {
    /** Per-peer stamp frontier observed by this replica. */
    val frontier: StampFrontier = StampFrontier()

    /** Merge a remote [op] into the register; returns whether the value changed. */
    fun applyRemote(op: CrdtOp): Boolean {
        clock.observe(op.stamp)
        frontier.observe(op.stamp.peer, op.stamp)
        val bytes = decodeInline(op.state, codec)
        @Suppress("UNCHECKED_CAST")
        val value = decodeValue(codec, bytes) as V
        val changed = register.merge(value, op.stamp)
        val converged = register.value() ?: return changed
        if (changed && ctx.getCellAny(backing.id) != converged) {
            ctx.setCellAny(backing.id, converged)
        }
        return changed
    }

    /** Build a [CrdtOp] for a local edit at the next clock stamp. */
    fun localEdit(value: V, node: NodeId, key: NodeKey? = null): CrdtOp {
        val stamp = clock.tick()
        frontier.observe(stamp.peer, stamp)
        val bytes = when (register) {
            is LwwRegister -> (register as LwwRegister<V>).encode(value)
            is MvRegister -> (register as MvRegister<V>).encode(value)
            is PnCounter -> error("PnCounter local edits use localPnEdit")
        }
        return CrdtOp(node = node, key = key, stamp = stamp, state = IpcValue.Inline(bytes))
    }

    /** Build a [CrdtOp] for a local PN-counter edit (packed peer state). */
    fun localPnEdit(peer: PeerId, node: NodeId, key: NodeKey? = null): CrdtOp {
        val counter = register as? PnCounter ?: error("localPnEdit on non-PnCounter")
        val stamp = clock.tick()
        frontier.observe(stamp.peer, stamp)
        val packed = counter.packLocal(peer)
        return CrdtOp(node = node, key = key, stamp = stamp, state = IpcValue.Inline(CrdtCodec.long.encode(packed)))
    }

    /**
     * Drop tombstones stable at or before [watermark] (the version-vector
     * minimum over the membership). Safe because a collectable stamp is `≤`
     * every member's observation.
     */
    fun gc(watermark: WireStamp): Boolean = register.gc(watermark)
}

@Suppress("UNCHECKED_CAST")
private fun decodeValue(codec: CrdtCodec<*>, bytes: ByteArray): Any? =
    when (codec) {
        CrdtCodec.string -> CrdtCodec.string.decode(bytes)
        CrdtCodec.int -> CrdtCodec.int.decode(bytes)
        CrdtCodec.long -> CrdtCodec.long.decode(bytes)
        else -> error("unsupported CRDT codec")
    }

/**
 * Allocate a [ReplicatedCell] backed by a fresh root cell seeded with
 * [initial]. The cell is the merge unit; downstream derived cells/effects read
 * it like any ordinary cell.
 */
fun <V : Any> Context.replicatedCell(
    initial: V,
    register: CrdtRegister<V>,
    codec: CrdtCodec<V>,
    clock: CrdtClock,
): ReplicatedCell<V> {
    val handle = CellHandle<V>(cellAny(initial))
    val cell = ReplicatedCell(this, handle, register, codec, clock)
    // Seed the register with the initial value at a pre-history stamp so the
    // first real remote write (any stamp > (0,0,0)) supersedes it cleanly.
    register.merge(initial, WireStamp(0, 0, 0L))
    return cell
}

/**
 * Anti-entropy for a set of [ReplicatedCell]s: build a [CrdtSync] frame
 * carrying the current [frontier] and the given ops. The receiver applies each
 * op via [ReplicatedCell.applyRemote] and merges the frontier.
 */
fun crdtSyncFrame(frontier: StampFrontier, ops: List<CrdtOp>): CrdtSync =
    CrdtSync(frontier = frontier.entries().entries.map { it.key to it.value }, ops = ops)
