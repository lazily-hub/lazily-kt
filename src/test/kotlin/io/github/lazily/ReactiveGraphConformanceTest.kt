package io.github.lazily

import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-language conformance for the reactive-graph plane (`#lzspecconf`,
 * `#lzspecedgeindex`) — see the JSON fixtures under
 * `lazily-spec/conformance/reactive-graph/`.
 *
 * lazily-kt replayed **none** of these fixtures until this runner existed, and
 * then only 1 of 9: the other eight named teardown-scope, disposal, and
 * fan-out/churn ops that only `lazily-rs` had ever implemented. Those ops now
 * exist here ([Context.scope], [Context.disposeNode], [Context.dependentCount]
 * and their thread-safe and async counterparts), so the whole corpus replays.
 *
 * That gap was not academic: an invalidation-cascade defect shipped undetected
 * in both lazily-dart and lazily-go while a fixture encoding the violated
 * property (`transitive_invalidation_reaches_depth.json`) already sat on disk
 * with nothing running it.
 *
 * ## Replayed against EVERY context lazily-kt ships
 *
 * The runner is parameterised over the *execution model*, not written against
 * the default [Context]. This is the property that gives it its value: the dart
 * and go defects were both **correct synchronously and broken asynchronously**,
 * so a default-context-only replay would have reported green through the exact
 * defect it exists to catch.
 *
 *  - `Context` — the single-threaded pull engine
 *  - `ThreadSafeContext` — the `ReentrantLock` engine
 *  - `AsyncContext` — the coroutine engine (revision counters + in-flight state,
 *    the mechanism that broke the pull chain in lazily-dart)
 *
 * ## What the corpus pins, and since when
 *
 * Mutation against the nine-fixture corpus established that two of the disposal
 * plane's three stated semantics were invisible to it — scheduling effects
 * during a disposal cascade, and tearing a scope down in forward instead of
 * reverse order, each left all nine green. `lazily-spec` 1c80db5 has since added
 * `disposal_does_not_run_surviving_effects.json` and
 * `teardown_runs_members_in_reverse_creation_order.json`, which discriminate
 * both, and this runner replays them.
 *
 * `EdgeIndexTest`'s disposal group keeps its own direct tests for the same two
 * semantics anyway. They are not redundant: they cover the thread-safe and async
 * engines, where each semantic is a *separate* field on a *separate* class, and
 * they are independent of whether the canonical corpus keeps those two fixtures.
 *
 * ## Positive assertion (`#lzspecconf`)
 *
 * An absence guard ("is the spec sibling present?") cannot catch a replay that
 * is refactored away, renamed, or filtered out by a test selector. So this
 * asserts positively:
 *
 *  1. the fixture set on disk matches [FIXTURES] exactly — a fixture added or
 *     renamed upstream fails loudly instead of going unrun;
 *  2. a fixture is promoted to *executed* only after its ops ran and its
 *     assertions were evaluated, never by being found on disk;
 *  3. every fixture executed against every model, and the count is asserted
 *     non-zero and equal to the expected product;
 *  4. a non-zero number of ops and assertions actually executed;
 *  5. every op and every assertion key in a fixture is recognised — an
 *     unrecognised one is a hard error, never a silent skip.
 *
 * Every replay also reads through [ConformanceFixtures.read] so
 * `scripts/check-conformance-coverage.sh` sees `reactive-graph/` in the
 * manifest and fails CI if this suite ever stops running.
 */
class ReactiveGraphConformanceTest {
    private val json = Json

    /** Canonical spec area — sibling-relative, never a bundled copy. */
    private val area = "reactive-graph"

