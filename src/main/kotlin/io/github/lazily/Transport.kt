package io.github.lazily

/**
 * Cross-process zero-copy transport — pluggable blob backends (`#lzzcpy`).
 *
 * The Kotlin port of `lazily-rs/src/transport.rs` and the `lazily-formal`
 * `ZeroCopyTransport` module. A large payload is not copied through the wire
 * codec: the producer **spills** it to a [BlobBackend] and ships a [ShmBlobRef]
 * descriptor; the receiver **resolves** the descriptor against the same backend
 * and reads the bytes in place — zero copy. The descriptor's
 * [backend][ShmBlobRef.backend] discriminator routes resolution to the right
 * backend ([BlobRouter]).
 *
 * The model is backend-agnostic (proved in `ZeroCopyTransport`): any backend that
 * maintains its issued-blob table satisfies the same laws — spill-then-resolve
 * identity (`resolve_write`), backend routing (`resolve_wrong_backend`),
 * generation/ABA safety (`resolve_stale_generation`), checksum integrity
 * (`resolve_corrupt_checksum`), and the end-to-end round-trip
 * (`transport_roundtrip`).
 */

/**
 * Wire discriminator: which pluggable backend holds a blob. Mirrors
 * `BackendKind` in `ZeroCopyTransport` and `BlobBackendKind` in lazily-rs. The
 * wire string is lowercase (`"shm"` / `"arrow"` / `"in_process"`); unknown
 * strings fall back to [Shm] so a legacy or forward-compatible descriptor never
 * hard-fails resolution.
 */
enum class BlobBackendKind(val wire: String) {
    /** POSIX shared memory (`shm_open` + `mmap`) — the default cross-process backend, same host. */
    Shm("shm"),

    /** Apache Arrow IPC stream / Flight-resolved buffer — columnar zero-copy. */
    Arrow("arrow"),

    /** An in-process arena (single address space — the FFI host / an in-process plugin). */
    InProcess("in_process");

    /** Whether this is the default backend ([Shm]) — the field is omitted on the wire when so. */
    val isDefault: Boolean get() = this == Shm

    companion object {
        /** The default backend discriminator. Mirrors `BackendKind` default / lazily-rs `Shm`. */
        val Default: BlobBackendKind = Shm

        /** Parse a backend discriminator from its wire string; unknown → [Shm] (default). */
        fun fromWire(s: String): BlobBackendKind = when (s) {
            "arrow" -> Arrow
            "in_process" -> InProcess
            else -> Shm
        }
    }
}

/**
 * A pluggable blob backend — the zero-copy large-payload transport seam. A
 * backend mints a [ShmBlobRef] for bytes written to it ([write]) and resolves a
 * descriptor back to its bytes in place ([readView], zero copy). Mirrors the
 * `BlobBackend` trait in lazily-rs and the `Issued` + `resolve` pair in
 * `ZeroCopyTransport`.
 */
interface BlobBackend {
    /** This backend's discriminator — the [ShmBlobRef.backend] it stamps on descriptors it mints. */
    fun kind(): BlobBackendKind

    /** Write [bytes] into the backend, minting a descriptor tagged with [kind]. */
    fun write(bytes: ByteArray): ShmBlobRef

    /**
     * Resolve [descriptor] to its bytes in place (zero copy). Returns `null` when
     * the descriptor names a different backend, or is unknown / stale-generation /
     * corrupt-checksum (`resolve_wrong_backend` / `resolve_stale_generation` /
     * `resolve_corrupt_checksum`).
     */
    fun readView(descriptor: ShmBlobRef): ByteArray?

    /**
     * Advance the validity epoch. Descriptors minted before an epoch advance stay
     * valid until their arena slot is reused (generation guards ABA).
     */
    fun advanceEpoch()
}

/** Default inline/spill threshold in bytes — payloads below this stay inline (cheaper than a round-trip). */
const val BLOB_SPILL_THRESHOLD_DEFAULT: Int = 4096

/**
 * A [ShmBlobArena]-backed [BlobBackend] base. `write` stamps the arena's
 * (backend-agnostic) descriptor with this backend's [kind]; `readView` first
 * routes on the descriptor's backend (returning `null` for a foreign descriptor —
 * `resolve_wrong_backend`) then resolves against the arena, normalizing the
 * descriptor to the arena's backend-agnostic form.
 *
 * The arena is a heap [ByteArray]; a deployment that needs true cross-process
 * sharing swaps the [ShmBackend]'s arena for a memory-mapped region without
 * changing this contract (the descriptor + routing layer is unchanged).
 */
