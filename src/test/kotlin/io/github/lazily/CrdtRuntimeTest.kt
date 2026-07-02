package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec-compliance tests for the distributed CRDT cell plane runtime
 * (`lazily-spec/protocol.md` § Distributed, `lazily-spec/cell-model.md`
 * § Multi-write cells): the HLC clock, per-peer stamp frontier, causal-
 * stability watermark, the LWW / MV / PN-counter registers, and ingress of
 * remote `CrdtOp`s into a reactive root cell. The wire-type round-trips live in
 * [NodeKeyAndCrdtTest]; this covers the merge mechanism itself.
 */
class CrdtRuntimeTest {
    @Test
    fun `wire stamp total order is wall then logical then peer`() {
        val a = WireStamp(100, 0, 1)
        val b = WireStamp(200, 0, 2)
        val c = WireStamp(200, 5, 1)
        val d = WireStamp(200, 5, 2)
        assertTrue(a.isAfter(WireStamp(99, 99, 99)))
        assertTrue(b.isAfter(a))
        assertTrue(c.isAfter(b))
        assertTrue(d.isAfter(c))
        assertTrue(d.isAtOrBefore(d))
        assertFalse(d.isAfter(d))
    }

    @Test
    fun `hlc clock is monotonic and causally succeeds observed stamps`() {
        val clock = CrdtClock(peer = 1)
        val s1 = clock.tick()
        val s2 = clock.tick()
        val s3 = clock.tick()
        assertEquals(1, s1.peer)
        assertTrue(s2.isAfter(s1))
        assertTrue(s3.isAfter(s2))
        // Observing a future-distant stamp forces later ticks to succeed it.
        clock.observe(WireStamp(s3.wallTime + 10_000, 42, 2))
        val s4 = clock.tick()
        assertTrue(s4.isAfter(WireStamp(s3.wallTime + 10_000, 42, 2)), "tick must succeed observed remote stamp")
    }

    @Test
    fun `stamp frontier merge is per-peer max and idempotent`() {
        val f = StampFrontier()
        assertTrue(f.observe(1L, WireStamp(100, 0, 1)))
        assertFalse(f.observe(1L, WireStamp(100, 0, 1)), "re-observing an equal stamp is a no-op")
        assertTrue(f.observe(1L, WireStamp(200, 0, 1)))
        assertFalse(f.observe(1L, WireStamp(150, 0, 1)), "an older stamp does not advance the frontier")

        val g = StampFrontier(mapOf(2L to WireStamp(300, 0, 2)))
        assertTrue(f.merge(g))
        assertEquals(WireStamp(300, 0, 2), f.of(2L))
    }

    @Test
    fun `watermark is the min across membership and undefined for unobserved peers`() {
        val f = StampFrontier(
            mapOf(
                1L to WireStamp(200, 0, 1),
                2L to WireStamp(150, 0, 2),
                3L to WireStamp(300, 0, 3),
            ),
        )
        // min over membership {1,2,3} is the smallest (150,0,2).
        assertEquals(WireStamp(150, 0, 2), f.watermark(listOf(1L, 2L, 3L)))
        // A member the frontier has not observed -> undefined (fail closed).
        assertNull(f.watermark(listOf(1L, 99L)))
        assertNull(f.watermark(emptyList()))
    }

    @Test
    fun `lww register last write wins and ignores dominated stamps`() {
        val ctx = Context()
        val cell = ctx.replicatedCell(
            initial = "a",
            register = LwwRegister(CrdtCodec.string),
            codec = CrdtCodec.string,
            clock = CrdtClock(peer = 1),
        )
        // The register is seeded with the initial value at a pre-history stamp
        // (0,0,0), so a real remote write supersedes it.
        val op1 = CrdtOp(node = 1, key = null, stamp = WireStamp(100, 0, 2), state = IpcValue.Inline("v1".encodeToByteArray()))
        assertTrue(cell.applyRemote(op1))
        assertEquals("v1", cell.register.value())
        assertEquals("v1", ctx.getCellAny(cell.backing.id))

        // A later stamp wins.
        val op2 = CrdtOp(node = 1, key = null, stamp = WireStamp(200, 0, 2), state = IpcValue.Inline("v2".encodeToByteArray()))
        assertTrue(cell.applyRemote(op2))
        assertEquals("v2", cell.register.value())

        // Re-delivering an older/equal stamp is idempotent (dominated).
        assertFalse(cell.applyRemote(op1))
        assertEquals("v2", cell.register.value())
        // A stamp between also dominated by the current (200,...).
        val between = CrdtOp(node = 1, key = null, stamp = WireStamp(150, 0, 2), state = IpcValue.Inline("v3".encodeToByteArray()))
        assertFalse(cell.applyRemote(between))
        assertEquals("v2", cell.register.value())
    }