    private companion object {
        /**
         * The canonical fixture set, asserted against the directory listing so a
         * fixture added or renamed upstream fails loudly instead of going unrun.
         */
        val FIXTURES = listOf(
            "churn_returns_to_baseline.json",
            "cross_scope_teardown_hazard.json",
            "disarm_disposes_nothing.json",
            "disposal_does_not_run_surviving_effects.json",
            "dispose_detaches_edges_both_directions.json",
            "dispose_signal_reverts_to_lazy.json",
            // #lzmergefeed (lazily-spec, Step 3): the accumulate/fold write
            // surface. Five exercise the `merge_cell` op this runner does not
            // model (skipped as unsupported ops); the sixth uses only supported
            // ops but asserts the novel `drain_exhausted` key (parked). All six
            // are accounted-for skips in EXPECTED_SKIPS, not silent gaps.
            "exact_fold_paths_stay_exact.json",
            "feedback_drain_bound_reports_exhaustion.json",
            "merge_cell_acquires_no_dependency_edge.json",
            "merge_feed_through_a_formula_coalesces.json",
            "merge_folds_synchronously_in_batch.json",
            "merge_per_settled_cone_not_per_write.json",
            "read_after_dispose_is_an_error.json",
            "recycled_id_inherits_nothing.json",
            "scope_teardown_equals_fold_of_disposals.json",
            "scoping_bounds_teardown_not_visibility.json",
            "signal_materializes_once_per_batch.json",
            "signal_materializes_without_a_read.json",
            "teardown_runs_members_in_reverse_creation_order.json",
            "transitive_invalidation_reaches_depth.json",
        )

        /**
         * Ops this runner implements. A fixture naming anything outside this set
         * is skipped **loudly and by name**, its skip recorded in
         * [EXPECTED_SKIPS]. `merge_cell` (the #lzmergefeed accumulate/fold write
         * surface) is deliberately absent — the five merge-feed fixtures skip
         * through this filter. Never widen a skip to make a red build green: a
         * fixture that runs and fails is a finding to report, and adding
         * `merge_cell`/`merges_of` here without implementing the op would fake a
         * pass.
         */
        val SUPPORTED_OPS = setOf(
            "batch",
            "begin_scope",
            "cell",
            "churn",
            "computed",
            "disarm",
            "dispose",
            "dispose_fanout",
            // `dispose_signal` and `signal` are the on-disk vocab; `undrive` and
            // `drive` are the Cell-kernel names for the same ops (an eager
            // Computed — #lzcellkernel), dual-accepted so a future
            // spec-repo fixture emitting them does not panic as an unknown op.
            "dispose_signal",
            "drive",
            "dispose_stale_handle",
            "effect",
            "end_scope",
            "fanout",
            "read",
            "set_cell",
            "signal",
            "undrive",
        )

        /**
         * Assertion keys this runner evaluates. An `expect` block naming
         * anything else is a hard error: reporting green against an assertion
         * the runner silently ignored is the "green while testing nothing"
         * failure mode this whole suite exists to close.
         */
        val KNOWN_EXPECT_KEYS = setOf(
            "cleanup_order",
            "computes_of",
            "dependencies_of",
            "dependents_of",
            "error",
            "note",
            "observed_by",
            "observed_count",
            "read",
            "readable",
            "scope_owned_count",
            "value",
        )

        /**
         * Divergences between lazily-kt and the canonical corpus, as
         * `model/fixture#step:key`.
         *
         * Asserted **in both directions**: the observed set must equal this one,
         * so a new divergence fails the build *and* a fixed one forces its entry
         * to be deleted. Every entry would be a standing finding against
         * lazily-kt, never a relaxation of a fixture. Empty, and it must stay
         * empty unless a real divergence is found.
         */
        val KNOWN_DIVERGENCES = emptySet<String>()

        /**
         * Fixtures parked from replay despite using only [SUPPORTED_OPS]: they
         * assert novel expectation keys this runner does not model yet, so
         * replaying them would trip the unrecognised-assertion-key hard error.
         *
         * `feedback_drain_bound_reports_exhaustion` (#lzmergefeed) pins
         * `drain_exhausted`/`writes_own_cone`, the bounded-feedback drain
         * semantics tracked as a carry-forward item. Recorded as an
         * accounted-for skip rather than silently mis-replayed against
         * expectation keys the runner cannot check — mirrors the lazily-cpp
         * runner (d36130b). The five `merge_cell` fixtures are NOT parked here:
         * they skip through the unsupported-op filter instead.
         */
        val PARKED = mapOf(
            "feedback_drain_bound_reports_exhaustion.json" to
                "drain_exhausted/writes_own_cone (#lzmergefeed)",
        )

        /**
         * The full skip ledger: fixture -> reason, asserted to equal the
         * observed skip set EXACTLY (both directions) per model. A skipped
         * fixture that becomes replayable fails here until its entry is removed;
         * a newly-unsupported op fails here immediately. Neither direction is
         * silent — this is a ledger of findings against lazily-kt, not a
         * relaxation of the corpus.
         *
         * The five `merge_cell` entries are skipped because the op is absent
         * from [SUPPORTED_OPS] (the runner has no merge/fold node kind); the
         * `feedback_drain_bound_reports_exhaustion` entry is [PARKED] for its
         * novel assertion key. Do NOT implement merge ops to clear these — the
         * fix is upstream (#lzmergefeed), and clearing an entry means the op is
         * genuinely modelled.
         */
        val EXPECTED_SKIPS = mapOf(
            "exact_fold_paths_stay_exact.json" to "merge_cell",
            "feedback_drain_bound_reports_exhaustion.json" to
                "drain_exhausted/writes_own_cone (#lzmergefeed)",
            "merge_cell_acquires_no_dependency_edge.json" to "merge_cell",
            "merge_feed_through_a_formula_coalesces.json" to "merge_cell",
            "merge_folds_synchronously_in_batch.json" to "merge_cell",
            "merge_per_settled_cone_not_per_write.json" to "merge_cell",
        )

        /** Sentinel for a read that raised `read_after_dispose`. */
        const val READ_AFTER_DISPOSE = "read_after_dispose"
    }

    /** The kind of a node, as the corpus distinguishes them. */
    private enum class Kind { CELL, SLOT, EFFECT }

    // -- Models ------------------------------------------------------------

    /**
     * One execution model. The runner drives every model through this single
     * blocking surface so the three contexts cannot drift apart; the async model
     * bridges with `runBlocking` internally rather than making the whole runner
     * suspend, which keeps op dispatch identical for all three.
     */
    private interface Model {
        /** Effect ids, in the order their bodies ran. */
        val runLog: MutableList<String>

        /**
         * Effect ids, in the order their cleanups ran. Cumulative for the whole
         * replay — the individual-disposal scenario spreads three disposals over
         * three steps and pins the whole order on the last one.
         */
        val cleanupLog: MutableList<String>

        /**
         * Cumulative compute invocations per node id — the `computes_of`
         * observable.
         *
         * Counted from the start of the scenario, incremented by the compute
         * body itself (see [countCompute]) rather than derived from anything the
         * engine reports, and **never reset per step**. This is the only
         * caller-observable difference between an eager signal and the lazy computed
         * it is built on: the two return identical values for every read
         * sequence in these fixtures, so a runner that faked or approximated
         * this key would defeat the entire point of them.
         *
         * Synchronized because the async model's computes run on the context's
         * dispatcher, not the test thread.
         */
        val computeCounts: MutableMap<String, Int>

        /** Called from inside a wrapped compute body, once per invocation. */
        fun countCompute(id: String) {
            synchronized(computeCounts) {
                computeCounts[id] = (computeCounts[id] ?: 0) + 1
            }
        }

        /** Zero for a node that has never computed — never absent, never null. */
        fun computesOf(id: String): Int =
            synchronized(computeCounts) { computeCounts[id] ?: 0 }

        fun defineCell(id: String, value: Int, scope: String?)
        fun defineComputed(id: String, reads: List<String>, offset: Int, scope: String?)
        fun defineEffect(id: String, reads: List<String>, scope: String?)

        /**
         * An eager signal: `sum(reads) + offset`, the same compute convention as
         * [defineComputed]. Materializes once at creation (clause 1).
         */
        fun defineSignal(id: String, reads: List<String>, offset: Int, scope: String?)

        /**
         * Dispose the eager puller and nothing else (clause 4). NOT a node
         * teardown: the backing value stays readable and reverts to lazy
         * recompute-on-read, which is why the corpus's own docs call the name a
         * known inaccuracy.
         */
        fun disposeSignal(id: String)

        /**
         * Perform every write inside ONE batch, so N writes coalesce into one
         * effect flush at the outermost exit (clause 3).
         */
        fun batchWrites(writes: List<Pair<String, Int>>)

        /**
         * Throws [DisposedNodeException] when the node — or a node it recomputes
         * through — has been disposed. That throw is the corpus's
         * `read_after_dispose`.
         */
        fun read(id: String): Int

        fun setCell(id: String, value: Int)
        fun disposeId(id: String)
        fun kindOf(id: String): Kind
        fun isEffectActive(id: String): Boolean
        fun dependentsOf(id: String): Int
        fun dependenciesOf(id: String): Int
        fun beginScope(name: String)
        fun endScope(name: String)
        fun disarmScope(name: String)
        fun scopeOwned(name: String): Int

