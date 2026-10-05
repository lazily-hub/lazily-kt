package io.github.lazily

// Wire declarations and codecs (DeltaOp, IpcValue, NodeState, Delta) are
// generated into DeltaWireGen.kt from the shared wire model in lazily-spec
// (#lzwiremodel7). This file keeps the delta semantics: the byte-payload
// constructors, the companion factories, read filtering and the epoch decision.

/** The inline payload bytes. */
fun IpcValue.Inline.toByteArray(): ByteArray = bytes

/** The node's payload bytes. */
fun NodeState.Payload.toByteArray(): ByteArray = bytes

fun IpcValue.Companion.inline(bytes: ByteArray): IpcValue = IpcValue.Inline(bytes)

fun IpcValue.Companion.sharedBlob(blob: ShmBlobRef): IpcValue = IpcValue.SharedBlob(blob)

/** `DeltaOp.CellSet(node, bytes)`: an inline-bytes payload. */
operator fun DeltaOp.CellSet.Companion.invoke(
    node: NodeId,
    bytes: ByteArray,
): DeltaOp.CellSet = DeltaOp.CellSet(node, IpcValue.Inline(bytes))

/** `DeltaOp.SlotValue(node, bytes)`: an inline-bytes payload. */
operator fun DeltaOp.SlotValue.Companion.invoke(
    node: NodeId,
    bytes: ByteArray,
): DeltaOp.SlotValue = DeltaOp.SlotValue(node, IpcValue.Inline(bytes))

/**
 * `DeltaOp.QueuePush(node, bytes)`: append inline bytes to the queue at [node]
 * (`#queue-oplog`, `#lzdeltaqueueops`). The payload is an [IpcValue], so it
 * spills/resolves exactly like a `CellSet` payload.
 */
operator fun DeltaOp.QueuePush.Companion.invoke(
    node: NodeId,
    bytes: ByteArray,
): DeltaOp.QueuePush = DeltaOp.QueuePush(node, IpcValue.Inline(bytes))

fun DeltaOp.Companion.cellSet(
    node: NodeId,
    bytes: ByteArray,
): DeltaOp = DeltaOp.CellSet(node, bytes)

fun DeltaOp.Companion.cellSet(
    node: NodeId,
    payload: IpcValue,
): DeltaOp = DeltaOp.CellSet(node, payload)

fun DeltaOp.Companion.slotValue(
    node: NodeId,
    bytes: ByteArray,
): DeltaOp = DeltaOp.SlotValue(node, bytes)

fun DeltaOp.Companion.slotValue(
    node: NodeId,
    payload: IpcValue,
): DeltaOp = DeltaOp.SlotValue(node, payload)

fun DeltaOp.Companion.invalidate(node: NodeId): DeltaOp = DeltaOp.Invalidate(node)

fun DeltaOp.Companion.nodeAdd(
    node: NodeId,
    typeTag: String,
    state: NodeState,
): DeltaOp = DeltaOp.NodeAdd(node, typeTag, state)

fun DeltaOp.Companion.nodeAdd(
    node: NodeId,
    typeTag: String,
    state: NodeState,
    key: NodeKey?,
): DeltaOp = DeltaOp.NodeAdd(node, typeTag, state, key)

fun DeltaOp.Companion.nodeRemove(node: NodeId): DeltaOp = DeltaOp.NodeRemove(node)

fun DeltaOp.Companion.edgeAdd(
    dependent: NodeId,
    dependency: NodeId,
): DeltaOp = DeltaOp.EdgeAdd(dependent, dependency)

fun DeltaOp.Companion.edgeRemove(
    dependent: NodeId,
    dependency: NodeId,
): DeltaOp = DeltaOp.EdgeRemove(dependent, dependency)

fun DeltaOp.Companion.queuePush(
    node: NodeId,
    bytes: ByteArray,
): DeltaOp = DeltaOp.QueuePush(node, bytes)

fun DeltaOp.Companion.queuePush(
    node: NodeId,
    payload: IpcValue,
): DeltaOp = DeltaOp.QueuePush(node, payload)

fun DeltaOp.Companion.queuePop(node: NodeId): DeltaOp = DeltaOp.QueuePop(node)

fun DeltaOp.Companion.queueClose(node: NodeId): DeltaOp = DeltaOp.QueueClose(node)

/**
 * Whether [peer] may read every node this op names. An op targeting an
 * unreadable node is omitted from a permission-filtered [Delta].
 */
fun DeltaOp.targetReadable(
    permissions: PeerPermissions,
    peer: PeerId,
): Boolean =
    when (this) {
        is DeltaOp.CellSet -> permissions.canRead(peer, node)
        is DeltaOp.SlotValue -> permissions.canRead(peer, node)
        is DeltaOp.Invalidate -> permissions.canRead(peer, node)
        is DeltaOp.NodeAdd -> permissions.canRead(peer, node)
        is DeltaOp.NodeRemove -> permissions.canRead(peer, node)
        is DeltaOp.EdgeAdd -> permissions.canRead(peer, dependent) && permissions.canRead(peer, dependency)
        is DeltaOp.EdgeRemove -> permissions.canRead(peer, dependent) && permissions.canRead(peer, dependency)
        is DeltaOp.QueuePush -> permissions.canRead(peer, node)
        is DeltaOp.QueuePop -> permissions.canRead(peer, node)
        is DeltaOp.QueueClose -> permissions.canRead(peer, node)
    }

fun Delta.isNextAfter(lastEpoch: Long): Boolean = baseEpoch == lastEpoch && epoch == baseEpoch + 1

/**
 * The accepted-event span this delta advances: `epoch - baseEpoch` (usually 1,
 * `> 1` for a coalesced multi-epoch-span delta; `#lzsync`, spec §
 * Multi-epoch-span delta). Coerced to `>= 0` for a malformed backward delta.
 */
fun Delta.span(): Long = (epoch - baseEpoch).coerceAtLeast(0)

fun Delta.applyStatus(lastEpoch: Long): DeltaApplyStatus =
    if (isNextAfter(lastEpoch)) {
        DeltaApplyStatus.Apply
    } else {
        DeltaApplyStatus.ResyncRequired(lastEpoch, baseEpoch, epoch)
    }

fun Delta.filterReadable(
    permissions: PeerPermissions,
    peer: PeerId,
): Delta = Delta(baseEpoch, epoch, ops.filter { it.targetReadable(permissions, peer) })

fun Delta.Companion.next(
    baseEpoch: Long,
    ops: List<DeltaOp>,
): Delta = Delta(baseEpoch = baseEpoch, epoch = baseEpoch + 1, ops = ops)
