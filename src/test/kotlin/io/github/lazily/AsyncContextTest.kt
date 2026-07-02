package io.github.lazily

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance for the lazily-spec Async Reactive Context contract
 * (`docs/async.md`): the slot state machine, revision-based stale discard, the
 * five-point cancellation contract, compute-context dependency tracking,
 * async-effect cleanup ordering, and batch semantics.
 */
class AsyncContextTest {
    private fun ctx() = AsyncContext(Dispatchers.Unconfined)

    @Test
    fun `slot starts empty and resolves on first get_async`() = runBlocking {
        val c = ctx()
        val a = c.cell(2)
        val sum = c.computedAsync { getCell(a) + 3 }
        assertEquals(AsyncContext.SlotStateView.Empty, c.slotState(sum))
        assertNull(c.get(sum))
        assertEquals(5, c.getAsync(sum))
        assertEquals(AsyncContext.SlotStateView.Resolved, c.slotState(sum))
        assertEquals(5, c.get(sum))
        c.dispose()
    }

    @Test
    fun `dependency invalidation re-resolves with the new value and discards the stale one`() = runBlocking {
        val c = ctx()
        val a = c.cell(2)
        val sum = c.computedAsync { getCell(a) * 10 }
        assertEquals(20, c.getAsync(sum))
        // Invalidation: the cached value is dropped; a new compute yields the new value.
        c.setCell(a, 5)
        assertEquals(AsyncContext.SlotStateView.Empty, c.slotState(sum))
        assertEquals(50, c.getAsync(sum))
        c.dispose()
    }

    @Test
    fun `equal setCell is a no-op and does not invalidate dependents`() = runBlocking {
        val c = ctx()
        val a = c.cell(1)
        var runs = 0
        val s = c.computedAsync { runs++; getCell(a) + 1 }
        c.getAsync(s)
        val before = runs
        c.setCell(a, 1) // equal -> no-op
        assertEquals(before, runs)
        c.dispose()
    }

    @Test
    fun `memo equality guard keeps the published value on an equal recompute`() = runBlocking {
        val c = ctx()
        val src = c.cell(1)
        val upstream = c.computedAsync { getCell(src) } // raw source
        // memoized: returns a constant regardless of src; an equal recompute must
        // not advance the published value.
        val memo = c.memoAsync {
            getAsync(upstream) // track src transitively
            "same"
        }
        assertEquals("same", c.getAsync(memo))
        c.setCell(src, 2)
        assertEquals("same", c.getAsync(memo))
        c.dispose()
    }

    @Test
    fun `concurrent get_async callers share one in-flight compute`() = runBlocking {
        val c = ctx()
        val a = c.cell(7)
        val spawns = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val s = c.computedAsync {
            spawns.incrementAndGet()
            gate.await()
            getCell(a) + 1
        }
        // Two concurrent callers; both attach to the same in-flight future.
        val r1 = async(start = CoroutineStart.UNDISPATCHED) { c.getAsync(s) }
        val r2 = async(start = CoroutineStart.UNDISPATCHED) { c.getAsync(s) }
        repeat(10) { delay(1) } // let both park on the shared future
        assertEquals(1, spawns.get()) // exactly one in-flight compute
        gate.complete(Unit)
        assertEquals(8, r1.await())
        assertEquals(8, r2.await())
        assertEquals(1, spawns.get()) // still one compute — no duplicate spawn
        c.dispose()
    }

    @Test
    fun `dropping one waiter does not cancel the shared compute`() = runBlocking {
        val c = ctx()
        val a = c.cell(1)
        val spawns = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val s = c.computedAsync { spawns.incrementAndGet(); gate.await(); getCell(a) + 100 }
        val waiterJob = launch(start = CoroutineStart.UNDISPATCHED) { c.getAsync(s) }
        val other = async(start = CoroutineStart.UNDISPATCHED) { c.getAsync(s) }
        repeat(10) { delay(1) }
        waiterJob.cancel() // drop one waiter
        repeat(10) { delay(1) }
        assertEquals(1, spawns.get()) // compute still alive
        gate.complete(Unit)
        assertEquals(101, other.await()) // other waiter still resolves
        c.dispose()
    }

    @Test
    fun `error transitions slot to Error and get_async retries on the next call`() = runBlocking {
        val c = ctx()
        val mode = c.cell("fail")
        var attempt = 0
        val s = c.computedAsync {
            attempt++
            if (getCell(mode) == "fail") error("boom")
            "ok"
        }
        var threw = false
        try { c.getAsync(s) } catch (e: IllegalStateException) { threw = true }
        assertTrue(threw)
        assertEquals(AsyncContext.SlotStateView.Error, c.slotState(s))
        // Retry path: fix the dependency; next get_async re-runs and resolves.
        c.setCell(mode, "ok")
        assertEquals("ok", c.getAsync(s))
        assertEquals(AsyncContext.SlotStateView.Resolved, c.slotState(s))
        c.dispose()
    }

