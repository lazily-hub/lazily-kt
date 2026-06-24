package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

typealias NodeId = Long
typealias PeerId = Long

private val ipcJson = Json {
    prettyPrint = false
}

private fun JsonElement.asObject(name: String): JsonObject =
    this as? JsonObject ?: error("$name must be a JSON object")

private fun JsonElement.asArray(name: String): JsonArray =
    this as? JsonArray ?: error("$name must be a JSON array")

private fun JsonObject.required(name: String): JsonElement =
    this[name] ?: error("missing required field: $name")

private fun JsonObject.longField(name: String): Long =
    required(name).jsonPrimitive.long

private fun JsonObject.stringField(name: String): String =
    required(name).jsonPrimitive.content

private fun bytesToJson(bytes: List<Int>): JsonArray = buildJsonArray {
    for (byte in bytes) {
        require(byte in 0..255) { "byte value out of range: $byte" }
        add(JsonPrimitive(byte))
    }
}

private fun bytesFromJson(element: JsonElement): List<Int> =
    element.asArray("byte payload").map { value ->
        val byte = value.jsonPrimitive.int
        require(byte in 0..255) { "byte value out of range: $byte" }
        byte
    }

private fun ByteArray.toWireBytes(): List<Int> = map { it.toInt() and 0xff }

data class ShmBlobRef(
    val offset: Long,
    val len: Long,
    val generation: Long,
    val epoch: Long,
    val checksum: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("offset", offset)
        put("len", len)
        put("generation", generation)
        put("epoch", epoch)
        put("checksum", checksum)
    }

    companion object {
        fun fromJson(element: JsonElement): ShmBlobRef {
            val obj = element.asObject("ShmBlobRef")
            return ShmBlobRef(
                offset = obj.longField("offset"),
                len = obj.longField("len"),
                generation = obj.longField("generation"),
                epoch = obj.longField("epoch"),
                checksum = obj.longField("checksum"),
            )
        }
    }
}

sealed interface NodeState {
    fun toJson(): JsonElement

    data class Payload(val bytes: List<Int>) : NodeState {
        constructor(bytes: ByteArray) : this(bytes.toWireBytes())

        init {
            bytesToJson(bytes)
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

        override fun toJson(): JsonElement = buildJsonObject {
            put("Payload", bytesToJson(bytes))
        }
    }

    data class SharedBlob(val blob: ShmBlobRef) : NodeState {
        override fun toJson(): JsonElement = buildJsonObject {
            put("SharedBlob", blob.toJson())
        }
    }

    data object Opaque : NodeState {
        override fun toJson(): JsonElement = JsonPrimitive("Opaque")
    }

    companion object {
        fun fromJson(element: JsonElement): NodeState {
            element.jsonPrimitiveOrNull()?.contentOrNull?.let { tag ->
                require(tag == "Opaque") { "unknown NodeState unit variant: $tag" }
                return Opaque
            }

            val obj = element.asObject("NodeState")
            require(obj.size == 1) { "NodeState must be externally tagged" }
            val (tag, body) = obj.entries.single()
            return when (tag) {
                "Payload" -> Payload(bytesFromJson(body))
                "SharedBlob" -> SharedBlob(ShmBlobRef.fromJson(body))
                "Opaque" -> Opaque
                else -> error("unknown NodeState variant: $tag")
            }
        }
    }
}

sealed interface IpcValue {
    fun toJson(): JsonElement

    data class Inline(val bytes: List<Int>) : IpcValue {
        constructor(bytes: ByteArray) : this(bytes.toWireBytes())

        init {
            bytesToJson(bytes)
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

        override fun toJson(): JsonElement = buildJsonObject {
            put("Inline", bytesToJson(bytes))
        }
    }

    data class SharedBlob(val blob: ShmBlobRef) : IpcValue {
        override fun toJson(): JsonElement = buildJsonObject {
            put("SharedBlob", blob.toJson())
        }
    }

    companion object {
        fun inline(bytes: ByteArray): IpcValue = Inline(bytes)
        fun sharedBlob(blob: ShmBlobRef): IpcValue = SharedBlob(blob)

        fun fromJson(element: JsonElement): IpcValue {
            val obj = element.asObject("IpcValue")
            require(obj.size == 1) { "IpcValue must be externally tagged" }
            val (tag, body) = obj.entries.single()
            return when (tag) {
                "Inline" -> Inline(bytesFromJson(body))
                "SharedBlob" -> SharedBlob(ShmBlobRef.fromJson(body))
                else -> error("unknown IpcValue variant: $tag")
            }
        }
    }
}

data class NodeSnapshot(
    val node: NodeId,
    val typeTag: String,
    val state: NodeState,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("node", node)
        put("type_tag", typeTag)
        put("state", state.toJson())
    }

