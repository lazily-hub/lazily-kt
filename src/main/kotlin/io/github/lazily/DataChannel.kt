package io.github.lazily

/**
 * Minimal ordered, reliable, bidirectional byte-frame channel — the seam a
 * concrete WebRTC `RTCDataChannel` backend (or any other transport) must provide.
 *
 * Each frame is exactly one serialized [IpcMessage]; ordering and reliability are
 * the backend's responsibility (a WebRTC DataChannel opened with `ordered: true`).
 * The methods are non-blocking so they satisfy the synchronous
 * [WebRtcSink]/[WebRtcSource] contracts. Wiring a real backend (establishing the
 * `RTCPeerConnection` via the [SignalingClient] SDP/ICE handshake) is a
 * deliberate consumer-provided follow-up so a large native WebRTC stack is not
 * pulled into the default dependency graph. See `webrtc_transport.rs`.
 */
interface DataChannel {
    /** Enqueue one serialized frame for delivery. Must not block. */
    fun sendFrame(frame: ByteArray)

    /** Pop the next received frame, or `null` when none is pending. */
    fun tryRecvFrame(): ByteArray?

    /** Whether the channel is still usable. */
    val isOpen: Boolean
}

/**
 * In-process loopback [DataChannel] for deterministic tests. No real network or
 * WebRTC stack: [InMemoryDataChannel.pair] returns two cross-wired endpoints — a
 * frame sent on one is received on the other, in order.
 */
class InMemoryDataChannel private constructor(
    private val tx: ArrayDeque<ByteArray>,
    private val rx: ArrayDeque<ByteArray>,
    private val openState: OpenState,
) : DataChannel {
    /** Shared open/closed flag so closing either endpoint closes the pair. */
    class OpenState(var open: Boolean = true)

    override fun sendFrame(frame: ByteArray) {
        tx.addLast(frame)
    }

    override fun tryRecvFrame(): ByteArray? = rx.removeFirstOrNull()

    override val isOpen: Boolean
        get() = openState.open

    /** Close both ends of the pair (simulates a dropped connection). */
    fun close() {
        openState.open = false
    }

    companion object {
        /** Build a connected loopback pair. */
        fun pair(): Pair<InMemoryDataChannel, InMemoryDataChannel> {
            val aToB = ArrayDeque<ByteArray>()
            val bToA = ArrayDeque<ByteArray>()
            val open = OpenState(true)
            val a = InMemoryDataChannel(tx = aToB, rx = bToA, openState = open)
            val b = InMemoryDataChannel(tx = bToA, rx = aToB, openState = open)
            return a to b
        }
    }
}
