package io.github.lazily

import kotlinx.serialization.json.Json
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the reactive queue (`QueueCell`), the
 * layer required of every binding — see the Binding Conformance Matrix in
 * `lazily-spec/protocol.md` and `lazily-spec/cell-model.md` § "Reactive
 * queues".
 *
 * These are **compute** fixtures: lazily-kt loads the `initial` state, replays
 * each `step`'s `op`, and asserts the `expected` observable effects (resulting
 * `elements` / `head` / `len` / `is_empty` / `is_full` / `closed`, and — the
 * core of the spec — exactly which reader classes (`head` / `len` / `is_empty`
 * / `is_full` / `closed`) invalidate). The five fixtures cover SPSC total FIFO,
 * the popped-head observation, MPSC multi-writer inside `batch()`, bounded
 * reactive backpressure, and the closure lifecycle.
 */
class QueueCellConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("collections/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    /**
     * `capacity`: absent or JSON null means unbounded, any other value must
     * decode as an Int (`#lzflagcoercion`).
     */
    private fun capacityOf(initial: JsonObject): Int? =
        when (val raw = initial["capacity"]) {
            null, JsonNull -> null
            else -> raw.jsonPrimitive.int
        }

    private fun buildInitial(
        ctx: Context,
        initial: JsonObject,
    ): QueueCell<V, VecDequeStorage<V>> {
        // Absent or an EXPLICIT JSON null means unbounded — the corpus spells both.
        // Anything else has to decode as an Int: `intOrNull` used to fold a
        // malformed `capacity` into the same "unbounded" answer as a deliberate
        // null, which replays a different fixture than the one on disk
        // (`#lzflagcoercion`).
        val cap = capacityOf(initial)
        val q = if (cap != null) QueueCell.bounded<V>(ctx, cap) else QueueCell.unbounded<V>(ctx)
        initial["elements"]?.jsonArray?.forEach { e ->
            assertNullError(q.tryPush(e.jsonPrimitive.content), "initial push")
        }
        // `closed` in initial is rare but supported: honor it. A present value
        // has to BE a boolean — `booleanOrNull == true` read every other spelling
        // as "not closed" and replayed a different fixture (`#lzflagcoercion`).
        if (initial["closed"]?.jsonPrimitive?.boolean == true) q.close()
        return q
    }

    /**
     * The reader-kind slots whose invalidation we can observe via [Context.isSet],
     * keyed by the CORPUS's spelling of the kind.
     *
     * A map, not five named fields (`#lzsiblingrunnermasking`). Five fields force
     * the matrix check to be a check-list of the kinds this file happens to know,
     * and a check-list cannot notice a kind it does not list — see
     * [assertInvalidation].
     */
    private class Readers(
        val byKind: Map<String, Computed<Unit>>,
    )

    private fun makeReaders(
        ctx: Context,
        q: QueueCell<V, *>,
    ): Readers {
        // Each reader subscribes to exactly one reader-kind cell. We wrap the
        // reactive read in a `computed` returning `Unit` so `ctx.isSet` reports
        // whether the cached value survived the last op.
        return Readers(
            mapOf(
                "head" to
                    ctx.computed {
                        q.head(this)
                        Unit
                    },
                "len" to
                    ctx.computed {
                        q.len(this)
                        Unit
                    },
                "is_empty" to
                    ctx.computed {
                        q.isEmpty(this)
                        Unit
                    },
                "is_full" to
                    ctx.computed {
                        q.isFull(this)
                        Unit
                    },
                "closed" to
                    ctx.computed {
                        q.isClosed(this)
                        Unit
                    },
            ),
        )
    }

    /** Materialize every reader's cache so the next op's invalidation is observable via [Context.isSet] (a cached reader that stays cached was not invalidated). */
    private fun materializeAll(
        ctx: Context,
        readers: Readers,
    ) {
        readers.byKind.values.forEach { ctx.get(it) }
    }

    /**
     * Assert the per-reader-kind invalidation matrix for one step. Call this
     * immediately after the op (with readers still holding their pre-op cached
     * values), then it re-materializes for the next step.
     *
     * A reader kind explicitly present in [invalidates] is asserted
     * (`true` ⇒ must invalidate, `false` ⇒ must stay cached). A reader kind
     * **absent** from [invalidates] is not asserted — fixtures that focus on one
     * reader kind (e.g. `popped_head_observation`) only declare the kind under
     * test, so absence means "don't check", not "must be false". That is a
     * property of the corpus, and QueueFamilyConformanceTest reads it the same way.
     *
     * Driven by the fixture's OWN keys, never by a check-list of the five kinds
     * this file knows (`#lzsiblingrunnermasking`). The check-list form ran
     * `invalidates[name] ?: return` once per known name, so a kind the corpus
     * ADDS or RENAMES — `is_closed` for `closed`, a sixth reader — was asserted
     * by nothing at all: the matrix silently shrank and the row read exactly like
     * a key the fixture never carried. QueueFamilyConformanceTest already
     * iterated the fixture's keys and resolved each through
     * `readers.getValue(kind)`, which THROWS on a kind it cannot resolve, so over
     * these same five fixtures the family runner was the only thing that would
     * have caught it. Coverage that depends on which sibling runner happens to be
     * strict is an accident of which runners exist, not a property of this
     * assertion — delete or rename that runner and the hole opens.
     */
    private fun assertInvalidation(
        ctx: Context,
        readers: Readers,
        invalidates: JsonObject,
    ) {
        for ((kind, rawWant) in invalidates) {
            val reader =
                readers.byKind[kind]
                    ?: error(
                        "expected.invalidates names reader kind '$kind', which this runner " +
                            "cannot resolve (known: ${readers.byKind.keys.sorted()}). A kind " +
                            "nobody resolves is a row asserted by nothing — wire the reader, " +
                            "never skip the row (#lzsiblingrunnermasking)",
                    )
            val expectedInv = rawWant.jsonPrimitive.boolean
            val cached = ctx.isSet(reader)
            if (expectedInv) {
                assertFalse(cached, "reader `$kind` should have been invalidated but stayed cached")
            } else {
                assertTrue(cached, "reader `$kind` should have stayed cached but was invalidated")
            }
        }
        // Re-materialize all readers so the next step starts from a known-cached
        // state regardless of which were invalidated.
        materializeAll(ctx, readers)
    }

    /** Assert the observable queue state after a step. */
    private fun assertState(
        q: QueueCell<V, VecDequeStorage<V>>,
        expected: JsonObject,
    ) {
        expected["elements"]?.jsonArray?.let { want ->
            assertEquals(
                want.map { it.jsonPrimitive.content },
                q.elements(),
                "elements mismatch",
            )
        }
        expected["head"]?.let { headEl ->
            val want: V? = if (headEl is JsonNull) null else headEl.jsonPrimitive.content
            assertEquals(want, q.head(), "head mismatch")
        }
        // Presence FIRST, then the type — never `booleanOrNull?.let` /
        // `intOrNull?.let` (`#lzflagcoercion`). Those decode a wrong-typed
        // expectation to null and then skip the assertion on it, so `is_empty: 0`
        // or `len: 1.5` was indistinguishable from a key the fixture never
        // carried: the arm ran zero comparisons and the suite stayed green.
        // `.int` / `.boolean` throw on the same input, which is the whole point,
        // and it is what QueueFamilyConformanceTest already does for these keys.
        expected["len"]?.let {
            assertEquals(it.jsonPrimitive.int, q.len(), "len mismatch")
        }
        expected["is_empty"]?.let {
            assertEquals(it.jsonPrimitive.boolean, q.isEmpty(), "is_empty mismatch")
        }
        expected["is_full"]?.let {
            assertEquals(it.jsonPrimitive.boolean, q.isFull(), "is_full mismatch")
        }
        expected["closed"]?.let {
            assertEquals(it.jsonPrimitive.boolean, q.isClosed(), "closed mismatch")
        }
    }

    /**
     * Run a single fixture file: replay every step and assert state + invalidation.
     *
     * `expected` and `expected.invalidates` are REQUIRED, never defaulted to an
     * empty block (`#lzsiblingrunnermasking`). `?: JsonObject(emptyMap())` made a
     * step that lost its expectations upstream replay its op and then assert
     * NOTHING — an empty matrix iterates zero rows, an empty `expected` compares
     * zero observables, and the step still counted as run.
     * QueueFamilyConformanceTest reads both through `getValue` over these same
     * five fixtures, which is the only reason a dropped block would have reddened
     * anything; that is the sibling mask this runner no longer relies on.
     *
     * The step-count floor is the second half: `forEachIndexed` over an empty
     * `steps` array is a passing test that replayed nothing, and a floor inside
     * the loop cannot see it. The counter is compared to the length the fixture
     * declared, so there is no number to re-pin.
     */
    private fun runFixture(fixture: JsonObject) {
        val ctx = Context()
        val q = buildInitial(ctx, fixture.getValue("initial").jsonObject)
        val readers = makeReaders(ctx, q)
        materializeAll(ctx, readers)

        val steps = fixture.getValue("steps").jsonArray
        assertTrue(steps.isNotEmpty(), "fixture declares no steps — a zero-step replay is not a pass")
        var executed = 0

        for ((i, stepEl) in steps.withIndex()) {
            val step = stepEl.jsonObject
            assertFalse(
                step.containsKey("invalidates"),
                "step $i spells `invalidates` on the STEP — the matrix lives under " +
                    "`expected.invalidates`, and lazily-rs read it off the step, so its " +
                    "assertion never ran once (#lzflagcoercion)",
            )
            val op = step.getValue("op").jsonObject
            val opType = op.getValue("type").jsonPrimitive.content
            val expected = step.getValue("expected").jsonObject
            val invalidates = expected.getValue("invalidates").jsonObject

            val gotReturns: kotlinx.serialization.json.JsonElement =
                when (opType) {
                    "push" -> {
                        val v = op.getValue("value").jsonPrimitive.content
                        val r = q.tryPush(v)
                        assertNullError(r, "step $i: push should succeed")
                        JsonNull
                    }
                    "try_push" -> {
                        val v = op.getValue("value").jsonPrimitive.content
                        when (val r = q.tryPush(v)) {
                            null -> JsonNull
                            QueuePushError.Full -> JsonPrimitive("Full")
                            QueuePushError.Closed -> JsonPrimitive("Closed")
                        }
                    }
                    "pop", "try_pop" ->
                        when (val r = q.tryPop()) {
                            is QueuePop.Value -> JsonPrimitive(r.value)
                            is QueuePop.Failed ->
                                when (r.error) {
                                    QueuePopError.Empty -> JsonPrimitive("Empty")
                                    QueuePopError.Closed -> JsonPrimitive("Closed")
                                }
                        }
                    "close" -> {
                        q.close()
                        JsonNull
                    }
                    "batch" -> {
                        ctx.batch {
                            for (inner in op.getValue("ops").jsonArray) {
                                val io = inner.jsonObject
                                assertEquals(
                                    "push",
                                    io.getValue("type").jsonPrimitive.content,
                                    "batch currently only wraps pushes",
                                )
                                assertNullError(q.tryPush(io.getValue("value").jsonPrimitive.content), "batch push")
                            }
                        }
                        JsonNull
                    }
                    else -> error("unknown queue op type: $opType")
                }

            // Assert the observable state.
            assertState(q, expected)

            // Assert the `returns` value (element or error label).
            step["returns"]?.let { want ->
                assertEquals(want, gotReturns, "step $i: returns mismatch")
            }

            // Assert the per-reader-kind invalidation matrix.
            assertInvalidation(ctx, readers, invalidates)
            executed++
        }

        check(executed == steps.size) {
            "loaded ${steps.size} steps but executed $executed"
        }
    }

    @Test fun `conformance spsc push pop`() = runFixture(loadFixture("queuecell_spsc_push_pop.json"))

    @Test fun `conformance popped head observation`() = runFixture(loadFixture("queuecell_popped_head_observation.json"))

    @Test fun `conformance mpsc multi writer`() = runFixture(loadFixture("queuecell_mpsc_multi_writer.json"))

    @Test fun `conformance bounded backpressure`() = runFixture(loadFixture("queuecell_bounded_backpressure.json"))

    @Test fun `conformance closure lifecycle`() = runFixture(loadFixture("queuecell_closure_lifecycle.json"))

    // -----------------------------------------------------------------------
    // Direct (non-fixture) tests of the backpressure effect wiring — the
    // spec's signature property: a consumer's pop that transitions full →
    // not-full wakes a producer-side effect that was backed off on is_full.
    // -----------------------------------------------------------------------

    @Test fun `backpressure pop wakes push side effect`() {
        val ctx = Context()
        val q = QueueCell.bounded<Int>(ctx, 1)

        // A push-side effect that observes is_full and records each (is_full, len)
        // sample. When full it "backs off" (records Full); when not full it resumes
        // (records Ready).
        val log = mutableListOf<Pair<Boolean, Int>>()
        ctx.effect {
            val full = q.isFull(this)
            val len = q.len(this)
            log.add(full to len)
            null
        }
        // After effect setup, the initial sample is (false, 0).
        assertEquals(listOf(false to 0), log)

        // Fill the queue → is_full flips → effect reruns and records (true, 1).
        assertNullError(q.tryPush(1), "push 1")
        assertEquals(listOf(false to 0, true to 1), log)

        // A consumer pop transitions full → not-full. The effect's is_full
        // subscription is invalidated (true → false) and the effect reruns without
        // polling — the reactive backpressure signal.
        val popped = q.tryPop()
        assertTrue(popped is QueuePop.Value, "pop should return a value")
        assertEquals(1, (popped as QueuePop.Value).value)
        assertEquals(listOf(false to 0, true to 1, false to 0), log)
    }

    // -----------------------------------------------------------------------
    // Pluggable storage adapter seam
    // -----------------------------------------------------------------------

    /** A minimal custom backend proving the [QueueStorage] adapter seam works. */
    private class BoundedRing<T : Any>(
        private val cap: Int,
    ) : QueueStorage<T> {
        private val buf = ArrayDeque<T>()
        private var closed = false

        override fun tryPush(value: T): QueuePushError? {
            if (closed) return QueuePushError.Closed
            if (buf.size >= cap) return QueuePushError.Full
            buf.addLast(value)
            return null
        }

        override fun tryPop(): QueuePop<T> =
            if (buf.isNotEmpty()) {
                QueuePop.Value(buf.removeFirst())
            } else if (closed) {
                QueuePop.Failed(QueuePopError.Closed)
            } else {
                QueuePop.Failed(QueuePopError.Empty)
            }

        override fun peek(): T? = buf.firstOrNull()

        override fun len(): Int = buf.size

        override fun capacity(): Int? = cap

        override fun isClosed(): Boolean = closed

        override fun close() {
            closed = true
        }
    }

    @Test fun `pluggable storage via interface`() {
        val ctx = Context()
        val q = QueueCell<Int, BoundedRing<Int>>(ctx, BoundedRing(2))

        assertNullError(q.tryPush(1), "push 1")
        assertNullError(q.tryPush(2), "push 2")
        assertTrue(q.isFull(), "queue at capacity")
        assertEquals(QueuePushError.Full, q.tryPush(3), "push at capacity rejected")

        val popped = q.tryPop()
        assertTrue(popped is QueuePop.Value, "pop returns value")
        assertEquals(1, (popped as QueuePop.Value).value)
        assertFalse(q.isFull(), "pop freed a slot")
        assertEquals(1, q.len(), "len after pop")
        assertEquals(2, q.head(), "head after pop")
    }

    /**
     * A raw-channel-style backend implementing ONLY the required contract —
     * tryPush / tryPop / len / isClosed / close — using the default (absent)
     * peek/capacity. Proves the minimal contract (Phase 0 #relaycell): fully
     * conforming, with no head reader and never full.
     */
    private class MinimalFifo<T : Any> : QueueStorage<T> {
        private val buf = ArrayDeque<T>()
        private var closed = false

        override fun tryPush(value: T): QueuePushError? {
            if (closed) return QueuePushError.Closed
            buf.addLast(value)
            return null
        }

        override fun tryPop(): QueuePop<T> =
            if (buf.isNotEmpty()) {
                QueuePop.Value(buf.removeFirst())
            } else if (closed) {
                QueuePop.Failed(QueuePopError.Closed)
            } else {
                QueuePop.Failed(QueuePopError.Empty)
            }

        override fun len(): Int = buf.size

        override fun isClosed(): Boolean = closed

        override fun close() {
            closed = true
        }
        // NB: no peek(), no capacity() — the interface defaults apply.
    }

    @Test fun `raw channel backend conforms to minimal contract`() {
        val ctx = Context()
        val q = QueueCell<Int, MinimalFifo<Int>>(ctx, MinimalFifo())

        assertTrue(q.isEmpty(), "new minimal queue is empty")
        assertNullError(q.tryPush(1), "push 1")
        assertNullError(q.tryPush(2), "push 2")
        assertEquals(2, q.len(), "len after pushes")

        // No peek → no head reader (null); no capacity → never full.
        assertEquals(null, q.head(), "no peek capability → head is null")
        assertFalse(q.isFull(), "unbounded → never full")
        assertEquals(null, q.capacity(), "no capacity capability → unbounded")

        assertEquals(1, (q.tryPop() as QueuePop.Value).value, "FIFO pop 1")
        assertEquals(2, (q.tryPop() as QueuePop.Value).value, "FIFO pop 2")
        assertTrue(q.isEmpty(), "empty after drain")

        q.close()
        assertTrue(q.isClosed(), "closed after close()")
        assertEquals(QueuePushError.Closed, q.tryPush(3), "push after close rejected")
        assertTrue(q.tryPop() is QueuePop.Failed, "pop on closed empty fails")
    }

    @Test fun `raw channel reader kinds stay reactive`() {
        val ctx = Context()
        val q = QueueCell<Int, MinimalFifo<Int>>(ctx, MinimalFifo())
        val log = mutableListOf<Int>()
        ctx.effect {
            log.add(q.len(this))
            null
        }

        assertEquals(listOf(0), log, "initial len sample")
        q.tryPush(10)
        assertEquals(listOf(0, 1), log, "push invalidates len reader")
        q.tryPop()
        assertEquals(listOf(0, 1, 0), log, "pop invalidates len reader")
    }

    @Test fun `vecdeque storage snapshot is fifo order`() {
        val storage = VecDequeStorage<Int>(4)
        assertNullError(storage.tryPush(1), "push 1")
        assertNullError(storage.tryPush(2), "push 2")
        assertNullError(storage.tryPush(3), "push 3")
        assertEquals(listOf(1, 2, 3), storage.elements(), "elements in FIFO order")
    }

    private companion object {
        /** Assert a push returned success (`null` error), with a message. */
        fun assertNullError(
            error: QueuePushError?,
            message: String,
        ) {
            assertTrue(error == null, "$message: expected success, got $error")
        }
    }
}

/** Local typealias kept private to the file (mirrors the Rust `type V = String`). */
private typealias V = String