        /**
         * Drive the model to quiescence before assertions are evaluated.
         *
         * The synchronous models are already quiescent when an op returns. Async
         * effect reruns are executor-scheduled by contract, so the async model
         * must let them settle before `observed_by`, `observed_count`, or any
         * degree assertion can mean anything. This changes *when* assertions are
         * evaluated, never *what* they assert: an effect that never runs still
         * fails.
         */
        fun settle()

        fun close()
    }

    /** The synchronous [Context] — lazy slots, cells, effects, [TeardownScope]s. */
    private class SyncModel : Model {
        override val runLog = mutableListOf<String>()
        override val cleanupLog = mutableListOf<String>()
        override val computeCounts = HashMap<String, Int>()

        private val ctx = Context()
        private val nodes = HashMap<String, GraphNode>()
        private val scopes = HashMap<String, TeardownScope>()

        /**
         * The eager computeds, kept alongside [nodes] rather than in it: [nodes]
         * holds the Computed for an eager id, so reads, `readable`, and the degree
         * assertions all resolve through the ordinary computed path — and so
         * `dispose_signal`/`lazy` is visibly not a node teardown but a revert to
         * lazy. This map exists only to reach the Computed for `lazy`.
         */
        private val signals = HashMap<String, Computed<Int>>()

        // Value-threaded read (#lzcellkernel): the tracking surface is a parameter,
        // so a read inside a compute/effect closure (Compute receiver) tracks and a
        // top-level read (Context receiver) does not.
        @Suppress("UNCHECKED_CAST")
        private fun readNode(cx: ComputeOps, id: String): Int = when (val n = nodes[id]) {
            is Cell<*> -> cx.get(n as Cell<Int>)
            else -> error("unknown or unreadable node '$id'")
        }

        override fun defineCell(id: String, value: Int, scope: String?) {
            nodes[id] = scopes[scope]?.source(value) ?: ctx.source(value)
        }

        override fun defineComputed(id: String, reads: List<String>, offset: Int, scope: String?) {
            val compute: Compute.() -> Int = {
                countCompute(id)
                var sum = offset
                for (r in reads) sum += readNode(this, r)
                sum
            }
            // v2: every `computed` is guarded (`==` suppression) — there is no
            // unguarded mode. The reactive-graph fixtures' `computes_of` counts do
            // not distinguish the two on a `computed` node (an equal recompute
            // that would diverge never appears on one), so guarded replays green,
            // exactly as lazily-rs does.
            nodes[id] = scopes[scope]?.computed(compute) ?: ctx.computed(compute)
        }

        override fun defineSignal(id: String, reads: List<String>, offset: Int, scope: String?) {
            // The eager construction: an eager Computed (`computed().eager()`).
            val compute: Compute.() -> Int = {
                countCompute(id)
                var sum = offset
                for (r in reads) sum += readNode(this, r)
                sum
            }
            val fc = scopes[scope]?.eagerComputed(compute) ?: ctx.computed(compute).eager(ctx)
            signals[id] = fc
            nodes[id] = fc
        }

        override fun disposeSignal(id: String) =
            (signals[id] ?: error("no signal '$id'")).lazy(ctx)

        override fun batchWrites(writes: List<Pair<String, Int>>) {
            ctx.batch { for ((id, v) in writes) setCell(id, v) }
        }

        override fun defineEffect(id: String, reads: List<String>, scope: String?) {
            val run: Compute.() -> (() -> Unit)? = {
                runLog.add(id)
                // Swallowed, not propagated: an effect that reads through a
                // disposed node must not turn the publish that scheduled it into
                // a throw. The corpus asserts read-after-dispose at top-level
                // reads.
                try {
                    for (r in reads) readNode(this, r)
                } catch (_: DisposedNodeException) {
                    // Observed by the top-level read that names the same node.
                }
                { cleanupLog.add(id) }
            }
            nodes[id] = scopes[scope]?.effect(run) ?: ctx.effect(run)
        }

        override fun read(id: String): Int = readNode(ctx, id)

        @Suppress("UNCHECKED_CAST")
        override fun setCell(id: String, value: Int) {
            val cell = nodes[id] as? Source<*> ?: error("set_cell on non-cell '$id'")
            (cell as Source<Int>).set(ctx, value)
        }

        // The entry stays in the map: a disposed node remains
        // readable-as-an-error, and disposing it again must be a no-op.
        override fun disposeId(id: String) = ctx.disposeNode(nodes[id] ?: error("unknown '$id'"))

        override fun kindOf(id: String): Kind = when (nodes[id]) {
            is Source<*> -> Kind.CELL
            is Effect -> Kind.EFFECT
            else -> Kind.SLOT
        }

        override fun isEffectActive(id: String): Boolean =
            ctx.isEffectActive(nodes[id] as Effect)

        override fun dependentsOf(id: String): Int = ctx.dependentCount(nodes[id]!!)
        override fun dependenciesOf(id: String): Int = ctx.dependencyCount(nodes[id]!!)

        override fun beginScope(name: String) { scopes[name] = ctx.scope() }
        override fun endScope(name: String) = scopes[name]!!.end()
        override fun disarmScope(name: String) = scopes[name]!!.disarm()
        override fun scopeOwned(name: String): Int = scopes[name]!!.size

        override fun settle() {}
        override fun close() {}
    }

    /** The lock-backed [ThreadSafeContext]. Same graph semantics, serialized. */
    private class ThreadSafeModel : Model {
        override val runLog = mutableListOf<String>()
        override val cleanupLog = mutableListOf<String>()
        override val computeCounts = HashMap<String, Int>()

        private val ctx = ThreadSafeContext()
        private val nodes = HashMap<String, ThreadSafeGraphNode>()
        private val scopes = HashMap<String, ThreadSafeTeardownScope>()

        /** See [SyncModel.signals]. */
        private val signals = HashMap<String, ThreadSafeSignalHandle<Int>>()

        @Suppress("UNCHECKED_CAST")
        private fun readNode(id: String): Int = when (val n = nodes[id]) {
            is ThreadSafeCellHandle<*> -> ctx.getCell(n as ThreadSafeCellHandle<Int>)
            is ThreadSafeSlotHandle<*> -> ctx.get(n as ThreadSafeSlotHandle<Int>)
            else -> error("unknown or unreadable node '$id'")
        }