    companion object {
        fun payload(node: NodeId, typeTag: String, bytes: ByteArray): NodeSnapshot =
            NodeSnapshot(node, typeTag, NodeState.Payload(bytes))

        fun sharedBlob(node: NodeId, typeTag: String, blob: ShmBlobRef): NodeSnapshot =
            NodeSnapshot(node, typeTag, NodeState.SharedBlob(blob))

        fun opaque(node: NodeId, typeTag: String): NodeSnapshot =
            NodeSnapshot(node, typeTag, NodeState.Opaque)

        fun fromJson(element: JsonElement): NodeSnapshot {
            val obj = element.asObject("NodeSnapshot")
            return NodeSnapshot(
                node = obj.longField("node"),
                typeTag = obj.stringField("type_tag"),
                state = NodeState.fromJson(obj.required("state")),
            )
        }
    }
}

data class EdgeSnapshot(
    val dependent: NodeId,
    val dependency: NodeId,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("dependent", dependent)
        put("dependency", dependency)
    }

    fun isReadableBy(permissions: PeerPermissions, peer: PeerId): Boolean =
        permissions.canRead(peer, dependent) && permissions.canRead(peer, dependency)

    companion object {
        fun fromJson(element: JsonElement): EdgeSnapshot {
            val obj = element.asObject("EdgeSnapshot")
            return EdgeSnapshot(
                dependent = obj.longField("dependent"),
                dependency = obj.longField("dependency"),
            )
        }
    }
}

data class Snapshot(
    val epoch: Long,
    val nodes: List<NodeSnapshot> = emptyList(),
    val edges: List<EdgeSnapshot> = emptyList(),
    val roots: List<NodeId> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("epoch", epoch)
        put("nodes", buildJsonArray { nodes.forEach { add(it.toJson()) } })
        put("edges", buildJsonArray { edges.forEach { add(it.toJson()) } })
        put("roots", buildJsonArray { roots.forEach { add(JsonPrimitive(it)) } })
    }

    fun filterReadable(permissions: PeerPermissions, peer: PeerId): Snapshot =
        Snapshot(
            epoch = epoch,
            nodes = nodes.filter { permissions.canRead(peer, it.node) },
            edges = edges.filter { it.isReadableBy(permissions, peer) },
            roots = permissions.filterReadable(peer, roots),
        )

    companion object {
        fun fromJson(element: JsonElement): Snapshot {
            val obj = element.asObject("Snapshot")
            return Snapshot(
                epoch = obj.longField("epoch"),
                nodes = obj.required("nodes").jsonArray.map { NodeSnapshot.fromJson(it) },
                edges = obj.required("edges").jsonArray.map { EdgeSnapshot.fromJson(it) },
                roots = obj.required("roots").jsonArray.map { it.jsonPrimitive.long },
            )
        }
    }
}

sealed interface DeltaOp {
    fun toJson(): JsonObject
    fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean

    data class CellSet(val node: NodeId, val payload: IpcValue) : DeltaOp {
        constructor(node: NodeId, bytes: ByteArray) : this(node, IpcValue.Inline(bytes))

