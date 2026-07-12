package io.github.lazily

// -- C-ABI FFI boundary (host) -----------------------------------------------
//
// Native Kotlin host for the lazily-spec C-ABI FFI boundary
// (`lazily-spec/protocol.md` § FFI Boundary, `lazily-spec/schemas/ffi.json`).
// The contract is the lingua franca that lets any binding embed any other
// without a language-specific bridge:
//
//   - `LazilyFfiBytes` — owned byte buffer crossing the boundary.
//   - `LazilyFfiStatus` — `Ok | Empty | NullPointer | InvalidMessage |
//     EncodeFailed | Panic` (0..5). Panics are caught before crossing the C ABI.
//   - `LazilyFfiMessageKind` — `Unknown | Snapshot | Delta | CrdtSync` (0..3).
//     The `CrdtSync = 3` discriminant MUST be present.
//   - The channel decodes each accepted frame as `IpcMessage` and re-encodes
//     canonical JSON bytes, so a host and any peer share one canonical wire
//     interchangeably.
//
// This module supplies the embeddable JVM contract plus a JNI-ready native entry
// table ([LazilyFfiNative]); real `extern "C"` symbol export is provided by a
// Graal native-image build of the artifact (see `lazily_ffi.h`) or the JNI shim.
// lazily-kt's platform CAN host a native in-process boundary, so it declares the
// `ffi = host` capability.

/**
 * Owned byte buffer crossing the FFI boundary. Mirrors the C
 * `LazilyFfiBytes { uint8_t* ptr; size_t len; }` struct.
 *
 * Ownership is explicit: the caller owns input bytes; the host owns output
 * buffers until the paired [LazilyFfiChannel.freeBytes] is called.
 */
data class LazilyFfiBytes(val bytes: ByteArray, val len: Int = bytes.size) {
    override fun equals(other: Any?): Boolean =
        other is LazilyFfiBytes && len == other.len && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * FFI operation status code. Mirrors the C `LazilyFfiStatus` enum
 * (`lazily-spec/schemas/ffi.json`): 0..5.
 */
enum class LazilyFfiStatus(val code: Int) {
    Ok(0),
    Empty(1),
    NullPointer(2),
    InvalidMessage(3),
    EncodeFailed(4),
    Panic(5);

    companion object {
        fun fromCode(code: Int): LazilyFfiStatus =
            entries.firstOrNull { it.code == code } ?: error("unknown LazilyFfiStatus code: $code")
    }
}

/**
 * IPC message-kind discriminant. Mirrors the C `LazilyFfiMessageKind` enum
 * (`lazily-spec/schemas/ffi.json`): `Unknown | Snapshot | Delta | CrdtSync`.
 *
 * **`CrdtSync = 3` is required** of every conforming FFI host.
 */
enum class LazilyFfiMessageKind(val code: Int) {
    Unknown(0),
    Snapshot(1),
    Delta(2),
    CrdtSync(3),
    ResyncRequest(4),
    OutboxAck(5);

    companion object {
        fun fromCode(code: Int): LazilyFfiMessageKind =
            entries.firstOrNull { it.code == code } ?: error("unknown LazilyFfiMessageKind code: $code")
    }
}

/** The canonical-JSON byte payload + decoded [IpcMessage] returned by [LazilyFfiChannel.decode]. */
data class LazilyFfiDecoded(
    val status: LazilyFfiStatus,
    val message: IpcMessage?,
    val canonicalBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is LazilyFfiDecoded && status == other.status && message == other.message &&
            canonicalBytes.contentEquals(other.canonicalBytes)

    override fun hashCode(): Int = canonicalBytes.contentHashCode()
}

/**
 * The embeddable FFI channel. Each accepted frame is decoded as [IpcMessage],
 * re-encoded to canonical JSON bytes, and returned. All throwables are caught
 * and mapped to a [LazilyFfiStatus] (`InvalidMessage` for decode errors,
 * `EncodeFailed` for re-encode errors, `Panic` for anything else) so a panic
 * never crosses the C ABI.
 *
 * This is the JVM implementation behind the `extern "C"` entry table
 * ([LazilyFfiNative]); it is also directly embeddable in-process by any JVM host.
 */
object LazilyFfiChannel {
    /**
     * Decode [input] (claiming it is of [kind]) as an [IpcMessage] and re-encode
     * canonical JSON bytes. Returns a failed status (never throws) on any error.
     */
    fun decode(kind: LazilyFfiMessageKind, input: LazilyFfiBytes): LazilyFfiDecoded {
        if (input.len == 0) return LazilyFfiDecoded(LazilyFfiStatus.Empty, null, ByteArray(0))
        return try {
            val message = IpcMessage.decodeJson(input.bytes.copyOf(input.len))
            val actualKind = messageKindOf(message)
            if (kind != LazilyFfiMessageKind.Unknown && kind != actualKind) {
                LazilyFfiDecoded(LazilyFfiStatus.InvalidMessage, null, ByteArray(0))
            } else {
                val canonical = try {
                    message.encodeJson()
                } catch (e: Throwable) {
                    return LazilyFfiDecoded(LazilyFfiStatus.EncodeFailed, null, ByteArray(0))
                }
                LazilyFfiDecoded(LazilyFfiStatus.Ok, message, canonical)
            }
        } catch (e: Throwable) {
            LazilyFfiDecoded(LazilyFfiStatus.InvalidMessage, null, ByteArray(0))
        }
    }

