package io.github.lazily

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
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canonical queue-family corpus, replayed against every execution flavor
 * lazily-kt ships. The capability ledger is exact: adding a flavor without a
 * runner or leaving a runner behind after staging a flavor both fail.
 */
/**
 * `initial.capacity`: absent or an EXPLICIT JSON null means unbounded — the corpus
 * spells both — and any other value MUST decode as an Int. `intOrNull` folded a
 * malformed capacity into the same "unbounded" answer as a deliberate null, which
 * silently replays a different fixture than the one on disk (`#lzflagcoercion`).
 */
private fun capacityOf(initial: JsonObject): Int? =
    when (val raw = initial["capacity"]) {
        null, JsonNull -> null
        else -> raw.jsonPrimitive.int
    }

class QueueFamilyConformanceTest {
    private enum class Flavor { Sync, ThreadSafe, Async }

    private enum class Primitive { Queue, Topic, WorkQueue }

    private data class Capability(
        val primitive: Primitive,
        val flavor: Flavor,
    )

    private val queueFixtures =
        listOf(
            "queuecell_spsc_push_pop.json",
            "queuecell_popped_head_observation.json",
            "queuecell_mpsc_multi_writer.json",
            "queuecell_bounded_backpressure.json",
            "queuecell_closure_lifecycle.json",
        )
    private val topicFixtures =
        listOf(
            "topiccell_broadcast_cursor_isolation.json",
            "topiccell_durable_replay_gc.json",
            "topiccell_ephemeral_lifecycle.json",
            "topiccell_offline_tail_bounds.json",
        )
    private val workFixtures =
        listOf(
            "workqueue_competing_delivery.json",
            "workqueue_lease_deadletter.json",
        )

    private val shipped =
        Primitive.entries
            .flatMap { primitive ->
                Flavor.entries.map { flavor -> Capability(primitive, flavor) }
            }.toSet()
    private val skipped = emptyMap<Capability, String>()

    private var fixtureOverride: ((String) -> JsonObject)? = null
    private var expectationTransform: (String, JsonObject) -> JsonObject = { _, expected -> expected }
    private var bindCanonicalBlocks = true

    private fun fixture(name: String): JsonObject =
        fixtureOverride?.invoke(name)
            ?: Json.parseToJsonElement(ConformanceFixtures.read("collections/$name")).jsonObject

    private fun assertionKeys(
        name: String,
        stepIndex: Int,
        expected: JsonObject,
        flavor: Flavor,
        visitedSites: MutableSet<String>?,
        bindSite: Boolean = true,
    ): AssertionKeys {
        val fixturePath = "collections/$name"
        val siteId = "$fixturePath|steps[$stepIndex].expected"
        if (bindSite && visitedSites != null) {
            check(visitedSites.add(siteId)) { "$siteId: assertion block visited more than once" }
        }
        return AssertionKeys(
            siteId,
            expectationTransform(siteId, expected),
            fixturePath,
            rungZeroBind = bindCanonicalBlocks && bindSite && flavor == Flavor.Sync,
        )
    }

    private fun assertExactSites(
        names: List<String>,
        expectedCount: Int,
        visitedSites: Set<String>,
    ) {
        val declared =
            names
                .flatMap { name ->
                    val path = "collections/$name"
                    ConformanceFixtures.blockSitesOf(path, fixture(name)).keys
                }.toSet()
        assertEquals(expectedCount, declared.size, "collections assertion-block site pin")
        assertEquals(declared, visitedSites, "collections exact assertion-block sites")
    }

    /**
     * Steps the corpus DECLARES, read back from the fixtures rather than written down
     * here (`#lzcorpusfloorguard`). Paired with the per-replay executed-vs-loaded check
     * this pins that every row a fixture carries actually ran, with no number to rot;
     * a shrinking corpus is caught in lazily-spec by `corpus-counts.json` /
     * `scripts/check-corpus-floors.mjs`, not by a floor in this file.
     */
    private fun declaredSteps(names: List<String>): Int = names.sumOf { fixture(it).getValue("steps").jsonArray.size }

    @Test
    fun `capability and skip ledger is exact and non-vacuous`() {
        val declared = shipped + skipped.keys
        val expected =
            Primitive.entries
                .flatMap { primitive ->
                    Flavor.entries.map { flavor -> Capability(primitive, flavor) }
                }.toSet()
        assertEquals(expected, declared, "every primitive × flavor pair must be shipped or skipped")
        assertTrue(shipped.isNotEmpty(), "an all-skip ledger is not conformance")
        assertTrue(skipped.isEmpty(), "lazily-kt ships all three flavors; no staged skip is expected")
        assertEquals(11, queueFixtures.size + topicFixtures.size + workFixtures.size)
    }

    // -- QueueCell ---------------------------------------------------------

    private interface QueueHarness : AutoCloseable {
        val flavor: Flavor

        fun tryPush(value: String): QueuePushError?

        fun tryPop(): QueuePop<String>

        fun closeQueue()

        fun batchPush(values: List<String>)

        fun elements(): List<String>

        fun head(): String?

        fun len(): Int

        fun isEmpty(): Boolean

        fun isFull(): Boolean

        fun isClosed(): Boolean

        fun primeReaders()

        fun readerIsSet(kind: String): Boolean
    }

    private class SyncQueueHarness(
        initial: JsonObject,
    ) : QueueHarness {
        override val flavor = Flavor.Sync
        private val ctx = Context()
        private val queue: QueueCell<String, VecDequeStorage<String>> =
            capacityOf(initial)?.let {
                QueueCell.bounded<String>(ctx, it)
            } ?: QueueCell.unbounded(ctx)
        private val readers =
            mapOf(
                "head" to
                    ctx.computed {
                        queue.head(this)
                        Unit
                    },
                "len" to
                    ctx.computed {
                        queue.len(this)
                        Unit
                    },
                "is_empty" to
                    ctx.computed {
                        queue.isEmpty(this)
                        Unit
                    },
                "is_full" to
                    ctx.computed {
                        queue.isFull(this)
                        Unit
                    },
                "closed" to
                    ctx.computed {
                        queue.isClosed(this)
                        Unit
                    },
            )

        init {
            initial["elements"]?.jsonArray?.forEach {
                assertNull(queue.tryPush(it.jsonPrimitive.content))
            }
            if (initial["closed"]?.jsonPrimitive?.boolean == true) queue.close()
        }

        override fun tryPush(value: String) = queue.tryPush(value)

        override fun tryPop() = queue.tryPop()

        override fun closeQueue() = queue.close()

        override fun batchPush(values: List<String>) = ctx.batch { values.forEach { assertNull(queue.tryPush(it)) } }

        override fun elements() = queue.elements()

        override fun head() = queue.head()

        override fun len() = queue.len()

        override fun isEmpty() = queue.isEmpty()

        override fun isFull() = queue.isFull()

        override fun isClosed() = queue.isClosed()

        override fun primeReaders() = readers.values.forEach { ctx.get(it) }

        override fun readerIsSet(kind: String) = ctx.isSet(readers.getValue(kind))

        override fun close() = Unit
    }

