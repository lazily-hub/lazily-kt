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
import kotlinx.coroutines.yield
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

/** Bound on [AsyncContext.settle]'s re-check loop. */
private const val SETTLE_ROUNDS = 256

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

    /**
     * Depth of the disposal-driven invalidation cascade (`#lzspecedgeindex`).
     * See `Context.disposalDepth`. Only read or written under [lock].
     *
     * The async graph makes semantic 2 *more* load-bearing than the synchronous
     * one, not less: an effect scheduled here is dispatched to a coroutine that
     * resumes after `dispose` has returned, so the spurious rerun would surface
     * detached in time from the teardown that caused it.
     */
    private var disposalDepth = 0

    /**
     * Depth of an in-progress multi-root invalidation drain that owns its own
     * terminal flush (`#lzsignaleager` clause 3). While positive, [invalidateSlot]
     * marks its cone dirty but does **not** flush effects — the drain's caller
     * flushes exactly once when every root has been marked.
     *
     * This is what makes the async [batch] coalesce like the synchronous
     * `Context.flushBatched`: a per-cell flush mid-drain launches one puller run
     * per write, and because puller runs (and their computes) are dispatched
     * rather than inline, a run's compute can begin before the next write's
     * invalidation supersedes it — materializing the signal more than once per
     * batch under some schedules (green locally, red under CI's scheduling).
     * Suppressing the mid-drain flush makes a batch launch exactly one coalesced
     * puller run, hence one compute, regardless of scheduling. Only read or
     * written under [lock].
     */
    private var flushSuppressed = 0

    // -- Handles ----------------------------------------------------------

    /** A mutable input cell — the synchronous input layer. */
    open inner class AsyncSource<T> internal constructor(internal val id: Int) : AsyncGraphNode {
        override val nodeId: Int get() = id
    }

    /** A computed/memoized async slot. */
    open inner class AsyncComputed<T> internal constructor(internal val id: Int) : AsyncGraphNode {
        override val nodeId: Int get() = id
    }

    @Deprecated("Use AsyncSource", ReplaceWith("AsyncSource<T>"))
    inner class AsyncCellHandle<T> internal constructor(id: Int) : AsyncSource<T>(id)

    @Deprecated("Use AsyncComputed", ReplaceWith("AsyncComputed<T>"))
    inner class AsyncSlotHandle<T> internal constructor(id: Int) : AsyncComputed<T>(id)

    /** An async effect handle. */
    inner class AsyncEffectHandle internal constructor(internal val id: Int) : AsyncGraphNode {
        override val nodeId: Int get() = id
    }

    /** An eager async derived value (memo slot + puller effect). */
    inner class AsyncSignalHandle<T> internal constructor(
        internal val slot: AsyncComputed<T>,
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

    /**
     * Await quiescence: every scheduled effect flushed and every in-flight
     * effect run settled.
     *
     * The synchronous contexts are quiescent the moment an op returns; this one
     * is not, because effect reruns are executor-scheduled by contract rather
     * than inline. Without a way to await that, any assertion about *whether an
     * effect ran* is a race, and a conformance replay against this context
     * would be measuring its own scheduling luck.
     *
     * Loops because a settling effect can schedule another. Bounded so a
     * genuinely non-terminating effect cycle surfaces as a stuck graph rather
     * than as a hang inside `settle`.
     */
    suspend fun settle() {
        repeat(SETTLE_ROUNDS) {
            val jobs = locked {
                nodes.filterIsInstance<AsyncNode.Effect>().mapNotNull { it.runJob }
            }
            val queued = locked { pendingEffects.isNotEmpty() }
            if (!queued && jobs.none { it.isActive }) return
            for (job in jobs) {
                try { job.join() } catch (_: Throwable) {}
            }
            yield()
        }
    }

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

    fun <T : Any> source(value: T): AsyncSource<T> {
        val id = locked {
            val id = allocId()
            nodes[id] = AsyncNode.Cell(value, LinkedHashSet())
            id
        }
        return AsyncSource(id)
    }

    @Deprecated("Renamed to source (#lzcellkernel).", ReplaceWith("source(value)"))
    fun <T : Any> cell(value: T): AsyncSource<T> = source(value)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(handle: AsyncSource<T>): T {
        locked {
            val node = nodes[handle.id] as? AsyncNode.Cell
                ?: throw DisposedNodeException(handle.id, "cell")
            return node.value as T
        }
    }

    @Deprecated("Reads are unified — use get (#lzcellkernel).", ReplaceWith("get(handle)"))
    fun <T : Any> getCell(handle: AsyncSource<T>): T = get(handle)

    fun <T : Any> set(handle: AsyncSource<T>, value: T) {
        val dependents: List<Int> = locked {
            val node = nodes[handle.id] as? AsyncNode.Cell
                ?: throw DisposedNodeException(handle.id, "cell")
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

    @Deprecated("Writes are unified — use set (#lzcellkernel).", ReplaceWith("set(handle, value)"))
    fun <T : Any> setCell(handle: AsyncSource<T>, value: T) = set(handle, value)

    // -- Async slots ------------------------------------------------------

    /**
     * Create an async computed slot. [compute] returns the value; it is
     * re-resolved on the next [getAsync] after a dependency invalidates.
     */
    fun <T : Any> computedAsync(
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncComputed<T> = slotAsyncWithEquals(compute, equals = null)

    /**
     * Create an async memoized slot. Like [computedAsync] but an equal
     * recomputation (per [equals]) does not advance the published value.
     */
    fun <T : Any> memoAsync(
        equals: (Any?, Any?) -> Boolean = { a, b -> a == b },
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncComputed<T> = slotAsyncWithEquals(compute, equals)

    private fun <T : Any> slotAsyncWithEquals(
        compute: suspend AsyncComputeContext.() -> T,
        equals: ((Any?, Any?) -> Boolean)?,
    ): AsyncComputed<T> {
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
        return AsyncComputed(id)
    }

    /** Synchronous cached read; the value if `Resolved`, else null. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(handle: AsyncComputed<T>): T? = doGet(handle.id) as T?

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
    suspend fun <T : Any> getAsync(handle: AsyncComputed<T>): T = doGetAsync(handle.id) as T

    private suspend fun doGetAsync(slotId: Int): Any? {
        while (true) {
            // A disposed slot is an error, never a null. Checked before the
            // fast path so `read_after_dispose` cannot be mistaken for
            // "resolved to nothing yet" (`#lzspecedgeindex`).
            locked {
                if (nodes[slotId] !is AsyncNode.Slot) {
                    throw DisposedNodeException(slotId, "slot")
                }
            }
            // Fast path: value already published.
            doGet(slotId)?.let { return it }

            var recv: CompletableDeferred<Outcome>? = null
            locked {
                val slot = nodes[slotId] as? AsyncNode.Slot
                    ?: throw DisposedNodeException(slotId, "slot")
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
    suspend fun disposeEffect(handle: AsyncEffectHandle) = disposeNode(handle)

    // -- Disposal + degree introspection (`#lzspecedgeindex`) --------------
    //
    // The async mirror of the `Context` plane. Two things differ, both forced by
    // the execution model rather than chosen:
    //
    //  * teardown **suspends**. Disposing a node cancels its in-flight compute
    //    or effect run and awaits that cancellation before running the cleanup,
    //    so a cleanup never races the body it is undoing. That is why
    //    [AsyncTeardownScope.end] is `suspend` and the scope is not
    //    `AutoCloseable`: `use` cannot await, and a `close` that blocked on
    //    `runBlocking` inside a coroutine would deadlock a confined dispatcher.
    //  * the disposal cascade is marked under the lock but the cancel/cleanup
    //    tail runs outside it, so a user cleanup cannot deadlock the graph.

    /** Resolve a handle to its live node, or `null` if stale. Caller holds [lock]. */
    private fun resolveLocked(node: AsyncGraphNode): AsyncNode? {
        val id = node.nodeId
        if (id < 0 || id >= nodes.size) return null
        val n = nodes[id] ?: return null
        return when (node) {
            is AsyncSource<*> -> n as? AsyncNode.Cell
            is AsyncComputed<*> -> n as? AsyncNode.Slot
            is AsyncEffectHandle -> n as? AsyncNode.Effect
        }
    }

    /** Size of [node]'s reverse edge set. See `Context.dependentCount`. */
    fun dependentCount(node: AsyncGraphNode): Int = locked {
        when (val n = resolveLocked(node)) {
            is AsyncNode.Cell -> n.dependents.size
            is AsyncNode.Slot -> n.dependents.size
            else -> 0
        }
    }

    /** Size of [node]'s forward edge set. See `Context.dependencyCount`. */
    fun dependencyCount(node: AsyncGraphNode): Int = locked {
        when (val n = resolveLocked(node)) {
            is AsyncNode.Slot -> n.dependencies.size
            is AsyncNode.Effect -> n.dependencies.size
            else -> 0
        }
    }

    /** Whether [node] has been torn down. See `Context.isDisposed`. */
    fun isDisposed(node: AsyncGraphNode): Boolean = locked { resolveLocked(node) == null }

    /** What a locked teardown leaves for the suspending tail to finish. */
    private class DisposePlan(
        @JvmField val cleanup: (suspend () -> Unit)?,
        @JvmField val runJob: Job?,
    )

    /**
     * Tear [node] out of the graph. See `Context.disposeNode` for the shared
     * contract; the graph mutation happens under [lock] and the cancellation and
     * cleanup happen after it is released.
     */
    suspend fun disposeNode(node: AsyncGraphNode) {
        val plan = locked {
            val resolved = resolveLocked(node) ?: return@locked null
            disposeLocked(node.nodeId, resolved)
        } ?: return
        plan.runJob?.cancelAndJoin()
        plan.cleanup?.let { c -> try { c() } catch (_: Throwable) {} }
    }

    /** Tear down an async slot. See [disposeNode]. */
    suspend fun disposeSlot(handle: AsyncComputed<*>) = disposeNode(handle)

    /** Tear down a source cell. See [disposeNode]. */
    suspend fun disposeCell(handle: AsyncSource<*>) = disposeNode(handle)

    /** Tear down both halves of a signal, puller first. */
    suspend fun disposeSignalNode(handle: AsyncSignalHandle<*>) {
        disposeNode(handle.effect)
        disposeNode(handle.slot)
    }

    /**
     * The shared teardown path; caller holds [lock]. Order matches
     * `Context.disposeResolved`: vacate the arena slot, detach upstream, detach
     * downstream, then dirty the surviving cone mark-only.
     */
    private fun disposeLocked(id: Int, node: AsyncNode): DisposePlan {
        nodes[id] = null
        freeIds.addLast(id)
        var cleanup: (suspend () -> Unit)? = null
        var runJob: Job? = null
        val dependents: List<Int> = when (node) {
            is AsyncNode.Cell -> node.dependents.toList()
            is AsyncNode.Slot -> {
                for (dep in node.dependencies) detachDependentLocked(dep, id)
                // Supersede anyone awaiting this slot: they re-resolve, find the
                // arena slot empty, and get the DisposedNodeException.
                node.job?.cancel()
                node.inFlight?.complete(Outcome.Retry)
                node.dependents.toList()
            }
            is AsyncNode.Effect -> {
                pendingEffects.remove(id)
                scheduledEffects.remove(id)
                // The upstream detach the pre-disposal `disposeEffect` skipped
                // for cells and slots alike. Leaving it out was a real leak: an
                // effect removed from the context still sat in `dependents` for
                // every dependency it had read, so async subscribe/unsubscribe
                // churn grew each source's dependent set without bound.
                for (dep in node.dependencies) detachDependentLocked(dep, id)
                cleanup = node.cleanup
                runJob = node.runJob
                emptyList()
            }
        }
        if (dependents.isNotEmpty()) {
            for (d in dependents) detachDependencyLocked(d, id)
            disposalDepth++
            try {
                for (d in dependents) {
                    // Effects reached by the walk are deliberately left alone —
                    // disposal is not a publish. Slots are invalidated so a
                    // dependent cannot serve a value it computed through the
                    // node being torn down.
                    if (nodes[d] is AsyncNode.Slot) invalidateSlot(d)
                }
            } finally {
                disposalDepth--
            }
        }
        return DisposePlan(cleanup, runJob)
    }

    /** Remove [dependentId] from [depId]'s reverse edge set. Caller holds [lock]. */
    private fun detachDependentLocked(depId: Int, dependentId: Int) {
        (nodes[depId] as? AsyncNode.Slot)?.dependents?.remove(dependentId)
        (nodes[depId] as? AsyncNode.Cell)?.dependents?.remove(dependentId)
    }

    /** Remove [depId] from [parentId]'s forward edge set. Caller holds [lock]. */
    private fun detachDependencyLocked(parentId: Int, depId: Int) {
        (nodes[parentId] as? AsyncNode.Slot)?.dependencies?.remove(depId)
        (nodes[parentId] as? AsyncNode.Effect)?.dependencies?.remove(depId)
    }

    /** Open a teardown scope. See [AsyncTeardownScope]. */
    fun scope(): AsyncTeardownScope = AsyncTeardownScope(this)

    /**
     * Run [body] with a teardown scope ended when it returns, even on a throw —
     * the suspending analogue of `ctx.scope().use { ... }`.
     *
     * `use` is not available here because [AsyncTeardownScope.end] suspends, and
     * `AutoCloseable.close` cannot. This is the bracket in its place.
     */
    suspend fun <R> withScope(body: suspend (AsyncTeardownScope) -> R): R {
        val scope = scope()
        try {
            return body(scope)
        } finally {
            scope.end()
        }
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
            if (drained.isNotEmpty()) {
                // Mark every batched cell's cone dirty WITHOUT flushing mid-drain
                // (`flushSuppressed`), then flush effects exactly once at the
                // boundary — mirroring the synchronous `Context.flushBatched`
                // (one frontier pass + one flush). A per-cell flush would launch
                // one puller run per write and let the signal materialize more
                // than once per batch under async scheduling (#lzsignaleager
                // clause 3, #lzcellkernel).
                locked { flushSuppressed += 1 }
                try {
                    for (cellId in drained) {
                        val dependents = locked {
                            (nodes[cellId] as? AsyncNode.Cell)?.dependents?.toList() ?: emptyList()
                        }
                        invalidateDependents(dependents)
                    }
                } finally {
                    locked { flushSuppressed -= 1 }
                }
                flushEffects()
            }
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
        // A disposal cascade is mark-only. Flushing here would not just run the
        // effects this walk reached (`scheduleEffectLocked` already dropped
        // those) — it would drain any *pre-existing* queue entry, firing an
        // unrelated effect as a side effect of a teardown.
        if (effectDeps.isNotEmpty() && locked { disposalDepth == 0 && flushSuppressed == 0 }) flushEffects()
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
        // Disposal is not a publish — see [disposalDepth].
        if (disposalDepth > 0) return
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
    fun slotState(handle: AsyncComputed<*>): SlotStateView = locked {
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
    /** Read a source synchronously, recording it as a dependency. */
    fun <T : Any> get(handle: AsyncContext.AsyncSource<T>): T {
        dependencies.add(handle.id)
        return ctx.get(handle)
    }

    @Deprecated("Reads are unified — use get (#lzcellkernel).", ReplaceWith("get(handle)"))
    fun <T : Any> getCell(handle: AsyncContext.AsyncSource<T>): T = get(handle)

    /** Await a slot value, recording it as a dependency before awaiting. */
    suspend fun <T : Any> getAsync(handle: AsyncContext.AsyncComputed<T>): T {
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

/**
 * A teardown scope over an [AsyncContext]: nodes created through it are disposed
 * when it ends (`#lzspecedgeindex`).
 *
 * See [TeardownScope] for why a scope's end is an explicit statement in Kotlin
 * rather than a destructor. The difference here is that [end] **suspends**,
 * because async teardown cancels in-flight computes and awaits their
 * cancellation before running cleanups. That rules out [AutoCloseable]/`use`:
 * `close` cannot suspend, and a `close` that bridged with `runBlocking` would
 * deadlock on a confined dispatcher — exactly where a caller would reach for it.
 * [AsyncContext.withScope] is the bracket in `use`'s place.
 *
 * Structured concurrency is the obvious third option and is deliberately not the
 * primary shape. `Job.invokeOnCompletion` would end the scope when a *coroutine*
 * finishes, but the lifetime a scope usually tracks is a connection or a
 * subscription, which outlives the coroutine that opened it and is closed by a
 * peer rather than by returning. It also cannot await: `invokeOnCompletion` runs
 * a non-suspending callback, so the cleanup ordering [end] guarantees would be
 * lost. Callers who genuinely want job-scoped teardown can still write
 * `job.invokeOnCompletion { launch { scope.end() } }` — that is a policy for the
 * caller to choose, not a semantic for the library to impose.
 */
class AsyncTeardownScope internal constructor(
    /** The context this scope belongs to. */
    val ctx: AsyncContext,
) {
    private val owned = ArrayList<AsyncGraphNode>()
    private var ended = false

    /** How many nodes this scope currently owns. */
    val size: Int get() = owned.size

    /** Whether this scope owns nothing. */
    fun isEmpty(): Boolean = owned.isEmpty()

    /** Whether this scope owns at least one node. */
    fun isNotEmpty(): Boolean = owned.isNotEmpty()

    /** Whether [end] has already run. */
    val isEnded: Boolean get() = ended

    /** Take ownership of an existing node. See `TeardownScope.adopt`. */
    fun <T : AsyncGraphNode> adopt(node: T): T {
        if (!ended) owned.add(node)
        return node
    }

    /** A source owned by this scope. */
    fun <T : Any> source(value: T): AsyncContext.AsyncSource<T> = adopt(ctx.source(value))

    @Deprecated("Renamed to source (#lzcellkernel).", ReplaceWith("source(value)"))
    fun <T : Any> cell(value: T): AsyncContext.AsyncSource<T> = source(value)

    /** An async computed slot owned by this scope. */
    fun <T : Any> computedAsync(
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncContext.AsyncComputed<T> = adopt(ctx.computedAsync(compute))

    /** An async memoized slot owned by this scope. */
    fun <T : Any> memoAsync(
        equals: (Any?, Any?) -> Boolean = { a, b -> a == b },
        compute: suspend AsyncComputeContext.() -> T,
    ): AsyncContext.AsyncComputed<T> = adopt(ctx.memoAsync(equals, compute))

    /** An async effect owned by this scope. */
    fun effectAsync(
        effect: suspend AsyncComputeContext.() -> (suspend () -> Unit)?,
    ): AsyncContext.AsyncEffectHandle = adopt(ctx.effectAsync(effect))

    /** Cancel this scope's teardown. See `TeardownScope.disarm`. */
    fun disarm() {
        owned.clear()
    }

    /**
     * Dispose every owned node in reverse creation order, awaiting each node's
     * cancellation and cleanup before moving to the next.
     *
     * Sequential rather than concurrent on purpose: reverse creation order is
     * only observable if the cleanups actually run in that order, and
     * `disposeScope_eq_disposeAll` compares against a sequential fold.
     */
    suspend fun end() {
        if (ended) return
        ended = true
        for (i in owned.indices.reversed()) ctx.disposeNode(owned[i])
        owned.clear()
    }

    override fun toString(): String =
        if (ended) "AsyncTeardownScope(ended)" else "AsyncTeardownScope(${owned.size} owned)"
}
