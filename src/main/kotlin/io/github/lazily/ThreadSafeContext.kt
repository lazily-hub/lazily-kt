package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// -- Handles -----------------------------------------------------------------

/**
 * Lightweight typed reference to a lazy memoized slot in a [ThreadSafeContext].
 *
 * Clonable by value (a `value class` over an id), mirroring lazily-rs
 * `ThreadSafeSlotHandle<T>: Copy`.
 */
@JvmInline
value class ThreadSafeSlotHandle<T : Any> @PublishedApi internal constructor(val id: Int)

/**
 * Lightweight typed reference to a mutable source cell in a [ThreadSafeContext].
 * Clonable by value, mirroring lazily-rs `ThreadSafeCellHandle<T>: Copy`.
 */
@JvmInline
value class ThreadSafeCellHandle<T : Any> @PublishedApi internal constructor(val id: Int)

/**
 * Reference to a registered side-effecting observer in a [ThreadSafeContext].
 * Clonable by value. Dispose to stop reruns.
 */
@JvmInline
value class ThreadSafeEffectHandle @PublishedApi internal constructor(val id: Int)

/**
 * Reference to an **eager** derived value in a [ThreadSafeContext]. Composed of
 * a memoized backing slot plus a puller effect that re-materializes the slot
 * after every invalidation. Composed of clonable value-class handles, so it is
 * freely shareable across threads through the owning context.
 */
class ThreadSafeSignalHandle<T : Any> @PublishedApi internal constructor(
    @PublishedApi internal val slot: ThreadSafeSlotHandle<T>,
    @PublishedApi internal val effect: ThreadSafeEffectHandle,
) {
    /** Dispose this signal's eager puller; the backing value stays readable (reverts to lazy). */
    fun dispose(ctx: ThreadSafeContext) = ctx.disposeSignal(this)
}

// -- Context -----------------------------------------------------------------

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
 *   `setCell`/`batch`**, preserving glitch-free pull-based ordering. The JVM
 *   memory model's monitor happens-before guarantee is the counterpart of
 *   Rust's `Send + Sync` obligation: every value and callback published under
 *   the lock is safely visible to any thread that observes it under the lock.
 * - **Clonable handles** — [ThreadSafeSlotHandle] / [ThreadSafeCellHandle] /
 *   [ThreadSafeEffectHandle] / [ThreadSafeSignalHandle] are value classes
 *   (copyable by value), so a handle minted on one thread may be read on
 *   another through the shared context.
 * - **Per-thread dependency tracking** — a `ThreadLocal` tracking stack
 *   discovers each reading frame's edges, mirroring lazily-rs's `thread_local!`
 *   tracking stack; two threads computing concurrently never mix their edges
 *   (and the graph lock serializes recomputation regardless).
 *
 * `ReentrantLock` is reentrant, so a compute/effect callback that re-enters the
 * same context from the same thread (e.g. a slot reading another slot) does not
 * self-deadlock. As in [Context], `setCell` is `==`-guarded (equal value is a
 * no-op), `memo` adds the same guard to a recompute, a `Signal` is materialized
 * by the time the invalidating `setCell`/`batch` returns, and `batch` coalesces
 * invalidations into one effect flush.
 */
class ThreadSafeContext {
    private sealed interface Node {
        class Cell(var value: Any?, val dependents: MutableSet<Int> = HashSet()) : Node
        class Slot(
            var value: Any? = null,
            var hasValue: Boolean = false,
            val memo: Boolean = false,
            val compute: ThreadSafeContext.() -> Any? = { null },
            val dependencies: MutableSet<Int> = HashSet(),
            val dependents: MutableSet<Int> = HashSet(),
            var dirty: Boolean = false,
            var forceRecompute: Boolean = false,
            var inProgress: Boolean = false,
        ) : Node
        class Effect(
            val run: ThreadSafeContext.() -> (() -> Unit)?,
            val dependencies: MutableSet<Int> = HashSet(),
            var cleanup: (() -> Unit)? = null,
            var forceRun: Boolean = false,
        ) : Node
    }

    private val lock = ReentrantLock()
    private val nodes: MutableMap<Int, Node> = HashMap()
    private var nextId: Int = 1
    private val freeIds: ArrayDeque<Int> = ArrayDeque()

    private val pendingEffects: ArrayDeque<Int> = ArrayDeque()
    private val scheduledEffects: MutableSet<Int> = HashSet()
    private var flushingEffects: Boolean = false

    @PublishedApi
    internal var batchDepth: Int = 0
    private val batchedCells: MutableSet<Int> = HashSet()

