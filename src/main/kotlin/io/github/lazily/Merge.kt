package io.github.lazily

/**
 * Phase 1 of the RelayCell backpressure plan (#relaycell) — the merge algebra.
 * Under the v2 Cell kernel (#lzcellkernel) the read/write split is `Cell<T>` read
 * genus vs writes inherent to the concrete `Source<T>` handle, not any deleted
 * `Reactive`/`Source` interface.
 *
 * See `lazily-spec/docs/reactive-graph.md` § "MergeCell and the merge algebra"
 * and `relaycell-backpressure-analysis.md` §4.0/§4.3. A merge policy is an
 * *associative* fold `⊕: T×T→T`; the properties it satisfies (associativity
 * always; commutativity = reordering tax; idempotency = durability tax) select
 * which overflow behaviour is sound. [MergeCell] generalizes a plain `Cell` —
 * `Cell ≡ MergeCell(KeepLatest)` — a source whose write is a merge. Backed by an
 * ordinary cell, so it inherits the Phase-0 `!=` store-guard + store-without-cascade.
 */

/**
 * An associative merge `⊕` with its transport-selected property flags.
 * Associativity (`(a⊕b)⊕c == a⊕(b⊕c)`) is a law, verified by the law-tests, not
 * a flag. [commutative] is the reordering tax; [idempotent] the durability tax;
 * [conflates] gates the `Conflate` overflow (Phase 2 — only `RawFifo` cannot bound).
 */
class MergePolicy<T : Any>(
    val name: String,
    val merge: (old: T, op: T) -> T,
    val commutative: Boolean,
    val idempotent: Boolean,
    val conflates: Boolean = true,
)

/** Keep-latest band (`old ⊕ op = op`) — the policy behind a plain `Cell`. */
fun <T : Any> keepLatest(): MergePolicy<T> =
    MergePolicy("KeepLatest", { _, op -> op }, commutative = false, idempotent = true)

/** Additive commutative monoid (`old + op`). Not idempotent. */
fun sum(): MergePolicy<Long> =
    MergePolicy("Sum", { a, b -> a + b }, commutative = true, idempotent = false)

/** Max semilattice (`max(old, op)`). Associative, commutative, idempotent. */
fun max(): MergePolicy<Long> =
    MergePolicy("Max", { a, b -> maxOf(a, b) }, commutative = true, idempotent = true)

/** Grow-only set-union semilattice over [Set]. */
fun <E> setUnion(): MergePolicy<Set<E>> =
    MergePolicy("SetUnion", { old, op -> old + op }, commutative = true, idempotent = true)

/**
 * Raw FIFO append over [List] (`old ++ op`). Order + multiplicity are meaning —
 * associative only; cannot conflate.
 */
fun <E> rawFifo(): MergePolicy<List<E>> =
    MergePolicy(
        "RawFifo",
        { old, op -> old + op },
        commutative = false,
        idempotent = false,
        conflates = false,
    )

/**
 * A [Source] whose write is a *merge* under [policy] rather than a plain
 * replace. `Source ≡ MergeCell(KeepLatest)`. `merge` routes through the
 * cell's `!=`-guarded write, so an idempotent policy's no-op merge fires no
 * cascade (free dedup).
 *
 * This is the value-level home of the merge policy for Kotlin: the v2 handle is
 * the single-parameter [Source]`<T>` (Kotlin has neither a zero-cost type-level
 * policy nor default type arguments, so there is no `Source<T, M>` phantom), so a
 * policy-carrying source keeps its [MergePolicy] here at runtime. Reads go through
 * [Context.get]; writes through the source-only [set].
 *
 * The former vestigial `Reactive<T>` (read) and `Source<T>` (write) interfaces
 * are deleted: reads are the [Cell]`<T>` read abstraction and writes are inherent
 * to the concrete [Source] handle, so neither trait carried its weight.
 */
class MergeCell<T : Any>(
    private val ctx: Context,
    val cell: Source<T>,
    val policy: MergePolicy<T>,
) {
    // Uses the erased `*Any` accessors (not the `reified` genus `get`/`set`)
    // because `T` is a class type parameter, not reified. `getCellAny` still
    // registers the dependency, so a `get()` inside a computation is reactive.
    @Suppress("UNCHECKED_CAST")
    fun get(ops: ComputeOps = ctx): T = ops.getCellAny(cell.id) as T

    fun set(value: T) = ctx.setCellAny(cell.id, value)

    fun merge(op: T) = ctx.setCellAny(cell.id, policy.merge(get(), op))
}

/** Create a [MergeCell] over this context. */
inline fun <reified T : Any> Context.mergeCell(initial: T, policy: MergePolicy<T>): MergeCell<T> =
    MergeCell(this, source(initial), policy)
