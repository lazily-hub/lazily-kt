package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private val signalingJson = Json { prettyPrint = false }

private fun JsonObject.peerField(name: String): PeerId =
    (this[name] ?: error("missing required field: $name")).jsonPrimitive.long

private fun JsonObject.strField(name: String): String =
    (this[name] ?: error("missing required field: $name")).jsonPrimitive.content

/**
 * A message the client sends to the signaling server. `type` tags are
 * kebab-case; client-directed frames carry `to` (never `from`). Mirrors
 * lazily-rs `ClientMessage` and the shared `signaling/frames.json` fixture.
 */
sealed interface ClientMessage {
    fun toJson(): JsonObject

    /** Encode to a wire JSON string. */
    fun toJsonString(): String = signalingJson.encodeToString(JsonElement.serializer(), toJson())

    /** Join the session as [peer], optionally advertising [capabilities]. */
    data class Join(val peer: PeerId, val capabilities: List<String>? = null) : ClientMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "join")
            put("peer", peer)
            if (capabilities != null) {
                put("capabilities", buildJsonArray { capabilities.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    /** WebRTC offer for [to]. */
    data class Offer(val to: PeerId, val sdp: String) : ClientMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "offer")
            put("to", to)
            put("sdp", sdp)
        }
    }

    /** WebRTC answer for [to]. */
    data class Answer(val to: PeerId, val sdp: String) : ClientMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "answer")
            put("to", to)
            put("sdp", sdp)
        }
    }

    /** ICE candidate for [to]. */
    data class Ice(val to: PeerId, val candidate: String) : ClientMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "ice")
            put("to", to)
            put("candidate", candidate)
        }
    }

    /** Opaque application payload (e.g. a CRDT delta) relayed to [to]. */
    data class Relay(val to: PeerId, val payload: JsonElement) : ClientMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "relay")
            put("to", to)
            put("payload", payload)
        }
    }

    /** Leave the session. */
    data object Leave : ClientMessage {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "leave") }
    }

    companion object {
        fun fromJson(element: JsonElement): ClientMessage {
            val obj = element.jsonObject
            return when (val type = obj.strField("type")) {
                "join" -> Join(
                    peer = obj.peerField("peer"),
                    capabilities = obj["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
                "offer" -> Offer(to = obj.peerField("to"), sdp = obj.strField("sdp"))
                "answer" -> Answer(to = obj.peerField("to"), sdp = obj.strField("sdp"))
                "ice" -> Ice(to = obj.peerField("to"), candidate = obj.strField("candidate"))
                "relay" -> Relay(to = obj.peerField("to"), payload = obj["payload"] ?: error("missing payload"))
                "leave" -> Leave
                else -> error("unknown ClientMessage type: $type")
            }
        }

        fun fromJsonString(text: String): ClientMessage =
            fromJson(signalingJson.parseToJsonElement(text))
    }
}

/**
 * A message the signaling server sends to the client. Server-forwarded frames
 * carry a server-stamped `from` (never a client-supplied value); `to`/`from` are
 * never both present. Mirrors lazily-rs `ServerMessage`.
 */
sealed interface ServerMessage {
    fun toJson(): JsonObject

    fun toJsonString(): String = signalingJson.encodeToString(JsonElement.serializer(), toJson())

    /** Sent on join: this peer's id and the current roster (excluding self). */
    data class Welcome(val peer: PeerId, val peers: List<PeerId>) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "welcome")
            put("peer", peer)
            put("peers", buildJsonArray { peers.forEach { add(JsonPrimitive(it)) } })
        }
    }

    /** Another peer joined. */
    data class PeerJoined(val peer: PeerId) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "peer-joined")
            put("peer", peer)
        }
    }

    /** Another peer left. */
    data class PeerLeft(val peer: PeerId) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "peer-left")
            put("peer", peer)
        }
    }

    /** Forwarded WebRTC offer from [from]. */
    data class Offer(val from: PeerId, val sdp: String) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "offer")
            put("from", from)
            put("sdp", sdp)
        }
    }

    /** Forwarded WebRTC answer from [from]. */
    data class Answer(val from: PeerId, val sdp: String) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "answer")
            put("from", from)
            put("sdp", sdp)
        }
    }

    /** Forwarded ICE candidate from [from]. */
    data class Ice(val from: PeerId, val candidate: String) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "ice")
            put("from", from)
            put("candidate", candidate)
        }
    }

    /** Forwarded opaque payload from [from]. */
    data class Relay(val from: PeerId, val payload: JsonElement) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "relay")
            put("from", from)
            put("payload", payload)
        }
    }

    /** Server-side error (e.g. `permission_denied`, `unknown_target`). */
    data class Error(val code: String, val message: String) : ServerMessage {
        override fun toJson(): JsonObject = buildJsonObject {
            put("type", "error")
            put("code", code)
            put("message", message)
        }
    }

    companion object {
        fun fromJson(element: JsonElement): ServerMessage {
            val obj = element.jsonObject
            return when (val type = obj.strField("type")) {
                "welcome" -> Welcome(
                    peer = obj.peerField("peer"),
                    peers = obj["peers"]!!.jsonArray.map { it.jsonPrimitive.long },
                )
                "peer-joined" -> PeerJoined(peer = obj.peerField("peer"))
                "peer-left" -> PeerLeft(peer = obj.peerField("peer"))
                "offer" -> Offer(from = obj.peerField("from"), sdp = obj.strField("sdp"))
                "answer" -> Answer(from = obj.peerField("from"), sdp = obj.strField("sdp"))
                "ice" -> Ice(from = obj.peerField("from"), candidate = obj.strField("candidate"))
                "relay" -> Relay(from = obj.peerField("from"), payload = obj["payload"] ?: error("missing payload"))
                "error" -> Error(code = obj.strField("code"), message = obj.strField("message"))
                else -> error("unknown ServerMessage type: $type")
            }
        }

        fun fromJsonString(text: String): ServerMessage =
            fromJson(signalingJson.parseToJsonElement(text))
    }
}

