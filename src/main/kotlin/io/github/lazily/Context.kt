package io.github.lazily

// -- Handles -----------------------------------------------------------------

/** Lightweight typed reference to a lazy memoized slot, mirroring lazily-rs `SlotHandle<T>`. */
@JvmInline
value class SlotHandle<T : Any> @PublishedApi internal constructor(val id: Int)

/** Lightweight typed reference to a mutable source cell, mirroring lazily-rs `CellHandle<T>`. */
@JvmInline
value class CellHandle<T : Any> @PublishedApi internal constructor(val id: Int)

/** Reference to a registered side-effecting observer. Dispose to stop reruns. */
class EffectHandle @PublishedApi internal constructor(val id: Int)

/**
 * Reference to an **eager** derived value. Composed of a memoized backing slot
 * plus a puller effect that re-materializes the slot after every invalidation.
 */
class SignalHandle<T : Any> @PublishedApi internal constructor(val slot: SlotHandle<T>, val effect: EffectHandle)

// -- Context -----------------------------------------------------------------

private const val INITIAL_NODE_CAPACITY = 16

/**
 * A reactive dependency graph: the lazily-rs `Context` semantics ported to
 * native Kotlin.
 *
 * The reactive family is **Slot** (lazy memoized derived) → **Cell** (mutable
 * source) → **Signal** (eager derived), plus **Effect** (side-effecting
 * observer). Reading a cell/slot/signal inside a computation auto-registers a
 * dependency edge; mutating a cell invalidates dependents.
 *
 * - Lazy slots mark dirty on invalidation and recompute on the next read
 *   (pull-based, glitch-free: a slot that reads other slots always observes
 *   values consistent with the current inputs).
 * - Cells use a `==` (PartialEq) guard: setting an equal value is a no-op.
 * - `memo` adds a `==` guard so an equal recompute suppresses downstream.
 * - Signals are eager: a backing memo slot plus a puller effect — the value is
 *   re-materialized by the time the invalidating `setCell`/`batch` returns.
 * - Effects rerun after any tracked dependency invalidates.
 *
 * Single-threaded by design (mirrors lazily-rs `Context`, which uses `RefCell`).
 * A thread-safe counterpart would mirror `ThreadSafeContext`.
 *
 * Compose with [StateMachine] for a reactive FSM, exactly as in lazily-rs/py/zig.
 */
class Context {
    private sealed interface Node {
        class Cell(var value: Any?, val dependents: ArrayList<Int> = ArrayList()) : Node
        class Slot(
            var value: Any? = null,
            var hasValue: Boolean = false,
            val memo: Boolean = false,
            val compute: Context.() -> Any? = { null },
            val dependencies: ArrayList<Int> = ArrayList(),
            val dependents: ArrayList<Int> = ArrayList(),
            var dirty: Boolean = false,
            var forceRecompute: Boolean = false,
            var inProgress: Boolean = false,
        ) : Node
        class Effect(
            val run: Context.() -> (() -> Unit)?,
            val dependencies: ArrayList<Int> = ArrayList(),
            var cleanup: (() -> Unit)? = null,
            var forceRun: Boolean = false,
        ) : Node
    }

    // Dense node arena indexed directly by id (mirrors lazily-rs
    // `Vec<Option<Node>>` context.rs ~161-175). ids are dense 1..nextId with
    // LIFO recycling via [freeIds], so a direct array index replaces the
    // `HashMap<Int,Node>` lookup and eliminates Int autoboxing on every op.
    private var nodes: Array<Node?> = arrayOfNulls(INITIAL_NODE_CAPACITY)
    private var nextId: Int = 1
    private val freeIds: ArrayDeque<Int> = ArrayDeque()

    /** Tracking frame stack: the currently-computing slot/effect id (auto-dep discovery). */
    private val trackingStack: ArrayDeque<Int> = ArrayDeque()

    private val pendingEffects: ArrayDeque<Int> = ArrayDeque()
    private val scheduledEffects: MutableSet<Int> = HashSet()
    private var flushingEffects: Boolean = false
    @PublishedApi
    internal var batchDepth: Int = 0
    private val batchedCells: MutableSet<Int> = HashSet()
    private val batchedSlots: MutableSet<Int> = HashSet()

    // -- Creation ----------------------------------------------------------

    /** A mutable source cell with an initial value. `setCell` invalidates dependents on `==` change. */
    inline fun <reified T : Any> cell(value: T): CellHandle<T> = CellHandle(cellAny(value))

    @PublishedApi
    internal fun cellAny(value: Any): Int {
        val id = allocId()
        nodes[id] = Node.Cell(value)
        return id
    }

    /** A lazy derived slot (no memo guard): recomputes on read when deps invalidate. */
    inline fun <reified T : Any> computed(noinline compute: Context.() -> T): SlotHandle<T> =
        SlotHandle(slotAny(memo = false) { compute() })

