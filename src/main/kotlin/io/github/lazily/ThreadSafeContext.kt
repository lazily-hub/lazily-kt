package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// -- Handles -----------------------------------------------------------------

/**
 * Lightweight typed reference to a lazy memoized slot in a [ThreadSafeContext].
 *
 * Clonable by value (a `value class` over an id), mirroring lazily-rs
 * `ThreadSafeComputed<T>: Copy`.
 */
@JvmInline
value class ThreadSafeComputed<T : Any> @PublishedApi internal constructor(val id: Int) :
    ThreadSafeGraphNode {
    override val nodeId: Int get() = id
}

/**
 * Lightweight typed reference to a mutable source cell in a [ThreadSafeContext].
 * Clonable by value, mirroring lazily-rs `ThreadSafeSource<T>: Copy`.
 */
@JvmInline
value class ThreadSafeSource<T : Any> @PublishedApi internal constructor(val id: Int) :
    ThreadSafeGraphNode {
    override val nodeId: Int get() = id
}

@Deprecated("Use ThreadSafeComputed", ReplaceWith("ThreadSafeComputed<T>"))
typealias ThreadSafeSlotHandle<T> = ThreadSafeComputed<T>

@Deprecated("Use ThreadSafeSource", ReplaceWith("ThreadSafeSource<T>"))
typealias ThreadSafeCellHandle<T> = ThreadSafeSource<T>

/**
 * Reference to a registered side-effecting observer in a [ThreadSafeContext].
 * Clonable by value. Dispose to stop reruns.
 */
@JvmInline
value class ThreadSafeEffectHandle @PublishedApi internal constructor(val id: Int) :
    ThreadSafeGraphNode {
    override val nodeId: Int get() = id
}

/**
 * Reference to an **eager** derived value in a [ThreadSafeContext]. Composed of
 * a memoized backing slot plus a puller effect that re-materializes the slot
 * after every invalidation. Composed of clonable value-class handles, so it is
 * freely shareable across threads through the owning context.
 */
class ThreadSafeSignalHandle<T : Any> @PublishedApi internal constructor(
    @PublishedApi internal val slot: ThreadSafeComputed<T>,
    @PublishedApi internal val effect: ThreadSafeEffectHandle,
) {
    /** Dispose this signal's eager puller; the backing value stays readable (reverts to lazy). */
    fun dispose(ctx: ThreadSafeContext) = ctx.disposeSignal(this)
}

// -- Context -----------------------------------------------------------------

private const val INITIAL_NODE_CAPACITY = 16

/**
 * Lock-backed reactive dependency graph — the thread-safe counterpart of
 * [Context], mirroring lazily-rs `ThreadSafeContext`.
 *
 * The reactive family is identical to [Context]: **Slot** (lazy memoized
 * derived) → **Cell** (mutable source) → **Signal** (eager derived), plus
 * **Effect** (side-effecting observer). Reading a cell/slot/signal inside a
 * computation auto-registers a dependency edge; mutating a cell invalidates
 * dependents.
 *
 * This is the layer the lazily-spec `thread_safe` capability requires of any
 * binding whose platform exposes preemptive multi-threading — the JVM/Kotlin
 * runtime structurally supports OS threads and a shared heap, so lazily-kt
 * declares `thread_safe = host`. It satisfies the spec contract:
 *
 * - **Lock-backed** — a single [ReentrantLock] serializes every graph mutation
 *   and read, so observers fire **synchronously within the invalidating
*   `set`/`batch`**, preserving glitch-free pull-based ordering. The JVM
 *   memory model's monitor happens-before guarantee is the counterpart of
 *   Rust's `Send + Sync` obligation: every value and callback published under
 *   the lock is safely visible to any thread that observes it under the lock.
 * - **Clonable handles** — [ThreadSafeComputed] / [ThreadSafeSource] /
 *   [ThreadSafeEffectHandle] / [ThreadSafeSignalHandle] are value classes
 *   (copyable by value), so a handle minted on one thread may be read on
 *   another through the shared context.
 * - **Per-thread dependency tracking** — a `ThreadLocal` tracking stack
 *   discovers each reading frame's edges, mirroring lazily-rs's `thread_local!`
 *   tracking stack; two threads computing concurrently never mix their edges
 *   (and the graph lock serializes recomputation regardless).
 *
 * `ReentrantLock` is reentrant, so a compute/effect callback that re-enters the
* same context from the same thread (e.g. a computed reading another computed) does not
* self-deadlock. As in [Context], `set` is `==`-guarded (equal value is a
 * no-op), `memo` adds the same guard to a recompute, a `Signal` is materialized
* by the time the invalidating `set`/`batch` returns, and `batch` coalesces
 * invalidations into one effect flush.
 */
