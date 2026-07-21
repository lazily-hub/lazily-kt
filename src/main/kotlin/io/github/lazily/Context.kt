package io.github.lazily

// -- Handles -----------------------------------------------------------------
//
// The value-node concept `Cell<T>` and its two concrete kinds `Source` /
// `Computed` live in Cell.kt (the v2 Cell kernel — #lzcellkernel). Effect stays
// here: it is a sink, outside the `Cell` hierarchy.

/** Reference to a registered side-effecting observer. Dispose to stop reruns. */
class Effect @PublishedApi internal constructor(val id: Int) : GraphNode {
    override val nodeId: Int get() = id
}

/** @suppress Renamed to [Effect] (v2 Cell kernel — #lzcellkernel). */
@Deprecated("Renamed to Effect (v2 Cell kernel — #lzcellkernel).", ReplaceWith("Effect"))
typealias EffectHandle = Effect

/**
 * @suppress Back-compat wrapper for the retired eager-`Signal` surface. The
 * eager construction is now an **eager** [Computed] (`computed().eager()`); this
 * pair of a computed cell plus its puller effect is kept only so callers of the
 * former `signal()` keep compiling.
 */
@Deprecated("Signal is retired: use `ctx.computed { … }.eager(ctx)` — an eager Computed (#lzcellkernel).")
class SignalHandle<T : Any> @PublishedApi internal constructor(
    val slot: Computed<T>,
    val effect: Effect,
)

// -- Context -----------------------------------------------------------------

private const val INITIAL_NODE_CAPACITY = 16

/**
 * A reactive dependency graph: the lazily-rs `Context` semantics ported to
 * native Kotlin.
 *
 * The kernel is the value-node concept [Cell]`<T>` (see Cell.kt) over two
 * concrete kinds — [Source] (written from outside) and [Computed] (computed from
 * upstream) — plus [Effect] (a side-effecting sink, outside the hierarchy).
 * Reading any cell inside a computation auto-registers a dependency edge; writing
 * a source invalidates dependents.
 *
 * - A [Computed] marks dirty on invalidation and recomputes on the next read
 *   (pull-based, glitch-free: a computed that reads other computeds always
 *   observes values consistent with the current inputs). Every [Computed] is
 *   **guarded** (`==`) — there is no unguarded mode — so an equal recompute
 *   suppresses downstream (matching TC39 `Signal.Computed`).
 * - A [Source] uses a `==` guard: setting an equal value is a no-op.
 * - An **eager** computed (`computed().eager()`) re-materializes its value by the
 *   time the invalidating `set`/`batch` returns. This is the eager construction
 *   that retires the former `Signal`; eagerness is a bit on the node plus the
 *   [eagerBy] side table, not a distinct type.
 * - Effects rerun after any tracked dependency invalidates.
 *
 * Single-threaded by design (mirrors lazily-rs `Context`, which uses `RefCell`).
 * A thread-safe counterpart would mirror `ThreadSafeContext`.
 *
 * Compose with [StateMachine] for a reactive FSM, exactly as in lazily-rs/py/zig.
 */