/** Canonical signaling error codes (wire strings). */
object SignalingErrorCode {
    const val BAD_MESSAGE = "bad_message"
    const val NOT_JOINED = "not_joined"
    const val ALREADY_JOINED = "already_joined"
    const val DUPLICATE_PEER = "duplicate_peer"
    const val UNKNOWN_TARGET = "unknown_target"
    const val PERMISSION_DENIED = "permission_denied"
}

/**
 * A frame the server room emits: the target connection id and the message.
 */
data class RoomEmit<Conn>(val to: Conn, val frame: ServerMessage)

/**
 * The server-side room routing core (`RoomCore`), independent of any transport.
 *
 * Given a connection id and a received [ClientMessage], [handle] returns the
 * frames to emit — enforcing the load-bearing anti-spoof invariant: a forwarded
 * frame's `from` is always set to the SENDER's server-registered peer id, never
 * a client-supplied value. The `welcome` roster excludes the joining peer's own
 * id and is sorted ascending; a directed frame to an unknown peer yields an
 * `unknown_target` error. Replays `signaling/anti_spoof_session.json`.
 *
 * [Conn] is the opaque connection identity (e.g. a socket handle or a test id).
 */
class RoomCore<Conn> {
    private val connToPeer = LinkedHashMap<Conn, PeerId>()
    private val peerToConn = LinkedHashMap<PeerId, Conn>()

    /** The current roster, sorted ascending. */
    fun roster(): List<PeerId> = peerToConn.keys.sorted()

    /** Route a received client message from [conn]; returns the frames to emit. */
    fun handle(conn: Conn, message: ClientMessage): List<RoomEmit<Conn>> =
        when (message) {
            is ClientMessage.Join -> onJoin(conn, message.peer)
            is ClientMessage.Offer -> forward(conn, message.to) { from -> ServerMessage.Offer(from, message.sdp) }
            is ClientMessage.Answer -> forward(conn, message.to) { from -> ServerMessage.Answer(from, message.sdp) }
            is ClientMessage.Ice -> forward(conn, message.to) { from -> ServerMessage.Ice(from, message.candidate) }
            is ClientMessage.Relay -> forward(conn, message.to) { from -> ServerMessage.Relay(from, message.payload) }
            is ClientMessage.Leave -> onLeave(conn)
        }

    private fun onJoin(conn: Conn, peer: PeerId): List<RoomEmit<Conn>> {
        if (connToPeer.containsKey(conn)) {
            return listOf(RoomEmit(conn, ServerMessage.Error(SignalingErrorCode.ALREADY_JOINED, "connection already joined")))
        }
        if (peerToConn.containsKey(peer)) {
            return listOf(RoomEmit(conn, ServerMessage.Error(SignalingErrorCode.DUPLICATE_PEER, "peer $peer already in this session")))
        }
        // Roster excludes self, sorted ascending — computed BEFORE registering.
        val roster = peerToConn.keys.sorted()
        val notifyOthers = connToPeer.keys.toList()
        connToPeer[conn] = peer
        peerToConn[peer] = conn
        val emits = ArrayList<RoomEmit<Conn>>()
        emits.add(RoomEmit(conn, ServerMessage.Welcome(peer, roster)))
        for (other in notifyOthers) {
            emits.add(RoomEmit(other, ServerMessage.PeerJoined(peer)))
        }
        return emits
    }

    private fun onLeave(conn: Conn): List<RoomEmit<Conn>> {
        val peer = connToPeer.remove(conn)
            ?: return listOf(RoomEmit(conn, ServerMessage.Error(SignalingErrorCode.NOT_JOINED, "connection has not joined")))
        peerToConn.remove(peer)
        return connToPeer.keys.map { RoomEmit(it, ServerMessage.PeerLeft(peer)) }
    }

