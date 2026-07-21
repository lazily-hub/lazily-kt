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
 * The base [NodeId] for entries a family materializes on first remote observation
 * (`#lzfamilysync`). Family entry nodes are locally-private — keyed ops resolve by
 * [NodeKey], never by raw NodeId — so this only needs to avoid colliding with
 * application-assigned node ids; the runtime skips any id already in use.
 */
private const val FAMILY_NODE_BASE: Long = 1L shl 48

/**
 * A last-writer-wins family (`#lzfamilysync`): entries are `LwwRegister<V>` cells
 * addressed by [NodeKey] `namespace/suffix`. Materializes a fresh entry cell from a
 * remote op's converged bytes so a keyed op for an entry not registered locally is
 * materialized on ingest instead of being dropped.
 */
private class LwwFamilyFactory<V : Any>(
    val namespace: String,
    val codec: CrdtCodec<V>,
    private val clock: CrdtClock,
) {
    fun materialize(ctx: Context, op: CrdtOp): ReplicatedCell<V> {
        val bytes = when (val state = op.state) {
            is IpcValue.Inline -> state.toByteArray()
            else -> error("family op requires Inline state")
        }
        @Suppress("UNCHECKED_CAST")
        val value = decodeCrdtValue(codec, bytes) as V
        // Seed the backing cell with the decoded value; the caller's applyRemote(op)
        // sets the register's real stamp and drives the reactive graph.
        val handle = Source<V>(ctx.cellAny(value))
        return ReplicatedCell(ctx, handle, LwwRegister(codec), codec, clock)
    }
}

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

    // -- Family sync (#lzfamilysync) -----------------------------------------
    private val families = LinkedHashMap<String, LwwFamilyFactory<*>>()
    private val familyMembers = LinkedHashMap<String, MutableList<NodeKey>>()
    private var familyCtx: Context? = null
    private var membershipEpochCell: Source<Long>? = null
    private var nextFamilyNode: Long = FAMILY_NODE_BASE

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
            val typed = typedCells[node]
            if (typed != null) {
                typed.applyRemote(op)
                recordNew(op, node)
                applied++
                continue
            }
            // Materialize-on-ingest (#lzfamilysync): a keyed op whose entry is not
            // registered locally materializes it if its key belongs to a registered
            // family, instead of being dropped. Record under the fresh local node so
            // future ops for the key resolve + dedup correctly.
            val materialized = maybeMaterializeFamily(op)
            if (materialized != null) {
                val (localNode, cell) = materialized
                cell.applyRemote(op)
                recordNew(op, localNode)
                applied++
                continue
            }
            // A non-family unregistered node: raw plane state only (introspectable
            // via `value`), so unregistered nodes still converge as before.
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

    // -- Family sync (#lzfamilysync) -----------------------------------------

    /**
     * Register a last-writer-wins family under [namespace]. An inbound keyed op
     * whose first [NodeKey] segment matches materializes a fresh entry on [ingest]
     * (seeded from the op's converged register) instead of being dropped. Creates the
     * membership signal on first call; build family entries with this runtime's
     * [clock].
     */
    fun <V : Any> registerFamilyLww(ctx: Context, namespace: String, codec: CrdtCodec<V>) {
        familyCtx = ctx
        if (membershipEpochCell == null) membershipEpochCell = Source(ctx.cellAny(0L))
        familyMembers.getOrPut(namespace) { mutableListOf() }
        families[namespace] = LwwFamilyFactory(namespace, codec, clock)
    }

    /**
     * The reactive membership signal (`#lzfamilysync`): depend on it from a derived
     * aggregate over a family so a remote-materialized key forces a recompute. `null`
     * until the first family is registered.
     */
    fun membershipEpoch(): Source<Long>? = membershipEpochCell

    /** The materialized keys of family [namespace], in first-materialization order. */
    fun familyKeys(namespace: String): List<NodeKey> =
        familyMembers[namespace]?.toList() ?: emptyList()

    /** The current converged value of family entry `namespace/keySuffix`. */
    fun <V : Any> familyValueLww(namespace: String, keySuffix: String): V? {
        val key = NodeKey.fromSegments(listOf(namespace, keySuffix))
        val node = keyToNode[key] ?: return null
        return typedValue<V>(node)
    }

    /**
     * Insert or update local LWW family entry `namespace/keySuffix` to [value],
     * returning the [CrdtOp] to broadcast (or `null` for a value-preserving update).
     * Materializes the entry (and bumps membership) on first insert.
     */
    @Suppress("UNCHECKED_CAST")
    fun <V : Any> familySetLww(namespace: String, keySuffix: String, value: V): CrdtOp? {
        val ctx = familyCtx ?: error("register the family before setting entries")
        val factory = families[namespace] as? LwwFamilyFactory<V>
            ?: error("no family registered for namespace '$namespace'")
        val key = NodeKey.fromSegments(listOf(namespace, keySuffix))
        keyToNode[key]?.let { return localUpdate(it, value) }
        // First local insert: seed a fresh entry at a real stamp and emit the op.
        val node = mintFamilyNode()
        val stamp = clock.tick()
        val reg = LwwRegister(factory.codec)
        reg.merge(value, stamp)
        val handle = Source<V>(ctx.cellAny(value))
        val cell = ReplicatedCell(ctx, handle, reg, factory.codec, clock)
        register(node, key, cell)
        recordFamilyMember(namespace, key)
        bumpMembershipEpoch()
        val op = CrdtOp(node = node, key = key, stamp = stamp, state = IpcValue.Inline(factory.codec.encode(value)))
        recordNew(op, node)
        return op
    }

    private fun mintFamilyNode(): NodeId {
        while (true) {
            val candidate = nextFamilyNode
            nextFamilyNode++
            if (!typedCells.containsKey(candidate) && !rawState.containsKey(candidate)) return candidate
        }
    }

    private fun recordFamilyMember(namespace: String, key: NodeKey) {
        val members = familyMembers.getOrPut(namespace) { mutableListOf() }
        if (!members.contains(key)) members.add(key)
    }

    private fun bumpMembershipEpoch() {
        val cell = membershipEpochCell ?: return
        val ctx = familyCtx ?: return
        val current = ctx.getCellAny(cell.id) as? Long ?: 0L
        ctx.setCellAny(cell.id, current + 1L)
    }

    /**
     * If [op] is a keyed op belonging to a registered family whose entry is not yet
     * registered locally, materialize it (fresh local node, indexed by the wire key)
     * and return `(node, cell)`; otherwise `null`.
     */
    private fun maybeMaterializeFamily(op: CrdtOp): Pair<NodeId, ReplicatedCell<*>>? {
        val key = op.key ?: return null
        val namespace = key.segments().firstOrNull() ?: return null
        val factory = families[namespace] ?: return null
        val ctx = familyCtx ?: return null
        val node = mintFamilyNode()
        val cell = factory.materialize(ctx, op)
        register(node, key, cell)
        recordFamilyMember(namespace, key)
        bumpMembershipEpoch()
        return node to cell
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
