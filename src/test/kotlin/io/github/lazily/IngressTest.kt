package io.github.lazily

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transport-agnostic ingress family (`#designimplementtransport`): the admission
 * algebra's named invariants plus the reactivity each flavor shell adds.
 *
 * The cross-language corpus is replayed by `IngressFamilyConformanceTest`; this file
 * names the invariants the corpus does not spell out step-by-step — construction
 * validation, the two load-bearing admission orderings, the receipt bound, and the
 * one-frontier-walk fan-out that a partial invalidation would expose as
 * "new value, old authority".
 */
class IngressTest {
    private fun core(
        policy: IngressPolicy = IngressPolicy(),
        merge: MergePolicy<Long> = sum(),
    ) = IngressCore<String, Long>(policy, merge)

    private fun env(
        key: String,
        generation: Long,
        sequence: Long,
        stampedAt: Long,
        payload: Long,
    ) = IngressEnvelope(key, generation, sequence, stampedAt, payload)

    // -- Construction ------------------------------------------------------

    @Test
    fun `conflate is rejected for a non-conflating algebra`() {
        val failure =
            assertFailsWith<IngressConfigException> {
                IngressCore<String, List<Long>>(
                    IngressPolicy(overflow = Overflow.Conflate),
                    rawFifo(),
                )
            }
        assertEquals(IngressConfigError.ConflateNotBounding, failure.error)
    }

    @Test
    fun `zero receipt capacity is rejected`() {
        val failure =
            assertFailsWith<IngressConfigException> {
                core(IngressPolicy(receiptCapacity = 0))
            }
        assertEquals(IngressConfigError.ZeroReceiptCapacity, failure.error)
    }

    // -- Delivery, dedupe, ordering ----------------------------------------

    @Test
    fun `in order delivery conflates and receipts`() {
        val core = core()
        val (change, admission) = core.admit(env("a", 1, 0, 0, 5))
        assertEquals(IngressAdmission.Accepted(0), admission)
        assertTrue(change.acceptedReceipts)
        assertEquals(listOf("a" to IngressScopeChange.all), change.scopes)

        assertEquals(IngressAdmission.Conflated(1), core.admit(env("a", 1, 1, 0, 7)).second)
        assertEquals(12L, core.peek("a"))
        assertEquals(2, core.receipts(IngressReceiptChannel.Accepted).size)
        assertTrue(core.receipts(IngressReceiptChannel.Dropped).isEmpty())
    }

    @Test
    fun `reorder buffers then flushes in one invalidation`() {
        val core = core()
        val (first, buffered) = core.admit(env("a", 1, 2, 0, 4))
        assertEquals(IngressAdmission.Buffered(0), buffered)
        // A buffered envelope mints no receipt and moves no value. The scope's first
        // appearance does move it off `Unknown`, and saying so is the difference
        // between a sound invalidation set and a reader stuck on `Unknown` forever.
        assertTrue(!first.acceptedReceipts && !first.droppedReceipts)
        assertEquals(listOf("a" to IngressScopeChange.creation), first.scopes)
        assertNull(core.peek("a"))

        val (second, alsoBuffered) = core.admit(env("a", 1, 1, 0, 2))
        assertEquals(IngressAdmission.Buffered(0), alsoBuffered)
        // Now the scope exists, so a second buffered envelope really is invisible.
        assertTrue(second.isEmpty)

        // Three ops coalesced, so the delivery reports Conflated even though the
        // window it started from was empty.
        assertEquals(IngressAdmission.Conflated(2), core.admit(env("a", 1, 0, 0, 1)).second)
        assertEquals(7L, core.peek("a"))
        assertEquals(0, assertNotNull(core.view("a")).buffered)
        assertEquals(1, core.receipts(IngressReceiptChannel.Accepted).size)
    }