class Context {
    private sealed interface Node {
        class Cell(var value: Any?, val dependents: SmallEdgeList = SmallEdgeList()) : Node
        class Slot(
            var value: Any? = null,
            var hasValue: Boolean = false,
            val compute: Context.() -> Any? = { null },
            val dependencies: SmallEdgeList = SmallEdgeList(),
            val dependents: SmallEdgeList = SmallEdgeList(),
            var dirty: Boolean = false,
            var forceRecompute: Boolean = false,
            var inProgress: Boolean = false,
            // "Am I eager?" — an eager computed has an eager puller effect
            // (`computed().eager()`). Free: lands in the padding the other bools
            // already occupy. The puller's id is kept off the node in the
            // [eagerBy] side table (#lzcellkernel §9.3.3), so a lazy computed
            // pays nothing.
            var eager: Boolean = false,
        ) : Node
        class Effect(
            val run: Context.() -> (() -> Unit)?,
            val dependencies: SmallEdgeList = SmallEdgeList(),
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

    // "Which effect keeps me eager?" — one entry per EAGER computed, keyed by the
    // computed's slot id, valued by its puller effect id (#lzcellkernel §9.3.3).
    // Owner-keyed, so it MUST be cleared on computed disposal/lazy or LIFO id
    // recycling would alias a stale puller onto an unrelated node — the same
    // hazard `recycled_id_inherits_nothing` guards. A lazy computed is absent.
    private val eagerBy: HashMap<Int, Int> = HashMap()

    /**
     * Depth of the disposal-driven invalidation cascade (`#lzspecedgeindex`).
     *
     * Non-zero while [detachDependents] is walking the cone left behind by a
     * teardown. That walk exists because detaching edges is not enough: a
     * dependent still holding a value it computed *through* the disposed node
     * would serve it forever instead of erroring (`lazily-rs` 5db90d2;
     * `lazily-js` had the identical defect in 4d20670).
     *
     * While it is set the cascade is **mark-only** — [scheduleEffect] drops the
     * effect entirely. Disposal is not a publish: running an effect here
     * re-enters a compute that reads the node being torn down, which turns
     * `dispose` itself into a throw and breaks teardown idempotence. The
     * contract is "the dependent errors on its next *recompute*", and that
     * recompute is driven by a real write.
     *
     * A depth counter rather than a flag because teardown nests: an effect
     * cleanup run by one disposal may dispose a scope of its own.
     */
    private var disposalDepth: Int = 0

    // -- Creation ----------------------------------------------------------

    /**
     * A [Source] with an initial value — a node written from outside. `set`
     * invalidates dependents on `==` change. Defaults to the keep-latest policy;
     * a policy-carrying source is [mergeCell] (see Merge.kt).
     */
    inline fun <reified T : Any> source(value: T): Source<T> = Source(cellAny(value))

    /** @suppress Renamed to [source] (the Cell kernel — #lzcellkernel). */
    @Deprecated("Renamed to source (the Cell kernel — #lzcellkernel).", ReplaceWith("source(value)"))
    inline fun <reified T : Any> cell(value: T): Source<T> = source(value)

    @PublishedApi
    internal fun cellAny(value: Any): Int {
        val id = allocId()
        nodes[id] = Node.Cell(value)
        return id
    }

    /**
     * A [Computed] computed from upstream — **guarded** (`==`), so an equal
     * recompute suppresses downstream (matching TC39 `Signal.Computed`). Lazy
     * until read; make it eager with `.eager(ctx)`.
     *
     * Every computed cell is guarded; there is no unguarded mode. The former
     * `memo` constructor is removed (it was already an alias of the guarded
     * form), and `computed` — which historically named the *unguarded* form in
     * these bindings — is now the guarded default.
     */
    inline fun <reified T : Any> computed(noinline compute: Context.() -> T): Computed<T> =
        Computed(slotAny { compute() })

    /** @suppress Renamed to [computed] (guarded; v2 Cell kernel — #lzcellkernel). */
    @Deprecated("Renamed to computed (guarded by default; v2 Cell kernel — #lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> formula(noinline compute: Context.() -> T): Computed<T> = computed(compute)

    /** @suppress Renamed to [computed]. */
    @Deprecated("Renamed to computed (guarded by default; v2 Cell kernel — #lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> slot(noinline compute: Context.() -> T): Computed<T> = computed(compute)

    @PublishedApi
    internal fun slotAny(compute: Context.() -> Any?): Int {
        val id = allocId()
        nodes[id] = Node.Slot(compute = compute)
        return id
    }

    /**
     * @suppress The eager construction is now `computed { … }.eager(ctx)` — an
     * **eager** [Computed]. Kept as a thin wrapper over that so the former
     * `signal` surface keeps compiling.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Use `computed { … }.eager(ctx)` — an eager Computed (#lzcellkernel).", ReplaceWith("computed(compute).eager(this)"))
    inline fun <reified T : Any> signal(noinline compute: Context.() -> T): SignalHandle<T> {
        val ids = signalAny { compute() }
        return SignalHandle(Computed(ids.slot), Effect(ids.effect))
    }

    @PublishedApi
    internal class SignalIds(@PublishedApi internal val slot: Int, @PublishedApi internal val effect: Int)

    /** Build an eager computed and surface its (slot, puller) id pair for the back-compat [SignalHandle]. */
    @PublishedApi
    internal fun signalAny(compute: Context.() -> Any?): SignalIds {
        val slot = slotAny(compute = compute)
        makeEager(slot)
        return SignalIds(slot, eagerBy[slot]!!)
    }

    /**
     * Run [run] immediately, then rerun it after any tracked cell/slot/signal it
     * reads invalidates. [run] may return a cleanup closure (`(() -> Unit)?`);
     * cleanup runs before each rerun and on dispose.
     */
    fun effect(run: Context.() -> (() -> Unit)?): Effect = Effect(effectAny(run))

    @PublishedApi
    internal fun effectAny(run: Context.() -> (() -> Unit)?): Int {
        val id = allocId()
        nodes[id] = Node.Effect(run = run, forceRun = true)
        scheduleEffect(id, force = false)
        flushEffects()
        return id
    }

    // -- Read --------------------------------------------------------------

    /**
     * Read any [Cell] — the genus read. A [Computed] is refreshed/recomputed
     * if necessary; a [Source] returns its stored value. Auto-subscribes the
     * reading node either way.
     *
     * Dispatches on the **handle's** kind, not the arena node's: a stale handle
     * whose id was recycled (LIFO) onto a node of another kind must throw
     * [DisposedNodeException], not silently read the new occupant
     * (`recycled_id_inherits_nothing`). `getSlotAny` / `getCellAny` each enforce
     * that the live node matches the expected kind.
     */
    inline fun <reified T : Any> get(cell: Cell<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when (cell) {
            is Computed<*> -> getSlotAny(cell.id)
            is Source<*> -> getCellAny(cell.id)
        } as T
    }

    /** @suppress Reads are unified on the genus [get]. */
    @Deprecated("Reads are unified — use `get` on any Cell (#lzcellkernel).", ReplaceWith("get(handle)"))
    inline fun <reified T : Any> getCell(handle: Source<T>): T = get(handle)

    /** @suppress A eager computed reads with the ordinary [get]. */
    @Suppress("DEPRECATION")
    @Deprecated("Signal is retired — read the driven Computed with `get` (#lzcellkernel).", ReplaceWith("get(handle.slot)"))
    inline fun <reified T : Any> getSignal(handle: SignalHandle<T>): T = get(handle.slot)

    @PublishedApi
    internal fun getSlotAny(id: Int): Any {
        // The disposed check comes *before* [registerDependency] deliberately: a
        // reader that hits a torn-down node must not leave an edge pointing at
        // it, or the arena's LIFO id recycling would silently re-point that edge
        // at whatever node next occupies the id. Throwing first leaves the
        // reader's upstream set clean (`#lzspecedgeindex`).
        if (nodes[id] !is Node.Slot) throw DisposedNodeException(id, "slot")
        currentFrame()?.let { registerDependency(id, it) }
        refreshSlot(id)
        val node = nodes[id] as? Node.Slot ?: throw DisposedNodeException(id, "slot")
        check(node.hasValue) { "slot $id has no value" }
        return node.value as Any
    }

    @PublishedApi
    internal fun getCellAny(id: Int): Any {
        // `disposeCell` and `disposeSlot` share one read-after-dispose contract.
        val node = nodes[id] as? Node.Cell ?: throw DisposedNodeException(id, "cell")
        currentFrame()?.let { registerDependency(id, it) }
        return node.value as Any
    }

    // -- Write -------------------------------------------------------------

    /** @suppress Writes are kind-restricted extensions — use `sourceCell.set(ctx, value)` (Cell.kt). */
    @Deprecated("Use `sourceCell.set(ctx, value)` — writes live on the source kind (#lzcellkernel).", ReplaceWith("handle.set(this, value)"))
    fun <T : Any> setCell(handle: Source<T>, value: T) = setCellAny(handle.id, value)

    @PublishedApi
    internal fun setCellAny(id: Int, value: Any) {
        // A write that silently vanishes is the same failure mode as a read that
        // silently returns stale.
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
    fun disposeEffect(handle: Effect) = disposeNode(handle)

    fun isEffectActive(handle: Effect): Boolean = nodes[handle.id] is Node.Effect

    // -- Disposal + degree introspection (`#lzspecedgeindex`) --------------

    /**
     * Resolve a handle to its live node, or `null` if the handle is stale.
     *
     * A handle is stale when the arena slot is empty **or** holds a node of a
     * different kind. The second case is not hypothetical here: ids are recycled
     * LIFO, so a disposed cell's id is handed to the next node created, and a
     * caller still holding the old [CellHandle] would otherwise tear down an
     * unrelated slot. Reading the node's kind out of the arena before acting is
     * exactly what `recycled_id_inherits_nothing.json` requires.
     *
     * Same-kind recycling (a slot id handed to another slot) is *not*
     * distinguishable this way and is explicitly out of contract — that would
     * need generational ids or refcounted handles, and the spec requires
     * neither.
     */
    private fun resolve(node: GraphNode): Node? {
        val id = node.nodeId
        if (id < 0 || id >= nodes.size) return null
        val n = nodes[id] ?: return null
        return when (node) {
            is Source<*> -> n as? Node.Cell
            is Computed<*> -> n as? Node.Slot
            is Effect -> n as? Node.Effect
        }
    }

    /**
     * How many nodes currently depend on [node] — the size of its reverse edge
     * set.
     *
     * This is the observable the disposal contract is written against: a
     * subscribe/unsubscribe cycle that disposes what it creates must leave this
     * at its starting value no matter how many cycles run. A binding that leaks
     * reports total-ever-created here instead of live-subscriber count, and pays
     * for it twice — in memory, and in propagation, since every publish walks
     * the whole list.
     *
     * `0` for a disposed node, and for effects, which are pure sinks.
     */
    fun dependentCount(node: GraphNode): Int = when (val n = resolve(node)) {
        is Node.Cell -> n.dependents.size
        is Node.Slot -> n.dependents.size
        else -> 0
    }

    /**
     * How many nodes [node] currently depends on — the size of its forward edge
     * set. Counterpart to [dependentCount]: disposal must detach both
     * directions, and a binding that detaches only one leaves a dangling
     * half-edge visible here.
     *
     * `0` for a disposed node, and for cells, which are pure sources.
     */
    fun dependencyCount(node: GraphNode): Int = when (val n = resolve(node)) {
        is Node.Slot -> n.dependencies.size
        is Node.Effect -> n.dependencies.size
        else -> 0
    }

    /** Whether [node] has been torn down. A disposed node reads as a [DisposedNodeException]. */
    fun isDisposed(node: GraphNode): Boolean = resolve(node) == null

    /**
     * Tear [node] out of the graph, dispatching on the kind found in the arena.
     *
     * Detaches both edge directions, marks the surviving dependent cone dirty
     * (see [detachDependents]), recycles the id, and makes the node read as a
     * [DisposedNodeException]. Idempotent, and a no-op on a stale handle, so
     * teardown paths compose.
     *
     * Without this a node is permanent. Handles are copyable ids, not owners, so
     * dropping every handle reclaims nothing — and the JVM's tracing GC does not
     * help either, because the arena and the reverse edge set hold *strong*
     * references to every node that ever read a source. A long-lived source's
     * dependent list therefore keeps lengthening under subscribe/unsubscribe
     * churn even though the live subscriber count is constant.
     *
     * Callers must ensure nothing still reads [node] in a live computation: a
     * dependent that does errors on its next recompute.
     */
    fun disposeNode(node: GraphNode) {
        val resolved = resolve(node) ?: return
        disposeResolved(node.nodeId, resolved)
    }

    /** @suppress Kind-agnostic teardown — prefer `computed.dispose(ctx)`. */
    @Deprecated("Use `cell.dispose(ctx)` (#lzcellkernel).", ReplaceWith("handle.dispose(this)"))
    fun disposeSlot(handle: Computed<*>) = disposeNode(handle)

    /** @suppress Kind-agnostic teardown — prefer `sourceCell.dispose(ctx)`. */
    @Deprecated("Use `cell.dispose(ctx)` (#lzcellkernel).", ReplaceWith("handle.dispose(this)"))
    fun disposeCell(handle: Source<*>) = disposeNode(handle)

    /**
     * @suppress Full teardown of a eager computed. Disposing the [Computed]
     * already tears down its puller (via the eager bit), so this is now just
     * `disposeNode(handle.slot)`; kept for the retired `Signal` surface.
     */
    @Suppress("DEPRECATION")
    @Deprecated("Signal is retired — dispose the driven Computed: `computed.dispose(ctx)` (#lzcellkernel).", ReplaceWith("handle.slot.dispose(this)"))
    fun disposeSignalNode(handle: SignalHandle<*>) {
        disposeNode(handle.slot)
    }

    /**
     * The single teardown path all three kinds share.
     *
     * The order is load-bearing:
     *
     *  1. Vacate the arena slot first, so a re-entrant disposal (an effect
     *     cleanup that ends its own scope) resolves to `null` and is a no-op
     *     rather than a second teardown, and so the mark-only cascade in step 4
     *     cannot walk back into this node.
     *  2. Detach **upstream** — remove this node from each dependency's
     *     dependent list. Skipping this is the leak the whole disposal contract
     *     exists for.
     *  3. Detach **downstream** — remove this node from each dependent's
     *     dependency list, so no surviving node holds a dangling half-edge. In a
     *     recycling arena this is a correctness requirement, not just hygiene: a
     *     stale forward edge would re-point at whichever node next takes the id.
     *  4. Mark the surviving dependent cone dirty. The step that is easy to omit
     *     and that leaves a live reader frozen on a value it computed *through*
     *     this node.
     *
     * The id is recycled at step 1 and effect cleanups run last, after the node
     * is fully detached, so a cleanup observes a consistent graph.
     */
    private fun disposeResolved(id: Int, node: Node) {
        nodes[id] = null
        freeIds.addLast(id)
        when (node) {
            is Node.Cell -> detachDependents(id, node.dependents)
            is Node.Slot -> {
                // A eager computed owns a puller effect: tear it down and clear
                // the owner-keyed side-table entry, or LIFO id recycling would
                // strand the puller and alias its id onto the next node
                // (#lzcellkernel §9.3.4). The bit means disposal always knows.
                if (node.eager) eagerBy.remove(id)?.let { disposeNode(Effect(it)) }
                for (dep in node.dependencies) removeDependentEdge(dep, id)
                node.dependencies.clear()
                detachDependents(id, node.dependents)
            }
            is Node.Effect -> {
                // Only search the pending queue when the effect is actually in
                // it (`#lzspecedgeindex`). `scheduledEffects` already answers
                // that in O(1), and the unguarded scan was quadratic in fan-out
                // for a reason that is not obvious:
                // `kotlin.collections.ArrayDeque.indexOf` computes
                // `tail = (head + size) % capacity` and takes its wraparound
                // branch on `head >= tail`, which an *empty* deque satisfies
                // (`tail == head`). It then scans `head until capacity` plus
                // `0 until tail` — the whole backing array. The deque never
                // shrinks, so after one wide flush every later dispose scanned
                // width slots looking for an id that could not be there, and
                // tearing down a width-W fan-out cost O(W^2).
                if (scheduledEffects.remove(id)) pendingEffects.remove(id)
                for (dep in node.dependencies) removeDependentEdge(dep, id)
                node.dependencies.clear()
                // Effects are pure sinks: nothing depends on one, so there is no
                // cone to dirty. The cleanup is the observable side effect, and
                // it runs last.
                node.cleanup?.invoke()
            }
        }
    }

    /**
     * Detach every dependent of the node just removed at [id], then dirty the
     * surviving cone **without scheduling anything**.
     *
     * Reuses [runFrontier] — the same iterative DFS every publish walks — rather
     * than a second traversal, so "transitively reached" has exactly one
     * definition in this library and the two cannot drift.
     */
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

    /** Remove [depId] from [parentId]'s forward (dependency) edge set. */
    private fun removeDependencyEdge(parentId: Int, depId: Int) {
        when (val parent = nodes[parentId]) {
            is Node.Slot -> removeEdge(parent.dependencies, depId)
            is Node.Effect -> removeEdge(parent.dependencies, depId)
            else -> {}
        }
    }

    /**
     * Open a teardown scope: nodes created through it are disposed when it ends.
     *
     * Grouping bounds *teardown*, not visibility — a scoped node reads
     * parent-owned and sibling-scope-owned nodes freely, and scoping never
     * restricts what an edge may point at.
     *
     * Same caveat as [disposeNode]: ending a scope tears down its nodes even if
     * something outside still names them, and that reader errors on its next
     * recompute. [TeardownScope] is [AutoCloseable], so prefer
     * `ctx.scope().use { ... }` whenever the lifetime is lexical.
     */
    fun scope(): TeardownScope = TeardownScope(this)

    /** @suppress Reverts a eager computed to lazy — use `computed.lazy(ctx)`. */
    @Suppress("DEPRECATION")
    @Deprecated("Use `computed.lazy(ctx)` (#lzcellkernel).", ReplaceWith("handle.slot.lazy(this)"))
    fun disposeSignal(handle: SignalHandle<*>) = makeLazy(handle.slot.nodeId)

    /** @suppress Use `computed.isEager(ctx)`. */
    @Suppress("DEPRECATION")
    @Deprecated("Use `computed.isEager(ctx)` (#lzcellkernel).", ReplaceWith("handle.slot.isEager(this)"))
    fun isSignalActive(handle: SignalHandle<*>): Boolean = isEagerId(handle.slot.nodeId)

    // -- Eager computeds (the eager construction; #lzcellkernel §9.3) -------

    /**
     * Ensure the formula at [id] is eager: attach a puller [Effect] that
     * re-materializes it after each invalidation, mark the eager bit, and record
     * the puller in [eagerBy]. Idempotent — a second call is a no-op, so a
     * formula never accumulates two pullers (the `#lzsignaleager` double-compute
     * cannot be built).
     */
    internal fun makeEager(id: Int) {
        val node = nodes[id] as? Node.Slot ?: throw DisposedNodeException(id, "formula")
        if (node.eager) return
        val effect = effectAny { getSlotAny(id); null }
        node.eager = true
        eagerBy[id] = effect
    }

    /** Make the computed lazy at [id]: dispose its puller, clear the bit and side-table entry. */
    internal fun makeLazy(id: Int) {
        val node = nodes[id] as? Node.Slot ?: return
        if (!node.eager) return
        node.eager = false
        eagerBy.remove(id)?.let { disposeNode(Effect(it)) }
    }

    /** Whether the formula at [id] is currently eager. */
    internal fun isEagerId(id: Int): Boolean = (nodes[id] as? Node.Slot)?.eager == true

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

    // Edge containers: see EdgeList.kt. SmallEdgeList inlines the 0-2 edge case
    // (#lzktsmalledgelist), scans for dedup while the degree is small, and
    // promotes to a hash index above EDGE_INDEX_THRESHOLD (#lzspecedgeindex) so
    // that a wide fan-out registers in amortized O(1) per edge instead of
    // degrading to O(n^2). Dedup is built into SmallEdgeList.add.
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
            // #lzktdepslist: iterate the dependency list by index instead of
            // copying it via toList(). Refreshing a dependency never mutates
            // *this* node's dependency list (it clears the dep's own list), so
            // an index view is stable here — saves an ArrayList + N boxed Ints.
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
        trackingStack.addLast(id)
        val result: Any? = try {
            compute()
        } finally {
            trackingStack.removeLast()
        }
        val unchanged = node.hasValue && node.value == result
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
        // Disposal is not a publish — see [disposalDepth]. Dropped entirely, not
        // deferred: leaving the effect *queued* only postpones the damage, since
        // it would then fire on the next unrelated flush as a spurious rerun no
        // publish asked for.
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

/**
 * A teardown scope over a [Context]: nodes created through it are disposed when
 * it ends (`#lzspecedgeindex`).
 *
 * ## Why this is not "scope-ends-on-drop"
 *
 * `lazily-rs` ends a scope in `Drop`, so the scope's lifetime *is* the block's
 * and there is nothing to forget. Kotlin has no destructor and no deterministic
 * finalization — `Cleaner`/`finalize` are best-effort and GC-timed — so that
 * shape does not transfer, and a scope whose teardown depended on the collector
 * would be strictly worse than no scope at all: the leak it exists to prevent
 * would come back non-deterministically, and only under load.
 *
 * The end therefore has to be a statement, and Kotlin already has the right two
 * spellings for it:
 *
 *  - **`use`.** This class is [AutoCloseable] and [close] is [end], so
 *    `ctx.scope().use { scope -> ... }` is the direct analogue of the Rust block
 *    scope, ends in a `finally`, and needs no bespoke `withScope` helper — it is
 *    the idiom Kotlin already uses everywhere a lifetime is lexical, and it
 *    composes with every other resource in a `use` chain.
 *  - **[end].** For the case `use` cannot express: a scope whose lifetime is a
 *    *connection*, a *subscription*, or a *route* — opened in one callback and
 *    ended in another, across an asynchronous gap. That is the primary use of
 *    scopes, so it cannot be bracket-only.
 *
 * Structured concurrency was the third candidate and is deliberately **not** the
 * primary shape: tying teardown to a `CoroutineScope`'s completion binds the
 * graph's lifetime to a *coroutine's*, which is the wrong lifetime for exactly
 * the connection/subscription case above, and it would drag `kotlinx.coroutines`
 * into the synchronous [Context]. [AsyncTeardownScope] is where suspension
 * genuinely enters the picture, and it is a separate type for that reason.
 *
 * Both endings are idempotent, so they compose: a scope ended early inside a
 * `use` block is not ended twice.
 *
 * ## What it stores
 *
 * Just the handles, in creation order. Teardown walks them in **reverse**
 * creation order — dependents before what they read — so the scope never
 * transiently dangles inside itself while tearing down. Graph state is
 * order-independent (`disposeAll_order_independent` in lazily-formal), but
 * effect *cleanups* are side effects and their order is observable; ending a
 * scope is proved observationally equal to disposing each member
 * (`disposeScope_eq_disposeAll`).
 */
class TeardownScope internal constructor(
    /** The context this scope belongs to. */
    val ctx: Context,
) : AutoCloseable {
    private val owned = ArrayList<GraphNode>()
    private var ended = false

    /** How many nodes this scope currently owns. */
    val size: Int get() = owned.size

    /** Whether this scope owns nothing — never populated, [disarm]ed, or [end]ed. */
    fun isEmpty(): Boolean = owned.isEmpty()

    /** Whether this scope owns at least one node. */
    fun isNotEmpty(): Boolean = owned.isNotEmpty()

    /** Whether [end] has already run. */
    val isEnded: Boolean get() = ended

    /**
     * Take ownership of an existing [node] so this scope disposes it at
     * end-of-life.
     *
     * The factories below are the ordinary path; this exists for nodes built by
     * a helper that does not know about scopes. A node adopted twice by the same
     * scope is disposed once (disposal is idempotent), and adopting into an
     * already-ended scope is a no-op rather than an immediate disposal — the
     * scope's moment has passed.
     */
    fun <T : GraphNode> adopt(node: T): T {
        if (!ended) owned.add(node)
        return node
    }

    /** A [Source] owned by this scope. */
    inline fun <reified T : Any> source(value: T): Source<T> = adopt(ctx.source(value))

    /** @suppress Renamed to [source]. */
    @Deprecated("Renamed to source (#lzcellkernel).", ReplaceWith("source(value)"))
    inline fun <reified T : Any> cell(value: T): Source<T> = source(value)

    /** A guarded [Computed] owned by this scope. */
    inline fun <reified T : Any> computed(noinline compute: Context.() -> T): Computed<T> =
        adopt(ctx.computed(compute))

    /** @suppress Renamed to [computed] (guarded; v2 Cell kernel — #lzcellkernel). */
    @Deprecated("Renamed to computed (guarded by default; v2 Cell kernel — #lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> formula(noinline compute: Context.() -> T): Computed<T> =
        computed(compute)

    /** @suppress Renamed to [computed]. */
    @Deprecated("Renamed to computed (#lzcellkernel).", ReplaceWith("computed(compute)"))
    inline fun <reified T : Any> slot(noinline compute: Context.() -> T): Computed<T> =
        computed(compute)

    /** An effect owned by this scope. */
    fun effect(run: Context.() -> (() -> Unit)?): Effect = adopt(ctx.effect(run))

    /**
     * An eager [Computed] owned by this scope — the eager construction. Owns the
     * computed; disposing it tears down the puller too (via the eager bit).
     */
    inline fun <reified T : Any> eagerComputed(noinline compute: Context.() -> T): Computed<T> =
        adopt(ctx.computed(compute).eager(ctx))

    /** @suppress Renamed to [eagerComputed] (v2 Cell kernel — #lzcellkernel). */
    @Deprecated("Renamed to eagerComputed (v2 Cell kernel — #lzcellkernel).", ReplaceWith("eagerComputed(compute)"))
    inline fun <reified T : Any> drivenFormula(noinline compute: Context.() -> T): Computed<T> =
        eagerComputed(compute)

    /**
     * @suppress Eager signal owned by this scope. Both halves are adopted,
     * backing computed first, so reverse-order teardown stops the puller before
     * the computed it pulls. Prefer [eagerComputed].
     */
    @Suppress("DEPRECATION")
    @Deprecated("Use `eagerComputed` — an eager Computed (#lzcellkernel).", ReplaceWith("eagerComputed(compute)"))
    inline fun <reified T : Any> signal(noinline compute: Context.() -> T): SignalHandle<T> {
        val handle = ctx.signal(compute)
        adopt(handle.slot)
        adopt(handle.effect)
        return handle
    }

    /**
     * Cancel this scope's teardown: ending it afterwards disposes nothing, and
     * its nodes revert to plain context ownership — the state every unscoped
     * node is already in.
     *
     * The nodes themselves are untouched. They keep their values, keep their
     * edges in both directions, keep propagating, and remain individually
     * disposable. The only thing that changes is whether this scope fires at
     * end-of-life, which is what the name says — the same sense as defusing a
     * guard.
     */
    fun disarm() {
        owned.clear()
    }

    /**
     * Dispose every node this scope owns, in reverse creation order.
     *
     * Idempotent, and safe over members whose own dependencies were already
     * disposed: teardown flows from the scope's owned set, not from
     * reachability.
     */
    fun end() {
        if (ended) return
        ended = true
        // Reverse creation order: dependents before what they read.
        for (i in owned.indices.reversed()) ctx.disposeNode(owned[i])
        owned.clear()
    }

    /** [end], so `ctx.scope().use { ... }` is the lexical form. */
    override fun close() = end()

    override fun toString(): String =
        if (ended) "TeardownScope(ended)" else "TeardownScope(${owned.size} owned)"
}
