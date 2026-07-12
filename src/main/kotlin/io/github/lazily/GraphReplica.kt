package io.github.lazily

/**
 * A generic, read-only replica of a lazily reactive graph (`#lzsync` 3B).
 *
 * The authoritative reactive graph (cells, recompute, dependency scheduling) lives in
 * the producer. A consumer that only needs the *current* node/edge image — an editor
 * plugin rendering UI, a monitor, a test — folds the canonical lazily wire
 * ([Snapshot] / [Delta], `NodeId`/`IpcValue`) into this flat map instead of standing up
 * a full reactive `Context`. The fold is a pure, deterministic, idempotent replay of
 * deduped events, so a re-emitted (no-op) delta leaves the replica unchanged.
 *
 * This is **domain-agnostic**: it knows nothing about any particular `type_tag` or
 * payload schema. Node payloads are the raw bytes the producer published
 * (`IpcValue.Inline` / `NodeState.Payload`); interpreting them (JSON, protobuf, …) is the
 * consumer's job. It replaces the agent-doc-bespoke, base64-`WireDelta` mirror: agent-doc
 * now layers its own projection on top of this generic replica rather than welding
 * domain logic into a lazily class.
 */
class GraphReplica {
    /** A tracked node: its id, `type_tag`, and the producer's raw published payload bytes. */
    data class Node(val id: NodeId, val typeTag: String, val payload: ByteArray?) {
        override fun equals(other: Any?): Boolean =
            other is Node &&
                id == other.id &&
                typeTag == other.typeTag &&
                payload.contentEqualsOrBothNull(other.payload)

        override fun hashCode(): Int =
            (31 * (31 * id.hashCode() + typeTag.hashCode())) + (payload?.contentHashCode() ?: 0)
    }

    private val nodes = LinkedHashMap<NodeId, Node>()
    private val edges = LinkedHashSet<Pair<NodeId, NodeId>>()

    /** Monotonic frontier — the highest lazily epoch applied so far. */
    var epoch: Long = 0
        private set

    /** True until at least one snapshot/delta has been applied. */
    var isInitialized: Boolean = false
        private set

    val nodeCount: Int get() = nodes.size

    /** Apply a cold-read [Snapshot], replacing the whole graph image. */
    fun applySnapshot(snapshot: Snapshot) {
        nodes.clear()
        edges.clear()
        for (node in snapshot.nodes) {
            nodes[node.node] = Node(node.node, node.typeTag, payloadOf(node.state))
        }
        for (edge in snapshot.edges) {
            edges.add(edge.dependent to edge.dependency)
        }
        epoch = snapshot.epoch
        isInitialized = true
    }

    /**
     * Apply a warm [Delta]. Ops apply verbatim in emission order; the frontier advances
     * to [Delta.epoch]. A no-op delta (empty ops) only advances the epoch.
     */
    fun applyDelta(delta: Delta) {
        for (op in delta.ops) {
            when (op) {
                is DeltaOp.NodeAdd -> nodes[op.node] = Node(op.node, op.typeTag, payloadOf(op.state))
                is DeltaOp.CellSet -> nodes[op.node]?.let { nodes[op.node] = it.copy(payload = payloadOf(op.payload)) }
                is DeltaOp.SlotValue -> nodes[op.node]?.let { nodes[op.node] = it.copy(payload = payloadOf(op.payload)) }
                is DeltaOp.Invalidate -> { /* derived recompute stays at the producer; keep the stale payload until a cell_set */ }
                is DeltaOp.NodeRemove -> nodes.remove(op.node)
                is DeltaOp.EdgeAdd -> edges.add(op.dependent to op.dependency)
                is DeltaOp.EdgeRemove -> edges.remove(op.dependent to op.dependency)
            }
        }
        epoch = maxOf(epoch, delta.epoch)
        isInitialized = true
    }

    /** The node for [id], or null. */
    fun node(id: NodeId): Node? = nodes[id]

    /** All tracked nodes of [typeTag], in stable insertion order. */
    fun nodesOfType(typeTag: String): List<Node> = nodes.values.filter { it.typeTag == typeTag }

    /** The single node of [typeTag], or null (first in insertion order if several). */
    fun singletonNode(typeTag: String): Node? = nodes.values.firstOrNull { it.typeTag == typeTag }

    /** Every tracked node, stable insertion order. */
    fun allNodes(): List<Node> = nodes.values.toList()

    /** Every dependency edge (dependent → dependency). */
    fun allEdges(): List<Pair<NodeId, NodeId>> = edges.toList()

    /** Inline payload bytes → raw bytes; `Opaque`/`SharedBlob` carry no inline payload. */
    private fun payloadOf(state: NodeState): ByteArray? = when (state) {
        is NodeState.Payload -> state.toByteArray()
        else -> null
    }

    private fun payloadOf(value: IpcValue): ByteArray? = when (value) {
        is IpcValue.Inline -> value.toByteArray()
        else -> null
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else this.contentEquals(other)