    @Test
    fun `duplicates are dropped after delivery and while buffered`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 1))
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.DuplicateSequence),
            core.admit(env("a", 1, 0, 0, 1)).second,
        )
        core.admit(env("a", 1, 5, 0, 1))
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.DuplicateBuffered),
            core.admit(env("a", 1, 5, 0, 1)).second,
        )
        assertEquals(1L, core.peek("a"))
    }

    @Test
    fun `reorder window overflow drops rather than growing`() {
        val core = core(IngressPolicy(reorderWindow = 2))
        core.admit(env("a", 1, 1, 0, 1))
        core.admit(env("a", 1, 2, 0, 1))
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.ReorderWindowOverflow),
            core.admit(env("a", 1, 3, 0, 1)).second,
        )
        assertEquals(2, assertNotNull(core.view("a")).buffered)
    }

    @Test
    fun `a zero reorder window drops every gap immediately`() {
        val core = core(IngressPolicy(reorderWindow = 0))
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.ReorderWindowOverflow),
            core.admit(env("a", 1, 1, 0, 1)).second,
        )
    }

    @Test
    fun `out of order arrival converges to the in order fold`() {
        // The reordering tax is paid by the buffer, not by the algebra: for any
        // arrival permutation of a contiguous run, the drained window equals the
        // in-order fold.
        val permutations =
            listOf(
                listOf(0L, 1L, 2L, 3L),
                listOf(3L, 2L, 1L, 0L),
                listOf(1L, 0L, 3L, 2L),
                listOf(2L, 0L, 1L, 3L),
                listOf(0L, 3L, 1L, 2L),
            )
        for (order in permutations) {
            val core = core()
            for (seq in order) core.admit(env("a", 1, seq, 0, 1L shl seq.toInt()))
            assertEquals(15L, core.peek("a"), "order $order")
            assertEquals(3L, assertNotNull(core.view("a")).deliveredThrough, "order $order")
        }
    }

    // -- The two load-bearing admission orderings --------------------------

    @Test
    fun `a stale generation is fenced before its sequence is consulted`() {
        val core = core()
        core.admit(env("a", 2, 0, 0, 1))
        // Sequence 0 would be a duplicate; generation 1 is stale. The fence wins,
        // which is what makes a zombie producer distinguishable from a retry.
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.StaleGeneration),
            core.admit(env("a", 1, 0, 0, 9)).second,
        )
        assertEquals(1L, core.peek("a"))
    }

    @Test
    fun `an expired envelope never occupies a reorder slot`() {
        val core = core(IngressPolicy(freshnessHorizon = 10, reorderWindow = 1))
        core.tick(100)
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.Expired),
            core.admit(env("a", 1, 3, 50, 1)).second,
        )
        // A refused envelope leaves no scope behind: an expired message for an
        // untracked key is not an admission plane.
        assertNull(core.view("a"))
        // The slot is still free for a fresh out-of-order envelope.
        assertEquals(IngressAdmission.Buffered(0), core.admit(env("a", 1, 3, 95, 1)).second)
    }

    // -- Generation handoff is a baseline reset ----------------------------

    @Test
    fun `a newer generation hands off and resets the sequence space`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 1))
        core.admit(env("a", 1, 7, 0, 1))
        assertEquals(
            IngressAdmission.GenerationHandoff(1, 2),
            core.admit(env("a", 2, 0, 0, 4)).second,
        )
        val view = assertNotNull(core.view("a"))
        assertEquals(2L, view.generation)
        assertEquals(0L, view.deliveredThrough)
        // The old generation's buffered successor is not replayed under the new fence
        // — its sequence numbers mean something else now.
        assertEquals(0, view.buffered)
        // Nor is its undrained window folded into the new baseline.
        assertEquals(4L, core.peek("a"))
    }

    @Test
    fun `a handoff that buffers still reports the baseline reset`() {
        // A NEWER generation arriving out of order resets the fence, the watermark,
        // AND the window before parking the envelope. Reporting that as "buffered,
        // nothing changed" would leave every reader showing the superseded
        // generation's value forever.
        val core = core()
        core.admit(env("a", 1, 0, 0, 5))
        val (change, admission) = core.admit(env("a", 2, 3, 0, 9))
        assertEquals(IngressAdmission.Buffered(0), admission)
        assertEquals(
            listOf(
                "a" to
                    IngressScopeChange(
                        value = true,
                        readiness = true,
                        authority = true,
                        retry = false,
                    ),
            ),
            change.scopes,
        )
        assertNull(core.peek("a"))
        val view = assertNotNull(core.view("a"))
        assertEquals(2L, view.generation)
        assertNull(view.deliveredThrough)
        assertEquals(1, view.buffered)
        // A buffered envelope under the SAME generation is still invisible.
        assertTrue(core.admit(env("a", 2, 4, 0, 1)).first.isEmpty)
    }

    // -- Backpressure reuses the relay algebra ----------------------------

    @Test
    fun `block overflow refuses without losing the window`() {
        val core = core(IngressPolicy(highWater = 1, overflow = Overflow.Block), keepLatest())
        core.admit(env("a", 1, 0, 0, 5))
        val (change, admission) = core.admit(env("a", 1, 1, 0, 9))
        assertEquals(IngressAdmission.Blocked, admission)
        assertTrue(change.droppedReceipts)
        assertEquals(5L, core.peek("a"))
        // The blocked envelope did not advance the watermark, so a producer retry
        // after a drain is still in order rather than a duplicate.
        assertEquals(0L, assertNotNull(core.view("a")).deliveredThrough)
        core.drain("a")
        assertEquals(IngressAdmission.Accepted(1), core.admit(env("a", 1, 1, 0, 9)).second)
    }

    @Test
    fun `drop oldest restarts the window at the incoming op`() {
        val core = core(IngressPolicy(highWater = 2, overflow = Overflow.DropOldest))
        core.admit(env("a", 1, 0, 0, 1))
        core.admit(env("a", 1, 1, 0, 2))
        assertEquals(IngressAdmission.Accepted(2), core.admit(env("a", 1, 2, 0, 30)).second)
        assertEquals(30L, core.peek("a"))
    }

    @Test
    fun `drop newest keeps the window and receipts the drop`() {
        val core = core(IngressPolicy(highWater = 1, overflow = Overflow.DropNewest))
        core.admit(env("a", 1, 0, 0, 5))
        val (change, admission) = core.admit(env("a", 1, 1, 0, 9))
        assertEquals(IngressAdmission.Dropped(IngressDropReason.Backpressure), admission)
        assertTrue(change.droppedReceipts)
        assertEquals(5L, core.peek("a"))
    }

    // -- Derives ----------------------------------------------------------

    @Test
    fun `readiness derives from lifecycle and freshness`() {
        val core = core(IngressPolicy(freshnessHorizon = 10))
        assertEquals(IngressReadiness.Unknown, core.readiness("a"))
        core.open("a", 1)
        assertEquals(IngressReadiness.Warming, core.readiness("a"))
        core.admit(env("a", 1, 0, 0, 1))
        assertEquals(IngressReadiness.Ready, core.readiness("a"))

        // Crossing the horizon is a readiness-only transition.
        assertEquals(
            listOf("a" to IngressScopeChange.readinessOnly),
            core.tick(50).scopes,
        )
        assertEquals(IngressReadiness.Stale, core.readiness("a"))
        // A further tick inside the same readiness dirties nothing.
        assertTrue(core.tick(60).isEmpty)
    }

    @Test
    fun `drain is a value only transition and empty drains dirty nothing`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 3))
        val (change, value) = core.drain("a")
        assertEquals(3L, value)
        assertEquals(listOf("a" to IngressScopeChange.valueOnly), change.scopes)
        val (again, empty) = core.drain("a")
        assertNull(empty)
        assertTrue(again.isEmpty)
        // Draining does not move the watermark: a drain is an egress, not an ack.
        assertEquals(0L, assertNotNull(core.view("a")).deliveredThrough)
    }

    @Test
    fun `suspend retains the watermark and reconnect replays the gap`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 1))
        core.admit(env("a", 1, 1, 0, 1))
        assertEquals(ReplayRequest(1, 2), core.suspend("a").second)
        assertEquals(IngressReadiness.Suspended, core.readiness("a"))
        // The coalesced window survives a disconnect; only readiness changed.
        assertEquals(2L, core.peek("a"))
        // Suspending twice is idempotent and dirties nothing.
        val (change, request) = core.suspend("a")
        assertTrue(change.isEmpty)
        assertNull(request)

        assertEquals(ReplayRequest(1, 2), core.reconnect("a", 1).second)
        assertEquals(IngressReadiness.Ready, core.readiness("a"))
    }

    @Test
    fun `reconnect at a higher generation discards the stale window`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 5))
        core.suspend("a")
        val (change, request) = core.reconnect("a", 3)
        assertEquals(ReplayRequest(3, 0), request)
        assertTrue(change.scopes.any { it.second.value && it.second.authority })
        assertNull(core.peek("a"))
    }

    @Test
    fun `errors deepen backoff and a delivery clears it`() {
        val core = core(IngressPolicy(retryBase = 10, retryCeiling = 25))
        core.open("a", 1)
        assertNull(core.retry("a"))

        core.fail("a", IngressError.TransportClosed)
        assertEquals(IngressRetry(1, 10, 0), core.retry("a"))
        core.fail("a", IngressError.TransportClosed)
        assertEquals(20L, assertNotNull(core.retry("a")).backoff)
        // Clamped, not doubled past the ceiling.
        core.fail("a", IngressError.TransportClosed)
        assertEquals(25L, assertNotNull(core.retry("a")).backoff)
        assertEquals(3, core.receipts(IngressReceiptChannel.Error).size)

        core.admit(env("a", 1, 0, 0, 1))
        assertNull(core.retry("a"))
    }

    @Test
    fun `a reconnect clears the error streak without a delivery`() {
        val core = core()
        core.open("a", 1)
        core.fail("a", IngressError.AuthorityLost)
        val (change, _) = core.reconnect("a", 1)
        assertTrue(change.scopes.any { it.second.retry })
        assertNull(core.retry("a"))
    }

    @Test
    fun `closed scopes admit nothing and claim no authority`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 1))
        core.close("a")
        assertNull(core.authority("a"))
        assertEquals(
            IngressAdmission.Dropped(IngressDropReason.ScopeClosed),
            core.admit(env("a", 1, 1, 0, 1)).second,
        )
        // Reopening a closed scope restarts its sequence space.
        core.open("a", 1)
        assertEquals(IngressAdmission.Accepted(0), core.admit(env("a", 1, 0, 0, 4)).second)
    }

    @Test
    fun `scopes are independent`() {
        val core = core()
        core.admit(env("a", 1, 0, 0, 1))
        val (change, _) = core.admit(env("b", 1, 0, 0, 2))
        assertEquals(1, change.scopes.size)
        assertEquals("b", change.scopes[0].first)
        core.close("b")
        assertEquals(IngressReadiness.Ready, core.readiness("a"))
        assertEquals(1L, core.peek("a"))
    }

    @Test
    fun `receipts are bounded and offsets stay monotone`() {
        val core = core(IngressPolicy(receiptCapacity = 2))
        for (seq in 0L until 4L) core.admit(env("a", 1, seq, 0, 1))
        val accepted = core.receipts(IngressReceiptChannel.Accepted)
        assertEquals(2, accepted.size)
        assertEquals(listOf(2L, 3L), accepted.map { it.offset })
    }

    @Test
    fun `a schedule offers a poll interval only without event delivery`() {
        assertNull(IngressSchedule.forKind(IngressTransportKind.EventChannel, 50).pollInterval)
        assertNull(IngressSchedule.forKind(IngressTransportKind.RpcTriggered, 50).pollInterval)
        assertEquals(
            50L,
            IngressSchedule.forKind(IngressTransportKind.BoundedPolling, 50).pollInterval,
        )
        // A zero interval would be an unbounded refresh loop.
        assertEquals(
            1L,
            IngressSchedule.forKind(IngressTransportKind.BoundedPolling, 0).pollInterval,
        )
    }

    // -- The single-threaded shell ----------------------------------------

    private fun cell(
        ctx: Context,
        policy: IngressPolicy = IngressPolicy(),
    ) = IngressCell<String, Long>(ctx, policy, sum())

    @Test
    fun `delivery is visible through the value reader`() {
        val ctx = Context()
        val ingress = cell(ctx)
        assertNull(ingress.value("a"))
        ingress.admit(env("a", 1, 0, 0, 5))
        assertEquals(5L, ingress.value("a"))
        ingress.admit(env("a", 1, 1, 0, 7))
        assertEquals(12L, ingress.value("a"))
        assertEquals(12L, ingress.drain("a"))
        assertNull(ingress.value("a"))
    }

    @Test
    fun `readiness and authority are derives of the same transitions`() {
        val ctx = Context()
        val ingress = cell(ctx, IngressPolicy(freshnessHorizon = 10))
        assertEquals(IngressReadiness.Unknown, ingress.readiness("a"))
        assertNull(ingress.authority("a"))

        ingress.open("a", 4)
        assertEquals(IngressReadiness.Warming, ingress.readiness("a"))
        assertEquals(IngressAuthority(4, null, 0), ingress.authority("a"))

        ingress.admit(env("a", 4, 0, 5, 1))
        assertEquals(IngressReadiness.Ready, ingress.readiness("a"))
        assertEquals(IngressAuthority(4, 0, 5), ingress.authority("a"))

        ingress.tick(100)
        assertEquals(IngressReadiness.Stale, ingress.readiness("a"))
    }

    @Test
    fun `a buffered envelope reruns no effect`() {
        val ctx = Context()
        val ingress = cell(ctx)
        ingress.open("a", 1)
        val observed = mutableListOf<Long?>()
        val effect =
            ctx.effect {
                observed += ingress.value("a", this)
                null
            }
        assertEquals(1, observed.size)

        // Out of order: nothing observable moved, so the value effect must not run.
        ingress.admit(env("a", 1, 2, 0, 4))
        ingress.admit(env("a", 1, 1, 0, 2))
        assertEquals(1, observed.size)

        // The delivery that closes the gap flushes all three as ONE value change.
        ingress.admit(env("a", 1, 0, 0, 1))
        assertEquals(listOf(null, 7L), observed)
        ctx.disposeEffect(effect)
    }

    @Test
    fun `a tick inside the horizon reruns no readiness effect`() {
        val ctx = Context()
        val ingress = cell(ctx, IngressPolicy(freshnessHorizon = 100))
        ingress.admit(env("a", 1, 0, 0, 1))
        var runs = 0
        val effect =
            ctx.effect {
                runs += 1
                ingress.readiness("a", this)
                null
            }
        assertEquals(1, runs)

        ingress.tick(50)
        assertEquals(1, runs, "a tick inside the horizon is not a change")
        ingress.tick(500)
        assertEquals(2, runs, "crossing the horizon is a change")
        ctx.disposeEffect(effect)
    }

    @Test
    fun `an error moves retry without touching the value`() {
        val ctx = Context()
        val ingress = cell(ctx)
        ingress.admit(env("a", 1, 0, 0, 9))
        var valueRuns = 0
        val effect =
            ctx.effect {
                valueRuns += 1
                ingress.value("a", this)
                null
            }
        ingress.fail("a", IngressError.TransportClosed)
        assertEquals(1, valueRuns)
        assertEquals(1, assertNotNull(ingress.retry("a")).attempt)
        assertEquals(9L, ingress.value("a"))
        ctx.disposeEffect(effect)
    }

    @Test
    fun `receipt channels are independent readers`() {
        val ctx = Context()
        val ingress = cell(ctx)
        ingress.admit(env("a", 2, 0, 0, 1))
        assertEquals(1, ingress.accepted().size)
        assertTrue(ingress.dropped().isEmpty())
        assertTrue(ingress.errors().isEmpty())

        // A fenced zombie shows up only on the dropped channel.
        ingress.admit(env("a", 1, 0, 0, 1))
        assertEquals(1, ingress.accepted().size)
        val dropped = ingress.dropped()
        assertEquals(1, dropped.size)
        assertEquals(
            IngressReceiptOutcome.Dropped(IngressDropReason.StaleGeneration),
            dropped[0].outcome,
        )

        ingress.fail("a", IngressError.DecodeFailed)
        assertEquals(1, ingress.errors().size)
        assertEquals(1, ingress.dropped().size)
    }

    @Test
    fun `the schedule derives from the transport and retunes live`() {
        val ctx = Context()
        val ingress = cell(ctx)
        assertNull(ingress.schedule().pollInterval)

        ingress.setTransport(IngressTransportKind.BoundedPolling)
        assertEquals(25L, ingress.schedule().pollInterval)
        ingress.setPollInterval(200)
        assertEquals(200L, ingress.schedule().pollInterval)

        ingress.setTransport(IngressTransportKind.RpcTriggered)
        assertNull(ingress.schedule().pollInterval)
    }

    @Test
    fun `pump admits a batch and requests replay for a surviving gap`() {
        val ctx = Context()
        val ingress = cell(ctx)
        val transport = InProcIngress<String, Long>(IngressTransportKind.EventChannel)
        transport.push(env("a", 1, 0, 0, 1))
        transport.push(env("a", 1, 2, 0, 4))

        val outcomes = ingress.pump(transport)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes[0].isDelivered)
        assertEquals(IngressAdmission.Buffered(1), outcomes[1])
        assertEquals(listOf("a" to ReplayRequest(1, 1)), transport.replays())

        // The replay closes the gap, and a second pump asks for nothing more.
        transport.push(env("a", 1, 1, 0, 2))
        ingress.pump(transport)
        assertEquals(7L, ingress.value("a"))
        assertEquals(1, transport.replays().size)
    }

    @Test
    fun `a polling transport cannot serve a replay`() {
        val ctx = Context()
        val ingress = cell(ctx)
        val transport = InProcIngress<String, Long>(IngressTransportKind.BoundedPolling)
        transport.push(env("a", 1, 3, 0, 1))
        ingress.pump(transport)
        assertTrue(transport.replays().isEmpty())
    }

    @Test
    fun `scopes do not invalidate each other`() {
        val ctx = Context()
        val ingress = cell(ctx)
        ingress.admit(env("a", 1, 0, 0, 1))
        var runs = 0
        val effect =
            ctx.effect {
                runs += 1
                ingress.value("a", this)
                null
            }
        assertEquals(1, runs)
        ingress.admit(env("b", 1, 0, 0, 2))
        ingress.close("b")
        assertEquals(1, runs)
        assertEquals(1L, ingress.value("a"))
        ctx.disposeEffect(effect)
    }

    // -- One frontier walk per admission, on every flavor -----------------

    /**
     * The gate that a per-root `invalidateSlots` would fail: a generation handoff
     * dirties the scope's value AND its authority, and clearing them one at a time
     * lets a correlated reader observe `new value, old authority` — the partial
     * fan-out a handoff must never expose.
     */
    @Test
    fun `a generation handoff never shows a new value with stale authority`() {
        val syncCtx = Context()
        val sync = IngressCell<String, Long>(syncCtx, IngressPolicy(), sum())
        sync.admit(env("a", 1, 0, 0, 5))
        val syncSeen = mutableListOf<Pair<Long?, Long?>>()
        val syncEffect =
            syncCtx.effect {
                syncSeen += sync.value("a", this) to sync.authority("a", this)?.generation
                null
            }
        sync.admit(env("a", 2, 0, 0, 9))
        assertEquals(listOf<Pair<Long?, Long?>>(5L to 1L, 9L to 2L), syncSeen)
        syncCtx.disposeEffect(syncEffect)

        val threadCtx = ThreadSafeContext()
        val thread = ThreadSafeIngressCell<String, Long>(threadCtx, IngressPolicy(), sum())
        thread.admit(env("a", 1, 0, 0, 5))
        val threadSeen = mutableListOf<Pair<Long?, Long?>>()
        threadCtx.effect {
            threadSeen += thread.value("a") to thread.authority("a")?.generation
            null
        }
        thread.admit(env("a", 2, 0, 0, 9))
        assertEquals(listOf<Pair<Long?, Long?>>(5L to 1L, 9L to 2L), threadSeen)

        runBlocking {
            val asyncCtx = AsyncContext()
            try {
                val async = AsyncIngressCell<String, Long>(asyncCtx, IngressPolicy(), sum())
                async.admit(env("a", 1, 0, 0, 5))
                val asyncSeen = mutableListOf<Pair<Long?, Long?>>()
                asyncCtx.effectAsync {
                    asyncSeen += async.value("a", this) to async.authority("a", this)?.generation
                    null
                }
                asyncCtx.settle()
                async.admit(env("a", 2, 0, 0, 9))
                asyncCtx.settle()
                assertEquals(listOf<Pair<Long?, Long?>>(5L to 1L, 9L to 2L), asyncSeen)
            } finally {
                asyncCtx.dispose()
            }
        }
    }

    @Test
    fun `the thread safe flavor serializes concurrent admissions`() {
        val ctx = ThreadSafeContext()
        val ingress = ThreadSafeIngressCell<String, Long>(ctx, IngressPolicy(), sum())
        val producers = 4
        val each = 50
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(producers)
        val futures =
            (0 until producers).map { producer ->
                pool.submit {
                    start.await()
                    repeat(each) { index ->
                        ingress.admit(env("k$producer", 1, index.toLong(), 0, 1))
                    }
                }
            }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "concurrent admissions deadlocked")
        futures.forEach { it.get() }
        for (producer in 0 until producers) {
            assertEquals(each.toLong(), ingress.value("k$producer"))
            assertEquals(
                (each - 1).toLong(),
                assertNotNull(ingress.view("k$producer")).deliveredThrough,
            )
        }
    }
}
