package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class IpcTest {
    @Test
    fun `snapshot round trips through JSON bytes`() {
        val snapshot = Snapshot(
            epoch = 7,
            nodes = listOf(
                NodeSnapshot.payload(1, "i32", byteArrayOf(1, 2, 3)),
                NodeSnapshot.opaque(2, "opaque-type"),
                NodeSnapshot.sharedBlob(
                    3,
                    "text/plain",
                    ShmBlobRef(offset = 0, len = 16, generation = 1, epoch = 7, checksum = 999),
                ),
            ),
            edges = listOf(EdgeSnapshot(2, 1), EdgeSnapshot(3, 1)),
            roots = listOf(1, 2),
        )

        val message = IpcMessage.ofSnapshot(snapshot)
        val decoded = IpcMessage.decodeJson(message.encodeJson())

        assertEquals(message, decoded)
        assertEquals(snapshot, assertIs<IpcMessage.SnapshotMessage>(decoded).snapshot)
    }

    @Test
    fun `delta round trips all operation variants`() {
        val delta = Delta.next(
            40,
            listOf(
                DeltaOp.cellSet(1, byteArrayOf(10)),
                DeltaOp.slotValue(2, byteArrayOf(20)),
                DeltaOp.invalidate(3),
                DeltaOp.nodeAdd(4, "u64", NodeState.Payload(byteArrayOf(64))),
                DeltaOp.nodeRemove(5),
                DeltaOp.edgeAdd(2, 1),
                DeltaOp.edgeRemove(3, 1),
            ),
        )

        val message = IpcMessage.ofDelta(delta)
        val decoded = IpcMessage.decodeJson(message.encodeJson())

        assertEquals(message, decoded)
        assertEquals(41, assertIs<IpcMessage.DeltaMessage>(decoded).delta.epoch)
    }

    @Test
    fun `payload serializes as byte array rather than base64`() {
        val op = DeltaOp.cellSet(1, byteArrayOf(10, -1, 0))

        assertEquals(
            """{"CellSet":{"node":1,"payload":{"Inline":[10,255,0]}}}""",
            op.toJson().toString(),
        )
    }

    @Test
    fun `shared blob can be carried by slot value`() {
        val blob = ShmBlobRef(offset = 40, len = 17, generation = 2, epoch = 9, checksum = 123)
        val op = DeltaOp.slotValue(7, IpcValue.sharedBlob(blob))

        val slotValue = assertIs<DeltaOp.SlotValue>(op)
        assertEquals(blob, assertIs<IpcValue.SharedBlob>(slotValue.payload).blob)
    }

    @Test
    fun `delta apply status requests resync on epoch gap`() {
        val delta = Delta(baseEpoch = 12, epoch = 13, ops = emptyList())

        assertTrue(delta.isNextAfter(12))
        assertFalse(delta.isNextAfter(10))

        when (val status = delta.applyStatus(10)) {
            DeltaApplyStatus.Apply -> fail("gap must not apply")
            is DeltaApplyStatus.ResyncRequired -> {
                assertEquals(10, status.lastEpoch)
                assertEquals(12, status.baseEpoch)
                assertEquals(13, status.epoch)
            }
        }
    }

    @Test
    fun `snapshot permission filter omits unreadable nodes`() {
        val permissions = PeerPermissions()
        permissions.allowMany(1, OpKind.Read, listOf(1, 2))

        val snapshot = Snapshot(
            epoch = 5,
            nodes = listOf(
                NodeSnapshot.payload(1, "i32", byteArrayOf(1)),
                NodeSnapshot.payload(2, "i32", byteArrayOf(2)),
                NodeSnapshot.payload(3, "i32", byteArrayOf(3)),
            ),
            edges = listOf(EdgeSnapshot(2, 1), EdgeSnapshot(3, 1)),
            roots = listOf(1, 2, 3),
        )

        val filtered = snapshot.filterReadable(permissions, 1)

        assertEquals(listOf(1L, 2L), filtered.nodes.map { it.node })
        assertEquals(listOf(EdgeSnapshot(2, 1)), filtered.edges)
        assertEquals(listOf(1L, 2L), filtered.roots)
    }

    @Test
    fun `delta permission filter omits without redaction`() {
        val permissions = PeerPermissions()
        permissions.allowMany(1, OpKind.Read, listOf(1, 2, 5))

        val delta = Delta.next(
            8,
            listOf(
                DeltaOp.cellSet(1, byteArrayOf(1)),
                DeltaOp.slotValue(2, byteArrayOf(2)),
                DeltaOp.invalidate(3),
                DeltaOp.nodeAdd(4, "u8", NodeState.Payload(byteArrayOf(4))),
                DeltaOp.nodeRemove(5),
                DeltaOp.edgeAdd(2, 1),
                DeltaOp.edgeRemove(3, 1),
            ),
        )

        val filtered = delta.filterReadable(permissions, 1)

        assertEquals(
            listOf(
                DeltaOp.CellSet::class,
                DeltaOp.SlotValue::class,
                DeltaOp.NodeRemove::class,
                DeltaOp.EdgeAdd::class,
            ),
            filtered.ops.map { it::class },
        )
    }

    @Test
    fun `permissions gate operation kinds independently`() {
        val permissions = PeerPermissions()

        assertTrue(permissions.allow(1, RemoteOp.read(10)))
        assertFalse(permissions.allow(1, RemoteOp.read(10)))
        assertTrue(permissions.isAllowed(1, RemoteOp.read(10)))
        assertFalse(permissions.isAllowed(1, RemoteOp.write(10)))
    }
}