        override fun toJson(): JsonObject = buildJsonObject {
            put("CellSet", buildJsonObject {
                put("node", node)
                put("payload", payload.toJson())
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, node)
    }

    data class SlotValue(val node: NodeId, val payload: IpcValue) : DeltaOp {
        constructor(node: NodeId, bytes: ByteArray) : this(node, IpcValue.Inline(bytes))

        override fun toJson(): JsonObject = buildJsonObject {
            put("SlotValue", buildJsonObject {
                put("node", node)
                put("payload", payload.toJson())
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, node)
    }

    data class Invalidate(val node: NodeId) : DeltaOp {
        override fun toJson(): JsonObject = buildJsonObject {
            put("Invalidate", buildJsonObject {
                put("node", node)
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, node)
    }

    data class NodeAdd(
        val node: NodeId,
        val typeTag: String,
        val state: NodeState,
    ) : DeltaOp {
        override fun toJson(): JsonObject = buildJsonObject {
            put("NodeAdd", buildJsonObject {
                put("node", node)
                put("type_tag", typeTag)
                put("state", state.toJson())
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, node)
    }

    data class NodeRemove(val node: NodeId) : DeltaOp {
        override fun toJson(): JsonObject = buildJsonObject {
            put("NodeRemove", buildJsonObject {
                put("node", node)
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, node)
    }

    data class EdgeAdd(val dependent: NodeId, val dependency: NodeId) : DeltaOp {
        override fun toJson(): JsonObject = buildJsonObject {
            put("EdgeAdd", buildJsonObject {
                put("dependent", dependent)
                put("dependency", dependency)
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, dependent) && permissions.canRead(peer, dependency)
    }

    data class EdgeRemove(val dependent: NodeId, val dependency: NodeId) : DeltaOp {
        override fun toJson(): JsonObject = buildJsonObject {
            put("EdgeRemove", buildJsonObject {
                put("dependent", dependent)
                put("dependency", dependency)
            })
        }

        override fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
            permissions.canRead(peer, dependent) && permissions.canRead(peer, dependency)
    }

    companion object {
        fun cellSet(node: NodeId, bytes: ByteArray): DeltaOp = CellSet(node, bytes)
        fun cellSet(node: NodeId, payload: IpcValue): DeltaOp = CellSet(node, payload)
        fun slotValue(node: NodeId, bytes: ByteArray): DeltaOp = SlotValue(node, bytes)
        fun slotValue(node: NodeId, payload: IpcValue): DeltaOp = SlotValue(node, payload)
        fun invalidate(node: NodeId): DeltaOp = Invalidate(node)
        fun nodeAdd(node: NodeId, typeTag: String, state: NodeState): DeltaOp =
            NodeAdd(node, typeTag, state)
        fun nodeRemove(node: NodeId): DeltaOp = NodeRemove(node)
        fun edgeAdd(dependent: NodeId, dependency: NodeId): DeltaOp =
            EdgeAdd(dependent, dependency)
        fun edgeRemove(dependent: NodeId, dependency: NodeId): DeltaOp =
            EdgeRemove(dependent, dependency)

        fun fromJson(element: JsonElement): DeltaOp {
            val obj = element.asObject("DeltaOp")
            require(obj.size == 1) { "DeltaOp must be externally tagged" }
            val (tag, bodyElement) = obj.entries.single()
            val body = bodyElement.asObject(tag)
            return when (tag) {
                "CellSet" -> CellSet(
                    node = body.longField("node"),
                    payload = IpcValue.fromJson(body.required("payload")),
                )
                "SlotValue" -> SlotValue(
                    node = body.longField("node"),
                    payload = IpcValue.fromJson(body.required("payload")),
                )
                "Invalidate" -> Invalidate(body.longField("node"))
                "NodeAdd" -> NodeAdd(
                    node = body.longField("node"),
                    typeTag = body.stringField("type_tag"),
                    state = NodeState.fromJson(body.required("state")),
                )
                "NodeRemove" -> NodeRemove(body.longField("node"))
                "EdgeAdd" -> EdgeAdd(
                    dependent = body.longField("dependent"),
                    dependency = body.longField("dependency"),
                )
                "EdgeRemove" -> EdgeRemove(
                    dependent = body.longField("dependent"),
                    dependency = body.longField("dependency"),
                )
                else -> error("unknown DeltaOp variant: $tag")
            }
        }
    }
}

sealed interface DeltaApplyStatus {
    data object Apply : DeltaApplyStatus
    data class ResyncRequired(
        val lastEpoch: Long,
        val baseEpoch: Long,
        val epoch: Long,
    ) : DeltaApplyStatus
}

data class Delta(
    val baseEpoch: Long,
    val epoch: Long,
    val ops: List<DeltaOp> = emptyList(),
) {
    fun isNextAfter(lastEpoch: Long): Boolean =
        baseEpoch == lastEpoch && epoch == baseEpoch + 1

    fun applyStatus(lastEpoch: Long): DeltaApplyStatus =
        if (isNextAfter(lastEpoch)) {
            DeltaApplyStatus.Apply
        } else {
            DeltaApplyStatus.ResyncRequired(lastEpoch, baseEpoch, epoch)
        }

    fun filterReadable(permissions: PeerPermissions, peer: PeerId): Delta =
        Delta(baseEpoch, epoch, ops.filter { it.targetReadable(permissions, peer) })

    fun toJson(): JsonObject = buildJsonObject {
        put("base_epoch", baseEpoch)
        put("epoch", epoch)
        put("ops", buildJsonArray { ops.forEach { add(it.toJson()) } })
    }

    companion object {
        fun next(baseEpoch: Long, ops: List<DeltaOp>): Delta =
            Delta(baseEpoch = baseEpoch, epoch = baseEpoch + 1, ops = ops)

        fun fromJson(element: JsonElement): Delta {
            val obj = element.asObject("Delta")
            return Delta(
                baseEpoch = obj.longField("base_epoch"),
                epoch = obj.longField("epoch"),
                ops = obj.required("ops").jsonArray.map { DeltaOp.fromJson(it) },
            )
        }
    }
}

sealed interface IpcMessage {
    fun toJson(): JsonObject

    data class SnapshotMessage(val snapshot: Snapshot) : IpcMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("Snapshot", snapshot.toJson())
        }
    }

    data class DeltaMessage(val delta: Delta) : IpcMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("Delta", delta.toJson())
        }
    }

    fun encodeJson(): ByteArray =
        ipcJson.encodeToString(JsonElement.serializer(), toJson()).encodeToByteArray()

    companion object {
        fun ofSnapshot(snapshot: Snapshot): IpcMessage = SnapshotMessage(snapshot)
        fun ofDelta(delta: Delta): IpcMessage = DeltaMessage(delta)

        fun decodeJson(data: ByteArray): IpcMessage =
            fromJson(ipcJson.parseToJsonElement(data.decodeToString()))

        fun decodeJson(data: String): IpcMessage =
            fromJson(ipcJson.parseToJsonElement(data))

        fun fromJson(element: JsonElement): IpcMessage {
            val obj = element.asObject("IpcMessage")
            require(obj.size == 1) { "IpcMessage must be externally tagged" }
            val (tag, body) = obj.entries.single()
            return when (tag) {
                "Snapshot" -> SnapshotMessage(Snapshot.fromJson(body))
                "Delta" -> DeltaMessage(Delta.fromJson(body))
                else -> error("unknown IpcMessage variant: $tag")
            }
        }
    }
}

enum class OpKind {
    Read,
    Write,
    TriggerEffect,
}

data class RemoteOp(val kind: OpKind, val node: NodeId) {
    companion object {
        fun read(node: NodeId): RemoteOp = RemoteOp(OpKind.Read, node)
        fun write(node: NodeId): RemoteOp = RemoteOp(OpKind.Write, node)
        fun triggerEffect(node: NodeId): RemoteOp = RemoteOp(OpKind.TriggerEffect, node)
    }
}

class PermissionDenied(val peer: PeerId, val op: RemoteOp) :
    RuntimeException("peer $peer denied ${op.kind} on node ${op.node}")

class PeerPermissions {
    private val peers: MutableMap<PeerId, MutableMap<OpKind, MutableSet<NodeId>>> = mutableMapOf()

    fun allow(peer: PeerId, op: RemoteOp): Boolean {
        val nodes = peers
            .getOrPut(peer) { mutableMapOf() }
            .getOrPut(op.kind) { mutableSetOf() }
        return nodes.add(op.node)
    }

    fun allowMany(peer: PeerId, kind: OpKind, nodes: Iterable<NodeId>) {
        peers
            .getOrPut(peer) { mutableMapOf() }
            .getOrPut(kind) { mutableSetOf() }
            .addAll(nodes)
    }

    fun revoke(peer: PeerId, op: RemoteOp): Boolean {
        val peerPermissions = peers[peer] ?: return false
        val nodes = peerPermissions[op.kind] ?: return false
        val removed = nodes.remove(op.node)
        prune(peer)
        return removed
    }

    fun revokePeer(peer: PeerId): Boolean = peers.remove(peer) != null

    fun isAllowed(peer: PeerId, op: RemoteOp): Boolean =
        peers[peer]?.get(op.kind)?.contains(op.node) == true

    fun canRead(peer: PeerId, node: NodeId): Boolean =
        isAllowed(peer, RemoteOp.read(node))

    fun check(peer: PeerId, op: RemoteOp) {
        if (!isAllowed(peer, op)) {
            throw PermissionDenied(peer, op)
        }
    }

    fun filterReadable(peer: PeerId, nodes: Iterable<NodeId>): List<NodeId> =
        nodes.filter { canRead(peer, it) }

    fun peerCount(): Int = peers.size

    private fun prune(peer: PeerId) {
        val peerPermissions = peers[peer] ?: return
        peerPermissions.entries.removeIf { it.value.isEmpty() }
        if (peerPermissions.isEmpty()) {
            peers.remove(peer)
        }
    }
}

private fun JsonElement.jsonPrimitiveOrNull() =
    this as? JsonPrimitive
