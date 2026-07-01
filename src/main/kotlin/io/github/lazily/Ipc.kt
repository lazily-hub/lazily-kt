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

/** Maximum encoded byte length of a [NodeKey] path. */
const val NODE_KEY_MAX_LEN: Int = 1024

/** Maximum number of `/`-separated segments in a [NodeKey] path. */
const val NODE_KEY_MAX_SEGMENTS: Int = 32

/** Why a [NodeKey] failed validation. Mirrors lazily-rs `NodeKeyError`. */
sealed class NodeKeyError(message: String) : RuntimeException(message) {
    data object Empty : NodeKeyError("node key path is empty")
    data class TooLong(val len: Int) :
        NodeKeyError("node key path is $len bytes, exceeds $NODE_KEY_MAX_LEN")
    data class TooManySegments(val segments: Int) :
        NodeKeyError("node key has $segments segments, exceeds $NODE_KEY_MAX_SEGMENTS")
    data object EmptySegment : NodeKeyError("node key path has an empty segment")
}

/**
 * Wire-stable keyed address for a collection entry — a `/`-joined path
 * (e.g. `scores/alice`, `outer/k1/inner/k2`). Unlike [NodeId] — the volatile
 * internal handle a producer may re-mint after a resync — a `NodeKey` is
 * producer-defined and stable across NodeId churn, so a peer can subscribe to
 * "entry `scores/alice`" without an out-of-band key→NodeId map. A multi-segment
 * path addresses nested collections with no extra machinery.
 *
 * Length and segment count are bounded ([NODE_KEY_MAX_LEN],
 * [NODE_KEY_MAX_SEGMENTS]); oversized keys are rejected on construction and on
 * the wire. Serialization is format-aware: self-describing codecs (JSON) omit a
 * `None` key so pre-`key` encoders and existing conformance fixtures round-trip
 * unchanged. See `lazily-spec/protocol.md` § NodeKey.
 */