class ThreadSafeContext {
    private sealed interface Node {
        class Cell(var value: Any?, val dependents: SmallEdgeList = SmallEdgeList()) : Node
        class Slot(
            var value: Any? = null,
            var hasValue: Boolean = false,
            val memo: Boolean = false,
            val compute: ThreadSafeContext.() -> Any? = { null },
            val dependencies: SmallEdgeList = SmallEdgeList(),
            val dependents: SmallEdgeList = SmallEdgeList(),
            var dirty: Boolean = false,
            var forceRecompute: Boolean = false,
            var inProgress: Boolean = false,
        ) : Node
        class Effect(
            val run: ThreadSafeContext.() -> (() -> Unit)?,
            val dependencies: SmallEdgeList = SmallEdgeList(),
            var cleanup: (() -> Unit)? = null,
            var forceRun: Boolean = false,
        ) : Node
    }

    private val lock = ReentrantLock()
    // Dense node arena indexed directly by id (mirrors lazily-rs
    // `Vec<Option<Node>>`; see Context for the recycling contract).
    private var nodes: Array<Node?> = arrayOfNulls(INITIAL_NODE_CAPACITY)
    private var nextId: Int = 1
    private val freeIds: ArrayDeque<Int> = ArrayDeque()

    private val pendingEffects: ArrayDeque<Int> = ArrayDeque()
    private val scheduledEffects: MutableSet<Int> = HashSet()
    private var flushingEffects: Boolean = false

    @PublishedApi
    internal var batchDepth: Int = 0
    private val batchedCells: MutableSet<Int> = HashSet()

    /**
     * Depth of the disposal-driven invalidation cascade (`#lzspecedgeindex`).
     * See `Context.disposalDepth` — identical contract, identical reason. Only
     * ever read or written under [lock].
     */
    private var disposalDepth: Int = 0

    /**
     * Per-thread tracking frame stack: the id of the slot/effect currently
     * computing on *this* thread. Each thread discovers its own edges; the
     * graph lock still serializes all recomputation.
     */
    private val trackingStack: ThreadLocal<ArrayDeque<Int>> =
        ThreadLocal.withInitial { ArrayDeque() }

    // -- Creation ----------------------------------------------------------

    /** A mutable source with an initial value. `set` invalidates dependents on `==` change. */
    inline fun <reified T : Any> source(value: T): ThreadSafeSource<T> =
        ThreadSafeSource(cellAny(value))

    @Deprecated("Renamed to source (#lzcellkernel).", ReplaceWith("source(value)"))
    inline fun <reified T : Any> cell(value: T): ThreadSafeSource<T> = source(value)

    @PublishedApi
    internal fun cellAny(value: Any): Int = locked {
        val id = allocId()
        nodes[id] = Node.Cell(value)
        id
    }

    /** A lazy guarded computed: equal recomputation suppresses downstream invalidation. */
    inline fun <reified T : Any> computed(noinline compute: ThreadSafeContext.() -> T): ThreadSafeComputed<T> =
        ThreadSafeComputed(slotAny(memo = true) { compute() })

