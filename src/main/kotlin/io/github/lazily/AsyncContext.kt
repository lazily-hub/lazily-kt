package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.withLock

/**
 * An async reactive graph — the Kotlin counterpart of `lazily-rs::AsyncContext`
 * and the [`lazily-spec`][spec] Async Reactive Context contract.
 *
 * It is a **separate** reactive surface from the synchronous [Context]: futures
 * introduce in-flight state, cancellation, stale completion, and dependency
 * tracking across suspension points that the synchronous graph does not have.
 * It is **compute, not protocol** — only resolved slot values cross IPC/FFI as
 * ordinary cell payloads.
 *
 * Conformance (see `lazily-spec/docs/async.md`):
 * - The async slot state machine `Empty` / `Computing` / `Resolved` / `Error`,
 *   including the stale `Computing → Computing` discard, is implemented exactly.
 * - Revision tracking discards every stale completion; a stale value is never
 *   published.
 * - All five cancellation properties hold, and [dispose] awaits cleanups.
 * - [getAsync] re-resolves through both benign-race windows without throwing.
 * - Dependencies are tracked through the [AsyncComputeContext] (not a
 *   thread-local) and registered **before** the awaited read.
 * - Async effect reruns are serialized, cleanup-before-body ordered, and
 *   executor-scheduled rather than inline.
 * - [batch] is synchronous at the mutation boundary; async reruns fire only
 *   after the outermost batch exits.
 *
 * User compute/effect/cleanup coroutines MUST be cancellation-safe: a hard
 * clear, invalidation, or [dispose] cancels in-flight work at an `.await`
 * boundary.
 *
 * [spec]: https://github.com/lazily-hub/lazily-spec/blob/main/docs/async.md
 */
private const val INITIAL_NODE_CAPACITY = 16

