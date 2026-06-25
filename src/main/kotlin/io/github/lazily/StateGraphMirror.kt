package io.github.lazily

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * lazily-spec wire shapes for the agent-doc state projection (`#lazilystatesync3`).
 *
 * These data classes mirror `agent-doc-orchestration/src/state_wire.rs` 1:1 so the
 * Kotlin mirror applies the same JSON the Rust FFI emits through
 * `agent_doc_state_subscribe`. Field names are pinned to the Rust serde shape
 * (snake_case) via [SerialName] so a change on either side is a compile/test
 * break, not a silent skew.
 */
@Serializable
data class WireNodeSnapshot(
    @SerialName("slot_id") val slotId: Long,
    @SerialName("type_tag") val typeTag: String,
    val state: String = "resolved",
    val payload: String? = null,
)

@Serializable
data class WireEdgeSnapshot(
    val dependent: Long,
    val dependency: Long,
)

@Serializable
data class WireSnapshot(
    val epoch: Long,
    @SerialName("document_hash") val documentHash: String,
    val nodes: List<WireNodeSnapshot> = emptyList(),
    val edges: List<WireEdgeSnapshot> = emptyList(),
    val roots: List<Long> = emptyList(),
)

@Serializable
sealed class WireDeltaOp {
    @Serializable @SerialName("cell_set")
    data class CellSet(@SerialName("slot_id") val slotId: Long, val payload: String) : WireDeltaOp()

    @Serializable @SerialName("slot_value")
    data class SlotValue(@SerialName("slot_id") val slotId: Long, val payload: String) : WireDeltaOp()

    @Serializable @SerialName("invalidate")
    data class Invalidate(@SerialName("slot_id") val slotId: Long) : WireDeltaOp()

    @Serializable @SerialName("node_add")
    data class NodeAdd(
        @SerialName("slot_id") val slotId: Long,
        @SerialName("type_tag") val typeTag: String,
        val payload: String? = null,
    ) : WireDeltaOp()

    @Serializable @SerialName("node_remove")
    data class NodeRemove(@SerialName("slot_id") val slotId: Long) : WireDeltaOp()

    @Serializable @SerialName("edge_add")
    data class EdgeAdd(val dependent: Long, val dependency: Long) : WireDeltaOp()

    @Serializable @SerialName("edge_remove")
    data class EdgeRemove(val dependent: Long, val dependency: Long) : WireDeltaOp()
}

@Serializable
data class WireDelta(
    @SerialName("base_epoch") val baseEpoch: Long,
    val epoch: Long,
    @SerialName("document_hash") val documentHash: String,
    val ops: List<WireDeltaOp> = emptyList(),
)

/**
 * One discriminator-tagged message from `agent_doc_state_subscribe`.
 * `"type":"snapshot"` → cold full image; `"type":"delta"` → warm incremental.
 *
 * Decoded by [io.github.lazily.decodeSubscribe] reading the `"type"` field then
 * dispatching to the concrete subclass serializer.
 */
@Serializable
sealed class WireSubscribe {
    @Serializable @SerialName("snapshot")
    data class Snapshot(val epoch: Long, @SerialName("document_hash") val documentHash: String, val nodes: List<WireNodeSnapshot>, val edges: List<WireEdgeSnapshot>, val roots: List<Long>) : WireSubscribe()

    @Serializable @SerialName("delta")
    data class Delta(@SerialName("base_epoch") val baseEpoch: Long, val epoch: Long, @SerialName("document_hash") val documentHash: String, val ops: List<WireDeltaOp>) : WireSubscribe()
}

/**
 * The pure, FFI-free mirror graph a plugin holds per document.
 *
 * The binary owns the authoritative reactive graph; this mirror applies the
 * lazily-spec `snapshot`/`delta` messages emitted by
 * `agent_doc_state_subscribe` so editor UI reads tracked cells instead of
 * re-rendering a full JSON snapshot on every observed event. Because the
 * projection is a pure fold of deduped events, applying a delta is
 * deterministic and idempotent — a no-op delta (re-emit) leaves the mirror
 * unchanged.
 *
 * Pure helper behavior pinned to `StateProjectionBridgeSupport` tests so the
 * plugin-local JB copy (Kotlin 1.9 / Gson) stays in conformance (`#lzpkgwire`).
 */
class StateGraphMirror {
    /** Tracked node keyed by `slot_id`. */
    data class Node(val slotId: Long, val typeTag: String, val payload: String?)

    private val nodes = LinkedHashMap<Long, Node>()
    private val edges = LinkedHashSet<Pair<Long, Long>>()
    /** Monotonic frontier — the highest lazily-spec epoch applied so far. */
    var epoch: Long = 0
        private set

    val documentHash: String? get() = declaredHash
    private var declaredHash: String? = null

    /** True until at least one snapshot/delta has been applied. */
    val isInitialized: Boolean get() = declaredHash != null

    val nodeCount: Int get() = nodes.size

