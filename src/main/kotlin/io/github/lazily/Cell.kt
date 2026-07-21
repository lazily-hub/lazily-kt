package io.github.lazily

/**
 * The Cell kernel (`#lzcellkernel`) — `SourceCell` / `FormulaCell` over a single
 * genus [Cell]`<T, K>`.
 *
 * See `tasks/software/lazily-cell-kernel-design.md`. One reactive-node genus with
 * a **kind** type parameter `K` replaces the former `SlotHandle` / `CellHandle` /
 * `SignalHandle` handle zoo and the vestigial `Reactive<T>` / `Source<T>`
 * read/write interfaces:
 *
 * ```text
 * Cell<T, K>                    genus — a node with a readable value
 * ├─ SourceCell<T>  (K = Source<M>)   written from outside; folds under policy M
 * └─ FormulaCell<T> (K = Formula)     computed from upstream
 * ```
 *
 * Both aliases answer the **same** question — *where does a node's value come
 * from* — so the pair is exhaustive: `SourceCell` from outside, `FormulaCell`
 * from upstream. [EffectHandle] stays outside the hierarchy (a sink, no value),
 * so nothing can depend on it.
 *
 * ## Write protection without a trait (§3/§4)
 *
 * Reads live on the genus ([Context.get] accepts any `Cell<T, *>`). Writes
 * ([set]/[merge]) are **extension functions declared on `Cell<T, Source<M>>`** —
 * the source instantiation — so they exist only where a value comes from
 * outside, and `formulaCell.set(…)` is an *unresolved reference* compile error
 * with no interface in sight. This is the §4 mechanism for Kotlin: the kind is a
 * phantom type parameter and the compiler restricts the write surface by it,
 * exactly as `lazily-rs` restricts an inherent impl to `Cell<T, Source<M>>`.
 *
 * A `SourceCell` reads and writes; a `FormulaCell` only reads:
 *
 * ```
 * val ctx = Context()
 * val n = ctx.source(1)                       // SourceCell<Int>
 * n.set(ctx, 2)                               // ok — `set` lives on the source kind
 * val doubled = ctx.formula { ctx.get(n) * 2 }.drive(ctx)
 * check(ctx.get(doubled) == 4)
 * // doubled.set(ctx, 9)                      // COMPILE ERROR: unresolved reference `set`
 * ```
 *
 * Because Kotlin has no zero-cost type-level merge policy, `Source<M>`'s `M` is a
 * phantom marker (default [KeepLatest]); a policy-carrying source keeps a runtime
 * [MergePolicy] via [MergeCell] (see Merge.kt), consistent with §5.0 (wire types
 * and the storage vocabulary are unchanged).
 */

// ---------------------------------------------------------------------------
// Kind markers (phantom — never instantiated)
// ---------------------------------------------------------------------------

/**
 * Kind marker for a **source** cell — a node written from outside, folding
 * accumulated writes under merge policy `M`. It carries the policy so writes
 * exist exactly where the policy does ([set]/[merge] on `Cell<T, Source<M>>`).
 *
 * Reuses the name of the former `Source<T>` *interface* (now deleted): a
 * `Source` is graph-theoretically a node with no incoming edges, and API-wise
 * the writable kind.
 */
sealed interface Source<M>

/** The default keep-latest (last-writer-wins) merge policy marker. */
sealed interface KeepLatest

/**
 * Kind marker for a **formula** cell — a node computed from upstream. A *driven*
 * formula (`formula().drive()`) is still this kind; drivenness is graph state
 * (a bit on the node + a side table), not a distinct type.
 */
sealed interface Formula

// ---------------------------------------------------------------------------
// The genus
// ---------------------------------------------------------------------------

/**
 * A typed handle to a reactive node within a [Context] — the genus of the
 * kernel. Lightweight: a dense-arena id and nothing else; the value lives inside
 * the `Context`.
 *
 * The two kinds are distinct value classes ([SourceCell], [FormulaCell]) so the
 * arena can still discriminate a stale recycled handle by its kind, while the
 * shared genus gives generic readers one type to accept and the extension-based
 * write surface stays restricted to the source kind.
 */
