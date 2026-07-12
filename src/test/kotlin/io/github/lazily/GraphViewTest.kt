package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphViewTest {
    @Test
    fun applies_native_snapshot_then_delta() {
        val replica = GraphView()
        assertEquals(false, replica.isInitialized)

        replica.applySnapshot(
            Snapshot(
                epoch = 3,
                nodes = listOf(
                    NodeSnapshot(1L, "doc.route", NodeState.Payload("hello".toByteArray())),
                    NodeSnapshot(2L, "doc.proof", NodeState.Opaque),
                ),
                edges = listOf(EdgeSnapshot(2L, 1L)),
                roots = listOf(1L),
            )
        )

        assertTrue(replica.isInitialized)
        assertEquals(3L, replica.epoch)
        assertEquals(2, replica.nodeCount)
        assertEquals("hello", String(replica.node(1L)!!.payload!!))
        assertNull(replica.node(2L)!!.payload) // Opaque carries no inline payload

        replica.applyDelta(
            Delta(
                baseEpoch = 3,
                epoch = 5,
                ops = listOf(
                    DeltaOp.CellSet(1L, IpcValue.Inline("world".toByteArray())),
                    DeltaOp.NodeAdd(3L, "doc.transport", NodeState.Payload("x".toByteArray())),
                    DeltaOp.NodeRemove(2L),
                    DeltaOp.EdgeRemove(2L, 1L),
                ),
            )
        )

        assertEquals(5L, replica.epoch)
        assertEquals("world", String(replica.node(1L)!!.payload!!)) // cell_set updated payload
        assertNull(replica.node(2L)) // removed
        assertEquals(2, replica.nodeCount) // added 3, removed 2
        assertEquals(1, replica.nodesOfType("doc.transport").size)
        assertEquals(emptyList(), replica.allEdges())
    }

    @Test
    fun reemitted_delta_is_idempotent() {
        val replica = GraphView()
        replica.applySnapshot(
            Snapshot(epoch = 1, nodes = listOf(NodeSnapshot(1L, "t", NodeState.Payload("a".toByteArray()))))
        )
        val delta = Delta(1, 2, listOf(DeltaOp.CellSet(1L, IpcValue.Inline("b".toByteArray()))))
        replica.applyDelta(delta)
        val afterFirst = replica.node(1L)
        replica.applyDelta(delta) // re-emit
        assertEquals(afterFirst, replica.node(1L))
        assertEquals(2L, replica.epoch)
    }
}