        override fun defineCell(id: String, value: Int, scope: String?) {
            nodes[id] = scopes[scope]?.cell(value) ?: ctx.cell(value)
        }

        override fun defineComputed(id: String, reads: List<String>, offset: Int, scope: String?) {
            val compute: ThreadSafeContext.() -> Int = {
                countCompute(id)
                var sum = offset
                for (r in reads) sum += readNode(r)
                sum
            }
            nodes[id] = scopes[scope]?.computed(compute) ?: ctx.computed(compute)
        }

        override fun defineSignal(id: String, reads: List<String>, offset: Int, scope: String?) {
            val handle = ctx.signal {
                countCompute(id)
                var sum = offset
                for (r in reads) sum += readNode(r)
                sum
            }
            signals[id] = handle
            nodes[id] = handle.slot
            scopes[scope]?.let { it.adopt(handle.slot); it.adopt(handle.effect) }
        }

        override fun disposeSignal(id: String) =
            ctx.disposeSignal(signals[id] ?: error("no signal '$id'"))

        override fun batchWrites(writes: List<Pair<String, Int>>) {
            ctx.batch { for ((id, v) in writes) setCell(id, v) }
        }

        override fun defineEffect(id: String, reads: List<String>, scope: String?) {
            val run: ThreadSafeContext.() -> (() -> Unit)? = {
                runLog.add(id)
                try {
                    for (r in reads) readNode(r)
                } catch (_: DisposedNodeException) {
                    // See SyncModel.defineEffect.
                }
                { cleanupLog.add(id) }
            }
            nodes[id] = scopes[scope]?.effect(run) ?: ctx.effect(run)
        }

        override fun read(id: String): Int = readNode(id)

        @Suppress("UNCHECKED_CAST")
        override fun setCell(id: String, value: Int) {
            val cell = nodes[id] as? ThreadSafeCellHandle<*>
                ?: error("set_cell on non-cell '$id'")
            ctx.setCell(cell as ThreadSafeCellHandle<Int>, value)
        }

        override fun disposeId(id: String) = ctx.disposeNode(nodes[id] ?: error("unknown '$id'"))

        override fun kindOf(id: String): Kind = when (nodes[id]) {
            is ThreadSafeCellHandle<*> -> Kind.CELL
            is ThreadSafeEffectHandle -> Kind.EFFECT
            else -> Kind.SLOT
        }

        override fun isEffectActive(id: String): Boolean =
            ctx.isEffectActive(nodes[id] as ThreadSafeEffectHandle)

        override fun dependentsOf(id: String): Int = ctx.dependentCount(nodes[id]!!)
        override fun dependenciesOf(id: String): Int = ctx.dependencyCount(nodes[id]!!)

        override fun beginScope(name: String) { scopes[name] = ctx.scope() }
        override fun endScope(name: String) = scopes[name]!!.end()
        override fun disarmScope(name: String) = scopes[name]!!.disarm()
        override fun scopeOwned(name: String): Int = scopes[name]!!.size

        override fun settle() {}
        override fun close() {}
    }

    /**
     * The [AsyncContext] — the model that matters most here. Reads go through
     * [AsyncContext.getAsync] so the replay drives real suspension and the
     * revision/in-flight machinery that broke the pull chain in lazily-dart, and
     * effect bodies and cleanups run on the context's dispatcher rather than
     * inline. The logs are synchronized for exactly that reason.
     */
    private class AsyncModel : Model {
        override val runLog: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        override val cleanupLog: MutableList<String> =
            Collections.synchronizedList(mutableListOf())

        // Computes run on the context's dispatcher, so this is mutated off the
        // test thread. Every access goes through Model.countCompute /
        // Model.computesOf, which synchronize on it.
        override val computeCounts = HashMap<String, Int>()

        private val ctx = AsyncContext()
        private val nodes = HashMap<String, AsyncGraphNode>()
        private val scopes = HashMap<String, AsyncTeardownScope>()

        /** See [SyncModel.signals]. */
        private val signals = HashMap<String, AsyncContext.AsyncSignalHandle<Int>>()

        @Suppress("UNCHECKED_CAST")
        private suspend fun AsyncComputeContext.readNode(id: String): Int =
            when (val n = nodes[id]) {
                is AsyncContext.AsyncCellHandle<*> ->
                    getCell(n as AsyncContext.AsyncCellHandle<Int>)
                is AsyncContext.AsyncSlotHandle<*> ->
                    getAsync(n as AsyncContext.AsyncSlotHandle<Int>)
                else -> error("unknown or unreadable node '$id'")
            }

        @Suppress("UNCHECKED_CAST")
        private suspend fun readTop(id: String): Int = when (val n = nodes[id]) {
            is AsyncContext.AsyncCellHandle<*> ->
                ctx.getCell(n as AsyncContext.AsyncCellHandle<Int>)
            is AsyncContext.AsyncSlotHandle<*> ->
                ctx.getAsync(n as AsyncContext.AsyncSlotHandle<Int>)
            else -> error("unknown or unreadable node '$id'")
        }

        override fun defineCell(id: String, value: Int, scope: String?) {
            nodes[id] = scopes[scope]?.cell(value) ?: ctx.cell(value)
        }

        override fun defineComputed(id: String, reads: List<String>, offset: Int, scope: String?) {
            val compute: suspend AsyncComputeContext.() -> Int = {
                countCompute(id)
                var sum = offset
                for (r in reads) sum += readNode(r)
                sum
            }
            nodes[id] = scopes[scope]?.computedAsync(compute) ?: ctx.computedAsync(compute)
        }

        override fun defineSignal(id: String, reads: List<String>, offset: Int, scope: String?) {
            val handle = ctx.signalAsync {
                countCompute(id)
                var sum = offset
                for (r in reads) sum += readNode(r)
                sum
            }
            signals[id] = handle
            nodes[id] = handle.slot
            scopes[scope]?.let { it.adopt(handle.slot); it.adopt(handle.effect) }
        }

        // AsyncContext ships `disposeSignalNode` (both halves) but no
        // puller-only counterpart, so the puller effect is disposed directly.
        // That IS clause 4's operation — disposing the effect and nothing else.
        override fun disposeSignal(id: String) = runBlocking {
            ctx.disposeNode((signals[id] ?: error("no signal '$id'")).effect)
        }

        override fun batchWrites(writes: List<Pair<String, Int>>) {
            ctx.batch { for ((id, v) in writes) setCell(id, v) }
        }