    @Test
    fun `lww merge into backing cell is equality-guarded downstream`() {
        val ctx = Context()
        val cell = ctx.replicatedCell(
            initial = "v",
            register = LwwRegister(CrdtCodec.string),
            codec = CrdtCodec.string,
            clock = CrdtClock(peer = 1),
        )
        val downstream = ctx.memo { ctx.getCell(cell.backing) }
        ctx.get(downstream) // establish dep

        // An equal-value merge at a later stamp MUST NOT invalidate downstream
        // (the PartialEq guard): the register adopts the stamp but the value is
        // unchanged, so the backing cell is never written.
        val equalMerge = CrdtOp(node = 1, key = null, stamp = WireStamp(300, 0, 2), state = IpcValue.Inline("v".encodeToByteArray()))
        cell.applyRemote(equalMerge)
        assertTrue(ctx.isSet(downstream), "equal merge does not invalidate downstream")
    }

    @Test
    fun `mv register surfaces concurrent writes and resolves on causally-later write`() {
        val reg = MvRegister(CrdtCodec.int)
        val sA = WireStamp(100, 0, 1)
        val sB = WireStamp(100, 0, 2) // concurrent with sA (incomparable)
        reg.merge(1, sA)
        reg.merge(2, sB)
        assertEquals(setOf(1, 2), reg.values(), "concurrent writes both preserved")
        assertNull(reg.value(), "conflict has no single resolved value")

        // A causally-later write supersedes the conflict.
        val sC = WireStamp(200, 0, 1)
        reg.merge(3, sC)
        assertEquals(setOf(3), reg.values())
        assertEquals(3, reg.value())
    }

    @Test
    fun `pn counter converges via per-peer max merge`() {
        val a = PnCounter()
        val b = PnCounter()
        a.increment(peer = 1, delta = 3)
        a.decrement(peer = 1, delta = 1)
        b.increment(peer = 2, delta = 5)

        // Replica a merges b's packed peer-2 state.
        assertTrue(a.mergeRemote(peer = 2, packedState = b.packLocal(peer = 2)))
        assertEquals(7L, a.value(), "3 - 1 + 5 = 7")
        // Re-merge is idempotent; a newer same-peer state advances.
        assertFalse(a.mergeRemote(peer = 2, packedState = b.packLocal(peer = 2)))
    }

    @Test
    fun `local edit produces a crdt op that round-trips through a peer replica`() {
        val ctxA = Context()
        val ctxB = Context()
        val codec = CrdtCodec.string
        val cellA = ctxA.replicatedCell("init", LwwRegister(codec), codec, CrdtClock(peer = 1))
        val cellB = ctxB.replicatedCell("init", LwwRegister(codec), codec, CrdtClock(peer = 2))

        val op = cellA.localEdit("hello", node = 7, key = NodeKey.from("greetings/alice"))
        assertEquals(7, op.node)
        assertEquals("greetings/alice", op.key?.path)

        // Peer B applies the op and converges.
        assertTrue(cellB.applyRemote(op))
        assertEquals("hello", cellB.register.value())
        assertEquals("hello", ctxB.getCellAny(cellB.backing.id))
        // B's frontier now observes peer 1's stamp.
        assertEquals(op.stamp, cellB.frontier.of(1L))
    }

    @Test
    fun `out of order and duplicated delivery converges`() {
        val ctx = Context()
        val codec = CrdtCodec.int
        val cell = ctx.replicatedCell(0, LwwRegister(codec), codec, CrdtClock(peer = 9))
        fun op(v: Int, s: WireStamp) = CrdtOp(1, null, s, IpcValue.Inline(codec.encode(v)))

        val s1 = WireStamp(100, 0, 1)
        val s2 = WireStamp(200, 0, 1)
        val s3 = WireStamp(300, 0, 1)
        // Deliver in reverse order, with a duplicate.
        cell.applyRemote(op(3, s3))
        cell.applyRemote(op(2, s2))
        cell.applyRemote(op(1, s1))
        cell.applyRemote(op(3, s3)) // duplicate
        assertEquals(3, cell.register.value(), "converges to the latest by stamp regardless of arrival order")
    }

    @Test
    fun `crdt sync frame carries frontier and ops`() {
        val frontier = StampFrontier(mapOf(1L to WireStamp(100, 0, 1)))
        val op = CrdtOp(node = 1, key = null, stamp = WireStamp(100, 0, 1), state = IpcValue.Inline(byteArrayOf(1)))
        val frame = crdtSyncFrame(frontier, listOf(op))
        assertEquals(listOf(1L), frame.frontier.map { it.first })
        assertEquals(1, frame.ops.size)
        // And it round-trips through the wire.
        val msg = IpcMessage.ofCrdtSync(frame)
        assertEquals(msg, IpcMessage.decodeJson(msg.encodeJson()))
    }
}