    /** Apply a cold-read snapshot, replacing the whole graph image. */
    fun applySnapshot(snapshot: WireSnapshot) {
        nodes.clear()
        edges.clear()
        declaredHash = snapshot.documentHash
        for (node in snapshot.nodes) {
            nodes[node.slotId] = Node(node.slotId, node.typeTag, node.payload)
        }
        for (edge in snapshot.edges) {
            edges.add(edge.dependent to edge.dependency)
        }
        epoch = snapshot.epoch
    }

    /**
     * Apply a warm delta. Ops are applied verbatim in emission order; the
     * frontier advances to [WireDelta.epoch]. A no-op delta (empty ops) only
     * advances the epoch.
     */
    fun applyDelta(delta: WireDelta) {
        if (delta.documentHash.isNotEmpty()) declaredHash = delta.documentHash
        for (op in delta.ops) {
            when (op) {
                is WireDeltaOp.NodeAdd -> nodes[op.slotId] = Node(op.slotId, op.typeTag, op.payload)
                is WireDeltaOp.CellSet -> nodes[op.slotId]?.let { it.copy(payload = op.payload) }?.let { nodes[op.slotId] = it }
                is WireDeltaOp.SlotValue -> nodes[op.slotId]?.let { it.copy(payload = op.payload) }?.let { nodes[op.slotId] = it }
                is WireDeltaOp.Invalidate -> { /* derived recompute is plugin-side; mirror keeps the stale payload until a cell_set arrives */ }
                is WireDeltaOp.NodeRemove -> nodes.remove(op.slotId)
                is WireDeltaOp.EdgeAdd -> edges.add(op.dependent to op.dependency)
                is WireDeltaOp.EdgeRemove -> edges.remove(op.dependent to op.dependency)
            }
        }
        epoch = maxOf(epoch, delta.epoch)
    }

    /** Apply a parsed [WireSubscribe] message (snapshot or delta). */
    fun apply(message: WireSubscribe) = when (message) {
        is WireSubscribe.Snapshot -> applySnapshot(
            WireSnapshot(message.epoch, message.documentHash, message.nodes, message.edges, message.roots)
        )
        is WireSubscribe.Delta -> applyDelta(WireDelta(message.baseEpoch, message.epoch, message.documentHash, message.ops))
    }

    /** All tracked nodes of [typeTag] (stable insertion order). */
    fun nodesOfType(typeTag: String): List<Node> =
        nodes.values.filter { it.typeTag == typeTag }

    /** The single document-level node for [typeTag], or null. */
    fun singletonNode(typeTag: String): Node? =
        nodes.values.firstOrNull { it.typeTag == typeTag }

    /**
     * Decode a node payload (`base64(serde_json(struct))`) as a JSON object, or
     * null when the node is missing or its payload is unset.
     */
    fun payloadObject(typeTag: String): JsonObject? {
        val node = singletonNode(typeTag) ?: return null
        return decodePayload(node.payload)
    }
}

/** Decode a `base64(serde_json(struct))` payload to a JSON object, or null on failure. */
fun decodePayload(payload: String?): JsonObject? {
    if (payload.isNullOrEmpty()) return null
    return try {
        val json = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        null
    }
}

/**
 * Reactive projection summary derived from a [StateGraphMirror]'s tracked
 * cells instead of re-parsing the full projection JSON (`#lazilystatesync3`).
 *
 * Reads `agent_doc.route`, `agent_doc.transport.patch`, and
 * `agent_doc.proof.marker` nodes. The wire node does not carry the transport
 * patch `entity_key` (patch_id) — the binary's full-snapshot `transport`
 * section does — so [latestTransportPatchId] is null from the warm path and
 * the cold-read [StateProjectionBridgeSupport.projectionSummary] supplies it.
 * The phase (the signal that drives busy/dispatch-ready) is always available.
 */
data class MirrorProjectionSummary(
    val routeReadiness: String?,
    val routePaneId: String?,
    val latestTransportPatchId: String?,
    val latestTransportPhase: String?,
    val proofMarkers: Int,
) {
    fun compact(): String =
        "route=${routeReadiness ?: "unknown"} pane=${routePaneId ?: "-"} " +
            "transport=${latestTransportPatchId ?: "-"}:${latestTransportPhase ?: "-"} " +
            "proof_markers=$proofMarkers"

    companion object {
        const val ROUTE = "agent_doc.route"
        const val TRANSPORT_PATCH = "agent_doc.transport.patch"
        const val PROOF_MARKER = "agent_doc.proof.marker"

        fun fromMirror(mirror: StateGraphMirror): MirrorProjectionSummary {
            val route = mirror.payloadObject(ROUTE)
            val readiness = route?.stringField("readiness")
            val paneId = route?.stringField("pane_id")

            val patches = mirror.nodesOfType(TRANSPORT_PATCH)
            val latest = patches.maxByOrNull { it.slotId }
            val phase = latest?.payload?.let(::decodePayload)?.stringField("phase")

            val proofMarkers = mirror.nodesOfType(PROOF_MARKER).size

            return MirrorProjectionSummary(
                routeReadiness = readiness,
                routePaneId = paneId,
                latestTransportPatchId = null,
                latestTransportPhase = phase,
                proofMarkers = proofMarkers,
            )
        }
    }
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