abstract class ArenaBlobBackend(private val arena: ShmBlobArena) : BlobBackend {
    private var epoch: Long = 0

    /** The backing arena — exposed for capacity/introspection, like lazily-rs `arena()`. */
    fun arena(): ShmBlobArena = arena

    /** The current validity epoch. */
    fun epoch(): Long = epoch

    final override fun write(bytes: ByteArray): ShmBlobRef =
        arena.writeBlob(epoch, bytes).copy(backend = kind())

    final override fun readView(descriptor: ShmBlobRef): ByteArray? {
        if (descriptor.backend != kind()) return null // routing (resolve_wrong_backend)
        return try {
            // The arena header is backend-agnostic; resolve against its own form.
            arena.readBlob(descriptor.copy(backend = BlobBackendKind.Shm))
        } catch (_: ShmBlobArenaError) {
            null // unknown / stale-generation / corrupt-checksum
        }
    }

    final override fun advanceEpoch() {
        epoch += 1
    }
}

/**
 * POSIX shared-memory backend — the default cross-process transport. Descriptors
 * carry [BlobBackendKind.Shm]. Heap-backed here; a memory-mapped arena is the
 * drop-in for genuine cross-process sharing.
 */
class ShmBackend(arena: ShmBlobArena) : ArenaBlobBackend(arena) {
    override fun kind(): BlobBackendKind = BlobBackendKind.Shm

    companion object {
        /** Create a shm backend over a fresh arena of [capacity] bytes. */
        fun withCapacity(capacity: Int): ShmBackend = ShmBackend(ShmBlobArena(capacity))
    }
}

/**
 * Apache Arrow IPC / Flight backend — columnar zero-copy. Descriptors carry
 * [BlobBackendKind.Arrow]; the bytes are the Arrow IPC stream the receiver
 * imports zero-copy. The arena is the transport substrate for the descriptor +
 * routing contract.
 */
class ArrowBackend(arena: ShmBlobArena) : ArenaBlobBackend(arena) {
    override fun kind(): BlobBackendKind = BlobBackendKind.Arrow

    companion object {
        /** Create an Arrow backend over a fresh arena of [capacity] bytes. */
        fun withCapacity(capacity: Int): ArrowBackend = ArrowBackend(ShmBlobArena(capacity))
    }
}

/**
 * In-process arena backend — a single address space (the FFI host / an
 * in-process plugin). Descriptors carry [BlobBackendKind.InProcess].
 */
class InProcessBackend(arena: ShmBlobArena) : ArenaBlobBackend(arena) {
    override fun kind(): BlobBackendKind = BlobBackendKind.InProcess

    companion object {
        /** Create an in-process backend over a fresh arena of [capacity] bytes. */
        fun withCapacity(capacity: Int): InProcessBackend = InProcessBackend(ShmBlobArena(capacity))
    }
}

/** The (possibly-spilled) result of a spill: the rewritten payload plus the bytes moved to a backend. */
data class SpillResult<T>(val value: T, val spilled: Int)

/**
 * Spill an [IpcValue] over [threshold] to a [BlobBackend], returning the
 * descriptor-carrying value and the byte count moved. Inline values below the
 * threshold, and values already carrying a descriptor, are returned unchanged
 * (`spilled = 0`). A backend write failure leaves the value inline.
 */
fun spillValue(
    value: IpcValue,
    backend: BlobBackend,
    threshold: Int = BLOB_SPILL_THRESHOLD_DEFAULT,
): SpillResult<IpcValue> {
    if (value is IpcValue.Inline && value.bytes.size >= threshold) {
        return try {
            val descriptor = backend.write(value.toByteArray())
            SpillResult(IpcValue.SharedBlob(descriptor), value.bytes.size)
        } catch (_: ShmBlobArenaError) {
            SpillResult(value, 0)
        }
    }
    return SpillResult(value, 0)
}

/** Spill a [NodeState.Payload] over [threshold] to a SharedBlob descriptor; other states pass through. */
fun spillState(
    state: NodeState,
    backend: BlobBackend,
    threshold: Int = BLOB_SPILL_THRESHOLD_DEFAULT,
): SpillResult<NodeState> {
    if (state is NodeState.Payload && state.bytes.size >= threshold) {
        return try {
            val descriptor = backend.write(state.toByteArray())
            SpillResult(NodeState.SharedBlob(descriptor), state.bytes.size)
        } catch (_: ShmBlobArenaError) {
            SpillResult(state, 0)
        }
    }
    return SpillResult(state, 0)
}

