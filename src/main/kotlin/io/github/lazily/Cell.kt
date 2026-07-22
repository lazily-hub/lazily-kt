package io.github.lazily

/**
 * The Cell kernel (`#lzcellkernel`) — the two concrete handle types [Source] and
 * [Computed].
 *
 * See `tasks/software/lazily-cell-kernel-design.md`. **`Cell` is the value-node
 * concept**: a *cell* is a value-bearing reactive node, and the two kinds of
 * cell are the two concrete handle structs a caller holds:
 *
 * ```text
 * Source<T>       handle to a source cell — written from outside; keep-latest by default
 * Computed<T>     handle to a computed cell — computed from upstream, guarded (`==`)
 * ```
 *
 * Both answer the same question — *where does a node's value come from* — so the
 * pair is exhaustive: [Source] from outside, [Computed] from upstream. [Effect]
 * stays outside the hierarchy (a sink, no value), so nothing can depend on it.
 *
 * ## `Cell<T>` is a read abstraction, not the v1 `Cell<T, K>` genus
 *
 * The former kind-parametric genus `Cell<T, K>` (whose `K` carried the write
 * surface) is **gone**. What remains is a *single-parameter* read interface
 * [Cell]`<T>` — the value-node concept itself — implemented by the two concrete
 * handles so that one thing (`Context.get`) can read either kind without a
 * value-class overload clash on the JVM, and so heterogeneous readers (the
 * conformance runner) have one type to hold. It is **not** the write vehicle:
 * writes live on [Source] concretely (below).
 *
 * ## Write protection without a trait (§3)
 *
 * Reads ([Context.get], [dispose]) exist on the [Cell] genus / both handles.
 * Writes ([set] / [merge]) are inherent-style **extension functions on [Source]
 * only**, so `computed.set(…)` is an *unresolved reference* compile error with no
 * trait in sight — the §3 mechanism for Kotlin. There is no `Cell<T, Source<M>>`
 * receiver anymore; the receiver is the concrete `Source<T>`.
 *
 * A [Source] reads and writes; a [Computed] only reads:
 *
 * ```
 * val ctx = Context()
 * val n = ctx.source(1)                       // Source<Int>
 * n.set(ctx, 2)                               // ok — `set` lives on the source handle
 * val doubled = ctx.computed { get(n) * 2 }.eager(ctx)   // `get` = the tracked Compute view
 * check(ctx.get(doubled) == 4)
 * // doubled.set(ctx, 9)                      // COMPILE ERROR: unresolved reference `set`
 * ```
 *
 * ## The merge policy is runtime, not a type parameter
 *
 * lazily-rs spells the source handle `Source<T, M = KeepLatest>`, carrying the
 * merge policy in a zero-cost phantom type parameter with a default. Kotlin has
 * **neither** a zero-cost type-level policy **nor** default type arguments, so a
 * two-parameter `Source<T, M>` would force every plain call site to spell
 * `Source<T, KeepLatest>`. Instead the policy stays a runtime [MergePolicy]
 * carried by [MergeCell] (see Merge.kt) — exactly as v1 did — and the bare
 * handle is the single-parameter [Source]`<T>`. `Cell ≡ Source<T>` under
 * keep-latest holds by construction.
 */

// ---------------------------------------------------------------------------
// The value-node concept: a single-parameter read abstraction
// ---------------------------------------------------------------------------

/**
 * A **cell**: a value-bearing reactive node within a [Context], read as `T`. The
 * two kinds of cell are [Source] (written from outside) and [Computed] (computed
 * from upstream); [Effect] is a sink and deliberately **not** a `Cell`.
 *
 * This is a read abstraction only — one type for [Context.get] and heterogeneous
 * readers to hold. It carries no kind parameter and no write surface; writes are
 * inherent to [Source] (see [set] / [merge]).
 */
sealed interface Cell<T : Any> : GraphNode

// ---------------------------------------------------------------------------
// Source — the source-cell handle
// ---------------------------------------------------------------------------

/**
 * A typed handle to a **source cell** — a node written from outside, keep-latest
 * by default (a policy-carrying source keeps its [MergePolicy] via [MergeCell]).
 * Lightweight: a dense-arena id and nothing else; the value lives inside the
 * `Context`. The kernel's source kind — reads via [Context.get], writes via
 * [set] / [merge].
 */
@JvmInline
value class Source<T : Any> @PublishedApi internal constructor(val id: Int) : Cell<T> {
    override val nodeId: Int get() = id
}

/**
 * A typed handle to a **computed cell** — a node computed from upstream. Guarded
 * (`==`) by default; lazy until read. `computed().eager()` makes it eager (an
 * eager computed cell). The kernel's computed kind — reads via [Context.get],
 * never writes.
 */
@JvmInline
value class Computed<T : Any> @PublishedApi internal constructor(val id: Int) : Cell<T> {
    override val nodeId: Int get() = id
}

// -- Source-only writes (§3) -------------------------------------------------

/**
 * Replace this source cell's value outright (the keep-latest write). Declared on
 * [Source] only, so `computed.set(…)` does not compile — write protection
 * without a trait.
 *
 * A no-op (no invalidation) when the new value `==` the old (the store-guard).
 */
fun <T : Any> Source<T>.set(ctx: Context, value: T): Unit =
    ctx.setCellAny(nodeId, value)

/**
 * Fold [op] into this source cell under [policy] (default [keepLatest], for which
 * a merge is a replace — `Cell ≡ Source<T>` under keep-latest). Like [set],
 * declared on [Source] only.
 */
fun <T : Any> Source<T>.merge(
    ctx: Context,
    op: T,
    policy: MergePolicy<T> = keepLatest(),
): Unit {
    @Suppress("UNCHECKED_CAST")
    val current = ctx.getCellAny(nodeId) as T
    ctx.setCellAny(nodeId, policy.merge(current, op))
}

// -- Computed-only lifecycle (eager bit + `eagerBy` side table) --------------

/**
 * Transition this computed cell to **eager**. Attaches a puller [Effect] that
 * re-materializes it after every invalidation, so its value is fresh by the time
 * the invalidating `set`/`batch` returns — observers never see an intermediate
 * unset state.
 *
 * Idempotent — a second `eager` is a no-op — and returns the **same** handle
 * (mutated graph state), so the caller keeps reading the computed cell it already
 * holds. This is the eager construction that retires the former `Signal`; the
 * coalescing comes from the scheduler (effects are scheduled, not inline), so a
 * per-write puller cannot be built (`#lzsignaleager` becomes unwritable).
 */
fun <T : Any> Computed<T>.eager(ctx: Context): Computed<T> {
    ctx.makeEager(id)
    return this
}

/**
 * Reverse of [eager]: stop eager recomputation and dispose the puller. The value
 * stays readable and reverts to lazy (recomputed on next read). No-op if the
 * computed cell is not eager.
 */
fun Computed<*>.lazy(ctx: Context): Unit = ctx.makeLazy(nodeId)

/** Whether this computed cell is currently eager (has an active puller). */
fun Computed<*>.isEager(ctx: Context): Boolean = ctx.isEagerId(nodeId)

// -- Disposal (genus-level) --------------------------------------------------

/** Tear this cell out of the graph. Kind-agnostic; an eager computed also drops its puller. */
fun Cell<*>.dispose(ctx: Context): Unit = ctx.disposeNode(this)