    /** Encode [message] to canonical JSON bytes. Returns a failed status (never throws). on error. */
    fun encode(message: IpcMessage): LazilyFfiDecoded = try {
        LazilyFfiDecoded(LazilyFfiStatus.Ok, message, message.encodeJson())
    } catch (e: Throwable) {
        LazilyFfiDecoded(LazilyFfiStatus.EncodeFailed, null, ByteArray(0))
    }

    /** The [LazilyFfiMessageKind] for [message]. */
    fun messageKindOf(message: IpcMessage): LazilyFfiMessageKind = when (message) {
        is IpcMessage.SnapshotMessage -> LazilyFfiMessageKind.Snapshot
        is IpcMessage.DeltaMessage -> LazilyFfiMessageKind.Delta
        is IpcMessage.CrdtSyncMessage -> LazilyFfiMessageKind.CrdtSync
        is IpcMessage.ResyncRequestMessage -> LazilyFfiMessageKind.ResyncRequest
        is IpcMessage.OutboxAckMessage -> LazilyFfiMessageKind.OutboxAck
    }

    /**
     * Run a host operation under a panic guard: any throwable is mapped to
     * [LazilyFfiStatus.Panic] (or [onDecodeError] for decode-shaped failures) so
     * a panic never crosses the C ABI. The C entry functions all route through
     * this guard.
     */
    inline fun panicGuard(onDecodeError: LazilyFfiStatus = LazilyFfiStatus.InvalidMessage, body: () -> LazilyFfiStatus): LazilyFfiStatus =
        try {
            body()
        } catch (e: Throwable) {
            onDecodeError
        }

    /** No-op free for the JVM heap-backed buffer (ownership hook for the JNI shim). */
    fun freeBytes(bytes: LazilyFfiBytes) {
        // Heap-backed: the GC reclaims the buffer. The hook exists so the JNI
        // shim can route `lazily_ffi_free` here uniformly across hosts.
    }
}

/**
 * The `extern "C"` entry table a Graal native-image build (or the JNI shim)
 * exports — declared in `lazily_ffi.h`. The JVM-side bodies delegate to
 * [LazilyFfiChannel] under [LazilyFfiChannel.panicGuard], so the C symbols are
 * thin trampolines over this object.
 *
 * Each entry returns a [LazilyFfiStatus] and writes output through caller-owned
 * pointers (`out` / `out_len`); the host owns every output buffer until
 * [lazilyFfiFree] is called with it. This shape lets a native peer link the
 * artifact directly (Graal `--export-symbols`) or via the JNI shim without
 * changing the contract.
 */
object LazilyFfiNative {
    /**
     * Encode a canonical-JSON [IpcMessage] frame. On success writes the buffer
     * into `out`/`outLen` and returns [LazilyFfiStatus.Ok]; on failure returns
     * [LazilyFfiStatus.EncodeFailed] / [LazilyFfiStatus.Panic].
     */
    fun lazilyFfiEncode(message: IpcMessage, out: Array<LazilyFfiBytes?>, outLen: IntArray): LazilyFfiStatus =
        LazilyFfiChannel.panicGuard(LazilyFfiStatus.Panic) {
            val result = LazilyFfiChannel.encode(message)
            if (result.status == LazilyFfiStatus.Ok) {
                out[0] = LazilyFfiBytes(result.canonicalBytes)
                outLen[0] = result.canonicalBytes.size
            }
            result.status
        }

    /**
     * Decode a [kind]-claimed frame [input] as [IpcMessage] and re-encode
     * canonical JSON bytes into `out`/`outLen`. Returns [LazilyFfiStatus.Ok] on
     * success or the matching error status otherwise.
     */
    fun lazilyFfiDecode(
        kind: LazilyFfiMessageKind,
        input: LazilyFfiBytes,
        out: Array<LazilyFfiBytes?>,
        outLen: IntArray,
    ): LazilyFfiStatus = LazilyFfiChannel.panicGuard(LazilyFfiStatus.Panic) {
        if (input.len == 0) return@panicGuard LazilyFfiStatus.Empty
        val result = LazilyFfiChannel.decode(kind, input)
        if (result.status == LazilyFfiStatus.Ok) {
            out[0] = LazilyFfiBytes(result.canonicalBytes)
            outLen[0] = result.canonicalBytes.size
        }
        result.status
    }

    /** Free a buffer returned by [lazilyFfiEncode] / [lazilyFfiDecode]. */
    fun lazilyFfiFree(bytes: LazilyFfiBytes?) {
        if (bytes != null) LazilyFfiChannel.freeBytes(bytes)
    }
}