    /** Alias of [computed] for symmetry with lazily-rs/py/zig. */
    inline fun <reified T : Any> slot(noinline compute: Context.() -> T): SlotHandle<T> = computed(compute)

    /** A lazy derived slot with a `==` memo guard: an equal recompute suppresses downstream. */
    inline fun <reified T : Any> memo(noinline compute: Context.() -> T): SlotHandle<T> =
        SlotHandle(slotAny(memo = true) { compute() })

    @PublishedApi
    internal fun slotAny(memo: Boolean, compute: Context.() -> Any?): Int {
        val id = allocId()
        nodes[id] = Node.Slot(memo = memo, compute = compute)
        return id
    }

    /**
     * An **eager** derived value: a memo slot plus a puller effect. The value is
     * materialized at creation and re-materialized by the time the invalidating
     * `setCell`/`batch` returns — observers never see an intermediate unset state.
     */
    inline fun <reified T : Any> signal(noinline compute: Context.() -> T): SignalHandle<T> {
        val ids = signalAny { compute() }
        return SignalHandle(SlotHandle(ids.slot), EffectHandle(ids.effect))
    }

    @PublishedApi
    internal class SignalIds(@PublishedApi internal val slot: Int, @PublishedApi internal val effect: Int)

    @PublishedApi
    internal fun signalAny(compute: Context.() -> Any?): SignalIds {
        val slot = slotAny(memo = true, compute = compute)
        val effect = effectAny { getSlotAny(slot); null }
        return SignalIds(slot, effect)
    }

    /**
     * Run [run] immediately, then rerun it after any tracked cell/slot/signal it
     * reads invalidates. [run] may return a cleanup closure (`(() -> Unit)?`);
     * cleanup runs before each rerun and on dispose.
     */
    fun effect(run: Context.() -> (() -> Unit)?): EffectHandle = EffectHandle(effectAny(run))

    @PublishedApi
    internal fun effectAny(run: Context.() -> (() -> Unit)?): Int {
        val id = allocId()
        nodes[id] = Node.Effect(run = run, forceRun = true)
        scheduleEffect(id, force = false)
        flushEffects()
        return id
    }

    // -- Read --------------------------------------------------------------

    /** Read a slot, computing/refreshing if necessary; auto-subscribes the reading node. */
    inline fun <reified T : Any> get(handle: SlotHandle<T>): T {
        @Suppress("UNCHECKED_CAST")
        return getSlotAny(handle.id) as T
    }

    /** Read a cell; auto-subscribes the reading node. */
    inline fun <reified T : Any> getCell(handle: CellHandle<T>): T {
        @Suppress("UNCHECKED_CAST")
        return getCellAny(handle.id) as T
    }

    /** Read a signal's current (always-materialized) value; auto-subscribes. */
    inline fun <reified T : Any> getSignal(handle: SignalHandle<T>): T = get(handle.slot)

    @PublishedApi
    internal fun getSlotAny(id: Int): Any {
        currentFrame()?.let { registerDependency(id, it) }
        refreshSlot(id)
        val node = nodes[id] as? Node.Slot ?: error("get on non-slot id $id")
        check(node.hasValue) { "slot $id has no value" }
        return node.value as Any
    }

    @PublishedApi
    internal fun getCellAny(id: Int): Any {
        currentFrame()?.let { registerDependency(id, it) }
        val node = nodes[id] as? Node.Cell ?: error("get_cell on non-cell id $id")
        return node.value as Any
    }

    // -- Write -------------------------------------------------------------

    /** Set a cell's value. A no-op (no invalidation) when the new value `==` the old. */
    fun <T : Any> setCell(handle: CellHandle<T>, value: T) = setCellAny(handle.id, value)