    /**
     * Per-thread tracking frame stack: the id of the slot/effect currently
     * computing on *this* thread. Each thread discovers its own edges; the
     * graph lock still serializes all recomputation.
     */
    private val trackingStack: ThreadLocal<ArrayDeque<Int>> =
        ThreadLocal.withInitial { ArrayDeque() }

    // -- Creation ----------------------------------------------------------

    /** A mutable source cell with an initial value. `setCell` invalidates dependents on `==` change. */
    inline fun <reified T : Any> cell(value: T): ThreadSafeCellHandle<T> =
        ThreadSafeCellHandle(cellAny(value))

    @PublishedApi
    internal fun cellAny(value: Any): Int = locked {
        val id = allocId()
        nodes[id] = Node.Cell(value)
        id
    }

    /** A lazy derived slot (no memo guard): recomputes on read when deps invalidate. */
    inline fun <reified T : Any> computed(noinline compute: ThreadSafeContext.() -> T): ThreadSafeSlotHandle<T> =
        ThreadSafeSlotHandle(slotAny(memo = false) { compute() })

    /** Alias of [computed] for symmetry with lazily-rs/py/zig. */
    inline fun <reified T : Any> slot(noinline compute: ThreadSafeContext.() -> T): ThreadSafeSlotHandle<T> =
        computed(compute)

    /** A lazy derived slot with a `==` memo guard: an equal recompute suppresses downstream. */
    inline fun <reified T : Any> memo(noinline compute: ThreadSafeContext.() -> T): ThreadSafeSlotHandle<T> =
        ThreadSafeSlotHandle(slotAny(memo = true) { compute() })

    @PublishedApi
    internal fun slotAny(memo: Boolean, compute: ThreadSafeContext.() -> Any?): Int = locked {
        val id = allocId()
        nodes[id] = Node.Slot(memo = memo, compute = compute)
        id
    }

