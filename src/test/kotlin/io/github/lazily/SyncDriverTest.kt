package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SyncDriver loop-shape tests (spec § SyncDriver, `#sync-driver`).
 *
 * A SimWorld-style deterministic pair: the sink records what the driver sends
 * (and can be toggled "down" to model a disconnect); the source replays a
 * scripted inbound frame stream (and can inject one read error). No threads, no
 * real socket — every tick is a pure step over injected state. Mirrors the
 * Rust reference tests in `lazily-rs/src/reliable_sync.rs`.
 */
class SyncDriverTest {
    /** Records sent frames; `up=false` models a downed transport (send fails). */
    private class TestSink(val sent: MutableList<IpcMessage> = mutableListOf(), var up: Boolean = true) : IpcSink {
        override fun send(message: IpcMessage): Boolean {
            if (!up) return false
            sent.add(message)
            return true
        }
    }

    /** Replays a scripted inbound queue; `err=true` throws once (the reconnect signal). */
    private class TestSource(val inbound: ArrayDeque<IpcMessage> = ArrayDeque(), var err: Boolean = false) : IpcSource {
        override fun recv(): IpcMessage? {
            if (err) {
                err = false
                throw RuntimeException("scripted read error")
            }
            return inbound.removeFirstOrNull()
        }
    }

    private object Zero : Clock {
        override fun nowMillis(): Long = 0
    }

    /** Answers a `ResyncRequest{from}` with a snapshot at `from + 5`. */
    private object SnapAhead : SnapshotProvider {
        override fun snapshot(fromEpoch: Long): IpcMessage =
            IpcMessage.ofSnapshot(Snapshot(epoch = fromEpoch + 5))
    }

    private class Harness(lastEpoch: Long = 0) {
        val sink = TestSink()
        val source = TestSource()
        val driver = SyncDriver(sink, source, InMemoryOutbox(), Zero, SnapAhead, lastEpoch)
    }

    private fun delta(base: Long, epoch: Long): IpcMessage =
        IpcMessage.ofDelta(Delta(baseEpoch = base, epoch = epoch))

    @Test
    fun drainsAppendBeforeSendAndRetainsUntilAcked() {
        val h = Harness()
        h.driver.enqueue(1, delta(0, 1))
        h.driver.enqueue(2, delta(1, 2))
        var p = h.driver.tick()
        assertEquals(2, p.sent, "both fresh frames pushed to the sink")
        assertEquals(2, h.sink.sent.size)
        assertEquals(2, p.retained, "appended-before-send, retained until acked")
        assertFalse(h.driver.isStalled())

        // Peer proves receipt → the outbox prunes and the resume cursor advances.
        h.source.inbound.addLast(IpcMessage.ofOutboxAck(OutboxAck(2)))
        p = h.driver.tick()
        assertEquals(2, p.peerAckedThrough)
        assertEquals(0, p.retained, "acked frames pruned")
    }

    @Test
    fun retainsOnSendFailureAndReplaysOnReconnect() {
        val h = Harness()
        h.sink.up = false // sink down before the first send
        h.driver.enqueue(1, delta(0, 1))
        var p = h.driver.tick()
        assertEquals(0, p.sent)
        assertTrue(h.driver.isStalled(), "a failed send stalls the driver")
        assertEquals(1, p.retained, "frame retained in the outbox despite the failure")
        assertTrue(h.sink.sent.isEmpty())
        assertEquals(250, h.driver.stalledFor(250), "stall duration is a host backoff signal")

        // Transport recovers → the unacked suffix replays from the ack cursor.
        h.sink.up = true
        h.driver.onReconnect()
        p = h.driver.tick()
        assertFalse(h.driver.isStalled())
        assertEquals(1, p.sent, "the retained frame is replayed")
        assertTrue(
            h.sink.sent.any { it is IpcMessage.DeltaMessage && it.delta.epoch == 1L },
            "the replayed delta reached the sink",
        )
    }

    @Test
    fun appliesDeltaAndAdvertisesReceiverCursor() {
        val h = Harness()
        h.source.inbound.addLast(delta(0, 1))
        val p = h.driver.tick()
        assertEquals(1, p.applied.size, "the applied frame is handed to the host")
        assertEquals(1, h.driver.lastEpoch())
        assertTrue(
            h.sink.sent.any { it is IpcMessage.OutboxAckMessage && it.ack.throughEpoch == 1L },
            "an OutboxAck advertising the new cursor was sent",
        )
    }

    @Test
    fun redeliveryIsIdempotentNoOp() {
        val h = Harness()
        h.source.inbound.addLast(delta(0, 1))
        assertEquals(1, h.driver.tick().applied.size)
        // Re-deliver the exact same frame (an outbox replay from the peer).
        h.source.inbound.addLast(delta(0, 1))
        val p = h.driver.tick()
        assertEquals(0, p.applied.size, "already-applied re-delivery is ignored")
        assertEquals(1, h.driver.lastEpoch(), "cursor does not double-advance")
    }

    @Test
    fun requestsSnapshotOnInboundGap() {
        val h = Harness(lastEpoch = 2)
        h.source.inbound.addLast(delta(3, 4)) // base 3 > last 2 → gap
        val p = h.driver.tick()
        assertTrue(p.resyncRequested)
        assertTrue(p.applied.isEmpty(), "the gapped delta is not applied")
        assertTrue(
            h.sink.sent.any { it is IpcMessage.ResyncRequestMessage && it.request.fromEpoch == 2L },
            "a ResyncRequest at the current cursor was emitted",
        )
    }

    @Test
    fun answersResyncRequestWithProviderSnapshot() {
        val h = Harness()
        h.source.inbound.addLast(IpcMessage.ofResyncRequest(ResyncRequest(2)))
        val p = h.driver.tick()
        assertEquals(1, p.snapshotsServed)
        assertTrue(
            h.sink.sent.any { it is IpcMessage.SnapshotMessage && it.snapshot.epoch == 7L },
            "a covering snapshot (fromEpoch + 5) was sent",
        )
    }

    @Test
    fun surfacesSourceReadError() {
        val h = Harness()
        h.source.err = true
        assertFailsWith<SyncDriverSourceException> { h.driver.tick() }
    }

    @Test
    fun gapThenSnapshotConverges() {
        // Receiver at 2; a gapped delta triggers a request, then a covering
        // snapshot lands and convergence is restored (resync_convergence shape).
        val h = Harness(lastEpoch = 2)
        h.source.inbound.addLast(delta(4, 5)) // gap
        h.driver.tick()
        assertEquals(2, h.driver.lastEpoch(), "still stuck at the pre-gap cursor")
        h.source.inbound.addLast(IpcMessage.ofSnapshot(Snapshot(epoch = 5)))
        val p = h.driver.tick()
        assertEquals(1, p.applied.size)
        assertEquals(5, h.driver.lastEpoch(), "snapshot restored convergence")
    }
}
