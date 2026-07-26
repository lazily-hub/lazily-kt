package io.github.lazily

import java.io.PrintWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * NDJSON adapter used by the cross-binding interoperability suite.
 *
 * This is test infrastructure, not a production daemon. Wire values and frames
 * are parsed by [IpcMessage], and all merge/dedup decisions are delegated to
 * [CrdtPlaneRuntime].
 */
private class InteropPeer {
    private var peerId: PeerId? = null
    private var logical: Long = 0L
    private var runtime: CrdtPlaneRuntime? = null
    private val addresses = linkedSetOf<Pair<NodeId, String?>>()

    fun handle(request: JsonObject): JsonObject = when (request.string("cmd")) {
        "hello" -> hello(request)
        "local_set" -> localSet(request)
        "deliver" -> deliver(request)
        "snapshot" -> snapshot()
        "bye" -> ok()
        "link_open", "link_send", "link_recv", "link_close", "link_stats" ->
            buildJsonObject {
                put("ok", false)
                put("error", "unsupported channel")
                put("unsupported", true)
            }
        else -> failure("unknown command")
    }

    private fun hello(request: JsonObject): JsonObject {
        if (request.long("protocol_version") != PROTOCOL_VERSION) {
            return failure("unsupported protocol_version")
        }
        val assigned = request.long("peer")
        peerId = assigned
        logical = 0L
        runtime = CrdtPlaneRuntime(assigned)
        addresses.clear()
        return buildJsonObject {
            put("ok", true)
            put("binding", "lazily-kt")
            put("version", "0.38.1")
            put("protocol_version", PROTOCOL_VERSION)
            put("features", strings("distributed_crdt"))
            put("codecs", strings("json"))
            put("channels", JsonArray(emptyList()))
            put("channel_variants", JsonObject(emptyMap()))
            put("platform_profile", "portable")
            put("carve_outs", strings("msgpack", "transport_links"))
        }
    }

    private fun localSet(request: JsonObject): JsonObject {
        val (plane, assigned) = ready()
        val node = request.long("node")
        val at = request.long("at")
        val key = request["key"]?.let {
            if (it is JsonNull) null else it.jsonPrimitive.content
        }
        logical += 1L
        val op = CrdtOp(
            node = node,
            key = key?.let(NodeKey::from),
            stamp = WireStamp(at, logical, assigned),
            state = IpcValue.fromJson(request.required("state")),
        )
        check(plane.ingest(CrdtSync(emptyList(), listOf(op)), at) == 1) {
            "production runtime rejected fresh local op"
        }
        addresses += node to key
        val frame = IpcMessage.ofCrdtSync(
            CrdtSync(plane.wireFrontier(), listOf(op)),
        ).toJson()
        return buildJsonObject {
            put("ok", true)
            put("frame", frame)
        }
    }

    private fun deliver(request: JsonObject): JsonObject {
        val (plane) = ready()
        val message = IpcMessage.fromJson(request.required("frame"))
        val sync = (message as? IpcMessage.CrdtSyncMessage)?.sync
            ?: error("deliver requires CrdtSync")
        sync.ops.forEach { addresses += it.node to it.key?.path }
        return buildJsonObject {
            put("ok", true)
            put("applied", plane.ingest(sync, request.long("at")))
        }
    }

    private fun snapshot(): JsonObject {
        val (plane) = ready()
        val cells = addresses
            .sortedWith(compareBy<Pair<NodeId, String?>>({ it.first }, { it.second ?: "" }))
            .mapNotNull { (node, key) ->
                plane.value(node)?.let { state ->
                    buildJsonObject {
                        put("node", node)
                        put("key", key?.let(::JsonPrimitive) ?: JsonNull)
                        put("state", state.toJson())
                    }
                }
            }
        return buildJsonObject {
            put("ok", true)
            put("cells", JsonArray(cells))
        }
    }

    private fun ready(): Pair<CrdtPlaneRuntime, PeerId> =
        (runtime ?: error("hello must run first")) to
            (peerId ?: error("hello must run first"))
}

private const val PROTOCOL_VERSION = 1L
private val controlJson = Json

private fun JsonObject.required(name: String): JsonElement =
    this[name] ?: error("missing field: $name")

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long = required(name).jsonPrimitive.long

private fun strings(vararg values: String): JsonArray =
    buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

private fun ok(): JsonObject = buildJsonObject { put("ok", true) }

private fun failure(message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", message)
}

private fun selfCheck() {
    val peer = InteropPeer()
    check(peer.handle(buildJsonObject {
        put("cmd", "hello")
        put("peer", 1)
        put("protocol_version", PROTOCOL_VERSION)
    })["ok"]?.jsonPrimitive?.content == "true")
    val local = peer.handle(controlJson.parseToJsonElement(
        """{"cmd":"local_set","node":7,"key":null,"state":{"Inline":[65]},"at":10}""",
    ).jsonObject)
    val frame = local.required("frame")
    val delivered = peer.handle(buildJsonObject {
        put("cmd", "deliver")
        put("frame", frame)
        put("at", 11)
    })
    check(delivered.long("applied") == 0L)
    check(peer.handle(buildJsonObject { put("cmd", "snapshot") })
        .required("cells").toString().contains("\"Inline\":[65]"))
}

fun main(args: Array<String>) {
    if (args.contains("--self-check")) {
        selfCheck()
        System.err.println("lazily-kt interop peer self-check: ok")
        return
    }

    val peer = InteropPeer()
    val output = PrintWriter(System.out, true)
    for (line in System.`in`.bufferedReader().lineSequence()) {
        var request: JsonObject? = null
        val response = try {
            request = controlJson.parseToJsonElement(line).jsonObject
            peer.handle(request)
        } catch (error: Exception) {
            failure(error.message ?: error::class.simpleName ?: "peer error")
        }
        output.println(response)
        if (request?.string("cmd") == "bye") break
    }
}