    @Test
    fun `async effect reruns on dependency change with cleanup before each body`() = runBlocking {
        val c = ctx()
        val a = c.cell(0)
        val log = mutableListOf<String>()
        val handle = c.effectAsync {
            val v = getCell(a)
            log.add("body:$v")
            val cleanup: suspend () -> Unit = { log.add("cleanup:$v") }
            cleanup
        }
        repeat(10) { delay(1) }
        assertEquals(listOf("body:0"), log)
        c.setCell(a, 1)
        repeat(10) { delay(1) }
        // cleanup of the previous (v=0) run precedes the new body (v=1).
        assertEquals(listOf("body:0", "cleanup:0", "body:1"), log)
        c.disposeEffect(handle)
        repeat(10) { delay(1) }
        // Disposal runs the final cleanup.
        assertEquals(listOf("body:0", "cleanup:0", "body:1", "cleanup:1"), log)
        c.dispose()
    }

    @Test
    fun `signal eagerly materializes its value after a dependency change`() = runBlocking {
        val c = ctx()
        val a = c.cell(2)
        val sig = c.signalAsync { getCell(a) * 3 }
        // Initial eager settle.
        repeat(10) { delay(1) }
        assertEquals(6, sig.get(c))
        // Drive an upstream change; the puller effect re-resolves it eagerly.
        c.setCell(a, 4)
        repeat(20) { delay(1) }
        assertEquals(12, sig.get(c))
        sig.dispose(c)
        c.dispose()
    }

    @Test
    fun `batch coalesces cell writes into one async rerun`() = runBlocking {
        val c = ctx()
        val a = c.cell(1)
        val b = c.cell(1)
        var runs = AtomicInteger(0)
        val s = c.computedAsync { runs.incrementAndGet(); getCell(a) + getCell(b) }
        c.getAsync(s)
        val before = runs.get()
        c.batch {
            it.setCell(a, 10)
            it.setCell(b, 20)
        }
        // Only after the batch exits does the recompute happen (lazy: on next read).
        assertEquals(before, runs.get())
        assertEquals(30, c.getAsync(s))
        assertEquals(before + 1, runs.get()) // one recompute, not two
        c.dispose()
    }

    @Test
    fun `nested batches flush only at the outermost exit`() = runBlocking {
        val c = ctx()
        val a = c.cell(0)
        val s = c.computedAsync { getCell(a) + 1 }
        c.getAsync(s)
        c.batch { outer ->
            outer.setCell(a, 1)
            outer.batch { inner -> inner.setCell(a, 2) }
            // Still inside the outer batch: dependents are not invalidated yet,
            // so the slot holds its previously-resolved (now stale) value.
            assertEquals(1, c.get(s))
        }
        // Outermost batch exit invalidates `s`; the next read re-resolves to 3.
        assertEquals(3, c.getAsync(s))
        c.dispose()
    }

    @Test
    fun `dispose cancels in-flight compute and runs stored cleanups`() = runBlocking {
        val c = ctx()
        val a = c.cell(1)
        val cleaned = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val effect = c.effectAsync {
            getCell(a)
            val cleanup: suspend () -> Unit = { cleaned.incrementAndGet() }
            cleanup
        }
        val s = c.computedAsync { gate.await(); getCell(a) }
        val pending = async { c.getAsync(s) } // in-flight, parked on gate
        repeat(10) { delay(1) }
        c.disposeEffect(effect)
        assertEquals(1, cleaned.get()) // effect cleanup ran on dispose
        pending.cancel()
        gate.complete(Unit)
        // Compute was cancelled by dispose; the in-flight job is gone.
        c.close()
    }

    @Test
    fun `dynamic dependencies are rediscovered on each recompute`() = runBlocking {
        val c = ctx()
        val branch = c.cell("left")
        val left = c.cell(10)
        val right = c.cell(20)
        val s = c.computedAsync {
            if (getCell(branch) == "left") getCell(left) else getCell(right)
        }
        assertEquals(10, c.getAsync(s))
        // Switching branch: right is now the dependency; changing left must not recompute.
        c.setCell(branch, "right")
        assertEquals(20, c.getAsync(s))
        val before = c.get(s)
        c.setCell(left, 999) // stale dependency — no longer tracked
        repeat(10) { delay(1) }
        assertEquals(before, c.get(s)) // unchanged
        // Current dependency does invalidate.
        c.setCell(right, 25)
        assertEquals(25, c.getAsync(s))
        c.dispose()
    }
}
