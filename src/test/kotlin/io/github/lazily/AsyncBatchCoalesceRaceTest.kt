package io.github.lazily

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deterministic reproduction of the #lzsignaleager clause-3 batch-coalescing race
 * (#lzcellkernel). A signal (eager memo slot + puller effect) must materialize
 * EXACTLY ONCE per batch, no matter how many writes the batch carries.
 *
 * The compute [yield]s after bumping its counter, widening the window in which a
 * puller run's compute is in-flight. Under the pre-fix per-cell batch flush that
 * lets a second write's invalidation supersede the first run's compute AFTER its
 * counter already fired, so the count scales past once-per-batch. With the
 * coalesced batch (one flush at the boundary) the count is exactly one per batch
 * under every schedule.
 */
class AsyncBatchCoalesceRaceTest {
    @Test
    fun signalMaterializesOncePerBatchUnderRace() = runBlocking {
        repeat(200) { iter ->
            val ctx = AsyncContext()
            val count = AtomicInteger(0)
            try {
                val a = ctx.cell(1)
                val b = ctx.cell(2)
                val sig = ctx.signalAsync {
                    count.incrementAndGet()
                    val av = getCell(a)
                    yield()
                    val bv = getCell(b)
                    av + bv
                }
                ctx.settle()
                assertEquals(1, count.get(), "iter=$iter baseline: one compute at creation")

                // Batch with two writes -> exactly one additional compute.
                ctx.batch {
                    it.setCell(a, 10)
                    it.setCell(b, 20)
                }
                ctx.settle()
                assertEquals(30, ctx.getAsync(sig.slot))
                assertEquals(2, count.get(), "iter=$iter: two writes, ONE additional compute")

                // Batch with three writes (incl. repeat) -> still one more compute.
                ctx.batch {
                    it.setCell(a, 100)
                    it.setCell(b, 200)
                    it.setCell(a, 101)
                }
                ctx.settle()
                assertEquals(301, ctx.getAsync(sig.slot))
                assertEquals(3, count.get(), "iter=$iter: three writes, ONE additional compute")
            } finally {
                ctx.dispose()
            }
        }
    }
}