sealed interface Cell<T : Any, K> : GraphNode

/**
 * A cell written from outside (default [KeepLatest] policy, last-writer-wins).
 * The kernel's source kind — reads via [Context.get], writes via [set] / [merge].
 */
@JvmInline
value class SourceCell<T : Any> @PublishedApi internal constructor(val id: Int) :
    Cell<T, Source<KeepLatest>> {
    override val nodeId: Int get() = id
}

/**
 * A cell computed from upstream. Guarded (`==`) by default; lazy until read.
 * `formula().drive()` makes it eager (a driven formula). The kernel's formula
 * kind — reads via [Context.get], never writes.
 */
@JvmInline
value class FormulaCell<T : Any> @PublishedApi internal constructor(val id: Int) :
    Cell<T, Formula> {
    override val nodeId: Int get() = id
}

// -- Back-compat aliases -----------------------------------------------------

/** @suppress Former name of [SourceCell]. */
@Deprecated("Renamed to SourceCell (the Cell kernel — #lzcellkernel).", ReplaceWith("SourceCell<T>"))
typealias CellHandle<T> = SourceCell<T>

/** @suppress Former name of [FormulaCell]. */
@Deprecated("Renamed to FormulaCell (the Cell kernel — #lzcellkernel).", ReplaceWith("FormulaCell<T>"))
typealias SlotHandle<T> = FormulaCell<T>

// -- Source-only writes (§3/§4) ---------------------------------------------

/**
 * Replace this source cell's value outright (the keep-latest write). Declared on
 * `Cell<T, Source<M>>`, so only a [SourceCell] resolves it — `formulaCell.set(…)`
 * does not compile.
 *
 * A no-op (no invalidation) when the new value `==` the old.
 */
fun <T : Any, M> Cell<T, Source<M>>.set(ctx: Context, value: T): Unit =
    ctx.setCellAny(nodeId, value)

/**
 * Fold [op] into this source cell under [policy] (default [keepLatest], for which
 * a merge is a replace — `Cell ≡ SourceCell<KeepLatest>`). Like [set], declared
 * on the source kind only.
 */
fun <T : Any, M> Cell<T, Source<M>>.merge(
    ctx: Context,
    op: T,
    policy: MergePolicy<T> = keepLatest(),
): Unit {
    @Suppress("UNCHECKED_CAST")
    val current = ctx.getCellAny(nodeId) as T
    ctx.setCellAny(nodeId, policy.merge(current, op))
}

// -- Formula-only lifecycle (driven bit + `drivenBy` side table) ------------

/**
 * **Drive** this formula: make it eager. Attaches a puller [EffectHandle] that
 * re-materializes the formula after every invalidation, so its value is fresh by
 * the time the invalidating `set`/`batch` returns — observers never see an
 * intermediate unset state.
 *
 * Idempotent — a second `drive` is a no-op — and returns the **same** handle
 * (mutated graph state), so the caller keeps reading the formula it already
 * holds. This is the eager construction that retires the former `Signal`; the
 * coalescing comes from the scheduler (effects are scheduled, not inline), so a
 * per-write puller cannot be built (`#lzsignaleager` becomes unwritable).
 */
fun <T : Any> FormulaCell<T>.drive(ctx: Context): FormulaCell<T> {
    ctx.driveFormula(id)
    return this
}

/**
 * Reverse of [drive]: stop eager recomputation and dispose the puller. The value
 * stays readable and reverts to lazy (recomputed on next read). No-op if the
 * formula is not driven.
 */
fun FormulaCell<*>.undrive(ctx: Context): Unit = ctx.undriveFormula(nodeId)

/** Whether this formula is currently driven (has an active puller). */
fun FormulaCell<*>.isDriven(ctx: Context): Boolean = ctx.isDrivenId(nodeId)

/** Tear this cell out of the graph. Kind-agnostic; a driven formula also drops its puller. */
fun Cell<*, *>.dispose(ctx: Context): Unit = ctx.disposeNode(this)
