package io.github.lazily

// -- The fortified compute view (#lzcellkernel) ------------------------------
//
// Dependency tracking has a **value-threaded** primary surface, not an ambient
// one. The identity a tracked read must attribute to — *which* node is being
// recomputed — is carried into every compute/effect closure as a value, through
// a per-recompute [Compute] view (mirrors lazily-rs `Compute` / `ComputeOps`;
// lazily-spec/cell-model.md §"Dependency tracking (the fortified compute view)").
//
// A read through the [Compute] handed to a closure registers the edge against
// that closure's node — by construction, not via an ambient frame that a
// suspension could clobber. This is now the **sole** tracking surface of the
// single-threaded core: every domain reader threads a [ComputeOps] surface, so a
// read inside a recompute goes through the [Compute] view and a read through a
// bare [Context] (top level or a snapshot) registers nothing. The former ambient
// **compatibility bridge** — an execution stack that attributed a captured
// `ctx.get(...)` to the recomputing node — has been deleted (`#lzcellkernel`).
// (`ThreadSafeContext` / `AsyncContext` keep their own ambient engines, matching
// lazily-rs.) The [Untracked] escape registers nothing on any surface.

/**
 * The **compute-time operations subset** shared by the read surfaces
 * (`#lzcellkernel`) — the Kotlin analogue of lazily-rs `ComputeOps`. Implemented
 * by three types:
 *
 * - [Context] — the owning graph; the **top-level / untracked** surface: a
 *   `ctx.get` registers no dependency edge (see [Context.getSlotAny]). Tracking is
 *   solely value-threaded through [Compute].
 * - [Compute] — the per-recompute *view*; its reads are **value-threaded**,
 *   registering a dependency edge against the recomputing node, and are
 *   generation-guarded against escape.
 * - [Untracked] — the explicit **untracked escape** returned by [untracked]; its
 *   reads register nothing on any surface.
 */
interface ComputeOps {
    /** Read a source cell's stored value (erased), with this surface's discipline. */
    fun getCellAny(id: Int): Any

    /** Read/refresh a computed cell's value (erased), with this surface's discipline. */
    fun getSlotAny(id: Int): Any

    /**
     * The explicit **untracked escape**: a surface whose reads register no
     * dependency edge (lazily-rs `Compute::untracked`). Inside a compute,
     * `untracked().get(x)` is the sole way to read `x` without forming an edge.
     */
    fun untracked(): ComputeOps

    /** The owning [Context] — the target of construction ops (never a tracked read). */
    val computeContext: Context
}

/**
 * Read any [Cell] over this surface's tracking discipline — the reified genus
 * read. A [Compute] registers a dependency edge against the recomputing node; a
 * bare [Context] bridges to the recomputing node (or, at top level, registers
 * nothing); an [Untracked] registers nothing. Dispatches on the **handle's** kind
 * so a recycled-id stale handle still throws [DisposedNodeException].
 */
inline fun <reified T : Any> ComputeOps.get(cell: Cell<T>): T {
    @Suppress("UNCHECKED_CAST")
    return when (cell) {
        is Computed<*> -> getSlotAny(cell.id)
        is Source<*> -> getCellAny(cell.id)
    } as T
}

/** @suppress Reads are unified on the genus [get]; kept for source-only call sites. */
@Deprecated("Reads are unified — use `get` on any Cell (#lzcellkernel).", ReplaceWith("get(handle)"))
inline fun <reified T : Any> ComputeOps.getCell(handle: Source<T>): T = get(handle)

// -- Construction over the compute surface -----------------------------------
//
// Constructing a cell is not a read, so these delegate to the owning [Context]
// untracked. They let a compute body mint child cells the same way top-level code
// does; on [Context] the member builders win, so resolution is unchanged there.

/** Create a source cell over this surface (construction; untracked). */
inline fun <reified T : Any> ComputeOps.source(value: T): Source<T> = computeContext.source(value)

/** Create a guarded computed cell over this surface (construction; untracked). */
inline fun <reified T : Any> ComputeOps.computed(noinline compute: Compute.() -> T): Computed<T> =
    computeContext.computed(compute)

/** Create a guarded computed with an explicit change predicate (construction). */
inline fun <reified T : Any> ComputeOps.computedRippleWhen(
    noinline compute: Compute.() -> T,
    noinline changed: (old: T, new: T) -> Boolean,
): Computed<T> = computeContext.computedRippleWhen(compute, changed)

/** Register an effect over this surface (construction; untracked). */
fun ComputeOps.effect(run: Compute.() -> (() -> Unit)?): Effect = computeContext.effect(run)

/**
 * The per-recompute **compute view** (`#lzcellkernel`): [Context] plus the id of
 * the node being recomputed, threaded into the closure as a **value**. Every
 * tracked read through it registers a dependency edge against [nodeId], so the
 * edge-attribution invariant ("every edge registered while recomputing `n` has
 * `n` as its dependent") holds **by construction**.
 *
 * ## Fortification (as Kotlin allows)
 *
 * - **Primary tracking surface.** The view handed to a closure is the surface a
 *   read should go through; the explicit [untracked] escape is the only way to
 *   read without forming an edge.
 * - **Non-escapable — by convention + a runtime guard.** The JVM has no
 *   lifetimes, so a `Compute` cannot be made un-storable at compile time the way
 *   lazily-rs binds it by lifetime + `!Send`. Instead every read is
 *   **generation-stamped**: the view captures the generation of the recompute
 *   frame that minted it, and [Context] validates that generation is still on the
 *   active-frame stack. A view stored and replayed after its recompute returned
 *   throws [IllegalStateException] rather than registering an edge against the
 *   wrong (or a disposed) node.
 * - **Generation-stamped disposal.** A read against the recomputing node after it
 *   was disposed/recycled mid-recompute is detected the same way.
 *
 * Single-threaded, like [Context]; `ThreadSafeContext` / `AsyncContext` keep their
 * own ambient engines (matching lazily-rs, which value-threads only the primary
 * single-threaded `Context`).
 */
class Compute @PublishedApi internal constructor(
    @PublishedApi internal val ctx: Context,
    /** The node being recomputed — the dependent every tracked read subscribes. */
    @PublishedApi internal val nodeId: Int,
    /** The recompute frame's generation stamp; validated on every read. */
    @PublishedApi internal val gen: Long,
) : ComputeOps {

    override val computeContext: Context get() = ctx

    /** The untracked escape (lazily-rs `Compute::untracked`). */
    override fun untracked(): ComputeOps = Untracked(ctx)

    override fun getCellAny(id: Int): Any {
        ctx.assertComputeValid(nodeId, gen)
        ctx.registerDependencyInternal(id, nodeId)
        return ctx.getCellRaw(id)
    }

    override fun getSlotAny(id: Int): Any {
        ctx.assertComputeValid(nodeId, gen)
        ctx.registerDependencyInternal(id, nodeId)
        return ctx.getSlotRaw(id)
    }
}

/**
 * The untracked read surface returned by [ComputeOps.untracked] (`#lzcellkernel`).
 * Reads go straight to the raw store — no edge, and no compatibility bridge — so
 * `untracked().get(x)` inside a compute is genuinely untracked, the deliberate
 * escape lazily-rs spells `Compute::untracked`.
 */
class Untracked @PublishedApi internal constructor(
    @PublishedApi internal val ctx: Context,
) : ComputeOps {
    override val computeContext: Context get() = ctx
    override fun untracked(): ComputeOps = this
    override fun getCellAny(id: Int): Any = ctx.getCellRaw(id)
    override fun getSlotAny(id: Int): Any = ctx.getSlotRaw(id)
}