    private class ThreadSafeQueueHarness(
        initial: JsonObject,
    ) : QueueHarness {
        override val flavor = Flavor.ThreadSafe
        private val ctx = ThreadSafeContext()
        private val queue: ThreadSafeQueueCell<String, VecDequeStorage<String>> =
            capacityOf(initial)?.let {
                ThreadSafeQueueCell.bounded<String>(ctx, it)
            } ?: ThreadSafeQueueCell.unbounded(ctx)
        private val readers =
            mapOf(
                "head" to
                    ctx.computed {
                        queue.head()
                        Unit
                    },
                "len" to
                    ctx.computed {
                        queue.len()
                        Unit
                    },
                "is_empty" to
                    ctx.computed {
                        queue.isEmpty()
                        Unit
                    },
                "is_full" to
                    ctx.computed {
                        queue.isFull()
                        Unit
                    },
                "closed" to
                    ctx.computed {
                        queue.isClosed()
                        Unit
                    },
            )

        init {
            initial["elements"]?.jsonArray?.forEach {
                assertNull(queue.tryPush(it.jsonPrimitive.content))
            }
            if (initial["closed"]?.jsonPrimitive?.boolean == true) queue.close()
        }

        override fun tryPush(value: String) = queue.tryPush(value)

        override fun tryPop() = queue.tryPop()

        override fun closeQueue() = queue.close()

        override fun batchPush(values: List<String>) = ctx.batch { values.forEach { assertNull(queue.tryPush(it)) } }

        override fun elements() = queue.elements()

        override fun head() = queue.head()

        override fun len() = queue.len()

        override fun isEmpty() = queue.isEmpty()

        override fun isFull() = queue.isFull()

        override fun isClosed() = queue.isClosed()

        override fun primeReaders() = readers.values.forEach { ctx.get(it) }

        override fun readerIsSet(kind: String) = ctx.isSet(readers.getValue(kind))

        override fun close() = Unit
    }

    private class AsyncQueueHarness(
        initial: JsonObject,
    ) : QueueHarness {
        override val flavor = Flavor.Async
        private val ctx = AsyncContext()
        private val queue: AsyncQueueCell<String, VecDequeStorage<String>> =
            capacityOf(initial)?.let {
                AsyncQueueCell.bounded<String>(ctx, it)
            } ?: AsyncQueueCell.unbounded(ctx)
        private val readers =
            mapOf(
                "head" to
                    ctx.computed {
                        queue.head(this)
                        Unit
                    },
                "len" to
                    ctx.computed {
                        queue.len(this)
                        Unit
                    },
                "is_empty" to
                    ctx.computed {
                        queue.isEmpty(this)
                        Unit
                    },
                "is_full" to
                    ctx.computed {
                        queue.isFull(this)
                        Unit
                    },
                "closed" to
                    ctx.computed {
                        queue.isClosed(this)
                        Unit
                    },
            )

        init {
            initial["elements"]?.jsonArray?.forEach {
                assertNull(queue.tryPush(it.jsonPrimitive.content))
            }
            if (initial["closed"]?.jsonPrimitive?.boolean == true) queue.close()
        }

        override fun tryPush(value: String) = queue.tryPush(value)

        override fun tryPop() = queue.tryPop()

        override fun closeQueue() = queue.close()

        override fun batchPush(values: List<String>) = ctx.batch { values.forEach { assertNull(queue.tryPush(it)) } }

        override fun elements() = queue.elements()

        override fun head() = queue.head()

        override fun len() = queue.len()

        override fun isEmpty() = queue.isEmpty()

        override fun isFull() = queue.isFull()

        override fun isClosed() = queue.isClosed()

        override fun primeReaders() = readers.values.forEach { requireNotNull(ctx.get(it)) }

        override fun readerIsSet(kind: String) = ctx.isSet(readers.getValue(kind))

        override fun close() = ctx.close()
    }

    private fun queueHarness(
        flavor: Flavor,
        initial: JsonObject,
    ): QueueHarness =
        when (flavor) {
            Flavor.Sync -> SyncQueueHarness(initial)
            Flavor.ThreadSafe -> ThreadSafeQueueHarness(initial)
            Flavor.Async -> AsyncQueueHarness(initial)
        }