    /** Deprecated compatibility spelling for guarded [computed]. */
    @Deprecated("Renamed to computed (#lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> slot(noinline compute: ThreadSafeContext.() -> T): ThreadSafeComputed<T> =
        computed(compute)

    /** Deprecated compatibility spelling for guarded [computed]. */
    @Deprecated("Use computed; every computed is guarded (#lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> memo(noinline compute: ThreadSafeContext.() -> T): ThreadSafeComputed<T> =
        computed(compute)

    @PublishedApi
    internal fun slotAny(memo: Boolean, compute: ThreadSafeContext.() -> Any?): Int = locked {
        val id = allocId()
        nodes[id] = Node.Slot(memo = memo, compute = compute)
        id
    }

    /**
     * An **eager** derived value: a memo slot plus a puller effect. The value is
     * materialized at creation and re-materialized by the time the invalidating
     * `set`/`batch` returns — observers never see an intermediate unset state.
     */
    inline fun <reified T : Any> signal(noinline compute: ThreadSafeContext.() -> T): ThreadSafeSignalHandle<T> {
        val ids = signalAny { compute() }
        return ThreadSafeSignalHandle(ThreadSafeComputed(ids.slot), ThreadSafeEffectHandle(ids.effect))
    }

    @PublishedApi
    internal class SignalIds(@PublishedApi internal val slot: Int, @PublishedApi internal val effect: Int)

    @PublishedApi
    internal fun signalAny(compute: ThreadSafeContext.() -> Any?): SignalIds = locked {
        val slot = slotAny(memo = true, compute = compute)
        val effect = effectAny { getSlotAny(slot); null }
        SignalIds(slot, effect)
    }

    /**
     * Run [run] immediately, then rerun it after any tracked cell/slot/signal it
     * reads invalidates. [run] may return a cleanup closure (`(() -> Unit)?`);
     * cleanup runs before each rerun and on dispose.
     */
    fun effect(run: ThreadSafeContext.() -> (() -> Unit)?): ThreadSafeEffectHandle =
        ThreadSafeEffectHandle(effectAny(run))

    @PublishedApi
    internal fun effectAny(run: ThreadSafeContext.() -> (() -> Unit)?): Int = locked {
        val id = allocId()
        nodes[id] = Node.Effect(run = run, forceRun = true)
        scheduleEffect(id, force = false)
        flushEffects()
        id
    }

    // -- Read --------------------------------------------------------------

    /** Read a computed, computing/refreshing if necessary; auto-subscribes the reading node. */
    inline fun <reified T : Any> get(handle: ThreadSafeComputed<T>): T {
        @Suppress("UNCHECKED_CAST")
        return getSlotAny(handle.id) as T
    }

    /** Read a source; auto-subscribes the reading node. */
    inline fun <reified T : Any> get(handle: ThreadSafeSource<T>): T {
        @Suppress("UNCHECKED_CAST")
        return getCellAny(handle.id) as T
    }

    @Deprecated("Reads are unified — use get (#lzcellkernel).", ReplaceWith("get(handle)"))
    inline fun <reified T : Any> getCell(handle: ThreadSafeSource<T>): T {
        return get(handle)
    }

    /** Read a signal's current (always-materialized) value; auto-subscribes. */
    inline fun <reified T : Any> getSignal(handle: ThreadSafeSignalHandle<T>): T = get(handle.slot)

    @PublishedApi
    internal fun getSlotAny(id: Int): Any = locked {
        // Disposed check before the edge registration; see Context.getSlotAny
        // for why a recycling arena makes that ordering load-bearing.
        if (nodes[id] !is Node.Slot) throw DisposedNodeException(id, "slot")
        currentFrame()?.let { registerDependency(id, it) }
        refreshSlot(id)
        val node = nodes[id] as? Node.Slot ?: throw DisposedNodeException(id, "slot")
        check(node.hasValue) { "slot $id has no value" }
        node.value as Any
    }

    @PublishedApi
    internal fun getCellAny(id: Int): Any = locked {
        val node = nodes[id] as? Node.Cell ?: throw DisposedNodeException(id, "cell")
        currentFrame()?.let { registerDependency(id, it) }
        node.value as Any
    }

    // -- Write -------------------------------------------------------------

    /** Set a source's value. A no-op when the new value `==` the old. */
    fun <T : Any> set(handle: ThreadSafeSource<T>, value: T) = setCellAny(handle.id, value)

    @Deprecated("Writes are unified — use set (#lzcellkernel).", ReplaceWith("set(handle, value)"))
    fun <T : Any> setCell(handle: ThreadSafeSource<T>, value: T) = set(handle, value)

    @PublishedApi
    internal fun setCellAny(id: Int, value: Any) = locked {
        val node = nodes[id] as? Node.Cell ?: throw DisposedNodeException(id, "cell")
        if (node.value != value) {
            node.value = value
            if (isBatching()) {
                batchedCells.add(id)
            } else {
                // Store-without-cascade: flush only when the dependent cone holds
                // an Effect; a cell with no active reactor stores its value +
                // dirty-marks lazy slots but pays no flush (§4.0 merge cost law).
                if (invalidateCellDependentsNow(id)) flushEffects()
            }
        }
    }

    // -- Batch -------------------------------------------------------------

    /**
     * Run several cell updates as one invalidation + effect flush at the
     * outermost batch. The whole block runs under the graph lock, so a batch is
     * atomic across threads: concurrent batches serialize, and no other thread
     * observes an intermediate state.
     */
    fun batch(run: ThreadSafeContext.() -> Unit) {
        lock.withLock {
            batchDepth++
            try {
                run()
            } finally {
                finishBatch()
            }
        }
    }

    @PublishedApi
    internal fun finishBatch() {
        check(batchDepth > 0) { "finishBatch without active batch" }
        batchDepth--
        if (batchDepth == 0) flushBatched()
    }

    private fun flushBatched() {
        // Merged frontier: one DFS pass for all batched cells (#lzbatchborrow).
        val stack = ArrayDeque<Int>()
        val forceStack = ArrayDeque<Boolean>()
        for (cellId in batchedCells) {
            val cell = nodes[cellId] as? Node.Cell ?: continue
            for (dep in cell.dependents) { stack.addLast(dep); forceStack.addLast(true) }
        }
        batchedCells.clear()
        runFrontier(stack, forceStack)
        flushEffects()
    }

    private fun isBatching(): Boolean = batchDepth > 0

    // -- Dispose -----------------------------------------------------------

    /** Dispose an effect: deschedule, drop edges, run its cleanup, recycle the id. */
    fun disposeEffect(handle: ThreadSafeEffectHandle) = disposeNode(handle)

    // -- Disposal + degree introspection (`#lzspecedgeindex`) --------------
    //
    // The thread-safe mirror of the `Context` plane; every doc comment there
    // applies verbatim. The only difference is that each entry point takes the
    // graph lock, so a teardown is atomic against concurrent readers: a thread
    // reading a node being disposed either completes its read or sees the
    // DisposedNodeException, never a half-detached edge set.

    /** Resolve a handle to its live node, or `null` if stale. See `Context.resolve`. */
    private fun resolve(node: ThreadSafeGraphNode): Node? {
        val id = node.nodeId
        if (id < 0 || id >= nodes.size) return null
        val n = nodes[id] ?: return null
        return when (node) {
            is ThreadSafeSource<*> -> n as? Node.Cell
            is ThreadSafeComputed<*> -> n as? Node.Slot
            is ThreadSafeEffectHandle -> n as? Node.Effect
        }
    }

    /** Size of [node]'s reverse edge set. See `Context.dependentCount`. */
    fun dependentCount(node: ThreadSafeGraphNode): Int = locked {
        when (val n = resolve(node)) {
            is Node.Cell -> n.dependents.size
            is Node.Slot -> n.dependents.size
            else -> 0
        }
    }

    /** Size of [node]'s forward edge set. See `Context.dependencyCount`. */
    fun dependencyCount(node: ThreadSafeGraphNode): Int = locked {
        when (val n = resolve(node)) {
            is Node.Slot -> n.dependencies.size
            is Node.Effect -> n.dependencies.size
            else -> 0
        }
    }

    /** Whether [node] has been torn down. See `Context.isDisposed`. */
    fun isDisposed(node: ThreadSafeGraphNode): Boolean = locked { resolve(node) == null }

    /** Tear [node] out of the graph. See `Context.disposeNode`. */
    fun disposeNode(node: ThreadSafeGraphNode) = locked {
        val resolved = resolve(node) ?: return@locked
        disposeResolved(node.nodeId, resolved)
    }

    /** Tear down a derived slot. See [disposeNode]. */
    fun disposeSlot(handle: ThreadSafeComputed<*>) = disposeNode(handle)

    /** Tear down a source cell. See [disposeNode]. */
    fun disposeCell(handle: ThreadSafeSource<*>) = disposeNode(handle)

    /** Tear down both halves of a signal, puller first. See `Context.disposeSignalNode`. */
    fun disposeSignalNode(handle: ThreadSafeSignalHandle<*>) {
        disposeNode(handle.effect)
        disposeNode(handle.slot)
    }

    /** The shared teardown path. Caller holds [lock]. See `Context.disposeResolved`. */
    private fun disposeResolved(id: Int, node: Node) {
        nodes[id] = null
        freeIds.addLast(id)
        when (node) {
            is Node.Cell -> detachDependents(id, node.dependents)
            is Node.Slot -> {
                for (dep in node.dependencies) removeDependentEdge(dep, id)
                node.dependencies.clear()
                detachDependents(id, node.dependents)
            }
            is Node.Effect -> {
                // O(1) scheduled check before the queue scan; see
                // Context.disposeResolved for why the unguarded form was
                // quadratic in fan-out (`#lzspecedgeindex`).
                if (scheduledEffects.remove(id)) pendingEffects.remove(id)
                for (dep in node.dependencies) removeDependentEdge(dep, id)
                node.dependencies.clear()
                node.cleanup?.invoke()
            }
        }
    }

    /** Detach dependents, then dirty the surviving cone mark-only. Caller holds [lock]. */
    private fun detachDependents(id: Int, dependents: SmallEdgeList) {
        if (dependents.isEmpty()) return
        val snapshot = dependents.toList()
        dependents.clear()
        for (parent in snapshot) removeDependencyEdge(parent, id)
        disposalDepth++
        try {
            val stack = ArrayDeque<Int>()
            val forceStack = ArrayDeque<Boolean>()
            for (parent in snapshot) { stack.addLast(parent); forceStack.addLast(true) }
            runFrontier(stack, forceStack)
        } finally {
            disposalDepth--
        }
    }

    /** Remove [depId] from [parentId]'s forward edge set. Caller holds [lock]. */
    private fun removeDependencyEdge(parentId: Int, depId: Int) {
        when (val parent = nodes[parentId]) {
            is Node.Slot -> removeEdge(parent.dependencies, depId)
            is Node.Effect -> removeEdge(parent.dependencies, depId)
            else -> {}
        }
    }

    /** Open a teardown scope. See `Context.scope`. */
    fun scope(): ThreadSafeTeardownScope = ThreadSafeTeardownScope(this)

    fun isEffectActive(handle: ThreadSafeEffectHandle): Boolean = locked {
        nodes[handle.id] is Node.Effect
    }

    /** Dispose a signal's eager puller; the backing value stays readable (reverts to lazy). */
    fun disposeSignal(handle: ThreadSafeSignalHandle<*>) = disposeEffect(handle.effect)

    fun isSignalActive(handle: ThreadSafeSignalHandle<*>): Boolean = isEffectActive(handle.effect)

    /** Whether a slot currently has a fresh cached value (testing). */
    fun isSet(handle: ThreadSafeComputed<*>): Boolean = locked {
        val node = nodes[handle.id] as? Node.Slot ?: return@locked false
        node.hasValue && !node.dirty
    }

    // -- Internals: lock helper -------------------------------------------

    private inline fun <T> locked(action: () -> T): T = lock.withLock(action)

    // -- Internals: id + edges --------------------------------------------

    private fun allocId(): Int {
        val id = freeIds.removeLastOrNull() ?: nextId++
        if (id >= nodes.size) {
            var newCap = nodes.size
            while (newCap <= id) newCap *= 2
            nodes = nodes.copyOf(newCap)
        }
        return id
    }

    private fun currentFrame(): Int? = trackingStack.get().lastOrNull()

    // Edge containers: see EdgeList.kt. Shared with Context — SmallEdgeList
    // inlines the 0-2 edge case, scans for dedup while the degree is small, and
    // promotes to a hash index above EDGE_INDEX_THRESHOLD (#lzspecedgeindex).
    // Before this, both add and remove were an unconditional linear scan, so a
    // width-N fan-out cost ~N^2/2 comparisons to build.
    private fun addEdge(edges: SmallEdgeList, id: Int) {
        edges.add(id)
    }

    private fun removeEdge(edges: SmallEdgeList, id: Int) {
        edges.remove(id)
    }

    private fun registerDependency(depId: Int, parentId: Int) {
        when (val dep = nodes[depId]) {
            is Node.Cell -> addEdge(dep.dependents, parentId)
            is Node.Slot -> addEdge(dep.dependents, parentId)
            else -> {}
        }
        when (val parent = nodes[parentId]) {
            is Node.Slot -> addEdge(parent.dependencies, depId)
            is Node.Effect -> addEdge(parent.dependencies, depId)
            else -> {}
        }
    }

    private fun removeDependentEdge(depId: Int, parentId: Int) {
        when (val dep = nodes[depId]) {
            is Node.Cell -> removeEdge(dep.dependents, parentId)
            is Node.Slot -> removeEdge(dep.dependents, parentId)
            else -> {}
        }
    }

    // -- Internals: refresh / recompute (pull-based, glitch-free) ----------

    private fun refreshSlot(id: Int): Boolean {
        val node = nodes[id] as? Node.Slot ?: return false
        // Fast path: clean cache hit (mirrors lazily-rs refresh_slot + Context).
        if (node.hasValue && !node.dirty && !node.forceRecompute) return false
        if (node.inProgress) {
            error("lazily: circular dependency detected at slot $id; a computed/memo slot depends on itself")
        }
        node.inProgress = true
        try {
            var dependencyChanged = false
            // #lzktdepslist: index iteration over the dependency list (no toList()
            // copy). The held ReentrantLock serializes the whole graph, and
            // refreshing a dependency clears that dep's own list — not this
            // node's — so the index view is stable across the loop.
            val deps = node.dependencies
            for (i in deps.indices) {
                val dep = deps[i]
                if (nodes[dep] is Node.Slot && refreshSlot(dep)) dependencyChanged = true
            }
            val needsRecompute = !node.hasValue || node.forceRecompute || dependencyChanged
            if (!needsRecompute) {
                node.dirty = false
                node.forceRecompute = false
                return false
            }
            return recomputeSlotNow(id, node)
        } finally {
            node.inProgress = false
        }
    }

    private fun recomputeSlotNow(id: Int, node: Node.Slot): Boolean {
        for (dep in node.dependencies) removeDependentEdge(dep, id)
        node.dependencies.clear()
        val compute = node.compute
        val stack = trackingStack.get()
        stack.addLast(id)
        val result: Any? = try {
            compute()
        } finally {
            stack.removeLast()
        }
        val unchanged = node.memo && node.hasValue && node.value == result
        node.dirty = false
        node.forceRecompute = false
        if (unchanged) return false
        val hadValue = node.hasValue
        node.value = result
        node.hasValue = true
        if (hadValue) notifySlotValueChanged(id)
        return hadValue
    }

    private fun notifySlotValueChanged(id: Int) {
        val deps = (nodes[id] as? Node.Slot)?.dependents ?: return
        val stack = ArrayDeque<Int>()
        val forceStack = ArrayDeque<Boolean>()
        val frame = trackingStack.get()
        for (d in deps) {
            // Skip a dependent that is currently on this thread's tracking stack:
            // it is mid-recompute and reading this slot right now, so it will
            // observe the fresh value directly — re-invalidating it would
            // redundantly re-run it (glitch-free guarantee). Port of the Context
            // guard (trackingStack is ThreadLocal here).
            if (d in frame) continue
            stack.addLast(d); forceStack.addLast(true)
        }
        runFrontier(stack, forceStack)
    }

    // -- Internals: invalidation propagation ------------------------------

    /**
     * Iterative DFS frontier dirty-marking (#lzbatchborrow, mirrors lazily-rs
     * mark_frontier_locked + Context.runFrontier). Returns true iff at least one
     * Effect was scheduled (store-without-cascade gate).
     */
    private fun runFrontier(stack: ArrayDeque<Int>, forceStack: ArrayDeque<Boolean>): Boolean {
        var scheduled = false
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            val force = forceStack.removeLast()
            when (val node = nodes[id]) {
                is Node.Slot -> {
                    val shouldPropagate = !node.dirty || (force && !node.forceRecompute)
                    node.dirty = true
                    if (force) node.forceRecompute = true
                    if (shouldPropagate) {
                        for (dep in node.dependents) {
                            stack.addLast(dep); forceStack.addLast(false)
                        }
                    }
                }
                is Node.Effect -> {
                    scheduleEffect(id, force)
                    scheduled = true
                }
                else -> {}
            }
        }
        return scheduled
    }

    /** Returns true iff at least one Effect was scheduled (store-without-cascade
     *  fast path: a false result means no flush is owed). */
    private fun invalidateCellDependentsNow(id: Int): Boolean {
        val deps = (nodes[id] as? Node.Cell)?.dependents ?: return false
        val stack = ArrayDeque<Int>()
        val forceStack = ArrayDeque<Boolean>()
        for (d in deps) { stack.addLast(d); forceStack.addLast(true) }
        return runFrontier(stack, forceStack)
    }

    // -- Internals: effect scheduling / flush ------------------------------

    private fun scheduleEffect(id: Int, force: Boolean) {
        // Disposal is not a publish — see [disposalDepth].
        if (disposalDepth > 0) return
        val node = nodes[id] as? Node.Effect ?: return
        if (force) node.forceRun = true
        if (scheduledEffects.add(id)) pendingEffects.addLast(id)
    }

    private fun flushEffects() {
        if (flushingEffects) return
        flushingEffects = true
        try {
            while (true) {
                val id = pendingEffects.removeFirstOrNull() ?: return
                scheduledEffects.remove(id)
                runEffect(id)
            }
        } finally {
            flushingEffects = false
        }
    }

    private fun runEffect(id: Int) {
        if (!effectShouldRun(id)) return
        val node = nodes[id] as? Node.Effect ?: return
        for (dep in node.dependencies) removeDependentEdge(dep, id)
        node.dependencies.clear()
        val cleanup = node.cleanup
        node.cleanup = null
        node.forceRun = false
        val run = node.run
        cleanup?.invoke()
        val stack = trackingStack.get()
        stack.addLast(id)
        val nextCleanup = try {
            run()
        } finally {
            stack.removeLast()
        }
        val current = nodes[id] as? Node.Effect
        if (current != null) current.cleanup = nextCleanup
        else nextCleanup?.invoke()
    }

    private fun effectShouldRun(id: Int): Boolean {
        val node = nodes[id] as? Node.Effect ?: return false
        if (node.forceRun) return true
        return node.dependencies.any { nodes[it] is Node.Slot && refreshSlot(it) }
    }
}

/**
 * A teardown scope over a [ThreadSafeContext]. The thread-safe mirror of
 * [TeardownScope] — see that class for why the end is an explicit statement
 * rather than a destructor, and why `use` is the lexical spelling.
 *
 * The scope's *own* bookkeeping (the owned list) is not synchronized: a scope is
 * a caller-side ownership record, and sharing one across threads would make its
 * creation order — the thing teardown order is defined against — meaningless.
 * Build a scope on the thread that owns the lifetime. Each individual
 * [ThreadSafeContext.disposeNode] it performs is still atomic under the graph
 * lock.
 */
class ThreadSafeTeardownScope internal constructor(
    /** The context this scope belongs to. */
    val ctx: ThreadSafeContext,
) : AutoCloseable {
    private val owned = ArrayList<ThreadSafeGraphNode>()
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
    fun <T : ThreadSafeGraphNode> adopt(node: T): T {
        if (!ended) owned.add(node)
        return node
    }

    /** A source owned by this scope. */
    inline fun <reified T : Any> source(value: T): ThreadSafeSource<T> = adopt(ctx.source(value))

    @Deprecated("Renamed to source (#lzcellkernel).", ReplaceWith("source(value)"))
    inline fun <reified T : Any> cell(value: T): ThreadSafeSource<T> = source(value)

    /** A lazy derived slot owned by this scope. */
    inline fun <reified T : Any> computed(
        noinline compute: ThreadSafeContext.() -> T,
    ): ThreadSafeComputed<T> = adopt(ctx.computed(compute))

    /** Deprecated compatibility alias of [computed]. */
    @Deprecated("Renamed to computed (#lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> slot(
        noinline compute: ThreadSafeContext.() -> T,
    ): ThreadSafeComputed<T> = computed(compute)

    /** Deprecated compatibility alias of guarded [computed]. */
    @Deprecated("Use computed; every computed is guarded (#lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> memo(
        noinline compute: ThreadSafeContext.() -> T,
    ): ThreadSafeComputed<T> = computed(compute)

    /** An effect owned by this scope. */
    fun effect(run: ThreadSafeContext.() -> (() -> Unit)?): ThreadSafeEffectHandle =
        adopt(ctx.effect(run))

    /** Cancel this scope's teardown. See `TeardownScope.disarm`. */
    fun disarm() {
        owned.clear()
    }

    /** Dispose every owned node in reverse creation order. See `TeardownScope.end`. */
    fun end() {
        if (ended) return
        ended = true
        for (i in owned.indices.reversed()) ctx.disposeNode(owned[i])
        owned.clear()
    }

    /** [end], so `ctx.scope().use { ... }` is the lexical form. */
    override fun close() = end()

    override fun toString(): String =
        if (ended) "ThreadSafeTeardownScope(ended)" else "ThreadSafeTeardownScope(${owned.size} owned)"
}
