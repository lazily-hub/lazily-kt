package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
        val specPath = Path.of("../lazily-spec/conformance/collections/$name")
        val text = if (Files.exists(specPath)) {
            Files.readString(specPath)
        } else {
            val resource = javaClass.getResource("/conformance/collections/$name")
                ?: error("missing conformance fixture: $name")
            resource.readText()
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun buildInitial(ctx: Context, initial: JsonObject): QueueCell<V, VecDequeStorage<V>> {
        val cap = initial["capacity"]?.jsonPrimitive?.intOrNull
        val q = if (cap != null) QueueCell.bounded<V>(ctx, cap) else QueueCell.unbounded<V>(ctx)
        initial["elements"]?.jsonArray?.forEach { e ->
            assertNullError(q.tryPush(e.jsonPrimitive.content), "initial push")
        }
        // `closed` in initial is rare but supported: honor it.
        if (initial["closed"]?.jsonPrimitive?.booleanOrNull == true) q.close()
        return q
    }

    /** A reader-kind slot whose invalidation we can observe via [Context.isSet]. */
    private class Readers(
        val head: SlotHandle<Unit>,
        val len: SlotHandle<Unit>,
        val isEmpty: SlotHandle<Unit>,
        val isFull: SlotHandle<Unit>,
        val closed: SlotHandle<Unit>,
    )

    private fun makeReaders(ctx: Context, q: QueueCell<V, *>): Readers {
        // Each reader subscribes to exactly one reader-kind cell. We wrap the
        // reactive read in a `computed` returning `Unit` so `ctx.isSet` reports
        // whether the cached value survived the last op.
        val head = ctx.computed { q.head(); Unit }
        val len = ctx.computed { q.len(); Unit }
        val isEmpty = ctx.computed { q.isEmpty(); Unit }
        val isFull = ctx.computed { q.isFull(); Unit }
        val closed = ctx.computed { q.isClosed(); Unit }
        return Readers(head, len, isEmpty, isFull, closed)
    }

    /** Materialize every reader's cache so the next op's invalidation is observable via [Context.isSet] (a cached reader that stays cached was not invalidated). */
    private fun materializeAll(ctx: Context, readers: Readers) {
        ctx.get(readers.head)
        ctx.get(readers.len)
        ctx.get(readers.isEmpty)
        ctx.get(readers.isFull)
        ctx.get(readers.closed)
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
     * test, so absence means "don't check", not "must be false".
     */
    private fun assertInvalidation(ctx: Context, readers: Readers, invalidates: JsonObject) {
        fun check(name: String, reader: SlotHandle<Unit>) {
            val node = invalidates[name] ?: return
            val expectedInv = node.jsonPrimitive.boolean
            val cached = ctx.isSet(reader)
            if (expectedInv) {
                assertFalse(cached, "reader `$name` should have been invalidated but stayed cached")
            } else {
                assertTrue(cached, "reader `$name` should have stayed cached but was invalidated")
            }
        }
        check("head", readers.head)
        check("len", readers.len)
        check("is_empty", readers.isEmpty)
        check("is_full", readers.isFull)
        check("closed", readers.closed)
        // Re-materialize all readers so the next step starts from a known-cached
        // state regardless of which were invalidated.
        materializeAll(ctx, readers)
    }

    /** Assert the observable queue state after a step. */
    private fun assertState(q: QueueCell<V, VecDequeStorage<V>>, expected: JsonObject) {
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
        expected["len"]?.jsonPrimitive?.intOrNull?.let {
            assertEquals(it, q.len(), "len mismatch")
        }
        expected["is_empty"]?.jsonPrimitive?.booleanOrNull?.let {
            assertEquals(it, q.isEmpty(), "is_empty mismatch")
        }
        expected["is_full"]?.jsonPrimitive?.booleanOrNull?.let {
            assertEquals(it, q.isFull(), "is_full mismatch")
        }
        expected["closed"]?.jsonPrimitive?.booleanOrNull?.let {
            assertEquals(it, q.isClosed(), "closed mismatch")
        }
    }

    /** Run a single fixture file: replay every step and assert state + invalidation. */
    private fun runFixture(fixture: JsonObject) {
        val ctx = Context()
        val q = buildInitial(ctx, fixture.getValue("initial").jsonObject)
        val readers = makeReaders(ctx, q)
        materializeAll(ctx, readers)

        for ((i, stepEl) in fixture.getValue("steps").jsonArray.withIndex()) {
            val step = stepEl.jsonObject
            val op = step.getValue("op").jsonObject
            val opType = op.getValue("type").jsonPrimitive.content
            val expected = step["expected"]?.jsonObject ?: JsonObject(emptyMap())
            val invalidates = expected["invalidates"]?.jsonObject ?: JsonObject(emptyMap())

            val gotReturns: kotlinx.serialization.json.JsonElement = when (opType) {
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
                "pop", "try_pop" -> when (val r = q.tryPop()) {
                    is QueuePop.Value -> JsonPrimitive(r.value)
                    is QueuePop.Failed -> when (r.error) {
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
            val full = q.isFull()
            val len = q.len()
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
    private class BoundedRing<T : Any>(private val cap: Int) : QueueStorage<T> {
        private val buf = ArrayDeque<T>()
        private var closed = false

        override fun tryPush(value: T): QueuePushError? {
            if (closed) return QueuePushError.Closed
            if (buf.size >= cap) return QueuePushError.Full
            buf.addLast(value)
            return null
        }

        override fun tryPop(): QueuePop<T> =
            if (buf.isNotEmpty()) QueuePop.Value(buf.removeFirst())
            else if (closed) QueuePop.Failed(QueuePopError.Closed)
            else QueuePop.Failed(QueuePopError.Empty)

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
            if (buf.isNotEmpty()) QueuePop.Value(buf.removeFirst())
            else if (closed) QueuePop.Failed(QueuePopError.Closed)
            else QueuePop.Failed(QueuePopError.Empty)

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
            log.add(q.len())
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
        fun assertNullError(error: QueuePushError?, message: String) {
            assertTrue(error == null, "$message: expected success, got $error")
        }
    }
}

/** Local typealias kept private to the file (mirrors the Rust `type V = String`). */
private typealias V = String