        override fun defineEffect(id: String, reads: List<String>, scope: String?) {
            val run: suspend AsyncComputeContext.() -> (suspend () -> Unit)? = {
                runLog.add(id)
                try {
                    for (r in reads) readNode(r)
                } catch (_: DisposedNodeException) {
                    // See SyncModel.defineEffect.
                }
                suspend { cleanupLog.add(id); Unit }
            }
            nodes[id] = scopes[scope]?.effectAsync(run) ?: ctx.effectAsync(run)
        }

        override fun read(id: String): Int = runBlocking { readTop(id) }

        @Suppress("UNCHECKED_CAST")
        override fun setCell(id: String, value: Int) {
            val cell = nodes[id] as? AsyncContext.AsyncCellHandle<*>
                ?: error("set_cell on non-cell '$id'")
            ctx.setCell(cell as AsyncContext.AsyncCellHandle<Int>, value)
        }

        override fun disposeId(id: String) = runBlocking {
            ctx.disposeNode(nodes[id] ?: error("unknown '$id'"))
        }

        override fun kindOf(id: String): Kind = when (nodes[id]) {
            is AsyncContext.AsyncCellHandle<*> -> Kind.CELL
            is AsyncContext.AsyncEffectHandle -> Kind.EFFECT
            else -> Kind.SLOT
        }

        override fun isEffectActive(id: String): Boolean =
            !ctx.isDisposed(nodes[id] as AsyncContext.AsyncEffectHandle)

        override fun dependentsOf(id: String): Int = ctx.dependentCount(nodes[id]!!)
        override fun dependenciesOf(id: String): Int = ctx.dependencyCount(nodes[id]!!)

        override fun beginScope(name: String) { scopes[name] = ctx.scope() }
        override fun endScope(name: String) = runBlocking { scopes[name]!!.end() }
        override fun disarmScope(name: String) = scopes[name]!!.disarm()
        override fun scopeOwned(name: String): Int = scopes[name]!!.size

        override fun settle() = runBlocking { ctx.settle() }
        override fun close() = runBlocking { ctx.dispose() }
    }

    // -- Observation / report ---------------------------------------------

    /** Everything a scenario leaves behind that `observationally_equal` compares. */
    private class Observation {
        var cleanupOrder: List<String> = emptyList()
        val readable = sortedMapOf<String, Boolean>()
        val reads = sortedMapOf<String, Any>()
        var afterPublishObserved: List<String> = emptyList()
        val afterPublishReads = sortedMapOf<String, Any>()
        val degrees = sortedMapOf<String, Int>()

        fun describe(): String =
            "cleanup_order=$cleanupOrder readable=$readable reads=$reads " +
                "after_publish_observed=$afterPublishObserved " +
                "after_publish_reads=$afterPublishReads degrees=$degrees"
    }

    /**
     * What a single fixture replay actually did. [ops]/[checks] are what promote
     * a fixture from "found on disk" to "executed".
     */
    private class Report {
        var ops = 0
        var checks = 0
        val failures = mutableListOf<String>()
        val observation = Observation()
    }

    // -- Fixture shape helpers --------------------------------------------

    private fun stepsOf(node: JsonObject): List<JsonObject> =
        node["steps"]!!.jsonArray.map { it.jsonObject }

    private fun scenariosOf(fx: JsonObject): List<JsonObject> =
        fx["scenarios"]!!.jsonArray.map { it.jsonObject }

