package io.github.lazily

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The transport attachment (`#lzegress`): one Effect per incarnation projects
 * `pending` to the transport and `inflight`, an acknowledgement reopens the window
 * reactively, and a reconnect leaves the old attachment inert. The canonical corpus
 * describes the delivery authority beneath any transport, so this is the unit
 * coverage of the attachment itself, on every flavor (mirrors lazily-rs
 * `src/egress.rs` / `thread_safe_egress.rs` / `async_egress.rs` tests).
 */
class EgressTest {
    private class Recorder(
        var failNext: Boolean = false,
    ) : EgressTransport<Int> {
        val sent: MutableList<Long> = Collections.synchronizedList(ArrayList())

        override fun send(envelope: EgressEnvelope<Int>): Boolean {
            if (failNext) {
                failNext = false
                return false
            }
            sent += envelope.sequence
            return true
        }
    }

    @Test
    fun `one effect projects pending to transport and inflight`() {
        val ctx = Context()
        val egress = EgressCell<Int>(ctx, 1)
        val transport = Recorder()
        egress.attachTransport(transport)
        egress.enqueue(10)
        egress.enqueue(20)
        assertEquals(listOf(0L, 1L), transport.sent)
        assertTrue(egress.pending().isEmpty())
        assertEquals(2, egress.inflight().size)
    }

    @Test
    fun `reconnect makes the old attachment inert`() {
        val ctx = Context()
        val egress = EgressCell<Int>(ctx, 3)
        val old = Recorder()
        egress.attachTransport(old)
        egress.enqueue(10)
        egress.reconnect(4)
        egress.enqueue(20)
        assertEquals(listOf(0L), old.sent)
        val fresh = Recorder()
        egress.attachTransport(fresh)
        assertEquals(listOf(0L, 1L), fresh.sent)
        assertEquals(listOf(4L, 4L), egress.inflight().map { it.generation })
    }

    @Test
    fun `ack reopens the window without a request-ack handshake`() {
        val ctx = Context()
        val egress = EgressCell<Int>(ctx, 1, EgressPolicy(inflightLimit = 1))
        val transport = Recorder()
        egress.attachTransport(transport)
        egress.enqueue(10)
        egress.enqueue(20)
        assertEquals(listOf(0L), transport.sent)
        assertEquals(1, egress.pending().size)
        assertEquals(EgressAck.Advanced(0), egress.ack(1, 0))
        assertEquals(listOf(0L, 1L), transport.sent)
        assertTrue(egress.pending().isEmpty())
    }

    @Test
    fun `a failed send parks the record instead of spinning`() {
        val ctx = Context()
        val egress = EgressCell<Int>(ctx, 1)
        val transport = Recorder(failNext = true)
        egress.attachTransport(transport)
        egress.enqueue(10)
        assertEquals(emptyList(), transport.sent)
        assertEquals(EgressRetry(0, 1, 10, exhausted = false), egress.retry())
        assertTrue(egress.pending().isEmpty() && egress.inflight().isEmpty())
        assertEquals(EgressRetryAction.Scheduled(0), egress.retryNow(1, 0))
        assertEquals(listOf(0L), transport.sent)
        assertEquals(2, egress.inflight().single().attempt)
    }

    @Test
    fun `reader kinds invalidate independently`() {
        val ctx = Context()
        val egress = EgressCell<Int>(ctx, 1)
        var pendingRuns = 0
        var ackRuns = 0
        ctx.effect {
            get(egress.pendingHandle())
            pendingRuns++
            null
        }
        ctx.effect {
            get(egress.ackedThroughHandle())
            ackRuns++
            null
        }
        egress.enqueue(1)
        assertEquals(2, pendingRuns)
        assertEquals(1, ackRuns)
    }

    @Test
    fun `thread-safe attachment drives the shared projection`() {
        val ctx = ThreadSafeContext()
        val egress = ThreadSafeEgressCell<Int>(ctx, 1, EgressPolicy(inflightLimit = 1))
        val transport = Recorder()
        egress.attachTransport(transport)
        egress.enqueue(7)
        egress.enqueue(8)
        assertEquals(listOf(0L), transport.sent)
        assertEquals(1, egress.inflight().size)
        egress.ack(1, 0)
        assertEquals(listOf(0L, 1L), transport.sent)
        egress.reconnect(2)
        egress.enqueue(9)
        assertEquals(listOf(0L, 1L), transport.sent, "the generation-1 attachment is fenced")
    }

    @Test
    fun `thread-safe producers assign unique monotone sequences`() {
        val ctx = ThreadSafeContext()
        val egress = ThreadSafeEgressCell<Int>(ctx, 1)
        val threads = (0 until 4).map { t -> Thread { repeat(250) { egress.enqueue(t) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(1000, egress.nextSequence)
        assertEquals((0L until 1000L).toList(), egress.pending().map { it.sequence })
    }

    @Test
    fun `async attachment awaits the transport and drives the projection`() {
        val ctx = AsyncContext()
        try {
            val egress = AsyncEgressCell<Int>(ctx, 1)
            val sent = Collections.synchronizedList(ArrayList<Long>())
            egress.attachTransport { envelope ->
                kotlinx.coroutines.yield()
                sent += envelope.sequence
                true
            }
            egress.enqueue(7)
            kotlinx.coroutines.runBlocking { ctx.settle() }
            assertEquals(listOf(0L), sent)
            assertEquals(1, egress.inflight().size)
            assertTrue(egress.pending().isEmpty())
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `an inadmissible policy is refused`() {
        assertEquals(
            EgressConfigError.ZeroInflightLimit,
            assertFailsWith<EgressConfigException> { EgressCore<Int>(1, EgressPolicy(inflightLimit = 0)) }.error,
        )
        assertEquals(
            EgressConfigError.RetryCeilingBelowBase,
            assertFailsWith<EgressConfigException> { EgressCore<Int>(1, EgressPolicy(retryBase = 5, retryCeiling = 4)) }.error,
        )
    }
}