    @PublishedApi
    internal fun setCellAny(id: Int, value: Any) {
        val node = nodes[id] as? Node.Cell ?: error("set_cell on non-cell id $id")
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

    /** Run several cell updates as one invalidation + effect flush at the outermost batch. */
    inline fun batch(run: Context.() -> Unit) {
        batchDepth++
        try {
            run()
        } finally {
            finishBatch()
        }
    }

    @PublishedApi
    internal fun finishBatch() {
        check(batchDepth > 0) { "finishBatch without active batch" }
        batchDepth--
        if (batchDepth == 0) flushBatched()
    }

    private fun flushBatched() {
        // Merged frontier: collect invalidation roots from ALL batched cells +
        // slots into ONE DFS pass (#lzbatchborrow, mirrors lazily-rs
        // flush_batched_invalidations). Avoids N separate DFS walks for N
        // batched cells.
        val stack = ArrayDeque<Int>()
        val forceStack = ArrayDeque<Boolean>()
        for (cellId in batchedCells) {
            val cell = nodes[cellId] as? Node.Cell ?: continue
            for (dep in cell.dependents) { stack.addLast(dep); forceStack.addLast(true) }
        }
        batchedCells.clear()
        for (slotId in batchedSlots) { stack.addLast(slotId); forceStack.addLast(true) }
        batchedSlots.clear()
        runFrontier(stack, forceStack)
        flushEffects()
    }

    private fun isBatching(): Boolean = batchDepth > 0

    /**
     * Batch-aware external invalidation of derived slots (used by demand-driven
     * readers like [QueueCell] reader-kinds that derive from an out-of-graph
     * mutation source). Marks each slot dirty and cascades to dependents, then
     * flushes effects exactly once. Inside a [batch] the flush is deferred to the
     * boundary. A slot with no subscriber cascades to nothing — an unobserved op
     * pays effectively nothing.
     */
    internal fun invalidateSlots(ids: IntArray) {
        if (isBatching()) {
            for (id in ids) batchedSlots.add(id)
            return
        }
        val stack = ArrayDeque<Int>()
        val forceStack = ArrayDeque<Boolean>()
        for (id in ids) { stack.addLast(id); forceStack.addLast(true) }
        val scheduled = runFrontier(stack, forceStack)
        if (scheduled) flushEffects()
    }

    // -- Dispose -----------------------------------------------------------

    /** Dispose an effect: deschedule, drop edges, run its cleanup, recycle the id. */
    fun disposeEffect(handle: EffectHandle) {
        val id = handle.id
        pendingEffects.remove(id)
        scheduledEffects.remove(id)
        val node = nodes[id] as? Node.Effect ?: return
        nodes[id] = null
        freeIds.addLast(id)
        for (dep in node.dependencies) removeDependentEdge(dep, id)
        node.cleanup?.invoke()
    }

    fun isEffectActive(handle: EffectHandle): Boolean = nodes[handle.id] is Node.Effect

    /** Dispose a signal's eager puller; the backing value stays readable (reverts to lazy). */
    fun disposeSignal(handle: SignalHandle<*>) = disposeEffect(handle.effect)

    fun isSignalActive(handle: SignalHandle<*>): Boolean = isEffectActive(handle.effect)

    /** Whether a slot currently has a fresh cached value (testing). */
    fun isSet(handle: SlotHandle<*>): Boolean {
        val node = nodes[handle.id] as? Node.Slot ?: return false
        return node.hasValue && !node.dirty
    }

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

    private fun currentFrame(): Int? = trackingStack.lastOrNull()

    // Small edge containers: most nodes carry 0-2 edges, so a flat ArrayList
    // with linear dedup beats a LinkedHashSet (mirrors lazily-rs SmallVec<[_;2]>
    // + edge_insert/edge_remove context.rs ~26-68). Order-preserving to match
    // the former LinkedHashSet effect-scheduling order.
    private fun addEdge(edges: ArrayList<Int>, id: Int) {
        if (id !in edges) edges.add(id)
    }

    private fun removeEdge(edges: ArrayList<Int>, id: Int) {
        val i = edges.indexOf(id)
        if (i >= 0) edges.removeAt(i)
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
        // Fast path: clean cache hit. When the slot holds a value and is
        // neither dirty nor force-recompute, no upstream value can have changed
        // since the last compute — invalidation always sets dirty=true on
        // dependents. The dependency-refresh walk, cycle guard, and dirty-flag
        // clear are all unnecessary on this path (mirrors lazily-rs context.rs
        // refresh_slot fast path).
        if (node.hasValue && !node.dirty && !node.forceRecompute) return false
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
        for (dep in node.dependencies) removeDependentEdge(dep, id)
        node.dependencies.clear()
        val compute = node.compute
        trackingStack.addLast(id)
        val result: Any? = try {
            compute()
        } finally {
            trackingStack.removeLast()
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
        for (d in deps) {
            // Skip a dependent that is currently on the tracking stack: it is
            // mid-recompute and reading this slot right now, so it will observe
            // the fresh value directly — re-invalidating it would redundantly
            // re-run it (glitch-free guarantee). This matters when a demand-driven
            // reader Slot recomputes inside the very Effect reading it.
            if (d in trackingStack) continue
            stack.addLast(d); forceStack.addLast(true)
        }
        runFrontier(stack, forceStack)
    }

    // -- Internals: invalidation propagation ------------------------------

    /**
     * Iterative DFS frontier dirty-marking (#lzbatchborrow, mirrors lazily-rs
     * mark_frontier_locked). Roots carry their seeded `force` flag (true for
     * invalidation/clear roots, false for transitive descendants). Marks
     * dirty/forceRecompute in place and iterates each node's dependents directly
     * — the marking walk never mutates a dependents edge set, so no per-node
     * snapshot copy is needed. Effects are scheduled inline; returns true iff at
     * least one Effect was scheduled (store-without-cascade gate).
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
        trackingStack.addLast(id)
        val nextCleanup = try {
            run()
        } finally {
            trackingStack.removeLast()
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