    /**
     * An **eager** derived value: a memo slot plus a puller effect. The value is
     * materialized at creation and re-materialized by the time the invalidating
     * `setCell`/`batch` returns — observers never see an intermediate unset state.
     */
    inline fun <reified T : Any> signal(noinline compute: ThreadSafeContext.() -> T): ThreadSafeSignalHandle<T> {
        val ids = signalAny { compute() }
        return ThreadSafeSignalHandle(ThreadSafeSlotHandle(ids.slot), ThreadSafeEffectHandle(ids.effect))
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

    /** Read a slot, computing/refreshing if necessary; auto-subscribes the reading node. */
    inline fun <reified T : Any> get(handle: ThreadSafeSlotHandle<T>): T {
        @Suppress("UNCHECKED_CAST")
        return getSlotAny(handle.id) as T
    }

    /** Read a cell; auto-subscribes the reading node. */
    inline fun <reified T : Any> getCell(handle: ThreadSafeCellHandle<T>): T {
        @Suppress("UNCHECKED_CAST")
        return getCellAny(handle.id) as T
    }

    /** Read a signal's current (always-materialized) value; auto-subscribes. */
    inline fun <reified T : Any> getSignal(handle: ThreadSafeSignalHandle<T>): T = get(handle.slot)

    @PublishedApi
    internal fun getSlotAny(id: Int): Any = locked {
        currentFrame()?.let { registerDependency(id, it) }
        refreshSlot(id)
        val node = nodes[id] as? Node.Slot ?: error("get on non-slot id $id")
        check(node.hasValue) { "slot $id has no value" }
        node.value as Any
    }

    @PublishedApi
    internal fun getCellAny(id: Int): Any = locked {
        currentFrame()?.let { registerDependency(id, it) }
        val node = nodes[id] as? Node.Cell ?: error("get_cell on non-cell id $id")
        node.value as Any
    }

    // -- Write -------------------------------------------------------------

    /** Set a cell's value. A no-op (no invalidation) when the new value `==` the old. */
    fun <T : Any> setCell(handle: ThreadSafeCellHandle<T>, value: T) = setCellAny(handle.id, value)

    @PublishedApi
    internal fun setCellAny(id: Int, value: Any) = locked {
        val node = nodes[id] as? Node.Cell ?: error("set_cell on non-cell id $id")
        if (node.value != value) {
            node.value = value
            if (isBatching()) {
                batchedCells.add(id)
            } else {
                invalidateCellDependentsNow(id)
                flushEffects()
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
        val cells = batchedCells.toList()
        batchedCells.clear()
        for (id in cells) invalidateCellDependentsNow(id)
        flushEffects()
    }

    private fun isBatching(): Boolean = batchDepth > 0

    // -- Dispose -----------------------------------------------------------

    /** Dispose an effect: deschedule, drop edges, run its cleanup, recycle the id. */
    fun disposeEffect(handle: ThreadSafeEffectHandle) = locked {
        val id = handle.id
        pendingEffects.remove(id)
        scheduledEffects.remove(id)
        val node = nodes.remove(id) as? Node.Effect ?: return@locked
        freeIds.addLast(id)
        for (dep in node.dependencies.toList()) removeDependentEdge(dep, id)
        node.cleanup?.invoke()
    }

    fun isEffectActive(handle: ThreadSafeEffectHandle): Boolean = locked {
        nodes[handle.id] is Node.Effect
    }

    /** Dispose a signal's eager puller; the backing value stays readable (reverts to lazy). */
    fun disposeSignal(handle: ThreadSafeSignalHandle<*>) = disposeEffect(handle.effect)

    fun isSignalActive(handle: ThreadSafeSignalHandle<*>): Boolean = isEffectActive(handle.effect)

    /** Whether a slot currently has a fresh cached value (testing). */
    fun isSet(handle: ThreadSafeSlotHandle<*>): Boolean = locked {
        val node = nodes[handle.id] as? Node.Slot ?: return@locked false
        node.hasValue && !node.dirty
    }

    // -- Internals: lock helper -------------------------------------------

    private inline fun <T> locked(action: () -> T): T = lock.withLock(action)

    // -- Internals: id + edges --------------------------------------------

    private fun allocId(): Int = freeIds.removeLastOrNull() ?: nextId++

    private fun currentFrame(): Int? = trackingStack.get().lastOrNull()

    private fun registerDependency(depId: Int, parentId: Int) {
        when (val dep = nodes[depId]) {
            is Node.Cell -> dep.dependents.add(parentId)
            is Node.Slot -> dep.dependents.add(parentId)
            else -> {}
        }
        when (val parent = nodes[parentId]) {
            is Node.Slot -> parent.dependencies.add(depId)
            is Node.Effect -> parent.dependencies.add(depId)
            else -> {}
        }
    }

    private fun removeDependentEdge(depId: Int, parentId: Int) {
        when (val dep = nodes[depId]) {
            is Node.Cell -> dep.dependents.remove(parentId)
            is Node.Slot -> dep.dependents.remove(parentId)
            else -> {}
        }
    }

    // -- Internals: refresh / recompute (pull-based, glitch-free) ----------

    private fun refreshSlot(id: Int): Boolean {
        val node = nodes[id] as? Node.Slot ?: return false
        if (node.inProgress) {
            error("lazily: circular dependency detected at slot $id; a computed/memo slot depends on itself")
        }
        node.inProgress = true
        try {
            var dependencyChanged = false
            for (dep in node.dependencies.toList()) {
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
        for (dep in node.dependencies.toList()) removeDependentEdge(dep, id)
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
        val deps = (nodes[id] as? Node.Slot)?.dependents?.toList() ?: return
        for (d in deps) invalidateDependentFromChangedValue(d)
    }

    // -- Internals: invalidation propagation ------------------------------

    private fun invalidateCellDependentsNow(id: Int) {
        val deps = (nodes[id] as? Node.Cell)?.dependents?.toList() ?: return
        for (d in deps) invalidateDependentFromChangedValue(d)
    }

    private fun invalidateDependentFromChangedValue(id: Int) {
        if (nodes[id] is Node.Effect) scheduleEffect(id, force = true)
        else markSlotDirty(id, force = true)
    }

    private fun markSlotDirty(id: Int, force: Boolean) {
        val node = nodes[id] as? Node.Slot ?: return
        val shouldPropagate = !node.dirty || (force && !node.forceRecompute)
        node.dirty = true
        if (force) node.forceRecompute = true
        if (!shouldPropagate) return
        for (d in node.dependents.toList()) {
            if (nodes[d] is Node.Effect) scheduleEffect(d, force = false)
            else markSlotDirty(d, force = false)
        }
    }

    // -- Internals: effect scheduling / flush ------------------------------

    private fun scheduleEffect(id: Int, force: Boolean) {
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
        val oldDeps = node.dependencies.toList()
        node.dependencies.clear()
        val cleanup = node.cleanup
        node.cleanup = null
        node.forceRun = false
        val run = node.run
        for (dep in oldDeps) removeDependentEdge(dep, id)
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
