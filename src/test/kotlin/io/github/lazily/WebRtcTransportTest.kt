package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class WebRtcTransportTest {
    private fun snapshotTwoNodes(): Snapshot =
        Snapshot(
            epoch = 1,
            nodes = listOf(
                NodeSnapshot.payload(1, "t", byteArrayOf(1, 2, 3)),
                NodeSnapshot.payload(2, "t", byteArrayOf(4, 5, 6)),
            ),
            edges = emptyList(),
            roots = listOf(1, 2),
        )

    @Test
    fun `loopback round-trips and filters unreadable nodes`() {
        val (here, there) = InMemoryDataChannel.pair()
        val peer: PeerId = 7

        // The peer may read node 1 but not node 2.
        val perms = PeerPermissions()
        perms.allow(peer, RemoteOp.read(1))

        val sink = WebRtcSink(here, perms, peer)
        val source = WebRtcSource(there)

        sink.send(IpcMessage.ofSnapshot(snapshotTwoNodes()))

        val received = source.recv() ?: error("expected a message")
        val snapshot = assertIs<IpcMessage.SnapshotMessage>(received).snapshot
        assertEquals(listOf<NodeId>(1), snapshot.nodes.map { it.node }, "node 2 must be filtered out")

        // Nothing else pending on an open channel.
        assertNull(source.recv())
    }

    @Test
    fun `crdt sync round-trips through the channel`() {
        val (here, there) = InMemoryDataChannel.pair()
        val peer: PeerId = 3
        val perms = PeerPermissions()
        perms.allow(peer, RemoteOp.read(1))

        val sync = CrdtSync(
            frontier = listOf(1L to WireStamp(5, 0, 1)),
            ops = listOf(CrdtOp(node = 1, key = null, stamp = WireStamp(5, 0, 1), state = IpcValue.Inline(byteArrayOf(42)))),
        )
        WebRtcSink(here, perms, peer).send(IpcMessage.ofCrdtSync(sync))
        val received = WebRtcSource(there).recv() ?: error("expected a message")
        val got = assertIs<IpcMessage.CrdtSyncMessage>(received).sync
        assertEquals(sync, got)
    }

    @Test
    fun `closed channel reports closed on send and recv`() {
        val (here, there) = InMemoryDataChannel.pair()
        here.close()
        val sink = WebRtcSink(here, PeerPermissions(), 1)
        assertFailsWith<WebRtcTransportError.Closed> {
            sink.send(IpcMessage.ofSnapshot(snapshotTwoNodes()))
        }
        val source = WebRtcSource(there)
        assertFailsWith<WebRtcTransportError.Closed> { source.recv() }
    }
}
