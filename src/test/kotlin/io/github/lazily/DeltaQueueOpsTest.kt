package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `QueuePush` / `QueuePop` / `QueueClose` as ordinary `DeltaOp` variants
 * (`#lzdeltaqueueops`, protocol.md § QueueCell op-log delta form, `#queue-oplog`).
 */
class DeltaQueueOpsTest {
    private val queueOps: List<DeltaOp> =
        listOf(
            DeltaOp.queuePush(6, byteArrayOf(97)),
            DeltaOp.queuePop(6),
            DeltaOp.queueClose(6),
        )

    private fun deltaOf(vararg ops: DeltaOp): Delta = Delta.next(3, ops.toList())

    @Test
    fun `queue ops round trip through the json codec`() {
        for (op in queueOps) {
            val message = IpcMessage.ofDelta(deltaOf(op))
            assertEquals(message, IpcMessage.decodeJson(message.encodeJson()))
        }
    }

    @Test
    fun `queue ops round trip through the msgpack codec`() {
        for (op in queueOps) {
            val message = IpcMessage.ofDelta(deltaOf(op))
            assertEquals(message, IpcMessage.decodeMsgpack(message.encodeMsgpack()))
        }
    }

    @Test
    fun `queue ops encode with the externally tagged wire shape`() {
        assertEquals(
            """{"QueuePush":{"node":6,"payload":{"Inline":[97]}}}""",
            DeltaOp.queuePush(6, byteArrayOf(97)).toJson().toString(),
        )
        assertEquals("""{"QueuePop":{"node":6}}""", DeltaOp.queuePop(6).toJson().toString())
        assertEquals("""{"QueueClose":{"node":6}}""", DeltaOp.queueClose(6).toJson().toString())
    }

    @Test
    fun `decoder rejects queue bodies missing required fields`() {
        for (frame in listOf(
            """{"QueuePush":{"payload":{"Inline":[97]}}}""",
            """{"QueuePush":{"node":6}}""",
            """{"QueuePop":{}}""",
            """{"QueueClose":{}}""",
        )) {
            val wire = """{"Delta":{"base_epoch":3,"epoch":4,"ops":[$frame]}}"""
            assertFailsWith<IpcDecodeException> { IpcMessage.decodeJson(wire.encodeToByteArray()) }
        }
    }

    @Test
    fun `permission filter is node scoped for queue ops`() {
        val permissions = PeerPermissions()
        permissions.allowMany(1, OpKind.Read, listOf(6))

        val delta =
            deltaOf(
                DeltaOp.queuePush(6, byteArrayOf(1)),
                DeltaOp.queuePush(7, byteArrayOf(2)),
                DeltaOp.queuePop(6),
                DeltaOp.queuePop(7),
                DeltaOp.queueClose(7),
                DeltaOp.queueClose(6),
            )

        assertEquals(
            listOf(
                DeltaOp.queuePush(6, byteArrayOf(1)),
                DeltaOp.queuePop(6),
                DeltaOp.queueClose(6),
            ),
            delta.filterReadable(permissions, 1).ops,
        )
        assertTrue(delta.filterReadable(permissions, 2).ops.isEmpty())
    }

    @Test
    fun `queue push payload spills and resolves like cell set`() {
        val backend = InProcessBackend.withCapacity(1024)
        val router = BlobRouter().register(backend)
        val big = ByteArray(48) { (it and 0xff).toByte() }
        val delta = deltaOf(DeltaOp.queuePush(6, IpcValue.inline(big)), DeltaOp.queuePop(6), DeltaOp.queueClose(6))

        val (spilled, moved) = spillMessage(IpcMessage.ofDelta(delta), backend, threshold = 16)

        assertEquals(48, moved)
        val ops = assertIs<IpcMessage.DeltaMessage>(spilled).delta.ops
        val push = assertIs<DeltaOp.QueuePush>(ops[0])
        assertIs<IpcValue.SharedBlob>(push.payload)
        assertContentEquals(big, router.resolve(push.payload))
        assertEquals(listOf(DeltaOp.queuePop(6), DeltaOp.queueClose(6)), ops.drop(1))
        // The spilled descriptor itself round-trips through both codecs.
        assertEquals(spilled, IpcMessage.decodeJson(spilled.encodeJson()))
        assertEquals(spilled, IpcMessage.decodeMsgpack(spilled.encodeMsgpack()))
    }

    @Test
    fun `small queue push payload stays inline`() {
        val backend = InProcessBackend.withCapacity(1024)
        val delta = deltaOf(DeltaOp.queuePush(6, byteArrayOf(1, 2)))
        val (spilled, moved) = spillMessage(IpcMessage.ofDelta(delta), backend, threshold = 16)
        assertEquals(0, moved)
        assertEquals(IpcMessage.ofDelta(delta), spilled)
    }

    @Test
    fun `graph view refuses queue ops without applying the delta`() {
        for (op in queueOps) {
            val view = GraphView()
            view.applySnapshot(
                Snapshot(
                    epoch = 3,
                    nodes = listOf(NodeSnapshot(6L, "queue", NodeState.Payload(byteArrayOf(0)))),
                    edges = emptyList(),
                    roots = listOf(6L),
                ),
            )
            // The queue op comes AFTER a mutating op: the refusal must be atomic.
            val error =
                assertFailsWith<UnsupportedQueueOpException> {
                    view.applyDelta(deltaOf(DeltaOp.nodeRemove(6), op))
                }
            assertEquals(op, error.op)
            assertTrue(error.message!!.contains("requires a queue projection adapter"), error.message)
            assertTrue(error.message!!.startsWith(op::class.simpleName!!), error.message)
            assertEquals(3L, view.epoch)
            assertEquals(1, view.nodeCount)
            assertContentEquals(byteArrayOf(0), view.node(6L)!!.payload)
        }
    }
}
