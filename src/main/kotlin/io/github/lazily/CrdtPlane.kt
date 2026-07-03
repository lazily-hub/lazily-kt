package io.github.lazily

// -- Distributed CRDT plane runtime (#lzcrdtplane5b) --------------------------
//
// Runtime glue that bridges the distributed CRDT plane to a replica's reactive
// graph, reusing the Crdt.kt primitives (CrdtClock / StampFrontier /
// ReplicatedCell) and the Ipc.kt wire types (WireStamp / CrdtOp / CrdtSync).
// Ported from lazily-rs `crdt_plane.rs`.
//
// One runtime per shared session per replica. It owns the plane clock + stamp
// frontier + membership, an op-log (dedup by (node, stamp)), a registry of
// replicated root cells, and a NodeKey<->NodeId index. Convergence is
// state-based CvRDT: merge is commutative/associative/idempotent, so
// out-of-order, duplicate, or batched delivery all converge, and re-delivering a
// frame applies 0 new ops.

/**
 * The live runtime that bridges the distributed CRDT plane to a reactive graph's
 * `merge:crdt` root cells.
 *
 * A node's converged state is tracked as a raw state-based LWW over [WireStamp]
 * (the op with the greatest `(wall_time, logical, peer)` wins), so the runtime is
 * value-type-agnostic and any node — registered or not — can be introspected via
 * [value]. Registering a typed [ReplicatedCell] additionally drives the reactive
 * graph on each converged remote op.
 */
class CrdtPlaneRuntime(private val peer: PeerId) {
    /**
     * The plane clock. Build [ReplicatedCell]s registered here with this clock so
     * local stamps stay coordinated with the plane.
     */
    val clock: CrdtClock = CrdtClock(peer)

    private val frontierState = StampFrontier()
    private val membershipSet: MutableSet<PeerId> = linkedSetOf(peer)

    /** Op-log, dedup + ordered by insertion, keyed by (resolved node, stamp). */
    private val log = LinkedHashMap<Pair<NodeId, WireStamp>, CrdtOp>()

    /** Winning op per node under max-stamp LWW (raw state introspection). */
    private val rawState = LinkedHashMap<NodeId, CrdtOp>()

    private val typedCells = LinkedHashMap<NodeId, ReplicatedCell<*>>()
    private val keyToNode = LinkedHashMap<NodeKey, NodeId>()
    private val nodeToKey = LinkedHashMap<NodeId, NodeKey>()

    /** The local replica identity. */
    fun peer(): PeerId = peer

    /** Number of registered typed `merge:crdt` root cells. */
    fun len(): Int = typedCells.size

    /** Whether no typed cells are registered. */
    fun isEmpty(): Boolean = typedCells.isEmpty()

    /** The observed membership (peers this replica has seen ops/frontier from). */
    fun membership(): Set<PeerId> = membershipSet.toSet()

    /**
     * Register a `merge:crdt` root cell under [node], optionally projecting a
     * wire-stable [key] so the cell stays addressable across NodeId churn. Build
     * [cell] with this runtime's [clock].
     */
    fun <V : Any> register(node: NodeId, key: NodeKey?, cell: ReplicatedCell<V>) {
        if (key != null) {
            keyToNode[key] = node
            nodeToKey[node] = key
        }
        typedCells[node] = cell
    }

    /** The registered typed cell at [node], if any. */
    fun cell(node: NodeId): ReplicatedCell<*>? = typedCells[node]

    /**
     * The current converged value of a registered typed cell at [node].
     */
    @Suppress("UNCHECKED_CAST")
    fun <V : Any> typedValue(node: NodeId): V? =
        (typedCells[node] as? ReplicatedCell<V>)?.register?.value()

    /** The raw converged wire state (winning op's state) at [node], if any. */
    fun value(node: NodeId): IpcValue? = rawState[node]?.state

    /**
     * Apply a local edit to the typed cell at [node]. Returns the [CrdtOp] to
     * broadcast, or `null` for a value-preserving edit, an unknown [node], or a
     * type mismatch. The plane clock ticks and the fresh stamp orders the op.
     */
    fun <V : Any> localUpdate(node: NodeId, value: V): CrdtOp? {
        @Suppress("UNCHECKED_CAST")
        val typed = typedCells[node] as? ReplicatedCell<V> ?: return null
        val op = typed.localEdit(value, node, nodeToKey[node])
        // Self-apply so the local reactive graph reflects the edit; a
        // value-preserving edit reports no change and emits no op.
        if (!typed.applyRemote(op)) return null
        recordNew(op, node)
        return op
    }

    /**
     * Ingest a remote anti-entropy frame: fold every not-yet-seen [CrdtOp] into
     * its target replica (driving the reactive graph) exactly once, advancing the
     * plane clock, stamp frontier, and membership. Returns the count of ops newly
     * applied; re-delivering a frame the receiver already has applies 0 (op-log
     * dedup by (node, stamp)).
     */
    fun ingest(sync: CrdtSync, nowMicros: Long = 0L): Int {
        for ((p, wire) in sync.frontier) {
            if (p != peer) clock.observe(wire)
            frontierState.observe(p, wire)
            membershipSet.add(p)
        }
        var applied = 0
        for (op in sync.ops) {
            val node = resolveNode(op)
            if (log.containsKey(node to op.stamp)) continue
            clock.observe(op.stamp)
            // Drive the registered reactive cell (if any); the raw plane state is
            // recorded regardless so unregistered nodes still converge.
            typedCells[node]?.applyRemote(op)
            recordNew(op, node)
            applied++
        }
        return applied
    }

    /**
     * This replica's stamp frontier in wire form — the per-peer highest observed
     * stamp it advertises so a peer can compute what it is missing.
     */
    fun wireFrontier(): List<Pair<PeerId, WireStamp>> =
        frontierState.entries().entries.map { it.key to it.value }

    /**
     * A frame shipping the entire op log plus this replica's frontier. Safe to
     * resend (the receiver dedups).
     */
    fun syncFrame(): CrdtSync = syncFrameSince(StampFrontier())

    /**
     * A frame advertising this replica's frontier and shipping only the ops a
     * peer described by [since] has not yet observed.
     */
    fun syncFrameSince(since: StampFrontier): CrdtSync {
        val ops = log.values.filter { op -> isMissing(op, since) }
        return CrdtSync(frontier = wireFrontier(), ops = ops)
    }

    /**
     * Reply to a peer's anti-entropy [request]: ship exactly the ops the
     * requester (described by `request.frontier`) is missing.
     */
    fun syncReply(request: CrdtSync): CrdtSync {
        val since = StampFrontier()
        for ((p, wire) in request.frontier) since.observe(p, wire)
        return syncFrameSince(since)
    }

    // -- internals -----------------------------------------------------------

    private fun resolveNode(op: CrdtOp): NodeId =
        op.key?.let { keyToNode[it] } ?: op.node

    private fun isMissing(op: CrdtOp, since: StampFrontier): Boolean {
        val seen = since.of(op.stamp.peer) ?: return true
        return op.stamp.isAfter(seen)
    }

    /** Record a not-yet-seen op into the log, frontier, membership, and raw state. */
    private fun recordNew(op: CrdtOp, node: NodeId) {
        log[node to op.stamp] = op
        frontierState.observe(op.stamp.peer, op.stamp)
        membershipSet.add(op.stamp.peer)
        val cur = rawState[node]
        if (cur == null || op.stamp.isAfter(cur.stamp)) {
            rawState[node] = op
        }
    }
}