    private fun opsOf(fx: JsonObject): Set<String> {
        val out = sortedSetOf<String>()
        fun collect(o: JsonObject) {
            for (s in stepsOf(o)) out.add(s["op"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        when (val shape = fx["shape"]?.jsonPrimitive?.content) {
            "steps" -> collect(fx)
            "scenarios" -> for (sc in scenariosOf(fx)) collect(sc)
            else -> error("unknown fixture shape '$shape'")
        }
        return out
    }

    private fun strs(v: JsonElement?): List<String> =
        v?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    /** A top-level read: the value, or [READ_AFTER_DISPOSE] on a disposed node. */
    private fun readOrError(model: Model, id: String): Any = try {
        model.read(id)
    } catch (_: DisposedNodeException) {
        READ_AFTER_DISPOSE
    }

    /**
     * `readable` is "can this node still be observed", which for an effect is
     * registration rather than a value.
     */
    private fun alive(model: Model, id: String): Boolean =
        if (model.kindOf(id) == Kind.EFFECT) {
            model.isEffectActive(id)
        } else {
            readOrError(model, id) != READ_AFTER_DISPOSE
        }

    /** Compare an observed value against a JSON scalar. */
    private fun jsonEq(got: Any?, want: JsonElement?): Boolean {
        val p = want as? JsonPrimitive ?: return false
        return when (got) {
            is Int -> runCatching { p.int }.getOrNull() == got
            is Boolean -> runCatching { p.boolean }.getOrNull() == got
            else -> p.content == got.toString()
        }
    }

    // -- Replay ------------------------------------------------------------

    /**
     * Replay one op stream. [tail] is the `scenarios` shape's `expected` block,
     * evaluated against the final world state when present.
     */
    private fun replay(
        model: Model,
        fixture: String,
        steps: List<JsonObject>,
        tail: JsonObject?,
    ): Report {
        val report = Report()
        var stepIdx = 0

        fun check(key: String, got: Any?, want: JsonElement?) {
            report.checks++
            if (!jsonEq(got, want)) report.failures.add("#$stepIdx:$key — got $got, want $want")
        }

        fun checkList(key: String, got: List<String>, want: List<String>) {
            report.checks++
            if (got != want) report.failures.add("#$stepIdx:$key — got $got, want $want")
        }

        for (i in steps.indices) {
            stepIdx = i
            val step = steps[i]
            val op = step["op"]!!.jsonObject
            val type = op["type"]!!.jsonPrimitive.content
            val scope = op["scope"]?.jsonPrimitive?.content
            val runsBefore = model.runLog.size
            var opValue: Any? = null
            var opError = false
            report.ops++

            when (type) {
                "cell" -> model.defineCell(
                    op["id"]!!.jsonPrimitive.content,
                    op["value"]!!.jsonPrimitive.int,
                    scope,
                )
                "computed" -> model.defineComputed(
                    op["id"]!!.jsonPrimitive.content,
                    strs(op["reads"]),
                    op["offset"]?.jsonPrimitive?.int ?: 0,
                    scope,
                )
                // `signal`/`drive` both define the eager construction — a driven
                // Computed (vocab-map: `signal(f)` ≡ `computed(f).eager()`).
                "signal", "drive" -> model.defineSignal(
                    op["id"]!!.jsonPrimitive.content,
                    strs(op["reads"]),
                    op["offset"]?.jsonPrimitive?.int ?: 0,
                    scope,
                )
                // `dispose_signal`/`undrive` both revert an eager value to lazy.
                "dispose_signal", "undrive" -> model.disposeSignal(op["id"]!!.jsonPrimitive.content)
                // A single op carrying its writes, not a begin/end pair, so the
                // runner needs no nesting state. All writes land in ONE batch.
                "batch" -> model.batchWrites(
                    op["writes"]!!.jsonArray.map {
                        val w = it.jsonObject
                        w["id"]!!.jsonPrimitive.content to w["value"]!!.jsonPrimitive.int
                    },
                )
                "effect" -> model.defineEffect(
                    op["id"]!!.jsonPrimitive.content,
                    strs(op["reads"]),
                    scope,
                )
                "read" -> {
                    opValue = readOrError(model, op["id"]!!.jsonPrimitive.content)
                    opError = opValue == READ_AFTER_DISPOSE
                }
                "set_cell" -> model.setCell(
                    op["id"]!!.jsonPrimitive.content,
                    op["value"]!!.jsonPrimitive.int,
                )
                "dispose" -> model.disposeId(op["id"]!!.jsonPrimitive.content)
                "fanout" -> {
                    // Subscribers are effects, not derived slots: the corpus
                    // asserts `observed_count` on a publish, and in a lazy
                    // binding only an eager reader observes a publish without
                    // being pulled.
                    val prefix = op["id_prefix"]!!.jsonPrimitive.content
                    val reads = strs(op["reads"])
                    for (n in 0 until op["count"]!!.jsonPrimitive.int) {
                        model.defineEffect("${prefix}_$n", reads, null)
                    }
                }
                "dispose_fanout" -> {
                    val prefix = op["id_prefix"]!!.jsonPrimitive.content
                    for (n in 0 until op["count"]!!.jsonPrimitive.int) {
                        model.disposeId("${prefix}_$n")
                    }
                }
                "churn" -> churn(model, op)
                "begin_scope" -> model.beginScope(op["scope"]!!.jsonPrimitive.content)
                "end_scope" -> model.endScope(op["scope"]!!.jsonPrimitive.content)
                // A disarmed scope owns nothing; it stays open under the same
                // name so a later `end_scope` is the no-op the fixture asserts.
                "disarm" -> model.disarmScope(op["scope"]!!.jsonPrimitive.content)
                "dispose_stale_handle" -> {
                    // The point of the op: the handle's id has been recycled
                    // onto a node of another kind, so tearing down through it
                    // must be a no-op. lazily-kt's arena recycles ids for real,
                    // so `disposeNode` reads the kind out of the arena and
                    // declines — the guard `Context.resolve` documents.
                    val of = op["handle_of"]!!.jsonPrimitive.content
                    val wantKind = op["handle_kind"]!!.jsonPrimitive.content
                    assertEquals(
                        wantKind,
                        model.kindOf(of).name.lowercase(),
                        "$fixture#$i: handle_kind does not match the recorded handle",
                    )
                    model.disposeId(of)
                }
                else -> error(
                    "$fixture#$i: unsupported op '$type' reached the engine — the " +
                        "runnability filter should have skipped this fixture",
                )
            }

            model.settle()
            val observed = model.runLog.toList().subList(runsBefore, model.runLog.size)

            val expect = step["expect"]?.jsonObject ?: continue
            val unknown = (expect.keys - KNOWN_EXPECT_KEYS).sorted()
            check(unknown.isEmpty()) {
                "$fixture#$i: unrecognised assertion key(s) $unknown — refusing to report " +
                    "green against an assertion this runner does not evaluate"
            }

            // Sorted so evaluation order is deterministic and matches the
            // reference runner's. Load-bearing, not cosmetic: `dependents_of`
            // sorts before `read`, and a lazy binding re-registers edges when it
            // recomputes, so reading first would change the degree the same step
            // then asserts.
            for (key in expect.keys.sorted()) {
                val want = expect[key]
                when (key) {
                    "note" -> {}
                    // Sorts FIRST of every key these fixtures use, which is
                    // load-bearing: `readable` and `value` both perform a read,
                    // and on a de-eagered signal a read triggers the lazy
                    // recompute that would make a conforming and a
                    // non-conforming binding agree. `dispose_signal`'s
                    // discriminating step asserts both keys on one step, and the
                    // count has to be sampled before the read.
                    "computes_of" -> for (id in want!!.jsonObject.keys.sorted()) {
                        check("computes_of.$id", model.computesOf(id), want.jsonObject[id])
                    }
                    "dependents_of" -> for (id in want!!.jsonObject.keys.sorted()) {
                        check("dependents_of.$id", model.dependentsOf(id), want.jsonObject[id])
                    }
                    "dependencies_of" -> for (id in want!!.jsonObject.keys.sorted()) {
                        check("dependencies_of.$id", model.dependenciesOf(id), want.jsonObject[id])
                    }
                    "error" -> {
                        val wantError = when {
                            want == null || want is JsonNull -> false
                            (want as JsonPrimitive).content == READ_AFTER_DISPOSE -> true
                            else -> error("$fixture#$i: unknown expected error $want")
                        }
                        report.checks++
                        if (opError != wantError) {
                            report.failures.add("#$stepIdx:error — got $opError, want $wantError")
                        }
                    }
                    // Paired with an `error` expectation the error key is
                    // authoritative; `value` only applies to a successful read.
                    // On a `read` op this is the value the op returned. On any
                    // other op (the signal fixtures assert it on `signal`) it
                    // means the value of the node the op names, so it is read
                    // here — after `computes_of`, which sorts first.
                    "value" -> if (expect["error"] == null) {
                        val got = opValue
                            ?: readOrError(model, op["id"]!!.jsonPrimitive.content)
                        check("value", got, want)
                    }
                    "read" -> for (id in want!!.jsonObject.keys.sorted()) {
                        check("read.$id", readOrError(model, id), want.jsonObject[id])
                    }
                    "readable" -> for (id in want!!.jsonObject.keys.sorted()) {
                        check("readable.$id", alive(model, id), want.jsonObject[id])
                    }
                    "observed_by" -> checkList("observed_by", observed, strs(want))
                    "observed_count" -> check("observed_count", observed.size, want)
                    // Only effects run a cleanup callback, so the expected order
                    // is projected onto its effect entries.
                    "cleanup_order" -> checkList(
                        "cleanup_order",
                        model.cleanupLog.toList(),
                        strs(want).filter { model.kindOf(it) == Kind.EFFECT },
                    )
                    "scope_owned_count" -> for (n in want!!.jsonObject.keys.sorted()) {
                        check("scope_owned_count.$n", model.scopeOwned(n), want.jsonObject[n])
                    }
                    else -> error("$fixture#$i: unhandled assertion key '$key'")
                }
            }
        }

        // -- `scenarios`-shaped tail --------------------------------------
        report.observation.cleanupOrder = model.cleanupLog.toList()
        if (tail == null) return report

        stepIdx = -1 // the `expected` tail is not a numbered step
        tail["final_state"]?.jsonObject?.let { finalState ->
            finalState["dependents_of"]?.jsonObject?.let { m ->
                for (id in m.keys.sorted()) {
                    val got = model.dependentsOf(id)
                    check("final.dependents_of.$id", got, m[id])
                    report.observation.degrees[id] = got
                }
            }
            finalState["readable"]?.jsonObject?.let { m ->
                for (id in m.keys.sorted()) {
                    val ok = alive(model, id)
                    check("final.readable.$id", ok, m[id])
                    report.observation.readable[id] = ok
                }
            }
            finalState["read"]?.jsonObject?.let { m ->
                for (id in m.keys.sorted()) {
                    val got = readOrError(model, id)
                    check("final.read.$id", got, m[id])
                    report.observation.reads[id] = got
                }
            }
        }

        val publish = tail["after_publish"]?.jsonObject
        val publishOp = publish?.get("op")?.jsonObject
        if (publish != null && publishOp != null) {
            val before = model.runLog.size
            model.setCell(
                publishOp["id"]!!.jsonPrimitive.content,
                publishOp["value"]!!.jsonPrimitive.int,
            )
            model.settle()
            report.observation.afterPublishObserved =
                model.runLog.toList().subList(before, model.runLog.size)
            checkList(
                "after_publish.observed_by",
                report.observation.afterPublishObserved,
                strs(publish["observed_by"]),
            )
            // Order matches the reference runner: reads (which re-register edges
            // in a lazy binding) precede the degree assertions that count them.
            publish["read"]?.jsonObject?.let { m ->
                for (id in m.keys.sorted()) {
                    val got = readOrError(model, id)
                    check("after_publish.read.$id", got, m[id])
                    report.observation.afterPublishReads[id] = got
                }
            }
            publish["dependents_of"]?.jsonObject?.let { m ->
                for (id in m.keys.sorted()) {
                    check("after_publish.dependents_of.$id", model.dependentsOf(id), m[id])
                }
            }
        }

        return report
    }

    private fun churn(model: Model, op: JsonObject) {
        val source = op["source"]!!.jsonPrimitive.content
        val prefix = op["id_prefix"]!!.jsonPrimitive.content
        val width = op["live_width"]!!.jsonPrimitive.int
        val cycles = op["cycles"]!!.jsonPrimitive.int
        when (val mode = op["mode"]!!.jsonPrimitive.content) {
            // Hold `live_width` subscribers; each cycle disposes one and creates
            // its replacement, so the live count is invariant.
            "dispose_then_create" -> for (c in 0 until cycles) {
                val id = "${prefix}_${c % width}"
                model.disposeId(id)
                model.defineEffect(id, listOf(source), null)
            }
            // One teardown scope per cycle; its subscriber is gone by the end of
            // its own cycle, so it contributes nothing to the steady state.
            "scope_per_cycle" -> {
                val scopeName = "${prefix}_scoped"
                for (c in 0 until cycles) {
                    model.beginScope(scopeName)
                    model.defineEffect("${prefix}_scoped_member", listOf(source), scopeName)
                    model.endScope(scopeName)
                }
            }
            else -> error("unknown churn mode '$mode'")
        }
    }

    // -- Corpus driver -----------------------------------------------------

    /** Replay the whole corpus against one execution model. Returns (fixtures, ops, checks). */
    private fun runCorpus(create: () -> Model, modelName: String): Triple<Int, Int, Int> {
        val executed = sortedSetOf<String>()
        val skipped = sortedMapOf<String, String>()
        val divergences = sortedSetOf<String>()
        var totalOps = 0
        var totalChecks = 0

        for (name in FIXTURES.sorted()) {
            // Every fixture is opened — including skipped ones, whose ops are
            // read from the file rather than assumed. That is what keeps the
            // coverage manifest (and the positive assertions) honest.
            val fx = json.parseToJsonElement(ConformanceFixtures.read("$area/$name")).jsonObject
            val unsupported = (opsOf(fx) - SUPPORTED_OPS).sorted()
            val reasons = mutableListOf<String>()
            if (unsupported.isNotEmpty()) reasons.add(unsupported.joinToString(", "))
            PARKED[name]?.let { reasons.add(it) }
            if (reasons.isNotEmpty()) {
                val reason = reasons.joinToString("; ")
                skipped[name] = reason
                println("reactive-graph[$modelName] SKIP $name — $reason")
                continue
            }

            // Dispatch on the fixture's declared `shape`, not on its filename: a
            // filename special case goes stale the moment a second
            // scenarios-shaped fixture is added. An unrecognised shape is a hard
            // error.
            val models = mutableListOf<Model>()
            val reports = mutableListOf<Report>()
            try {
                when (val shape = fx["shape"]?.jsonPrimitive?.content) {
                    "steps" -> {
                        val m = create().also { models.add(it) }
                        reports.add(replay(m, name, stepsOf(fx), null))
                    }
                    "scenarios" -> {
                        val tail = fx["expected"]?.jsonObject
                        for (sc in scenariosOf(fx)) {
                            // Each scenario gets its OWN context:
                            // `observationally_equal` is a claim about two
                            // independent worlds, not about one world twice.
                            val m = create().also { models.add(it) }
                            reports.add(replay(m, name, stepsOf(sc), tail))
                        }
                    }
                    else -> error("$name: unknown fixture shape '$shape'")
                }

                // `observationally_equal`: the named scenarios must agree on
                // every observable, not merely each satisfy `expected`
                // independently. This is the whole reason the `scenarios` shape
                // exists — a relation between two op streams is not expressible
                // in a single `steps` array.
                val pair = strs(fx["expected"]?.jsonObject?.get("observationally_equal"))
                if (pair.isNotEmpty()) {
                    val names = scenariosOf(fx).map { it["name"]!!.jsonPrimitive.content }
                    val idx = pair.map { p ->
                        names.indexOf(p).also {
                            check(it >= 0) { "$name: unknown scenario '$p'" }
                        }
                    }
                    for (w in 1 until idx.size) {
                        val a = reports[idx[w - 1]].observation.describe()
                        val b = reports[idx[w]].observation.describe()
                        if (a != b) {
                            reports[idx[w]].failures.add(
                                "#observationally_equal — ${pair[w - 1]} [$a] != ${pair[w]} [$b]",
                            )
                        }
                    }
                    reports[0].checks++
                }
            } finally {
                for (m in models) m.close()
            }

            val ops = reports.sumOf { it.ops }
            val checks = reports.sumOf { it.checks }
            assertTrue(ops > 0, "$modelName/$name: replayed zero ops")
            assertTrue(checks > 0, "$modelName/$name: replayed zero assertions")

            for ((si, r) in reports.withIndex()) {
                for (f in r.failures) {
                    val tag = if (reports.size > 1) "[$si]" else ""
                    val entry = "$modelName/$name$tag$f"
                    println("  DIVERGENCE $entry")
                    divergences.add(entry)
                }
            }

            // Promotion to `executed` happens HERE and only here: after ops ran
            // and assertions were evaluated. Finding the file is not enough.
            executed.add(name)
            totalOps += ops
            totalChecks += checks
            println("reactive-graph[$modelName] $name: $ops ops, $checks assertions")
        }

        println(
            "reactive-graph[$modelName]: ${executed.size}/${FIXTURES.size} fixtures replayed, " +
                "$totalOps ops, $totalChecks assertions, ${skipped.size} skipped, " +
                "${divergences.size} divergences",
        )

        // Divergence ledger, asserted in BOTH directions: a new divergence fails
        // the build and a fixed one forces its entry to be deleted. A divergence
        // is a FINDING against lazily-kt. Never relax a fixture to make this
        // pass.
        val documented = KNOWN_DIVERGENCES.filter { it.startsWith("$modelName/") }.toSortedSet()
        assertEquals(
            documented,
            divergences,
            "$modelName: divergence ledger is stale — update KNOWN_DIVERGENCES " +
                "(expected = documented, actual = observed)",
        )
        // The skip ledger must match EXACTLY, in both directions. A #lzmergefeed
        // fixture that becomes replayable fails here until its entry is removed;
        // a newly-unsupported op or a new parked fixture fails here immediately.
        // Never widen a skip to make a red build green.
        assertEquals(
            EXPECTED_SKIPS.toSortedMap(),
            skipped,
            "$modelName: skip ledger drifted — a #lzmergefeed fixture became replayable, " +
                "an entry became stale, or a newly-unsupported op arrived. Update EXPECTED_SKIPS " +
                "only after confirming the op is genuinely (un)modelled.",
        )
        val expectedExecuted = (FIXTURES.toSet() - EXPECTED_SKIPS.keys).toSortedSet()
        assertEquals(
            expectedExecuted,
            executed,
            "found ${FIXTURES.size} fixture(s), expected ${expectedExecuted.size} replayed and " +
                "${EXPECTED_SKIPS.size} skipped, but did not replay them all. A non-empty " +
                "fixture directory is not evidence of coverage — skipped: $skipped",
        )
        assertTrue(
            "transitive_invalidation_reaches_depth.json" in executed,
            "the transitive-depth fixture pins the async invalidation cascade and must run " +
                "against every context",
        )
        return Triple(executed.size, totalOps, totalChecks)
    }

    @Test
    fun reactiveGraphConformance() {
        ConformanceFixtures.requireRoot()

        // (1) The fixture set on disk must match FIXTURES exactly.
        val onDisk = Files.list(ConformanceFixtures.path(area)).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".json") }
                .sorted()
                .toList()
        }
        assertEquals(
            FIXTURES.sorted(),
            onDisk,
            "reactive-graph fixture set drifted from the canonical spec. A fixture was added, " +
                "renamed, or removed upstream — update FIXTURES/SUPPORTED_OPS so it cannot go " +
                "unrun (#lzspecconf).",
        )

        val models: List<Pair<String, () -> Model>> = listOf(
            "Context" to { SyncModel() },
            "ThreadSafeContext" to { ThreadSafeModel() },
            "AsyncContext" to { AsyncModel() },
        )

        var replayed = 0
        var ops = 0
        var checks = 0
        val failures = mutableListOf<String>()
        for ((modelName, create) in models) {
            try {
                val (r, o, c) = runCorpus(create, modelName)
                replayed += r
                ops += o
                checks += c
            } catch (t: Throwable) {
                // A fixture failure is a FINDING. Collect every model's result
                // rather than aborting on the first, so the report says which
                // contexts diverge — do not edit the fixture and do not loosen
                // the assertion.
                failures.add("$modelName: ${t.message}")
            }
        }

        // Skipped fixtures (#lzmergefeed) never replay against any model, so the
        // expected product is over the replayable set, not the whole corpus.
        val replayable = FIXTURES.size - EXPECTED_SKIPS.size
        val expected = replayable * models.size
        assertTrue(
            replayed > 0,
            "ZERO reactive-graph fixtures replayed. The runner executed nothing — this is the " +
                "exact silent-skip failure it exists to prevent (#lzspecconf).",
        )
        assertTrue(
            ops > 0 && checks > 0,
            "reactive-graph replay executed $ops ops and $checks assertions; both must be " +
                "non-zero or the fixtures did not really run.",
        )

        println(
            "reactive-graph conformance: replayed $replayed/$expected " +
                "($replayable of ${FIXTURES.size} fixtures x ${models.size} contexts: " +
                models.joinToString(", ") { it.first } + "; ${EXPECTED_SKIPS.size} #lzmergefeed " +
                "fixtures skipped), $ops ops, $checks assertions",
        )

        if (failures.isNotEmpty()) {
            fail(
                "reactive-graph conformance FAILURES (findings against lazily-kt, not the " +
                    "fixtures):\n" + failures.joinToString("\n"),
            )
        }
        assertEquals(expected, replayed, "not every fixture replayed against every context.")
    }
}
