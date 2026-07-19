package io.github.lazily

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-language conformance for the reactive-graph plane (`#lzspecconf`) — see
 * the JSON fixtures under `lazily-spec/conformance/reactive-graph/`.
 *
 * lazily-kt replayed **none** of these fixtures until this runner existed;
 * `lazily-rs` was the only binding executing the corpus family-wide. That gap is
 * not academic: an invalidation-cascade defect shipped undetected in both
 * lazily-dart and lazily-go while a fixture encoding the violated property
 * (`transitive_invalidation_reaches_depth.json`) already sat on disk with
 * nothing running it.
 *
 * ## Replayed against EVERY context lazily-kt ships
 *
 * The runner is parameterised over the *execution model*, not written against
 * the default [Context]. This is the property that gives it its value: the dart
 * and go defects were both **correct synchronously and broken asynchronously**,
 * so a default-context-only replay would have reported green through the exact
 * defect it exists to catch. [AsyncContext] in particular had been rated
 * *unmeasured* rather than correct by an earlier audit.
 *
 *  - [Model.SYNC]        — [Context], the single-threaded pull engine
 *  - [Model.THREAD_SAFE] — [ThreadSafeContext], the `ReentrantLock` engine
 *  - [Model.ASYNC]       — [AsyncContext], the coroutine engine (revision
 *                          counters + in-flight state — the mechanism that
 *                          broke the pull chain in dart)
 *
 * ## Op support and skipping
 *
 * lazily-kt ships no teardown-scope API (`ctx.scope()`/`disarm()`), no disposal
 * of a derived slot (only [Context.disposeEffect]), and no fanout/churn
 * harness op, so 8 of the 9 fixtures cannot be replayed today. They are skipped
 * **loudly and by name** — [SKIPPED] records the exact unsupported ops per
 * fixture and the test prints them. A silent skip is the failure mode this
 * whole runner exists to eliminate; it is never acceptable to widen a skip to
 * make a red build green.
 *
 * ## Positive assertion (`#lzspecconf`)
 *
 * An absence guard ("is the spec sibling present?") cannot catch a replay that
 * is refactored away, renamed, or filtered out by a test selector. So this
 * asserts positively:
 *
 *  1. the fixture set on disk matches [FIXTURES] exactly — a fixture added or
 *     renamed upstream fails loudly instead of going unrun;
 *  2. [SUPPORTED] and [SKIPPED] partition [FIXTURES] with no overlap and no
 *     gap, so a fixture cannot be dropped from both and vanish;
 *  3. every supported fixture was replayed against every model, counted, and
 *     the count is asserted **non-zero** and equal to the expected product;
 *  4. a non-zero number of ops and assertions actually executed.
 *
 * Every replay also records through [ConformanceFixtures.record] so
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
            "dispose_detaches_edges_both_directions.json",
            "read_after_dispose_is_an_error.json",
            "recycled_id_inherits_nothing.json",
            "scope_teardown_equals_fold_of_disposals.json",
            "scoping_bounds_teardown_not_visibility.json",
            "transitive_invalidation_reaches_depth.json",
        )

        /** Fixtures this runner replays in full, against every model. */
        val SUPPORTED = listOf(
            "transitive_invalidation_reaches_depth.json",
        )

        /**
         * Fixtures skipped, each mapped to the ops lazily-kt does not implement.
         *
         * These are **capability gaps in lazily-kt**, not fixture defects. Each
         * entry is a standing finding: implement the op and move the fixture to
         * [SUPPORTED]. Never add an entry to silence a failing assertion — a
         * fixture that runs and fails is a finding to report, not to skip.
         */
        val SKIPPED = mapOf(
            // No teardown-scope API: no ctx.scope()/TeardownScope, and no
            // disposal of a derived slot (only disposeEffect).
            "cross_scope_teardown_hazard.json" to listOf("begin_scope", "end_scope", "dispose"),
            "disarm_disposes_nothing.json" to listOf("begin_scope", "end_scope", "disarm", "dispose"),
            "scope_teardown_equals_fold_of_disposals.json" to
                listOf("begin_scope", "end_scope", "dispose"),
            "scoping_bounds_teardown_not_visibility.json" to listOf("begin_scope", "end_scope"),
            // No slot disposal.
            "dispose_detaches_edges_both_directions.json" to listOf("dispose"),
            "read_after_dispose_is_an_error.json" to listOf("dispose"),
            // No fanout/churn harness ops, and no id-recycling introspection.
            "churn_returns_to_baseline.json" to listOf("fanout", "churn", "dispose_fanout"),
            "recycled_id_inherits_nothing.json" to
                listOf("fanout", "dispose_fanout", "dispose_stale_handle", "dispose"),
        )
    }

    /** The execution models lazily-kt ships. Every supported fixture runs on all of them. */
    private enum class Model { SYNC, THREAD_SAFE, ASYNC }

    /**
     * A replay engine over one execution model. Deliberately minimal: `cell`,
     * `computed`, `read`, `set_cell` — exactly the ops the transitive-depth
     * fixture needs, implemented natively per model so the async path exercises
     * real suspension rather than a synchronous shim.
     */
    private interface Engine {
        fun cell(id: String, value: Int)
        fun computed(id: String, reads: List<String>, offset: Int)
        fun read(id: String): Int
        fun setCell(id: String, value: Int)
        fun close() {}
    }

    private class SyncEngine : Engine {
        private val ctx = Context()
        private val cells = HashMap<String, CellHandle<Int>>()
        private val slots = HashMap<String, SlotHandle<Int>>()

        override fun cell(id: String, value: Int) { cells[id] = ctx.cell(value) }

        override fun computed(id: String, reads: List<String>, offset: Int) {
            slots[id] = ctx.computed {
                var sum = offset
                for (r in reads) sum += readAny(r)
                sum
            }
        }

        private fun Context.readAny(id: String): Int =
            cells[id]?.let { getCell(it) } ?: slots[id]?.let { get(it) }
                ?: error("unknown node '$id'")

        override fun read(id: String): Int =
            cells[id]?.let { ctx.getCell(it) } ?: slots[id]?.let { ctx.get(it) }
                ?: error("unknown node '$id'")

        override fun setCell(id: String, value: Int) {
            ctx.setCell(cells[id] ?: error("unknown cell '$id'"), value)
        }
    }

    private class ThreadSafeEngine : Engine {
        private val ctx = ThreadSafeContext()
        private val cells = HashMap<String, ThreadSafeCellHandle<Int>>()
        private val slots = HashMap<String, ThreadSafeSlotHandle<Int>>()

        override fun cell(id: String, value: Int) { cells[id] = ctx.cell(value) }

        override fun computed(id: String, reads: List<String>, offset: Int) {
            slots[id] = ctx.computed {
                var sum = offset
                for (r in reads) sum += readAny(r)
                sum
            }
        }

        private fun ThreadSafeContext.readAny(id: String): Int =
            cells[id]?.let { getCell(it) } ?: slots[id]?.let { get(it) }
                ?: error("unknown node '$id'")

        override fun read(id: String): Int =
            cells[id]?.let { ctx.getCell(it) } ?: slots[id]?.let { ctx.get(it) }
                ?: error("unknown node '$id'")

        override fun setCell(id: String, value: Int) {
            ctx.setCell(cells[id] ?: error("unknown cell '$id'"), value)
        }
    }

    /**
     * The model that matters most here. Reads go through [AsyncContext.getAsync]
     * so the replay drives real suspension and the revision/in-flight machinery
     * that broke the pull chain in lazily-dart.
     */
    private class AsyncEngine : Engine {
        private val ctx = AsyncContext()
        private val cells = HashMap<String, AsyncContext.AsyncCellHandle<Int>>()
        private val slots = HashMap<String, AsyncContext.AsyncSlotHandle<Int>>()

        override fun cell(id: String, value: Int) { cells[id] = ctx.cell(value) }

        override fun computed(id: String, reads: List<String>, offset: Int) {
            slots[id] = ctx.computedAsync {
                var sum = offset
                for (r in reads) {
                    sum += cells[r]?.let { getCell(it) }
                        ?: slots[r]?.let { getAsync(it) }
                        ?: error("unknown node '$r'")
                }
                sum
            }
        }

        override fun read(id: String): Int = runBlocking {
            cells[id]?.let { ctx.getCell(it) } ?: slots[id]?.let { ctx.getAsync(it) }
                ?: error("unknown node '$id'")
        }

        override fun setCell(id: String, value: Int) {
            ctx.setCell(cells[id] ?: error("unknown cell '$id'"), value)
        }

        override fun close() = runBlocking { ctx.dispose() }
    }

    private fun engineFor(model: Model): Engine = when (model) {
        Model.SYNC -> SyncEngine()
        Model.THREAD_SAFE -> ThreadSafeEngine()
        Model.ASYNC -> AsyncEngine()
    }

    /** Ops + assertions executed, for the positive assertion. */
    private class Tally {
        var ops = 0
        var assertions = 0
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
                "renamed, or removed upstream — update FIXTURES/SUPPORTED/SKIPPED so it cannot " +
                "go unrun (#lzspecconf).",
        )

        // (2) SUPPORTED and SKIPPED must partition FIXTURES: no overlap, no gap.
        val overlap = SUPPORTED.filter { it in SKIPPED.keys }
        assertTrue(overlap.isEmpty(), "fixtures both supported and skipped: $overlap")
        assertEquals(
            FIXTURES.sorted(),
            (SUPPORTED + SKIPPED.keys).sorted(),
            "every fixture must be either replayed or explicitly skipped with its unsupported " +
                "ops named — a fixture in neither list would silently vanish.",
        )

        // (3) Replay every supported fixture against every model.
        val models = Model.entries
        val replayed = LinkedHashSet<String>()
        val tally = Tally()
        val failures = mutableListOf<String>()

        for (name in SUPPORTED) {
            val text = ConformanceFixtures.read("$area/$name")
            val fx = json.parseToJsonElement(text).jsonObject
            for (model in models) {
                try {
                    replaySteps(model, fx, tally)
                    replayed.add("$model/$name")
                } catch (t: Throwable) {
                    // A fixture failure is a FINDING. Collect every model's
                    // result rather than aborting on the first, so the report
                    // says which contexts diverge — do not edit the fixture and
                    // do not loosen the assertion.
                    failures.add("$model/$name: ${t.message}")
                }
            }
        }

        // (4) Positive assertion — fail loudly at zero, and at any shortfall.
        val expected = SUPPORTED.size * models.size
        assertTrue(
            replayed.isNotEmpty(),
            "ZERO reactive-graph fixtures replayed. The runner executed nothing — this is the " +
                "exact silent-skip failure it exists to prevent (#lzspecconf).",
        )
        assertTrue(
            tally.ops > 0 && tally.assertions > 0,
            "reactive-graph replay executed ${tally.ops} ops and ${tally.assertions} assertions; " +
                "both must be non-zero or the fixtures did not really run.",
        )

        println(
            "reactive-graph conformance: replayed ${replayed.size}/$expected " +
                "(${SUPPORTED.size} fixture(s) x ${models.size} contexts: " +
                models.joinToString(", ") + "), ${tally.ops} ops, ${tally.assertions} assertions",
        )
        // Skipped fixtures are printed but deliberately NOT recorded into the
        // coverage manifest. The manifest means "this fixture actually
        // replayed"; recording a skip there would inflate the count and let
        // `check-conformance-coverage.sh` report coverage this binding does not
        // have — the precise "green while testing nothing" failure this runner
        // exists to close.
        for ((fixture, ops) in SKIPPED.toSortedMap()) {
            println("reactive-graph SKIP $fixture — unsupported ops: ${ops.joinToString(", ")}")
        }

        if (failures.isNotEmpty()) {
            fail(
                "reactive-graph conformance FAILURES (findings against lazily-kt, not the " +
                    "fixtures):\n" + failures.joinToString("\n"),
            )
        }
        assertEquals(
            expected,
            replayed.size,
            "not every supported fixture replayed against every context.",
        )
    }

    /** Replay a `shape: steps` fixture against one model. */
    private fun replaySteps(model: Model, fx: JsonObject, tally: Tally) {
        val shape = fx["shape"]?.jsonPrimitive?.content
        check(shape == "steps") { "unsupported fixture shape '$shape'" }
        val engine = engineFor(model)
        try {
            for ((i, element) in fx["steps"]!!.jsonArray.withIndex()) {
                val step = element.jsonObject
                val op = step["op"]!!.jsonObject
                val opResult = applyOp(engine, op)
                tally.ops++
                val expect = step["expect"]?.jsonObject ?: continue
                applyExpect(engine, expect, opResult, model, i, tally)
            }
        } finally {
            engine.close()
        }
    }

    /** Apply one op; returns the observed value for a `read`, else null. */
    private fun applyOp(engine: Engine, op: JsonObject): Int? {
        val type = op["type"]!!.jsonPrimitive.content
        val id = op["id"]?.jsonPrimitive?.content
        when (type) {
            "cell" -> engine.cell(id!!, op["value"]!!.jsonPrimitive.int)
            "computed" -> engine.computed(
                id!!,
                op["reads"]!!.jsonArray.map { it.jsonPrimitive.content },
                op["offset"]?.jsonPrimitive?.int ?: 0,
            )
            "read" -> return engine.read(id!!)
            "set_cell" -> engine.setCell(id!!, op["value"]!!.jsonPrimitive.int)
            // Never skip an unrecognised op silently — that is the defect class
            // this runner exists to close.
            else -> error("unsupported op '$type' — implement it or add the fixture to SKIPPED")
        }
        return null
    }

    private fun applyExpect(
        engine: Engine,
        expect: JsonObject,
        opResult: Int?,
        model: Model,
        step: Int,
        tally: Tally,
    ) {
        for ((key, value) in expect) {
            when (key) {
                "note" -> {} // documentation only
                "value" -> {
                    // Asserts on the value the preceding `read` op observed.
                    // Requires a value-producing op — a null here means the
                    // fixture paired `value` with a non-read op and the
                    // assertion would otherwise silently pass.
                    val actual = opResult
                        ?: error("step $step: `value` expect on an op that produced no value")
                    assertEquals(
                        (value as JsonPrimitive).int,
                        actual,
                        "$model step $step: value",
                    )
                    tally.assertions++
                }
                "read" -> {
                    for ((nodeId, expected) in value.jsonObject) {
                        val actual = engine.read(nodeId)
                        assertEquals(
                            (expected as JsonPrimitive).int,
                            actual,
                            "$model step $step: read('$nodeId')",
                        )
                        tally.assertions++
                    }
                }
                else -> error("unrecognised assertion key '$key' at step $step")
            }
        }
    }
}
