package io.github.lazily

/**
 * Error from a [WebRtcSink] or [WebRtcSource]. Mirrors lazily-rs
 * `WebRtcTransportError`.
 */
sealed class WebRtcTransportError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    /** The channel was closed. */
    data object Closed : WebRtcTransportError("data channel closed")

    /** Frame serialization failure. */
    class Encode(cause: Throwable) : WebRtcTransportError("frame encode error: ${cause.message}", cause)

    /** Frame deserialization failure. */
    class Decode(cause: Throwable) : WebRtcTransportError("frame decode error: ${cause.message}", cause)
}

/**
 * Permission-filtering IPC sink over a [DataChannel].
 *
 * Every outbound `Snapshot`/`Delta`/`CrdtSync` is filtered to what [peer] is
 * allowed to **read** (via each message's `filterReadable`) before it is
 * serialized and sent, so a peer never receives graph state it is not entitled
 * to (omission, not redaction). One frame carries exactly one [IpcMessage].
 */
class WebRtcSink(
    private val channel: DataChannel,
    private val permissions: PeerPermissions,
    private val peer: PeerId,
) {
    /** The remote peer this sink filters for. */
    fun peer(): PeerId = peer

    /** Borrow the underlying channel. */
    fun channel(): DataChannel = channel

    /**
     * Permission-filter, encode, and send [msg] as one frame.
     * @throws WebRtcTransportError.Closed if the channel is closed.
     * @throws WebRtcTransportError.Encode if encoding fails.
     */
    fun send(msg: IpcMessage) {
        if (!channel.isOpen) throw WebRtcTransportError.Closed
        val filtered: IpcMessage = when (msg) {
            is IpcMessage.SnapshotMessage ->
                IpcMessage.SnapshotMessage(msg.snapshot.filterReadable(permissions, peer))
            is IpcMessage.DeltaMessage ->
                IpcMessage.DeltaMessage(msg.delta.filterReadable(permissions, peer))
            is IpcMessage.CrdtSyncMessage ->
                IpcMessage.CrdtSyncMessage(msg.sync.filterReadable(permissions, peer))
            // Reliable-sync control frames carry no node content; filtering is identity.
            is IpcMessage.ResyncRequestMessage, is IpcMessage.OutboxAckMessage -> msg
        }
        val frame = try {
            filtered.encodeJson()
        } catch (e: Throwable) {
            throw WebRtcTransportError.Encode(e)
        }
        channel.sendFrame(frame)
    }
}

/**
 * IPC source over a [DataChannel]. Delivers each decoded [IpcMessage] verbatim.
 *
 * Inbound write-permission enforcement is deliberately **not** done here: the
 * transport carries frames, and the graph-apply layer is the authority that
 * checks write permission before mutating local state.
 */
class WebRtcSource(private val channel: DataChannel) {
    /** Borrow the underlying channel. */
    fun channel(): DataChannel = channel

    /**
     * Pop the next frame and decode it, or `null` when none is pending on an
     * open channel.
     * @throws WebRtcTransportError.Closed if the channel is closed and drained.
     * @throws WebRtcTransportError.Decode if decoding fails.
     */
    fun recv(): IpcMessage? {
        val frame = channel.tryRecvFrame()
        if (frame == null) {
            return if (channel.isOpen) null else throw WebRtcTransportError.Closed
        }
        return try {
            IpcMessage.decodeJson(frame)
        } catch (e: Throwable) {
            throw WebRtcTransportError.Decode(e)
        }
    }
}