class AsyncContext(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    // #lzktasynccontextmodernize: ReentrantLock + flat Array arena, mirroring
    // ThreadSafeContext (ReentrantLock) and Context (dense `Array<Node?>`). The
    // former `synchronized(Any())` + `HashMap<Int, AsyncNode>` paid both a JVM
    // monitor tax and per-node HashMap boxing on every op; the flat arena
    // indexes directly by id with no autoboxing, and ReentrantLock matches the
    // thread-safe counterpart.
    private val lock = ReentrantLock()
    private var nextId = 1
    private val freeIds: ArrayDeque<Int> = ArrayDeque()
    private var batchDepth = 0
    private val batchedCells = LinkedHashSet<Int>()
    private val pendingEffects = ArrayDeque<Int>()
    private val scheduledEffects = LinkedHashSet<Int>()
    private var nodes: Array<AsyncNode?> = arrayOfNulls(INITIAL_NODE_CAPACITY)

    // -- Handles ----------------------------------------------------------

    /** A mutable input cell — the synchronous input layer. */
    inner class AsyncCellHandle<T> internal constructor(internal val id: Int)

    /** A computed/memoized async slot. */
    inner class AsyncSlotHandle<T> internal constructor(internal val id: Int)

    /** An async effect handle. */
    inner class AsyncEffectHandle internal constructor(internal val id: Int)

    /** An eager async derived value (memo slot + puller effect). */
    inner class AsyncSignalHandle<T> internal constructor(
        internal val slot: AsyncSlotHandle<T>,
        internal val effect: AsyncEffectHandle,
    ) {
        /** Snapshot the value if resolved, else null. */
        fun get(ctx: AsyncContext): Any? = ctx.doGet(slot.id)

        /** Await the up-to-date value, driving recomputation if needed. */
        suspend fun getAsync(ctx: AsyncContext): Any? = ctx.doGetAsync(slot.id)

        /** Stop eager recomputation; the backing value stays readable (lazy). */
        suspend fun dispose(ctx: AsyncContext) = ctx.disposeEffect(effect)
    }

    // -- Lifecycle --------------------------------------------------------

    /**
     * Cancel all in-flight computations, run stored effect cleanups, and await
     * their completion. Satisfies cancellation contract #4. The [AsyncContext]
     * is unusable afterwards.
     */
    suspend fun dispose() {
        val cleanups = ArrayList<suspend () -> Unit>()
        val jobs = ArrayList<Job>()
        locked {
            for (node in nodes) {
                if (node is AsyncNode.Effect) {
                    node.cleanup?.let { cleanups.add(it) }
                    node.runJob?.let { jobs.add(it) }
                }
                if (node is AsyncNode.Slot) {
                    node.job?.let { jobs.add(it) }
                }
            }
            pendingEffects.clear()
            scheduledEffects.clear()
            nodes = arrayOfNulls(INITIAL_NODE_CAPACITY)
            nextId = 1
            freeIds.clear()
        }
        for (cleanup in cleanups) {
            try { cleanup() } catch (_: Throwable) {}
        }
        scope.coroutineContext.job.cancelAndJoin()
        for (job in jobs) {
            try { job.join() } catch (_: Throwable) {}
        }
    }

    /** Synchronous disposal (blocks on [dispose]). */
    override fun close() = runBlocking { dispose() }

    // -- Internals: lock + id arena ---------------------------------------

    private inline fun <T> locked(action: () -> T): T = lock.withLock(action)

    /**
     * Allocate a dense id and grow the [nodes] arena on overflow, mirroring
     * `Context.allocId` / `ThreadSafeContext.allocId`. ids are recycled LIFO
     * via [freeIds] (mirrors the lazily-rs free-list).
     */
    private fun allocId(): Int {
        val id = freeIds.removeLastOrNull() ?: nextId++
        if (id >= nodes.size) {
            var newCap = nodes.size
            while (newCap <= id) newCap *= 2
            nodes = nodes.copyOf(newCap)
        }
        return id
    }

    // -- Cells (synchronous input layer) ----------------------------------

    fun <T : Any> cell(value: T): AsyncCellHandle<T> {
        val id = locked {
            val id = allocId()
            nodes[id] = AsyncNode.Cell(value, LinkedHashSet())
            id
        }
        return AsyncCellHandle(id)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getCell(handle: AsyncCellHandle<T>): T {
        locked {
            val node = nodes[handle.id] as? AsyncNode.Cell
                ?: error("AsyncCellHandle does not point to a Cell node")
            return node.value as T
        }
    }

    fun <T : Any> setCell(handle: AsyncCellHandle<T>, value: T) {
        val dependents: List<Int> = locked {
            val node = nodes[handle.id] as? AsyncNode.Cell
                ?: error("AsyncCellHandle does not point to a Cell node")
            @Suppress("UNCHECKED_CAST")
            if (node.value == value) return
            node.value = value
            if (batchDepth > 0) {
                batchedCells.add(handle.id)
                return
            }
            node.dependents.toList()
        }
        invalidateDependents(dependents)
        flushEffects()
    }

    // -- Async slots ------------------------------------------------------

    /**
     * Create an async computed slot. [compute] returns the value; it is
     * re-resolved on the next [getAsync] after a dependency invalidates.
     */
    fun <T : Any> computedAsync(
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncSlotHandle<T> = slotAsyncWithEquals(compute, equals = null)

    /**
     * Create an async memoized slot. Like [computedAsync] but an equal
     * recomputation (per [equals]) does not advance the published value.
     */
    fun <T : Any> memoAsync(
        equals: (Any?, Any?) -> Boolean = { a, b -> a == b },
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncSlotHandle<T> = slotAsyncWithEquals(compute, equals)

    private fun <T : Any> slotAsyncWithEquals(
        compute: suspend AsyncComputeContext.() -> T,
        equals: ((Any?, Any?) -> Boolean)?,
    ): AsyncSlotHandle<T> {
        val id = locked {
            val id = allocId()
            nodes[id] = AsyncNode.Slot(
                state = SlotState.Empty,
                value = null,
                revision = 0L,
                compute = { compute() },
                equals = equals,
                dependencies = LinkedHashSet(),
                dependents = LinkedHashSet(),
                inFlight = null,
                job = null,
            )
            id
        }
        return AsyncSlotHandle(id)
    }

    /** Synchronous cached read; the value if `Resolved`, else null. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(handle: AsyncSlotHandle<T>): T? = doGet(handle.id) as T?

    private fun doGet(slotId: Int): Any? = locked {
        val slot = nodes[slotId] as? AsyncNode.Slot ?: return null
        if (slot.state is SlotState.Resolved) slot.value else null
    }

    /**
     * Await a slot value, spawning async compute if needed. Re-resolves through
     * the two benign-race windows (resolved-since-`get()` and superseded
     * notifier) without throwing. Concurrent callers attach to the same
     * in-flight result instead of spawning duplicate futures.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getAsync(handle: AsyncSlotHandle<T>): T = doGetAsync(handle.id) as T

    private suspend fun doGetAsync(slotId: Int): Any? {
        while (true) {
            // Fast path: value already published.
            doGet(slotId)?.let { return it }

            var recv: CompletableDeferred<Outcome>? = null
            locked {
                val slot = nodes[slotId] as? AsyncNode.Slot
                    ?: error("AsyncSlotHandle does not point to a Slot node")
                when (val state = slot.state) {
                    is SlotState.Computing -> recv = slot.inFlight
                    SlotState.Empty, SlotState.Error -> recv = spawnComputeLocked(slotId)
                    is SlotState.Resolved -> {
                        // Window (1): resolved since the fast-path check above.
                        return slot.value
                    }
                }
            }
            // Await the shared in-flight result. Dropping this waiter does not
            // cancel the shared compute (it runs on the context scope).
            while (true) {
                val outcome = recv!!.await()
                when (outcome) {
                    is Outcome.Resolved -> return outcome.value
                    is Outcome.Failed -> throw outcome.error
                    Outcome.Retry -> break // world changed — re-resolve from current state.
                }
            }
        }
    }

    /**
     * Spawn (or attach to) the in-flight compute for [slotId]. Caller holds [lock].
     * Returns the shared completion deferred future waiters attach to.
     */
    private fun spawnComputeLocked(slotId: Int): CompletableDeferred<Outcome> {
        val slot = nodes[slotId] as? AsyncNode.Slot ?: error("not a slot node")
        val state = slot.state
        if (state is SlotState.Computing) {
            return slot.inFlight ?: error("computing without in-flight future")
        }
        val compute = slot.compute
        val spawnRevision = slot.revision
        val completion = CompletableDeferred<Outcome>()
        slot.inFlight = completion
        slot.state = SlotState.Computing(spawnRevision)
        val job = scope.launch {
            val deps = LinkedHashSet<Int>()
            val computeCtx = AsyncComputeContext(this@AsyncContext, slotId, deps)
            val result: Result<Any?> = try {
                Result.success(compute(computeCtx))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            locked {
                val current = nodes[slotId] as? AsyncNode.Slot
                if (current == null) {
                    // Slot removed during compute — discard, signal retry.
                    if (!completion.isCompleted) completion.complete(Outcome.Retry)
                    return@launch
                }
                if (current.revision != spawnRevision) {
                    // Stale completion (Computing -> Computing): discard, retry.
                    if (!completion.isCompleted) completion.complete(Outcome.Retry)
                    return@launch
                }
                updateDependenciesLocked(slotId, deps)
                result
                    .onSuccess { value ->
                        current.state = SlotState.Resolved
                        current.value = value
                        current.inFlight = null
                        current.job = null
                        completion.complete(Outcome.Resolved(value))
                    }
                    .onFailure { err ->
                        current.state = SlotState.Error
                        current.inFlight = null
                        current.job = null
                        completion.complete(Outcome.Failed(err))
                    }
            }
        }
        // Attach the in-flight job for cancellation, but only if the slot is
        // still Computing (under an inline dispatcher the compute may already
        // have resolved by the time launch returns).
        locked {
            val s = nodes[slotId] as? AsyncNode.Slot
            if (s != null && s.state is SlotState.Computing) s.job = job
        }
        return completion
    }

    // -- Async effects ----------------------------------------------------

    /**
     * Create an async effect. [effect] returns an optional suspend [cleanup];
     * the cleanup completes before the next body starts and on [disposeEffect].
     * Reruns are serialized per effect and executor-scheduled.
     */
    fun effectAsync(
        effect: suspend AsyncComputeContext.() -> (suspend () -> Unit)?,
    ): AsyncEffectHandle {
        val id = locked {
            val id = allocId()
            nodes[id] = AsyncNode.Effect(
                effectFn = effect,
                cleanup = null,
                dependencies = LinkedHashSet(),
                runJob = null,
                forceRun = true,
            )
            scheduleEffectLocked(id)
            id
        }
        flushEffects()
        return AsyncEffectHandle(id)
    }

    /** Dispose an async effect: drop pending reruns, cancel in-flight run, run cleanup. */
    suspend fun disposeEffect(handle: AsyncEffectHandle) {
        val (cleanup, runJob) = locked {
            pendingEffects.remove(handle.id)
            scheduledEffects.remove(handle.id)
            val node = nodes[handle.id]
            nodes[handle.id] = null
            if (handle.id < nodes.size) freeIds.addLast(handle.id)
            val eff = node as? AsyncNode.Effect
            if (eff != null) {
                for (dep in eff.dependencies) {
                    (nodes[dep] as? AsyncNode.Slot)?.dependents?.remove(handle.id)
                    (nodes[dep] as? AsyncNode.Cell)?.dependents?.remove(handle.id)
                }
            }
            eff?.cleanup to eff?.runJob
        }
        runJob?.cancelAndJoin()
        cleanup?.let { c -> try { c() } catch (_: Throwable) {} }
    }

    // -- Signal (eager) ---------------------------------------------------

    /** Eager async derived value (memo slot + puller effect). */
    fun <T : Any> signalAsync(
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncSignalHandle<T> {
        val slot = memoAsync(compute = compute)
        val effect = effectAsync {
            getAsync(slot)
            val none: (suspend () -> Unit)? = null
            none
        }
        return AsyncSignalHandle(slot, effect)
    }

    // -- Batch (synchronous boundary) -------------------------------------

    /** Synchronous batch boundary; async reruns are scheduled at the outermost exit. */
    fun <R> batch(run: (AsyncContext) -> R): R {
        locked { batchDepth += 1 }
        return try {
            run(this)
        } finally {
            val drained: List<Int> = locked {
                batchDepth -= 1
                if (batchDepth == 0) batchedCells.toList().also { batchedCells.clear() } else emptyList()
            }
            for (cellId in drained) {
                val dependents = locked {
                    (nodes[cellId] as? AsyncNode.Cell)?.dependents?.toList() ?: emptyList()
                }
                invalidateDependents(dependents)
            }
            if (drained.isNotEmpty()) flushEffects()
        }
    }

    // -- Internal: invalidation, dependency edges, effect scheduling -------

    private fun invalidateDependents(dependents: List<Int>) {
        // Process a stable snapshot; invalidation can cascade.
        val toInvalidate = ArrayList<Int>()
        val effects = ArrayList<Int>()
        for (depId in dependents) {
            locked {
                val node = nodes[depId]
                if (node is AsyncNode.Effect) {
                    effects.add(depId)
                } else if (node is AsyncNode.Slot) {
                    toInvalidate.add(depId)
                }
            }
        }
        for (slotId in toInvalidate) invalidateSlot(slotId)
        for (effectId in effects) locked { scheduleEffectLocked(effectId) }
    }

    private fun invalidateSlot(slotId: Int) {
        val (slotDeps, effectDeps) = locked {
            val slot = nodes[slotId] as? AsyncNode.Slot ?: return
            // Bump revision, clear cached state, supersede in-flight waiters.
            slot.revision += 1
            slot.state = SlotState.Empty
            slot.value = null
            val priorJob = slot.job
            val priorInFlight = slot.inFlight
            slot.job = null
            slot.inFlight = null
            priorJob?.cancel()
            priorInFlight?.complete(Outcome.Retry)
            val sd = ArrayList<Int>()
            val ed = ArrayList<Int>()
            for (d in slot.dependents) {
                when (nodes[d]) {
                    is AsyncNode.Effect -> ed.add(d)
                    else -> sd.add(d)
                }
            }
            sd to ed
        }
        for (effectId in effectDeps) locked { scheduleEffectLocked(effectId) }
        for (child in slotDeps) invalidateSlot(child)
        if (effectDeps.isNotEmpty()) flushEffects()
    }

    private fun updateDependenciesLocked(nodeId: Int, newDeps: Set<Int>) {
        val node = nodes[nodeId] ?: return
        val old = when (node) {
            is AsyncNode.Slot -> node.dependencies
            is AsyncNode.Effect -> node.dependencies
            is AsyncNode.Cell -> return
        }
        for (oldId in old - newDeps) {
            (nodes[oldId] as? AsyncNode.Slot)?.dependents?.remove(nodeId)
            (nodes[oldId] as? AsyncNode.Cell)?.dependents?.remove(nodeId)
        }
        when (node) {
            is AsyncNode.Slot -> {
                node.dependencies = LinkedHashSet(newDeps)
            }
            is AsyncNode.Effect -> {
                node.dependencies = LinkedHashSet(newDeps)
            }
            is AsyncNode.Cell -> {}
        }
        for (newId in newDeps) {
            (nodes[newId] as? AsyncNode.Slot)?.dependents?.add(nodeId)
            (nodes[newId] as? AsyncNode.Cell)?.dependents?.add(nodeId)
        }
    }

    private fun registerDependencyLocked(dependencyId: Int, dependentId: Int) {
        if (dependencyId == dependentId) return
        (nodes[dependentId] as? AsyncNode.Slot)?.dependencies?.add(dependencyId)
        (nodes[dependentId] as? AsyncNode.Effect)?.dependencies?.add(dependencyId)
        (nodes[dependencyId] as? AsyncNode.Slot)?.dependents?.add(dependentId)
        (nodes[dependencyId] as? AsyncNode.Cell)?.dependents?.add(dependentId)
    }

    private fun scheduleEffectLocked(id: Int) {
        val node = nodes[id] as? AsyncNode.Effect ?: return
        node.forceRun = true
        if (scheduledEffects.add(id)) pendingEffects.add(id)
    }

    private fun flushEffects() {
        val ids: List<Int> = locked {
            val drained = pendingEffects.toList()
            pendingEffects.clear()
            scheduledEffects.clear()
            drained
        }
        for (id in ids) {
            val plan: Pair<Job?, suspend AsyncComputeContext.() -> (suspend () -> Unit)?>? =
                locked {
                    val node = nodes[id] as? AsyncNode.Effect
                        ?: return@locked null
                    if (!node.forceRun) return@locked null
                    node.forceRun = false
                    node.runJob to node.effectFn
                }
            if (plan == null) continue
            val (prior, effectFn) = plan
            val runJob = scope.launch {
                // Serialized per effect: wait for the previous run to settle.
                prior?.join()
                val prevCleanup = locked {
                    val node = nodes[id] as? AsyncNode.Effect
                    node?.cleanup?.also { node.cleanup = null }
                }
                // Cleanup-before-body.
                if (prevCleanup != null) {
                    try { prevCleanup() } catch (_: Throwable) {}
                }
                if (!isActive) return@launch
                val deps = LinkedHashSet<Int>()
                val computeCtx = AsyncComputeContext(this@AsyncContext, id, deps)
                val newCleanup: (suspend () -> Unit)? = try {
                    computeCtx.effectFn()
                } catch (_: Throwable) {
                    null
                }
                var disposed = false
                locked {
                    val node = nodes[id] as? AsyncNode.Effect
                    if (node == null) {
                        disposed = true
                    } else {
                        updateDependenciesLocked(id, deps)
                        node.cleanup = newCleanup
                    }
                }
                // Disposed mid-run — undo this run's side effects (outside the lock).
                if (disposed) {
                    newCleanup?.let { c -> try { c() } catch (_: Throwable) {} }
                }
            }
            locked {
                (nodes[id] as? AsyncNode.Effect)?.runJob = runJob
            }
        }
    }

    /** Snapshot of a slot's state machine, for tests/diagnostics. */
    enum class SlotStateView { Empty, Computing, Resolved, Error, None }
    fun slotState(handle: AsyncSlotHandle<*>): SlotStateView = locked {
        val slot = nodes[handle.id] as? AsyncNode.Slot
            ?: return SlotStateView.None
        when (slot.state) {
            is SlotState.Empty -> SlotStateView.Empty
            is SlotState.Computing -> SlotStateView.Computing
            is SlotState.Resolved -> SlotStateView.Resolved
            is SlotState.Error -> SlotStateView.Error
        }
    }
}

/**
 * Passed to async compute/effect callbacks. Dependencies are recorded through
 * this context (not a thread-local) and registered **before** the awaited read,
 * so source invalidation while a future is suspended can supersede it.
 */
class AsyncComputeContext internal constructor(
    private val ctx: AsyncContext,
    private val nodeId: Int,
    private val dependencies: MutableSet<Int>,
) {
    /** Read a cell synchronously, recording it as a dependency. */
    fun <T : Any> getCell(handle: AsyncContext.AsyncCellHandle<T>): T {
        dependencies.add(handle.id)
        return ctx.getCell(handle)
    }

    /** Await a slot value, recording it as a dependency before awaiting. */
    suspend fun <T : Any> getAsync(handle: AsyncContext.AsyncSlotHandle<T>): T {
        dependencies.add(handle.id)
        return ctx.getAsync(handle)
    }

    /** Await an eager signal, recording its backing slot as a dependency. */
    suspend fun <T : Any> getAsync(signal: AsyncContext.AsyncSignalHandle<T>): T {
        dependencies.add(signal.slot.id)
        return ctx.getAsync(signal.slot)
    }
}

// -- Internal node / state / outcome models -----------------------------

private sealed class AsyncNode {
    class Cell(
        @JvmField var value: Any?,
        @JvmField val dependents: MutableSet<Int>,
    ) : AsyncNode()

    class Slot(
        @JvmField var state: SlotState,
        @JvmField var value: Any?,
        @JvmField var revision: Long,
        @JvmField val compute: suspend AsyncComputeContext.() -> Any?,
        @JvmField val equals: ((Any?, Any?) -> Boolean)?,
        @JvmField var dependencies: MutableSet<Int>,
        @JvmField val dependents: MutableSet<Int>,
        @JvmField var inFlight: CompletableDeferred<Outcome>?,
        @JvmField var job: Job?,
    ) : AsyncNode()

    class Effect(
        @JvmField val effectFn: suspend AsyncComputeContext.() -> (suspend () -> Unit)?,
        @JvmField var cleanup: (suspend () -> Unit)?,
        @JvmField var dependencies: MutableSet<Int>,
        @JvmField var runJob: Job?,
        @JvmField var forceRun: Boolean,
    ) : AsyncNode()
}

private sealed class SlotState {
    data object Empty : SlotState()
    data class Computing(val revision: Long) : SlotState()
    data object Resolved : SlotState()
    data object Error : SlotState()
}

private sealed class Outcome {
    data class Resolved(val value: Any?) : Outcome()
    data class Failed(val error: Throwable) : Outcome()
    data object Retry : Outcome()
}