    private fun replayQueue(
        name: String,
        flavor: Flavor,
        visitedSites: MutableSet<String>?,
    ): Int {
        val fixture = fixture(name)
        queueHarness(flavor, fixture.getValue("initial").jsonObject).use { queue ->
            queue.primeReaders()
            val steps = fixture.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), "$flavor $name has no steps")
            var executed = 0
            steps.forEachIndexed { index, raw ->
                val step = raw.jsonObject
                assertFalse(step.containsKey("invalidates"), "$name step $index uses step.invalidates")
                val op = step.getValue("op").jsonObject
                val returned: JsonElement =
                    when (op.getValue("type").jsonPrimitive.content) {
                        "push" -> {
                            assertNull(queue.tryPush(op.getValue("value").jsonPrimitive.content))
                            JsonNull
                        }
                        "try_push" ->
                            when (queue.tryPush(op.getValue("value").jsonPrimitive.content)) {
                                null -> JsonNull
                                QueuePushError.Full -> JsonPrimitive("Full")
                                QueuePushError.Closed -> JsonPrimitive("Closed")
                            }
                        "pop", "try_pop" ->
                            when (val result = queue.tryPop()) {
                                is QueuePop.Value -> JsonPrimitive(result.value)
                                is QueuePop.Failed ->
                                    JsonPrimitive(
                                        when (result.error) {
                                            QueuePopError.Empty -> "Empty"
                                            QueuePopError.Closed -> "Closed"
                                        },
                                    )
                            }
                        "close" -> {
                            queue.closeQueue()
                            JsonNull
                        }
                        "batch" -> {
                            queue.batchPush(
                                op.getValue("ops").jsonArray.map {
                                    it.jsonObject
                                        .getValue("value")
                                        .jsonPrimitive.content
                                },
                            )
                            JsonNull
                        }
                        else -> error("$flavor $name step $index: unknown queue op")
                    }
                step["returns"]?.let {
                    assertEquals(it, returned, "$flavor $name step $index returns")
                }
                val expected = step.getValue("expected").jsonObject
                val keys = assertionKeys(name, index, expected, flavor, visitedSites, bindSite = false)
                keys.sub("invalidates") { invalidates ->
                    for (kind in invalidates.keys.sorted()) {
                        invalidates.assertBoolean(kind) { !queue.readerIsSet(kind) }
                    }
                }
                keys.assertStrings("elements") { queue.elements() }
                keys.assertKeyWith("head") { want ->
                    assertEquals(if (want is JsonNull) null else want.jsonPrimitive.content, queue.head())
                }
                // `intOrNull?.let` decoded a wrong-typed `len` to null and then skipped
                // the comparison on it, so `len: 1.5` asserted nothing and read exactly
                // like an absent key. `.int` throws instead (`#lzflagcoercion`) — the
                // three boolean keys below already did.
                keys.assertInt("len") { queue.len() }
                keys.assertBoolean("is_empty") { queue.isEmpty() }
                keys.assertBoolean("is_full") { queue.isFull() }
                keys.assertBoolean("closed") { queue.isClosed() }
                keys.requireAllSatisfied()
                queue.primeReaders()
                executed++
            }
            check(executed == steps.size) { "$flavor $name: loaded ${steps.size} steps but executed $executed" }
            return executed
        }
    }

    @Test
    fun `all five QueueCell fixtures replay per flavor`() {
        for (flavor in Flavor.entries) {
            val steps = queueFixtures.sumOf { replayQueue(it, flavor, null) }
            assertTrue(steps > 0, "$flavor replayed zero QueueCell steps")
            assertEquals(declaredSteps(queueFixtures), steps, "every declared QueueCell step must run against $flavor")
        }
    }

    // -- TopicCell ---------------------------------------------------------

    private interface TopicHandle

    private interface TopicHarness : AutoCloseable {
        val flavor: Flavor

        fun subscribe(
            id: String,
            durability: TopicDurability,
        ): TopicSubscribeOutcome

        fun reconnect(id: String): TopicSubscribeOutcome

        fun restart()

        fun disconnect(id: String): Boolean

        fun publish(value: String): Long

        fun advance(id: String): String?

        fun gc(): Int

        fun baseOffset(): Long

        fun elements(): List<String>

        fun subscription(id: String): TopicSubscriptionSnapshot?

        fun subscriptionIds(): Set<String>

        fun readStream(id: String): List<String>

        fun handle(id: String): TopicHandle?

        fun prime(handle: TopicHandle)

        fun isSet(handle: TopicHandle): Boolean
    }

    private class SyncTopicHandle(
        val value: Computed<List<String>>,
    ) : TopicHandle

    private class ThreadSafeTopicHandle(
        val value: ThreadSafeComputed<List<String>>,
    ) : TopicHandle

    private class AsyncTopicHandle(
        val value: AsyncContext.AsyncComputed<List<String>>,
    ) : TopicHandle

    private fun parseTopicInitial(initial: JsonObject): TopicSnapshot<String> {
        val subscriptions =
            initial.getValue("subscriptions").jsonObject.mapValues { (_, raw) ->
                val sub = raw.jsonObject
                TopicSubscriptionSnapshot(
                    sub.getValue("cursor").jsonPrimitive.long,
                    parseDurability(sub.getValue("durability").jsonPrimitive.content),
                    sub.getValue("connected").jsonPrimitive.boolean,
                )
            }
        return TopicSnapshot(
            initial.getValue("base_offset").jsonPrimitive.long,
            initial.getValue("elements").jsonArray.map { it.jsonPrimitive.content },
            subscriptions,
        )
    }

    private fun parseDurability(value: String) =
        when (value) {
            "durable" -> TopicDurability.Durable
            "ephemeral" -> TopicDurability.Ephemeral
            else -> error("unknown durability $value")
        }

    private class SyncTopicHarness(
        initial: TopicSnapshot<String>,
    ) : TopicHarness {
        override val flavor = Flavor.Sync
        private val ctx = Context()
        private var topic = TopicCell(ctx, initial)

        override fun subscribe(
            id: String,
            durability: TopicDurability,
        ) = topic.subscribe(id, durability)

        override fun reconnect(id: String) = topic.reconnect(id)

        override fun restart() {
            topic = TopicCell(ctx, topic.snapshot())
        }

        override fun disconnect(id: String) = topic.disconnect(id)

        override fun publish(value: String) = topic.publish(value)

        override fun advance(id: String) = topic.advance(id)

        override fun gc() = topic.gc()

        override fun baseOffset() = topic.baseOffset()

        override fun elements() = topic.elements()

        override fun subscription(id: String) = topic.subscription(id)

        override fun subscriptionIds() = topic.snapshot().subscriptions.keys

        override fun readStream(id: String) = topic.readStream(id)

        override fun handle(id: String) = topic.readerHandle(id)?.let(::SyncTopicHandle)

        override fun prime(handle: TopicHandle) {
            ctx.get((handle as SyncTopicHandle).value)
        }

        override fun isSet(handle: TopicHandle) = ctx.isSet((handle as SyncTopicHandle).value)

        override fun close() = Unit
    }

    private class ThreadSafeTopicHarness(
        initial: TopicSnapshot<String>,
    ) : TopicHarness {
        override val flavor = Flavor.ThreadSafe
        private val ctx = ThreadSafeContext()
        private var topic = ThreadSafeTopicCell(ctx, initial)

        override fun subscribe(
            id: String,
            durability: TopicDurability,
        ) = topic.subscribe(id, durability)

        override fun reconnect(id: String) = topic.reconnect(id)

        override fun restart() {
            topic = ThreadSafeTopicCell(ctx, topic.snapshot())
        }

        override fun disconnect(id: String) = topic.disconnect(id)

        override fun publish(value: String) = topic.publish(value)

        override fun advance(id: String) = topic.advance(id)

        override fun gc() = topic.gc()

        override fun baseOffset() = topic.baseOffset()

        override fun elements() = topic.elements()

        override fun subscription(id: String) = topic.subscription(id)

        override fun subscriptionIds() = topic.snapshot().subscriptions.keys

        override fun readStream(id: String) = topic.readStream(id)

        override fun handle(id: String) = topic.readerHandle(id)?.let(::ThreadSafeTopicHandle)

        override fun prime(handle: TopicHandle) {
            ctx.get((handle as ThreadSafeTopicHandle).value)
        }

        override fun isSet(handle: TopicHandle) = ctx.isSet((handle as ThreadSafeTopicHandle).value)

        override fun close() = Unit
    }

    private class AsyncTopicHarness(
        initial: TopicSnapshot<String>,
    ) : TopicHarness {
        override val flavor = Flavor.Async
        private val ctx = AsyncContext()
        private var topic = AsyncTopicCell(ctx, initial)

        override fun subscribe(
            id: String,
            durability: TopicDurability,
        ) = topic.subscribe(id, durability)

        override fun reconnect(id: String) = topic.reconnect(id)

        override fun restart() {
            topic = AsyncTopicCell(ctx, topic.snapshot())
        }

        override fun disconnect(id: String) = topic.disconnect(id)

        override fun publish(value: String) = topic.publish(value)

        override fun advance(id: String) = topic.advance(id)

        override fun gc() = topic.gc()

        override fun baseOffset() = topic.baseOffset()

        override fun elements() = topic.elements()

        override fun subscription(id: String) = topic.subscription(id)

        override fun subscriptionIds() = topic.snapshot().subscriptions.keys

        override fun readStream(id: String) = topic.readStream(id)

        override fun handle(id: String) = topic.readerHandle(id)?.let(::AsyncTopicHandle)

        override fun prime(handle: TopicHandle) {
            requireNotNull(ctx.get((handle as AsyncTopicHandle).value))
        }

        override fun isSet(handle: TopicHandle) = ctx.isSet((handle as AsyncTopicHandle).value)

        override fun close() = ctx.close()
    }

    private fun topicHarness(
        flavor: Flavor,
        initial: TopicSnapshot<String>,
    ): TopicHarness =
        when (flavor) {
            Flavor.Sync -> SyncTopicHarness(initial)
            Flavor.ThreadSafe -> ThreadSafeTopicHarness(initial)
            Flavor.Async -> AsyncTopicHarness(initial)
        }

    private fun replayTopic(
        name: String,
        flavor: Flavor,
        visitedSites: MutableSet<String>?,
    ): Int {
        val fixture = fixture(name)
        topicHarness(flavor, parseTopicInitial(fixture.getValue("initial").jsonObject)).use { topic ->
            val initialIds =
                fixture
                    .getValue("initial")
                    .jsonObject
                    .getValue("subscriptions")
                    .jsonObject.keys
            initialIds.mapNotNull(topic::handle).forEach(topic::prime)
            val steps = fixture.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), "$flavor $name has no steps")
            var executed = 0
            steps.forEachIndexed { index, raw ->
                val step = raw.jsonObject
                assertFalse(step.containsKey("invalidates"), "$name step $index uses step.invalidates")
                val expected = step.getValue("expected").jsonObject
                val keys = assertionKeys(name, index, expected, flavor, visitedSites)
                val invalidates = expected.getValue("invalidates").jsonObject
                val before = invalidates.keys.associateWith(topic::handle)
                before.values.filterNotNull().forEach(topic::prime)
                val op = step.getValue("op").jsonObject
                val returned: JsonElement =
                    when (op.getValue("type").jsonPrimitive.content) {
                        "publish" -> JsonPrimitive(topic.publish(op.getValue("value").jsonPrimitive.content))
                        "subscribe" -> {
                            topic.subscribe(
                                op.getValue("subscriber").jsonPrimitive.content,
                                parseDurability(op.getValue("durability").jsonPrimitive.content),
                            )
                            JsonNull
                        }
                        "reconnect" -> {
                            topic.reconnect(op.getValue("subscriber").jsonPrimitive.content)
                            JsonNull
                        }
                        "restart" -> {
                            topic.restart()
                            JsonNull
                        }
                        "disconnect" -> {
                            topic.disconnect(op.getValue("subscriber").jsonPrimitive.content)
                            JsonNull
                        }
                        "advance" ->
                            topic
                                .advance(op.getValue("subscriber").jsonPrimitive.content)
                                ?.let(::JsonPrimitive) ?: JsonNull
                        "gc" -> JsonPrimitive(topic.gc())
                        else -> error("$flavor $name step $index: unknown topic op")
                    }
                step["returns"]?.let { assertEquals(it, returned, "$flavor $name step $index returns") }
                keys.sub("invalidates") { expectedInvalidates ->
                    for (id in expectedInvalidates.keys.sorted()) {
                        val handle = before[id] ?: topic.handle(id)
                        assertNotNull(handle, "$flavor $name step $index has no reader for $id")
                        expectedInvalidates.assertBoolean(id) { !topic.isSet(handle) }
                    }
                }
                keys.assertLong("base_offset") { topic.baseOffset() }
                keys.assertStrings("elements") { topic.elements() }
                keys.sub("subscriptions") { expectedSubs ->
                    assertEquals(expectedSubs.keys, topic.subscriptionIds())
                    for (id in expectedSubs.keys.sorted()) {
                        val got = assertNotNull(topic.subscription(id))
                        expectedSubs.sub(id) { want ->
                            want.assertLong("cursor") { got.cursor }
                            want.assertKeyWith("durability") { raw ->
                                assertEquals(parseDurability(raw.jsonPrimitive.content), got.durability)
                            }
                            want.assertBoolean("connected") { got.connected }
                        }
                    }
                }
                keys.sub("reads") { reads ->
                    for (id in reads.keys.sorted()) {
                        reads.assertStrings(id) { topic.readStream(id) }
                    }
                }
                val expectedSubs = expected.getValue("subscriptions").jsonObject
                expectedSubs.keys.mapNotNull(topic::handle).forEach(topic::prime)
                keys.requireAllSatisfied()
                executed++
            }
            check(executed == steps.size) { "$flavor $name: loaded ${steps.size} steps but executed $executed" }
            return executed
        }
    }

    @Test
    fun `all four TopicCell fixtures replay per flavor`() {
        val visitedSites = linkedSetOf<String>()
        for (flavor in Flavor.entries) {
            val steps = topicFixtures.sumOf { replayTopic(it, flavor, visitedSites.takeIf { flavor == Flavor.Sync }) }
            assertTrue(steps > 0, "$flavor replayed zero TopicCell steps")
            assertEquals(declaredSteps(topicFixtures), steps, "every declared TopicCell step must run against $flavor")
        }
        assertExactSites(topicFixtures, 29, visitedSites)
    }

    // -- WorkQueueCell -----------------------------------------------------

    private interface WorkHarness : AutoCloseable {
        val flavor: Flavor

        fun push(value: String): Long

        fun claim(
            worker: String,
            now: Long,
        ): WorkQueueDelivery<String>?

        fun ack(
            worker: String,
            id: Long,
        ): Boolean

        fun nack(
            worker: String,
            id: Long,
        ): Boolean

        fun reap(now: Long): Int

        fun pendingItems(): List<WorkQueueItem<String>>

        fun inFlight(): List<WorkQueueDelivery<String>>

        fun deadLetters(): List<WorkQueueDeadLetter<String>>

        fun pendingLen(): Int

        fun isEmpty(): Boolean

        fun inFlightLen(): Int

        fun deadLetterLen(): Int

        fun prime()

        fun isSet(kind: String): Boolean
    }

    private class SyncWorkHarness(
        timeout: Long,
        attempts: Int,
    ) : WorkHarness {
        override val flavor = Flavor.Sync
        private val ctx = Context()
        private val queue = WorkQueueCell<String>(ctx, timeout, attempts)

        override fun push(value: String) = queue.push(value)

        override fun claim(
            worker: String,
            now: Long,
        ) = queue.claim(worker, now)

        override fun ack(
            worker: String,
            id: Long,
        ) = queue.ack(worker, id)

        override fun nack(
            worker: String,
            id: Long,
        ) = queue.nack(worker, id)

        override fun reap(now: Long) = queue.reapExpired(now)

        override fun pendingItems() = queue.pendingItems()

        override fun inFlight() = queue.inFlightDeliveries()

        override fun deadLetters() = queue.deadLetterItems()

        override fun pendingLen() = queue.pendingLen()

        override fun isEmpty() = queue.isEmpty()

        override fun inFlightLen() = queue.inFlightLen()

        override fun deadLetterLen() = queue.deadLetterLen()

        override fun prime() {
            ctx.get(queue.readers.pendingLen)
            ctx.get(queue.readers.isEmpty)
            ctx.get(queue.readers.inFlightLen)
            ctx.get(queue.readers.deadLetterLen)
        }

        override fun isSet(kind: String) =
            ctx.isSet(
                when (kind) {
                    "pending_len" -> queue.readers.pendingLen
                    "is_empty" -> queue.readers.isEmpty
                    "in_flight_len" -> queue.readers.inFlightLen
                    "dead_letter_len" -> queue.readers.deadLetterLen
                    else -> error("unknown work reader $kind")
                },
            )

        override fun close() = Unit
    }

    private class ThreadSafeWorkHarness(
        timeout: Long,
        attempts: Int,
    ) : WorkHarness {
        override val flavor = Flavor.ThreadSafe
        private val ctx = ThreadSafeContext()
        private val queue = ThreadSafeWorkQueueCell<String>(ctx, timeout, attempts)

        override fun push(value: String) = queue.push(value)

        override fun claim(
            worker: String,
            now: Long,
        ) = queue.claim(worker, now)

        override fun ack(
            worker: String,
            id: Long,
        ) = queue.ack(worker, id)

        override fun nack(
            worker: String,
            id: Long,
        ) = queue.nack(worker, id)

        override fun reap(now: Long) = queue.reapExpired(now)

        override fun pendingItems() = queue.pendingItems()

        override fun inFlight() = queue.inFlightDeliveries()

        override fun deadLetters() = queue.deadLetterItems()

        override fun pendingLen() = queue.pendingLen()

        override fun isEmpty() = queue.isEmpty()

        override fun inFlightLen() = queue.inFlightLen()

        override fun deadLetterLen() = queue.deadLetterLen()

        override fun prime() {
            ctx.get(queue.readers.pendingLen)
            ctx.get(queue.readers.isEmpty)
            ctx.get(queue.readers.inFlightLen)
            ctx.get(queue.readers.deadLetterLen)
        }

        override fun isSet(kind: String) =
            ctx.isSet(
                when (kind) {
                    "pending_len" -> queue.readers.pendingLen
                    "is_empty" -> queue.readers.isEmpty
                    "in_flight_len" -> queue.readers.inFlightLen
                    "dead_letter_len" -> queue.readers.deadLetterLen
                    else -> error("unknown work reader $kind")
                },
            )

        override fun close() = Unit
    }

    private class AsyncWorkHarness(
        timeout: Long,
        attempts: Int,
    ) : WorkHarness {
        override val flavor = Flavor.Async
        private val ctx = AsyncContext()
        private val queue = AsyncWorkQueueCell<String>(ctx, timeout, attempts)

        override fun push(value: String) = queue.push(value)

        override fun claim(
            worker: String,
            now: Long,
        ) = queue.claim(worker, now)

        override fun ack(
            worker: String,
            id: Long,
        ) = queue.ack(worker, id)

        override fun nack(
            worker: String,
            id: Long,
        ) = queue.nack(worker, id)

        override fun reap(now: Long) = queue.reapExpired(now)

        override fun pendingItems() = queue.pendingItems()

        override fun inFlight() = queue.inFlightDeliveries()

        override fun deadLetters() = queue.deadLetterItems()

        override fun pendingLen() = queue.pendingLen()

        override fun isEmpty() = queue.isEmpty()

        override fun inFlightLen() = queue.inFlightLen()

        override fun deadLetterLen() = queue.deadLetterLen()

        override fun prime() {
            requireNotNull(ctx.get(queue.readers.pendingLen))
            requireNotNull(ctx.get(queue.readers.isEmpty))
            requireNotNull(ctx.get(queue.readers.inFlightLen))
            requireNotNull(ctx.get(queue.readers.deadLetterLen))
        }

        override fun isSet(kind: String) =
            ctx.isSet(
                when (kind) {
                    "pending_len" -> queue.readers.pendingLen
                    "is_empty" -> queue.readers.isEmpty
                    "in_flight_len" -> queue.readers.inFlightLen
                    "dead_letter_len" -> queue.readers.deadLetterLen
                    else -> error("unknown work reader $kind")
                },
            )

        override fun close() = ctx.close()
    }

    private fun workHarness(
        flavor: Flavor,
        config: JsonObject,
    ): WorkHarness {
        val timeout = config.getValue("visibility_timeout").jsonPrimitive.long
        val attempts = config.getValue("max_deliveries").jsonPrimitive.int
        return when (flavor) {
            Flavor.Sync -> SyncWorkHarness(timeout, attempts)
            Flavor.ThreadSafe -> ThreadSafeWorkHarness(timeout, attempts)
            Flavor.Async -> AsyncWorkHarness(timeout, attempts)
        }
    }

    /** The reader kinds a WorkQueueCell matrix is made of — asserted as a SET. */
    private val workInvalidationKinds =
        setOf("pending_len", "is_empty", "in_flight_len", "dead_letter_len")

    private fun deliveryJson(delivery: WorkQueueDelivery<String>): JsonObject =
        JsonObject(
            mapOf(
                "delivery_id" to JsonPrimitive(delivery.deliveryId),
                "item_id" to JsonPrimitive(delivery.itemId),
                "value" to JsonPrimitive(delivery.value),
                "worker" to JsonPrimitive(delivery.worker),
                "attempt" to JsonPrimitive(delivery.attempt),
                "deadline" to JsonPrimitive(delivery.deadline),
            ),
        )

    private fun replayWork(
        name: String,
        flavor: Flavor,
        visitedSites: MutableSet<String>?,
    ): Int {
        val fixture = fixture(name)
        workHarness(flavor, fixture.getValue("initial").jsonObject).use { queue ->
            queue.prime()
            val steps = fixture.getValue("steps").jsonArray
            assertTrue(steps.isNotEmpty(), "$flavor $name has no steps")
            var executed = 0
            steps.forEachIndexed { index, raw ->
                val step = raw.jsonObject
                assertFalse(step.containsKey("invalidates"), "$name step $index uses step.invalidates")
                val op = step.getValue("op").jsonObject
                val returned: JsonElement =
                    when (op.getValue("type").jsonPrimitive.content) {
                        "push" -> JsonPrimitive(queue.push(op.getValue("value").jsonPrimitive.content))
                        "claim" ->
                            queue
                                .claim(
                                    op.getValue("worker").jsonPrimitive.content,
                                    op.getValue("now").jsonPrimitive.long,
                                )?.let(::deliveryJson) ?: JsonNull
                        "ack" ->
                            JsonPrimitive(
                                queue.ack(
                                    op.getValue("worker").jsonPrimitive.content,
                                    op.getValue("delivery_id").jsonPrimitive.long,
                                ),
                            )
                        "nack" ->
                            JsonPrimitive(
                                queue.nack(
                                    op.getValue("worker").jsonPrimitive.content,
                                    op.getValue("delivery_id").jsonPrimitive.long,
                                ),
                            )
                        "reap_expired" -> JsonPrimitive(queue.reap(op.getValue("now").jsonPrimitive.long))
                        else -> error("$flavor $name step $index: unknown work op")
                    }
                assertEquals(step.getValue("returns"), returned, "$flavor $name step $index returns")
                val expected = step.getValue("expected").jsonObject
                val keys = assertionKeys(name, index, expected, flavor, visitedSites)
                // Iterating the fixture's keys catches a kind the corpus ADDS or
                // RENAMES (`isSet` resolves through a map that throws) and is blind
                // to one it DROPS. WorkQueueConformanceTest read the four by name,
                // catching the drop and blind to the add — each runner covered the
                // half the other missed, over these same two fixtures
                // (`#lzsiblingrunnermasking`). The set equality supplies the other
                // half here so neither runner depends on its sibling.
                keys.sub("invalidates") { invalidates ->
                    assertEquals(
                        workInvalidationKinds,
                        invalidates.keys,
                        "$flavor $name step $index expected.invalidates reader kinds",
                    )
                    for (kind in invalidates.keys.sorted()) {
                        invalidates.assertBoolean(kind) { !queue.isSet(kind) }
                    }
                }
                keys.sub("reads") { reads ->
                    reads.assertInt("pending_len") { queue.pendingLen() }
                    reads.assertBoolean("is_empty") { queue.isEmpty() }
                    reads.assertInt("in_flight_len") { queue.inFlightLen() }
                    reads.assertInt("dead_letter_len") { queue.deadLetterLen() }
                }
                keys.assertKeyWith("pending") { rawPending ->
                    val expectedPending = rawPending.jsonArray
                    assertEquals(expectedPending.size, queue.pendingItems().size, "$flavor $name step $index: pending size")
                    queue.pendingItems().zip(expectedPending).forEach { (got, rawItem) ->
                        val want = rawItem.jsonObject
                        assertEquals(setOf("item_id", "value", "attempts"), want.keys, "$name: pending fields")
                        assertEquals(want.getValue("item_id").jsonPrimitive.long, got.itemId, "$name: pending item_id")
                        assertEquals(want.getValue("value").jsonPrimitive.content, got.value, "$name: pending value")
                        assertEquals(want.getValue("attempts").jsonPrimitive.int, got.attempts, "$name: pending attempts")
                    }
                }
                keys.assertKeyWith("in_flight") { rawInFlight ->
                    val expectedInFlight = rawInFlight.jsonArray
                    assertEquals(expectedInFlight.size, queue.inFlight().size, "$flavor $name step $index: in_flight size")
                    queue.inFlight().zip(expectedInFlight).forEach { (got, rawDelivery) ->
                        assertEquals(rawDelivery, deliveryJson(got), "$name: in_flight delivery")
                    }
                }
                keys.assertKeyWith("dead_letters") { rawDeadLetters ->
                    val expectedDead = rawDeadLetters.jsonArray
                    assertEquals(expectedDead.size, queue.deadLetters().size, "$flavor $name step $index: dead_letters size")
                    queue.deadLetters().zip(expectedDead).forEach { (got, rawDead) ->
                        val want = rawDead.jsonObject
                        assertEquals(setOf("item_id", "value", "attempts", "reason"), want.keys, "$name: dead_letters fields")
                        assertEquals(want.getValue("item_id").jsonPrimitive.long, got.itemId, "$name: dead_letters item_id")
                        assertEquals(want.getValue("value").jsonPrimitive.content, got.value, "$name: dead_letters value")
                        assertEquals(want.getValue("attempts").jsonPrimitive.int, got.attempts, "$name: dead_letters attempts")
                        assertEquals(
                            want.getValue("reason").jsonPrimitive.content,
                            when (got.reason) {
                                WorkQueueDeadLetterReason.Nack -> "nack"
                                WorkQueueDeadLetterReason.Expired -> "expired"
                            },
                            "$name: dead_letters reason",
                        )
                    }
                }
                keys.requireAllSatisfied()
                queue.prime()
                executed++
            }
            check(executed == steps.size) { "$flavor $name: loaded ${steps.size} steps but executed $executed" }
            return executed
        }
    }

    @Test
    fun `both WorkQueueCell fixtures replay per flavor`() {
        val visitedSites = linkedSetOf<String>()
        for (flavor in Flavor.entries) {
            val steps = workFixtures.sumOf { replayWork(it, flavor, visitedSites.takeIf { flavor == Flavor.Sync }) }
            assertTrue(steps > 0, "$flavor replayed zero WorkQueueCell steps")
            assertEquals(declaredSteps(workFixtures), steps, "every declared WorkQueueCell step must run against $flavor")
        }
        assertExactSites(workFixtures, 18, visitedSites)
    }

    @Test
    fun `topic and work expectation families reject fixture value mutations`() {
        data class Mutation(
            val id: String,
            val family: String,
            val fixture: String,
            val siteId: String,
            val replacement: String,
            val topic: Boolean,
        )

        val topicFixture = "topiccell_broadcast_cursor_isolation.json"
        val topicSite = "collections/$topicFixture|steps[0].expected"
        val workFixture = "workqueue_competing_delivery.json"
        val workSite0 = "collections/$workFixture|steps[0].expected"
        val mutations =
            listOf(
                Mutation("topic.base_offset", "base_offset", topicFixture, topicSite, "99", true),
                Mutation("topic.elements", "elements", topicFixture, topicSite, "[\"wrong\"]", true),
                Mutation(
                    "topic.subscriptions",
                    "subscriptions",
                    topicFixture,
                    topicSite,
                    "{\"alpha\":{\"durability\":\"durable\",\"connected\":true,\"cursor\":99},\"beta\":{\"durability\":\"durable\",\"connected\":true,\"cursor\":0}}",
                    true,
                ),
                Mutation("topic.reads", "reads", topicFixture, topicSite, "{\"alpha\":[\"wrong\"],\"beta\":[\"a\"]}", true),
                Mutation("topic.invalidates", "invalidates", topicFixture, topicSite, "{\"alpha\":false,\"beta\":true}", true),
                Mutation("work.pending", "pending", workFixture, workSite0, "[{\"item_id\":0,\"value\":\"wrong\",\"attempts\":0}]", false),
                Mutation(
                    "work.in_flight",
                    "in_flight",
                    workFixture,
                    "collections/$workFixture|steps[2].expected",
                    "[{\"delivery_id\":0,\"item_id\":0,\"value\":\"a\",\"worker\":\"alpha\",\"attempt\":1,\"deadline\":999}]",
                    false,
                ),
                Mutation(
                    "work.dead_letters",
                    "dead_letters",
                    "workqueue_lease_deadletter.json",
                    "collections/workqueue_lease_deadletter.json|steps[6].expected",
                    "[{\"item_id\":0,\"value\":\"poison\",\"attempts\":2,\"reason\":\"nack\"}]",
                    false,
                ),
                Mutation("work.reads", "reads", workFixture, workSite0, "{\"pending_len\":99,\"is_empty\":false,\"in_flight_len\":0,\"dead_letter_len\":0}", false),
                Mutation("work.invalidates", "invalidates", workFixture, workSite0, "{\"pending_len\":false,\"is_empty\":true,\"in_flight_len\":false,\"dead_letter_len\":false}", false),
            )
        assertEquals(
            setOf(
                "topic.base_offset",
                "topic.elements",
                "topic.subscriptions",
                "topic.reads",
                "topic.invalidates",
                "work.pending",
                "work.in_flight",
                "work.dead_letters",
                "work.reads",
                "work.invalidates",
            ),
            mutations.map { it.id }.toSet(),
            "topic/work mutation matrix families",
        )
        // Other primary evaluators pin 8 cellmap/reconcile + 2 merge + 26 CRDT + 7 queue.
        assertEquals(53, 8 + 2 + 26 + 7 + mutations.size, "collections executable expectation-family mutation total")

        try {
            fixtureOverride = { name ->
                Json.parseToJsonElement(
                    Files.readString(ConformanceFixtures.path("collections/$name")),
                ).jsonObject
            }
            bindCanonicalBlocks = false
            for (mutation in mutations) {
                val replacement = Json.parseToJsonElement(mutation.replacement)
                val fixturePath = "collections/${mutation.fixture}"
                val raw = Files.readString(ConformanceFixtures.path(fixturePath))
                val canonical = ConformanceFixtures.blockSitesOf(fixturePath, raw).getValue(mutation.siteId)
                assertTrue(canonical.getValue(mutation.family) != replacement, "${mutation.id}: mutation changes value")
                var hits = 0
                expectationTransform = { siteId, expected ->
                    if (siteId == mutation.siteId) {
                        hits++
                        JsonObject(expected + (mutation.family to replacement))
                    } else {
                        expected
                    }
                }
                val failure = assertFails("${mutation.id}: changed fixture value survived production replay") {
                    if (mutation.topic) {
                        replayTopic(mutation.fixture, Flavor.Sync, linkedSetOf())
                    } else {
                        replayWork(mutation.fixture, Flavor.Sync, linkedSetOf())
                    }
                }
                assertEquals(1, hits, "${mutation.id}: target site visited exactly once")
                assertTrue(
                    failure.message.orEmpty().contains(mutation.family),
                    "${mutation.id}: production failure must name family '${mutation.family}', got ${failure.message}",
                )
            }
        } finally {
            fixtureOverride = null
            expectationTransform = { _, expected -> expected }
            bindCanonicalBlocks = true
        }
    }

    // -- Atomicity, concurrency, and positive mutation discriminators ------

    @Test
    fun `every queue flavor has positive and negative invalidation twins`() {
        for (flavor in Flavor.entries) {
            queueHarness(
                flavor,
                JsonObject(
                    mapOf(
                        "elements" to kotlinx.serialization.json.JsonArray(emptyList()),
                        "capacity" to JsonPrimitive(2),
                        "closed" to JsonPrimitive(false),
                    ),
                ),
            ).use { queue ->
                queue.primeReaders()
                queue.tryPush("a")
                assertFalse(queue.readerIsSet("head"), "$flavor head must invalidate from empty")
                queue.primeReaders()
                queue.tryPush("b")
                assertTrue(queue.readerIsSet("head"), "$flavor head must stay cached on tail push")
                assertFalse(queue.readerIsSet("len"), "$flavor len must positively invalidate")
            }
        }
    }

    @Test
    fun `correlated queue readers publish one atomic state per flavor`() {
        val syncCtx = Context()
        val sync = QueueCell.bounded<Int>(syncCtx, 1)
        val syncLog = mutableListOf<Pair<Int, Boolean>>()
        syncCtx.effect {
            syncLog += sync.len(this) to sync.isEmpty(this)
            null
        }
        sync.tryPush(1)
        assertEquals(listOf(0 to true, 1 to false), syncLog)

        val threadCtx = ThreadSafeContext()
        val thread = ThreadSafeQueueCell.bounded<Int>(threadCtx, 1)
        val threadLog = mutableListOf<Pair<Int, Boolean>>()
        threadCtx.effect {
            threadLog += thread.len() to thread.isEmpty()
            null
        }
        thread.tryPush(1)
        assertEquals(listOf(0 to true, 1 to false), threadLog)

        runBlocking {
            val asyncCtx = AsyncContext()
            try {
                val async = AsyncQueueCell.bounded<Int>(asyncCtx, 1)
                val asyncLog = mutableListOf<Pair<Int, Boolean>>()
                asyncCtx.effectAsync {
                    asyncLog += async.len(this) to async.isEmpty(this)
                    null
                }
                asyncCtx.settle()
                async.tryPush(1)
                asyncCtx.settle()
                assertEquals(listOf(0 to true, 1 to false), asyncLog)
            } finally {
                asyncCtx.dispose()
            }
        }
    }

    @Test
    fun `topic fanout and work lifecycle publish atomically per flavor`() {
        val syncCtx = Context()
        val syncTopic = TopicCell<String>(syncCtx)
        syncTopic.subscribe("a", TopicDurability.Durable)
        syncTopic.subscribe("b", TopicDurability.Durable)
        val syncTopicLog = mutableListOf<Pair<Int, Int>>()
        syncCtx.effect {
            syncTopicLog +=
                syncTopic.readStream("a", this).size to syncTopic.readStream("b", this).size
            null
        }
        syncTopic.publish("x")
        assertEquals(listOf(0 to 0, 1 to 1), syncTopicLog)

        val threadCtx = ThreadSafeContext()
        val threadTopic = ThreadSafeTopicCell<String>(threadCtx)
        threadTopic.subscribe("a", TopicDurability.Durable)
        threadTopic.subscribe("b", TopicDurability.Durable)
        val threadTopicLog = mutableListOf<Pair<Int, Int>>()
        threadCtx.effect {
            threadTopicLog +=
                threadTopic.readStream("a").size to threadTopic.readStream("b").size
            null
        }
        threadTopic.publish("x")
        assertEquals(listOf(0 to 0, 1 to 1), threadTopicLog)

        val syncWork = WorkQueueCell<String>(syncCtx, 10, 2)
        syncWork.push("x")
        val syncWorkLog = mutableListOf<Triple<Int, Boolean, Int>>()
        syncCtx.effect {
            syncWorkLog +=
                Triple(syncWork.pendingLen(this), syncWork.isEmpty(this), syncWork.inFlightLen(this))
            null
        }
        assertNotNull(syncWork.claim("worker", 0))
        assertEquals(listOf(Triple(1, false, 0), Triple(0, true, 1)), syncWorkLog)

        val threadWork = ThreadSafeWorkQueueCell<String>(threadCtx, 10, 2)
        threadWork.push("x")
        val threadWorkLog = mutableListOf<Triple<Int, Boolean, Int>>()
        threadCtx.effect {
            threadWorkLog +=
                Triple(threadWork.pendingLen(), threadWork.isEmpty(), threadWork.inFlightLen())
            null
        }
        assertNotNull(threadWork.claim("worker", 0))
        assertEquals(listOf(Triple(1, false, 0), Triple(0, true, 1)), threadWorkLog)

        runBlocking {
            val asyncCtx = AsyncContext()
            try {
                val asyncTopic = AsyncTopicCell<String>(asyncCtx)
                asyncTopic.subscribe("a", TopicDurability.Durable)
                asyncTopic.subscribe("b", TopicDurability.Durable)
                val asyncTopicLog = mutableListOf<Pair<Int, Int>>()
                asyncCtx.effectAsync {
                    asyncTopicLog +=
                        asyncTopic.readStream("a", this).size to
                        asyncTopic.readStream("b", this).size
                    null
                }
                asyncCtx.settle()
                asyncTopic.publish("x")
                asyncCtx.settle()
                assertEquals(listOf(0 to 0, 1 to 1), asyncTopicLog)

                val asyncWork = AsyncWorkQueueCell<String>(asyncCtx, 10, 2)
                asyncWork.push("x")
                val asyncWorkLog = mutableListOf<Triple<Int, Boolean, Int>>()
                asyncCtx.effectAsync {
                    asyncWorkLog +=
                        Triple(
                            asyncWork.pendingLen(this),
                            asyncWork.isEmpty(this),
                            asyncWork.inFlightLen(this),
                        )
                    null
                }
                asyncCtx.settle()
                assertNotNull(asyncWork.claim("worker", 0))
                asyncCtx.settle()
                assertEquals(
                    listOf(Triple(1, false, 0), Triple(0, true, 1)),
                    asyncWorkLog,
                )
            } finally {
                asyncCtx.dispose()
            }
        }
    }

    @Test
    fun `thread safe queues serialize concurrent producers and claims`() {
        val ctx = ThreadSafeContext()
        val queue = ThreadSafeQueueCell.unbounded<Int>(ctx)
        val threads = 4
        val each = 100
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val pushFutures =
            (0 until threads).map { producer ->
                pool.submit {
                    start.await()
                    repeat(each) { index -> assertNull(queue.tryPush(producer * each + index)) }
                }
            }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "concurrent pushes deadlocked")
        pushFutures.forEach { it.get() }
        assertEquals(threads * each, queue.len())

        val topic = ThreadSafeTopicCell<Int>(ctx)
        topic.subscribe("a", TopicDurability.Durable)
        topic.subscribe("b", TopicDurability.Durable)
        val offsets =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<Long>()
        val publishers = Executors.newFixedThreadPool(threads)
        val publishFutures =
            (0 until threads).map { publisher ->
                publishers.submit {
                    repeat(each) { index ->
                        assertTrue(offsets.add(topic.publish(publisher * each + index)))
                    }
                }
            }
        publishers.shutdown()
        assertTrue(publishers.awaitTermination(10, TimeUnit.SECONDS), "concurrent publish deadlocked")
        publishFutures.forEach { it.get() }
        assertEquals(threads * each, offsets.size)
        assertEquals(topic.elements(), topic.readStream("a"))
        assertEquals(topic.elements(), topic.readStream("b"))

        val work = ThreadSafeWorkQueueCell<Int>(ctx, 10, 2)
        repeat(threads * each) { work.push(it) }
        val claimed =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<Long>()
        val remaining = AtomicInteger(threads * each)
        val consumers = Executors.newFixedThreadPool(threads)
        val consumeFutures =
            (0 until threads).map { worker ->
                consumers.submit {
                    while (remaining.get() > 0) {
                        val delivery = work.claim("w$worker", 0) ?: break
                        assertTrue(claimed.add(delivery.itemId), "item was delivered twice concurrently")
                        assertTrue(work.ack("w$worker", delivery.deliveryId))
                        remaining.decrementAndGet()
                    }
                }
            }
        consumers.shutdown()
        assertTrue(consumers.awaitTermination(10, TimeUnit.SECONDS), "concurrent claims deadlocked")
        consumeFutures.forEach { it.get() }
        assertEquals(0, remaining.get())
        assertEquals(threads * each, claimed.size)
    }
}