/**
 * Spill every large value/state site across an [IpcMessage] to [backend]:
 * `Snapshot` node states, `Delta` `CellSet`/`SlotValue` payloads and `NodeAdd`
 * states, and `CrdtSync` op states. Returns the rewritten message (small on the
 * wire) plus the total bytes spilled. Sites already carrying a descriptor are
 * left untouched.
 */
fun spillMessage(
    message: IpcMessage,
    backend: BlobBackend,
    threshold: Int = BLOB_SPILL_THRESHOLD_DEFAULT,
): SpillResult<IpcMessage> {
    var total = 0
    val next: IpcMessage = when (message) {
        is IpcMessage.SnapshotMessage -> {
            val nodes = message.snapshot.nodes.map { node ->
                val (state, spilled) = spillState(node.state, backend, threshold)
                total += spilled
                if (spilled > 0) node.copy(state = state) else node
            }
            IpcMessage.SnapshotMessage(message.snapshot.copy(nodes = nodes))
        }
        is IpcMessage.DeltaMessage -> {
            val ops = message.delta.ops.map { op ->
                when (op) {
                    is DeltaOp.CellSet -> {
                        val (payload, spilled) = spillValue(op.payload, backend, threshold)
                        total += spilled
                        if (spilled > 0) op.copy(payload = payload) else op
                    }
                    is DeltaOp.SlotValue -> {
                        val (payload, spilled) = spillValue(op.payload, backend, threshold)
                        total += spilled
                        if (spilled > 0) op.copy(payload = payload) else op
                    }
                    is DeltaOp.NodeAdd -> {
                        val (state, spilled) = spillState(op.state, backend, threshold)
                        total += spilled
                        if (spilled > 0) op.copy(state = state) else op
                    }
                    else -> op
                }
            }
            IpcMessage.DeltaMessage(message.delta.copy(ops = ops))
        }
        is IpcMessage.CrdtSyncMessage -> {
            val ops = message.sync.ops.map { op ->
                val (state, spilled) = spillValue(op.state, backend, threshold)
                total += spilled
                if (spilled > 0) op.copy(state = state) else op
            }
            IpcMessage.CrdtSyncMessage(message.sync.copy(ops = ops))
        }
        // Reliable-sync control frames carry no blob payload to spill.
        is IpcMessage.ResyncRequestMessage, is IpcMessage.OutboxAckMessage -> message
    }
    return SpillResult(next, total)
}

/**
 * Resolve an [IpcValue] against a single [backend]: inline bytes returned
 * directly, a SharedBlob resolved zero-copy. Returns `null` if a SharedBlob fails
 * to resolve (unknown / stale / corrupt / wrong backend).
 */
fun resolveValue(value: IpcValue, backend: BlobBackend): ByteArray? = when (value) {
    is IpcValue.Inline -> value.toByteArray()
    is IpcValue.SharedBlob -> backend.readView(value.blob)
}

/**
 * Receiver-side multi-backend resolver. Holds backends by [BlobBackendKind] and
 * resolves any descriptor by its [backend][ShmBlobRef.backend] discriminator — a
 * `shm` descriptor routes to the shm backend, an `arrow` descriptor to the arrow
 * backend, etc. (`resolve_wrong_backend`: a descriptor never resolves against a
 * backend of the wrong kind). Mirrors lazily-rs `BlobRouter`.
 */
class BlobRouter {
    private val backends: MutableMap<BlobBackendKind, BlobBackend> = HashMap()

    /** Register [backend] for its [kind][BlobBackend.kind], replacing any prior one. Chainable. */
    fun register(backend: BlobBackend): BlobRouter {
        backends[backend.kind()] = backend
        return this
    }

    /** Resolve [descriptor] by routing to its backend kind; `null` if unregistered or unresolved. */
    fun readView(descriptor: ShmBlobRef): ByteArray? = backends[descriptor.backend]?.readView(descriptor)

    /** Resolve an [IpcValue]: inline bytes directly, SharedBlob routed by its backend discriminator. */
    fun resolve(value: IpcValue): ByteArray? = when (value) {
        is IpcValue.Inline -> value.toByteArray()
        is IpcValue.SharedBlob -> readView(value.blob)
    }
}
