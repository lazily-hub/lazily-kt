package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RelayCell Phases 2–6 spike (#relaycell) for lazily-kt. Mirrors the lazily-rs
 * relay tests: converged egress independent of drain schedule (relay_converges),
 * overflow behaviour, reactive readers, spill_lossless / spill_replay_idempotent,
 * transport_independent, Outbox/Inbox roles, and the Phase-6 policies.
 */
class RelayTest {
    private fun relay(
        ctx: Context,
        policy: MergePolicy<Long>,
        highWater: Long = 1_000_000,
        overflow: Overflow = Overflow.Conflate,
    ): RelayCell<Long> =
        RelayCell(
            ctx,
            BackpressurePolicy(ctx, BoundDim.Count, highWater, highWater / 2, overflow),
            policy,
        )

    // -- Phase 2: RelayCell core ---------------------------------------------

    @Test
    fun converged_egress_independent_of_drain_schedule() {
        for (policy in listOf(sum(), max())) {
            val ops = listOf(3L, 1L, 4L, 1L, 5L, 9L, 2L, 6L)
            val flat = ops.reduce { a, b -> policy.merge(a, b) }

            // Drain-every-op schedule.
            val ctxA = Context()
            val rA = relay(ctxA, policy)
            var accA: Long? = null
            for (op in ops) {
                rA.ingress(op)
                val d = rA.drain() ?: continue
                accA = if (accA == null) d else policy.merge(accA!!, d)
            }
            assertEquals(flat, accA, "${policy.name}: drain-every")

            // Drain-once-at-end schedule.
            val ctxB = Context()
            val rB = relay(ctxB, policy)
            for (op in ops) rB.ingress(op)
            assertEquals(flat, rB.drain(), "${policy.name}: drain-once")
        }
    }

    @Test
    fun reactive_depth_is_full_is_empty() {
        val ctx = Context()
        val r = relay(ctx, sum(), highWater = 3)
        assertTrue(r.isEmpty())
        assertEquals(0L, r.depth())
        assertFalse(r.isFull())

        r.ingress(1)
        r.ingress(1)
        assertFalse(r.isEmpty())
        assertEquals(2L, r.depth())
        assertFalse(r.isFull())

        r.ingress(1)
        assertEquals(3L, r.depth())
        assertTrue(r.isFull())

        r.drain()
        assertTrue(r.isEmpty())
        assertEquals(0L, r.depth())
    }

    @Test
    fun block_overflow_refuses_ingress() {
        val ctx = Context()
        val r = relay(ctx, sum(), highWater = 2, overflow = Overflow.Block)
        assertEquals(IngressOutcome.Accepted, r.ingress(1))
        assertEquals(IngressOutcome.Conflated, r.ingress(1))
        assertEquals(IngressOutcome.Blocked, r.ingress(1)) // at high water
        assertEquals(2L, r.drain()) // the blocked op was not merged
    }

    @Test
    fun drop_newest_and_drop_oldest() {
        val ctxN = Context()
        val rn = relay(ctxN, sum(), highWater = 2, overflow = Overflow.DropNewest)
        rn.ingress(1)
        rn.ingress(1)
        assertEquals(IngressOutcome.Dropped, rn.ingress(9))
        assertEquals(2L, rn.drain())

        val ctxO = Context()
        val ro = relay(ctxO, sum(), highWater = 2, overflow = Overflow.DropOldest)
        ro.ingress(1)
        ro.ingress(1)
        assertEquals(IngressOutcome.Dropped, ro.ingress(9))
        assertEquals(9L, ro.drain()) // window reset to the incoming op
    }

    @Test
    fun construction_rejects_conflate_for_raw_fifo() {
        val ctx = Context()
        assertFailsWith<RelayConfigException> {
            RelayCell(
                ctx,
                BackpressurePolicy(ctx, BoundDim.Count, 4, 2, Overflow.Conflate),
                rawFifo<Int>(),
            )
        }
    }

    // -- Phase 3: SpillStore -------------------------------------------------

    @Test
    fun spill_lossless_both_modes() {
        for (mode in listOf(SpillMode.CompactOnWrite, SpillMode.AppendCompact)) {
            val store = SpillStore(mode, 2, sum())
            val windows = listOf(1L, 2L, 3L, 4L, 5L)
            for (w in windows) store.spill(w, 1)
            val hot = 10L
            val flat = (windows + hot).reduce { a, b -> a + b }
            assertEquals(flat, store.reconstruct(0, hot), mode.name)
        }
    }

    @Test
    fun spill_replay_idempotent_for_idempotent_policy() {
        val store = SpillStore(SpillMode.AppendCompact, 1, max())
        for (w in listOf(3L, 7L, 5L)) store.spill(w, 1)
        // Re-delivering the unacked pages twice converges to the same downstream.
        val once = store.replayUnacked(0)
        val twice = store.replayUnacked(once)
        assertEquals(once, twice)
        assertEquals(7L, once)
    }

    @Test
    fun compact_on_write_bounds_pages_and_ack_reclaims() {
        val store = SpillStore(SpillMode.CompactOnWrite, 2, sum())
        repeat(5) { store.spill(1, 1) } // 5 ops, page size 2 → 3 pages
        assertEquals(3, store.pageCount())
        val ids = store.manifest().map { it.first }
        store.ackThrough(ids[0])
        assertEquals(2, store.pendingPages().size)
        store.reclaim()
        assertEquals(2, store.pageCount())
    }

    // -- Phase 4: Transport --------------------------------------------------

    @Test
    fun transport_independent_across_framing() {
        for (policy in listOf(sum(), max(), keepLatest<Long>())) {
            val ops = listOf(3L, 1L, 4L, 1L, 5L, 9L)
            val flat = ops.reduce { a, b -> policy.merge(a, b) }

            for (transport in listOf(InProcTransport<Long>(), FramedTransport(2), FramedTransport(3))) {
                for (op in ops) transport.deliver(op)
                val ctx = Context()
                val r = relay(ctx, policy)
                while (transport.hasPending()) {
                    for (op in transport.poll()) r.ingress(op)
                }
                assertEquals(flat, r.drain(), policy.name)
            }
        }
    }

    // -- Phase 5: Outbox / Inbox roles ---------------------------------------

    @Test
    fun outbox_conflates_state_broadcast() {
        val ctx = Context()
        val out = Outbox(ctx, highWater = 8, mergePolicy = keepLatest<Long>())
        out.send(1)
        out.send(2)
        out.send(3)
        assertEquals(3L, out.drain()) // keep-latest conflation
    }

    @Test
    fun inbox_credit_meters_remote() {
        val ctx = Context()
        val inbox = Inbox(ctx, highWater = 100, maxCredits = 2, mergePolicy = sum())
        assertTrue(inbox.ready())
        inbox.receive(5)
        inbox.receive(5)
        assertFalse(inbox.ready()) // credits exhausted
        val out = inbox.consume(replenish = 2)
        assertEquals(10L, out)
        assertTrue(inbox.ready()) // replenished
    }

    @Test
    fun outbox_to_inbox_link_converges() {
        val ctx = Context()
        val out = Outbox(ctx, highWater = 64, mergePolicy = sum())
        val inbox = Inbox(ctx, highWater = 64, maxCredits = 64, mergePolicy = sum())
        val transport = InProcTransport<Long>()
        val ops = listOf(1L, 2L, 3L, 4L)
        for (op in ops) out.send(op)
        transport.deliver(out.drain()!!)
        while (transport.hasPending()) {
            for (frame in transport.poll()) inbox.receive(frame)
        }
        assertEquals(ops.sum(), inbox.consume(64))
    }

    // -- Phase 6: policies ---------------------------------------------------

    @Test
    fun rate_policy_token_bucket() {
        val rate = RatePolicy(capacity = 2, refillPerTick = 1)
        assertTrue(rate.tryEgress())
        assertTrue(rate.tryEgress())
        assertFalse(rate.tryEgress()) // empty
        rate.tick()
        assertTrue(rate.tryEgress())
    }

    @Test
    fun window_policy_flush_on_fill_and_tick_preserves_sum() {
        val window = WindowPolicy(windowOps = 3)
        assertFalse(window.onIngress())
        assertFalse(window.onIngress())
        assertTrue(window.onIngress()) // full → flush
        assertFalse(window.onIngress())
        assertTrue(window.tick()) // interval boundary flushes remainder
        assertFalse(window.tick()) // nothing pending
    }

    @Test
    fun expiry_policy_drops_aged() {
        val expiry = ExpiryPolicy(ttl = 5)
        expiry.advance(10)
        val batch = listOf(3L to "old", 7L to "fresh", 10L to "now")
        assertEquals(listOf("fresh", "now"), expiry.retainLive(batch))
    }

    @Test
    fun priority_storage_pops_highest_first_fifo_within() {
        val pq = PriorityStorage<String>()
        pq.push(1, "low")
        pq.push(3, "highA")
        pq.push(2, "mid")
        pq.push(3, "highB")
        assertEquals("highA", pq.pop())
        assertEquals("highB", pq.pop()) // FIFO within priority 3
        assertEquals("mid", pq.pop())
        assertEquals("low", pq.pop())
        assertNull(pq.pop())
    }

    @Test
    fun keyed_relay_shards_per_key() {
        val ctx = Context()
        val keyed = KeyedRelay<String, Long>(ctx, highWater = 64, overflow = Overflow.Conflate, mergePolicy = sum())
        keyed.ingress("a", 1)
        keyed.ingress("b", 10)
        keyed.ingress("a", 2)
        assertEquals(3L, keyed.drain("a"))
        assertEquals(10L, keyed.drain("b"))
        assertEquals(setOf("a", "b"), keyed.keys())
    }
}