@JvmInline
value class NodeKey private constructor(val path: String) {
    init {
        validate(path)
    }

    /** Iterate the path segments. */
    fun segments(): List<String> = path.split('/')

    /** Attach this key to a [NodeSnapshot] (builder style). */
    fun attach(node: NodeSnapshot): NodeSnapshot = node.withKey(this)

    fun toJson(): JsonPrimitive = JsonPrimitive(path)

    companion object {
        /** Construct a validated key from a `/`-joined path; throws [NodeKeyError] on invalid input. */
        fun from(path: String): NodeKey {
            validate(path)
            return NodeKey(path)
        }

        /** Construct a key from already-validated segments (joined with `/`). */
        fun fromSegments(segments: Iterable<String>): NodeKey = from(segments.joinToString("/"))

        fun fromJson(element: JsonElement): NodeKey = from(element.jsonPrimitive.content)

        private fun validate(path: String) {
            if (path.isEmpty()) throw NodeKeyError.Empty
            if (path.length > NODE_KEY_MAX_LEN) throw NodeKeyError.TooLong(path.length)
            var segments = 0
            for (segment in path.split('/')) {
                if (segment.isEmpty()) throw NodeKeyError.EmptySegment
                segments += 1
            }
            if (segments > NODE_KEY_MAX_SEGMENTS) throw NodeKeyError.TooManySegments(segments)
        }
    }
}

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
    val key: NodeKey? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("node", node)
        put("type_tag", typeTag)
        put("state", state.toJson())
        if (key != null) put("key", key.toJson())
    }

    /** Attach a wire-stable [NodeKey] to this node (builder style). */
    fun withKey(key: NodeKey): NodeSnapshot = copy(key = key)

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
                key = (obj["key"] as? JsonPrimitive)?.let { NodeKey.fromJson(it) },
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
        val key: NodeKey? = null,
    ) : DeltaOp {
        override fun toJson(): JsonObject = buildJsonObject {
            put("NodeAdd", buildJsonObject {
                put("node", node)
                put("type_tag", typeTag)
                put("state", state.toJson())
                if (key != null) put("key", key.toJson())
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
        fun nodeAdd(node: NodeId, typeTag: String, state: NodeState, key: NodeKey?): DeltaOp =
            NodeAdd(node, typeTag, state, key)
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
                    key = (body["key"] as? JsonPrimitive)?.let { NodeKey.fromJson(it) },
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

/**
 * Wire-stable mirror of a hybrid-logical-clock stamp — all plain integers, so
 * the IPC layer carries CRDT causal-stability metadata without depending on a
 * distributed runtime. Total order is `(wall_time, logical, peer)`. See
 * `lazily-spec/protocol.md` § Distributed.
 */
data class WireStamp(
    val wallTime: Long,
    val logical: Long,
    val peer: PeerId,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("wall_time", wallTime)
        put("logical", logical)
        put("peer", peer)
    }

    companion object {
        fun fromJson(element: JsonElement): WireStamp {
            val obj = element.asObject("WireStamp")
            return WireStamp(
                wallTime = obj.longField("wall_time"),
                logical = obj.longField("logical"),
                peer = obj.longField("peer"),
            )
        }
    }
}

/**
 * One CRDT cell op on the wire (state-based / CvRDT): the converged register,
 * sequence, or text `state` for [node], tagged with the [stamp] that produced
 * it and the optional wire-stable [key] that survives NodeId churn. The
 * receiver merges `state` into its local replica; because every cell CRDT merge
 * is commutative, associative, and idempotent, out-of-order, duplicated, or
 * batched delivery all converge.
 *
 * `key` is serialized as `null` when absent (mirroring lazily-rs), so a decoder
 * accepts `null`, absent, or a bare path string.
 */
data class CrdtOp(
    val node: NodeId,
    val key: NodeKey?,
    val stamp: WireStamp,
    val state: IpcValue,
) {
    fun targetReadable(permissions: PeerPermissions, peer: PeerId): Boolean =
        permissions.canRead(peer, node)

    fun toJson(): JsonObject = buildJsonObject {
        put("node", node)
        put("key", key?.toJson() ?: JsonNull)
        put("stamp", stamp.toJson())
        put("state", state.toJson())
    }

    companion object {
        fun fromJson(element: JsonElement): CrdtOp {
            val obj = element.asObject("CrdtOp")
            val keyEl = obj["key"]
            val key = if (keyEl == null || keyEl is JsonNull) null else NodeKey.fromJson(keyEl)
            return CrdtOp(
                node = obj.longField("node"),
                key = key,
                stamp = WireStamp.fromJson(obj.required("stamp")),
                state = IpcValue.fromJson(obj.required("state")),
            )
        }
    }
}

/**
 * A CRDT anti-entropy sync frame: the sender advertises its per-peer stamp
 * [frontier] (the highest [WireStamp] it has observed from each peer) and ships
 * a batch of [ops]. The frontier is the `StampFrontier` exchange: it lets the
 * receiver compute which ops it is still missing (anti-entropy) and feeds the
 * causal-stability watermark (`min` over membership) that drives tombstone GC.
 * The exchange is bounded, idempotent, and resumable; re-sending a frame the
 * receiver already has is a no-op.
 */
data class CrdtSync(
    val frontier: List<Pair<PeerId, WireStamp>>,
    val ops: List<CrdtOp>,
) {
    /**
     * Return a peer-specific frame that omits ops for non-readable nodes
     * entirely (omission, not redaction — mirroring [Delta.filterReadable]).
     * The [frontier] advertisement is retained: it names peers and stamps, not
     * node content, and the receiver needs the whole frontier to compute a
     * sound causal-stability watermark.
     */
    fun filterReadable(permissions: PeerPermissions, peer: PeerId): CrdtSync =
        CrdtSync(
            frontier = frontier,
            ops = ops.filter { it.targetReadable(permissions, peer) },
        )

    fun toJson(): JsonObject = buildJsonObject {
        put("frontier", buildJsonArray {
            for ((peer, stamp) in frontier) {
                add(buildJsonArray {
                    add(JsonPrimitive(peer))
                    add(stamp.toJson())
                })
            }
        })
        put("ops", buildJsonArray { ops.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(element: JsonElement): CrdtSync {
            val obj = element.asObject("CrdtSync")
            return CrdtSync(
                frontier = obj.required("frontier").asArray("frontier").map { entry ->
                    val pair = entry.asArray("frontier entry")
                    require(pair.size == 2) { "frontier entry must be a [peer, stamp] pair" }
                    pair[0].jsonPrimitive.long to WireStamp.fromJson(pair[1])
                },
                ops = obj.required("ops").asArray("ops").map { CrdtOp.fromJson(it) },
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

    data class CrdtSyncMessage(val sync: CrdtSync) : IpcMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("CrdtSync", sync.toJson())
        }
    }

    fun encodeJson(): ByteArray =
        ipcJson.encodeToString(JsonElement.serializer(), toJson()).encodeToByteArray()

    companion object {
        fun ofSnapshot(snapshot: Snapshot): IpcMessage = SnapshotMessage(snapshot)
        fun ofDelta(delta: Delta): IpcMessage = DeltaMessage(delta)
        fun ofCrdtSync(sync: CrdtSync): IpcMessage = CrdtSyncMessage(sync)

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
                "CrdtSync" -> CrdtSyncMessage(CrdtSync.fromJson(body))
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
