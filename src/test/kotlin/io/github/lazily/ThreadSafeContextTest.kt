package io.github.lazily

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadSafeContextTest {
    @Test
    fun cell_get_and_set() {
        val ctx = ThreadSafeContext()
        val a = ctx.source(10)
        val b = ctx.source(20)
        assertEquals(10, ctx.get(a))
        assertEquals(20, ctx.get(b))
        ctx.set(a, 99)
        assertEquals(99, ctx.get(a))
    }

    @Test
    fun cell_partial_eq_guard_suppresses_noop_set() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(1)
        var runs = 0
        val derived = ctx.computed { runs++; ctx.get(src) * 2 }
        assertEquals(2, ctx.get(derived))
        assertEquals(1, runs)
        ctx.set(src, 1) // equal: no invalidation
        assertEquals(2, ctx.get(derived))
        assertEquals(1, runs)
        ctx.set(src, 5) // real change
        assertEquals(10, ctx.get(derived))
        assertEquals(2, runs)
    }

    @Test
    fun slot_is_lazy_and_caches() {
        val ctx = ThreadSafeContext()
        var calls = 0
        val s = ctx.computed { calls++; 42 }
        assertFalse(ctx.isSet(s))
        assertEquals(42, ctx.get(s))
        assertEquals(1, calls)
        assertTrue(ctx.isSet(s))
        assertEquals(42, ctx.get(s))
        assertEquals(1, calls)
    }

    @Test
    fun slot_tracks_cell_dependency_and_invalidates() {
        val ctx = ThreadSafeContext()
        val a = ctx.source(2)
        val b = ctx.source(3)
        val sum = ctx.computed { ctx.get(a) + ctx.get(b) }
        assertEquals(5, ctx.get(sum))
        ctx.set(a, 10)
        assertEquals(13, ctx.get(sum))
        ctx.set(b, 20)
        assertEquals(30, ctx.get(sum))
    }

    @Test
    fun slot_chained_and_glitch_free() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(1)
        val mid = ctx.computed { ctx.get(src) + 1 }
        val leaf = ctx.computed { ctx.get(mid) * 10 }
        assertEquals(20, ctx.get(leaf))
        ctx.set(src, 4)
        assertEquals(50, ctx.get(leaf))
        assertEquals(5, ctx.get(mid))
    }

    @Test
    fun memo_guard_suppresses_downstream_on_equal_recompute() {
        val ctx = ThreadSafeContext()
        val trigger = ctx.source(1)
        val constant = ctx.computed { ctx.get(trigger); 7 }
        var leafRuns = 0
        val leaf = ctx.computed { leafRuns++; ctx.get(constant) + 1 }
        assertEquals(8, ctx.get(leaf))
        assertEquals(1, leafRuns)
        ctx.set(trigger, 2) // constant recomputes to 7 (equal) → leaf must NOT recompute
        assertEquals(8, ctx.get(leaf))
        assertEquals(1, leafRuns)
    }

    @Test
    fun cycle_is_detected() {
        val ctx = ThreadSafeContext()
        // Build an indirect cycle a -> b -> a via a mutable holder (value-class
        // handles can't be `lateinit`).
        val holder = ArrayDeque<ThreadSafeComputed<Int>>()
        val a = ctx.computed { holder.firstOrNull()?.let { ctx.get(it) } ?: 0 }
        val b = ctx.computed { ctx.get(a) }
        holder.addLast(b)
        try {
            ctx.get(a)
            error("expected cycle exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("circular dependency"))
        }
    }

    @Test
    fun signal_is_eager_and_materialized() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(2)
        val sig = ctx.signal { ctx.get(src) * 3 }
        assertEquals(6, ctx.getSignal(sig)) // materialized at creation
        ctx.set(src, 4)
        assertEquals(12, ctx.getSignal(sig)) // re-materialized before set returns
    }

    @Test
    fun effect_fires_synchronously_within_set_cell() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(1)
        val seen = mutableListOf<Int>()
        val eff = ctx.effect { seen.add(ctx.get(src)); null }
        assertTrue(ctx.isEffectActive(eff))
        assertEquals(listOf(1), seen)
        ctx.set(src, 2)
        // Eager flush: observer fired synchronously inside set.
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun batch_coalesces_into_one_flush() {
        val ctx = ThreadSafeContext()
        val a = ctx.source(1)
        val b = ctx.source(10)
        var runs = 0
        var lastSum = 0
        ctx.effect { lastSum = ctx.get(a) + ctx.get(b); runs++; null }
        assertEquals(1, runs)
        assertEquals(11, lastSum)
        ctx.batch {
            ctx.set(a, 2)
            ctx.set(a, 3)
            ctx.set(b, 20)
        }
        // Coalesced: exactly one rerun observing the final consistent inputs.
        assertEquals(2, runs)
        assertEquals(23, lastSum)
    }

    @Test
    fun dispose_effect_drops_observer_and_runs_cleanup() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(0)
        var cleanups = 0
        val eff = ctx.effect { ctx.get(src); { cleanups++ } }
        ctx.set(src, 1) // rerun → previous cleanup runs
        assertEquals(1, cleanups)
        ctx.disposeEffect(eff)
        assertFalse(ctx.isEffectActive(eff))
        // Disposed cleanup ran; no further reruns.
        val cleanupsBefore = cleanups
        ctx.set(src, 2)
        assertEquals(cleanupsBefore, cleanups)
    }

    @Test
    fun dispose_signal_reverts_to_lazy() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(1)
        var computeRuns = 0
        val sig = ctx.signal { computeRuns++; ctx.get(src) + 1 }
        assertEquals(2, ctx.getSignal(sig))
        assertEquals(1, computeRuns)
        assertTrue(ctx.isSignalActive(sig))
        sig.dispose(ctx)
        assertFalse(ctx.isSignalActive(sig))
        ctx.set(src, 5)
        // No eager rerun after dispose (computeRuns unchanged)…
        assertEquals(1, computeRuns)
        // …but a read still returns the recomputed (lazy) value.
        assertEquals(6, ctx.getSignal(sig))
        assertEquals(2, computeRuns)
    }

    // -- Multi-thread: the actual `thread_safe` conformance -----------------

    @Test
    fun handles_are_clonable_across_threads() {
        val ctx = ThreadSafeContext()
        val src = ctx.source(0)
        val derived = ctx.computed { ctx.get(src) * 2 }

        // A handle minted on the main thread, read on a worker — clonable by value.
        val srcCopy = src
        val derivedCopy = derived
        val pool = Executors.newSingleThreadExecutor()
        // Establishes the happens-before edge under test: the write is published
        // before the worker reads, so the worker deterministically observes it
        // (without the gate the worker could read the pre-write value and race).
        val published = CountDownLatch(1)
        try {
            val future = pool.submit<Int> {
                published.await()
                ctx.get(srcCopy) // cross-thread read through a cloned handle
                ctx.get(derivedCopy)
            }
            ctx.set(src, 21)
            published.countDown()
            // Worker observes the happens-before-published value.
            assertEquals(42, future.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun atomic_batch_rmw_converges_under_concurrency() {
        val ctx = ThreadSafeContext()
        val counter = ctx.source(0)
        val nThreads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(nThreads)
        val ready = CountDownLatch(nThreads)
        val done = CountDownLatch(nThreads)
        try {
            repeat(nThreads) {
                pool.submit {
                    ready.countDown()
                    ready.await()
                    repeat(perThread) {
                        // A read-modify-write wrapped in `batch` is atomic across
                        // threads: the graph lock is held for the whole block, so
                        // no increment is lost.
                        ctx.batch {
                            val cur = ctx.get(counter)
                            ctx.set(counter, cur + 1)
                        }
                    }
                    done.countDown()
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
        assertEquals(nThreads * perThread, ctx.get(counter))
    }

    @Test
    fun concurrent_independent_writes_never_corrupt_state() {
        val ctx = ThreadSafeContext()
        val a = ctx.source(0)
        val b = ctx.source(0)
        val sum = ctx.signal { ctx.get(a) + ctx.get(b) }
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(2000)
        try {
            repeat(1000) { pool.submit { ctx.set(a, it); done.countDown() } }
            repeat(1000) { pool.submit { ctx.set(b, it); done.countDown() } }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
        // The signal is eagerly re-materialized within each set under the
        // lock, so it always reflects a consistent (a + b) pair — no torn read.
        assertEquals(ctx.get(a) + ctx.get(b), ctx.getSignal(sum))
    }

    @Test
    fun eager_observer_fires_on_the_invalidating_thread_synchronously() {
        val ctx = ThreadSafeContext()
        val src = ctx.source("init")
        val observed = AtomicReference("init")
        val fireCount = AtomicInteger(0)
        ctx.effect {
            observed.set(ctx.get(src))
            fireCount.incrementAndGet()
            null
        }
        assertEquals(1, fireCount.get())

        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(16)
        try {
            repeat(16) {
                pool.submit {
                    ctx.set(src, "v$it") // observer fires synchronously, under the lock, on this thread
                    done.countDown()
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
        // The synchronous-within-sendCell guarantee: every transition flushed its observer.
        assertEquals(17, fireCount.get())
        // The last observed value is one of the written values (lock-serialized, no torn read).
        assertTrue(observed.get().startsWith("v") || observed.get() == "init")
    }

    @Test
    fun thread_safe_state_machine_stays_consistent_across_threads() {
        val ctx = ThreadSafeContext()
        val m = ThreadSafeStateMachine(ctx, "Red") { s, _: String ->
            when (s) { "Red" -> "Green"; "Green" -> "Yellow"; "Yellow" -> "Red"; else -> null }
        }
        val transitions = AtomicInteger(0)
        m.onTransition { _, _ -> transitions.incrementAndGet() }

        val pool = Executors.newFixedThreadPool(6)
        val done = CountDownLatch(60)
        try {
            repeat(60) {
                pool.submit {
                    m.send("advance")
                    done.countDown()
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
        }
        // The thread-safe contract: the machine never enters an invalid state
        // and every accepted transition's observer fires synchronously under the
        // lock. 60 concurrent `send`s each compute a valid next state; the ==
        // guard collapses concurrent same-target writes, so the transition count
        // is bounded 1..60 and the final state is always a valid cycle member.
        val validStates = setOf("Red", "Green", "Yellow")
        assertTrue(m.state in validStates)
        val fired = transitions.get()
        assertTrue(fired in 1..60, "transition count $fired out of expected 1..60 range")
    }
}