    private fun forward(
        conn: Conn,
        to: PeerId,
        build: (from: PeerId) -> ServerMessage,
    ): List<RoomEmit<Conn>> {
        val from = connToPeer[conn]
            ?: return listOf(RoomEmit(conn, ServerMessage.Error(SignalingErrorCode.NOT_JOINED, "connection has not joined")))
        val targetConn = peerToConn[to]
            ?: return listOf(RoomEmit(conn, ServerMessage.Error(SignalingErrorCode.UNKNOWN_TARGET, "peer $to is not in this session")))
        // Anti-spoof: `from` is the sender's server-registered peer, not client-supplied.
        return listOf(RoomEmit(targetConn, build(from)))
    }
}

/**
 * Transport seam for a signaling connection: a text frame stream. A concrete
 * backend (a real WebSocket client) is consumer-provided; [InMemorySignalingSocket]
 * is the deterministic loopback used by tests. [recv] returns `null` on close.
 */
interface SignalingSocket {
    suspend fun send(text: String)
    suspend fun recv(): String?
}

/**
 * A connected signaling-session client over a [SignalingSocket] seam. Auto-sends
 * `join` when opened via [open]; the directed helpers send the corresponding
 * kebab-case frames; [recv] parses inbound frames to [ServerMessage]. Mirrors
 * lazily-rs `SignalingClient` without bundling a real WebSocket client.
 */
class SignalingClient(
    private val socket: SignalingSocket,
    private val peer: PeerId,
) {
    /** This client's peer id. */
    fun peer(): PeerId = peer

    /** Send a raw protocol message. */
    suspend fun send(message: ClientMessage) {
        socket.send(message.toJsonString())
    }

    /** Announce join, advertising optional [capabilities]. */
    suspend fun join(capabilities: List<String>? = null) {
        send(ClientMessage.Join(peer, capabilities))
    }

    /** Send a WebRTC offer to [to]. */
    suspend fun offer(to: PeerId, sdp: String) = send(ClientMessage.Offer(to, sdp))

    /** Send a WebRTC answer to [to]. */
    suspend fun answer(to: PeerId, sdp: String) = send(ClientMessage.Answer(to, sdp))

    /** Send an ICE candidate to [to]. */
    suspend fun ice(to: PeerId, candidate: String) = send(ClientMessage.Ice(to, candidate))

    /** Relay an opaque payload to [to]. */
    suspend fun relay(to: PeerId, payload: JsonElement) = send(ClientMessage.Relay(to, payload))

    /** Announce departure. */
    suspend fun leave() = send(ClientMessage.Leave)

    /** Receive the next server message, or `null` once the connection closes. */
    suspend fun recv(): ServerMessage? {
        val text = socket.recv() ?: return null
        return ServerMessage.fromJsonString(text)
    }

    companion object {
        /** Open a client over [socket] and auto-send `join` as [peer]. */
        suspend fun open(
            socket: SignalingSocket,
            peer: PeerId,
            capabilities: List<String>? = null,
        ): SignalingClient {
            val client = SignalingClient(socket, peer)
            client.join(capabilities)
            return client
        }
    }
}

/**
 * In-memory [SignalingSocket] loopback for tests. [pair] returns two cross-wired
 * endpoints: a text sent on one is received on the other, in order. Closing
 * either endpoint makes the peer's [recv] return `null` once drained.
 */
class InMemorySignalingSocket private constructor(
    private val outbox: ArrayDeque<String>,
    private val inbox: ArrayDeque<String>,
    private val openState: OpenState,
) : SignalingSocket {
    class OpenState(var open: Boolean = true)

    override suspend fun send(text: String) {
        outbox.addLast(text)
    }

    override suspend fun recv(): String? {
        while (true) {
            val next = inbox.removeFirstOrNull()
            if (next != null) return next
            if (!openState.open) return null
            // Cooperative yield: deterministic tests drain synchronously, so a
            // drained-but-open socket returns null rather than blocking forever.
            return null
        }
    }

    /** Non-suspending peek used by deterministic tests to drain all pending frames. */
    fun drain(): List<String> {
        val out = ArrayList<String>()
        while (true) out.add(inbox.removeFirstOrNull() ?: break)
        return out
    }

    fun close() {
        openState.open = false
    }

    companion object {
        fun pair(): Pair<InMemorySignalingSocket, InMemorySignalingSocket> {
            val aToB = ArrayDeque<String>()
            val bToA = ArrayDeque<String>()
            val open = OpenState(true)
            val a = InMemorySignalingSocket(outbox = aToB, inbox = bToA, openState = open)
            val b = InMemorySignalingSocket(outbox = bToA, inbox = aToB, openState = open)
            return a to b
        }
    }
}
