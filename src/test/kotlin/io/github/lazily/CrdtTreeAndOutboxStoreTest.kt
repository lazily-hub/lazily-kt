package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.lazily.outbox.Outbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class CrdtTreeAndOutboxStoreTest {
    private class FakeRoomDao : RoomOutboxDao {
        private val frames = sortedMapOf<Pair<String, Long>, ByteArray>(compareBy({ it.first }, { it.second }))
        private val cursors = mutableMapOf<String, Long>()

        override fun upsert(channel: String, epoch: Long, frame: ByteArray) {
            frames[channel to epoch] = frame.copyOf()
        }

        override fun deleteThrough(channel: String, epoch: Long) {
            frames.keys.removeAll { (storedChannel, storedEpoch) ->
                storedChannel == channel && storedEpoch <= epoch
            }
        }

        override fun scanAfter(channel: String, epoch: Long): List<Pair<Long, ByteArray>> =
            frames
                .filterKeys { (storedChannel, storedEpoch) -> storedChannel == channel && storedEpoch > epoch }
                .map { (key, frame) -> key.second to frame.copyOf() }

        override fun loadCursor(channel: String): Long? = cursors[channel]

        override fun saveCursor(channel: String, epoch: Long) {
            cursors[channel] = maxOf(cursors[channel] ?: 0L, epoch)
        }
    }

    private fun fixture(path: String) =
        Json.parseToJsonElement(
            requireNotNull(javaClass.getResource(path)) { "missing fixture $path" }.readText(),
        ).jsonObject

    @Test
    fun crdtTreeDeltaAndMergeLaws() {
        val left = TextCrdt(1, "abc")
        val right = left.fork(2)
        left.insertString(3, " left")
        right.delete(0)
        right.insertString(0, "R")

        val leftDelta = left.deltaSince(right.versionVector())
        val rightDelta = right.deltaSince(left.versionVector())
        assertTrue(right.applyDelta(leftDelta))
        assertTrue(left.applyDelta(rightDelta))
        assertEquals(left.value(), right.value())
        assertFalse(right.applyDelta(leftDelta), "delta replay is idempotent")

        val a = left.clone()
        val b = right.clone()
        a.mergeFrom(b)
        b.mergeFrom(a)
        assertEquals(a.value(), b.value(), "merge is commutative at the materialized value")
        assertFalse(a.mergeFrom(a.clone()), "merge is idempotent")
    }

    @Test
    fun genericOutboxPersistsCursorAndReplaysBytes() {
        val store = InMemoryStore()
        val first = Outbox(store)
        val one = IpcMessage.ofDelta(Delta(0, 1))
        val two = IpcMessage.ofDelta(Delta(1, 2))
        first.append(1, one)
        first.append(2, two)
        first.ackThrough(1)

        val reopened = Outbox(store)
        assertEquals(1, reopened.ackedThrough)
        assertEquals(listOf(2L to two), reopened.replayFrom(0))
        assertEquals(listOf(2L), reopened.retainedEpochs())
    }

    @Test
    fun roomStoreReloadsCursorAndUnacknowledgedSuffix() {
        val dao = FakeRoomDao()
        val first = Outbox(RoomStore(dao, "doc"))
        for (epoch in 1L..3L) first.append(epoch, IpcMessage.ofDelta(Delta(epoch - 1, epoch)))
        first.ackThrough(1)

        val reopened = Outbox(RoomStore(dao, "doc"))
        assertEquals(1, reopened.ackedThrough)
        assertEquals(listOf(2L, 3L), reopened.retainedEpochs())
        assertEquals(listOf(2L, 3L), reopened.replayFrom(0).map { it.first })

        val otherChannel = Outbox(RoomStore(dao, "other"))
        assertEquals(emptyList(), otherChannel.retainedEpochs(), "Room rows are channel-partitioned")
    }

    @Test
    fun crdtTreeCanonicalFixtureReplay() {
        val scenarios = fixture("/conformance/crdt-tree/algebra.json")["scenarios"]!!.jsonArray
        val mergeScenario = scenarios[0].jsonObject
        val seed = mergeScenario["seed"]!!.jsonObject
        val base = TextCrdt(seed["peer"]!!.jsonPrimitive.long, seed["text"]!!.jsonPrimitive.content)
        val replicas = mergeScenario["replicas"]!!.jsonArray.associate { definitionElement ->
            val definition = definitionElement.jsonObject
            val replica = base.fork(definition["peer"]!!.jsonPrimitive.long)
            replica.insertString(replica.len(), definition["insert"]!!.jsonPrimitive.content)
            definition["name"]!!.jsonPrimitive.content to replica
        }
        val folds = mergeScenario["merge_orders"]!!.jsonArray.mapIndexed { index, orderElement ->
            base.fork(100L + index).also { folded ->
                for (name in orderElement.jsonArray) {
                    folded.mergeFrom(replicas.getValue(name.jsonPrimitive.content))
                }
            }
        }
        for (folded in folds.drop(1)) {
            assertEquals(folds.first().value(), folded.value())
            assertEquals(folds.first().versionVector(), folded.versionVector())
        }

        val snapshotScenario = scenarios[1].jsonObject
        val snapshotSeed = snapshotScenario["seed"]!!.jsonObject
        val canonical = TextCrdt(
            snapshotSeed["peer"]!!.jsonPrimitive.long,
            snapshotSeed["text"]!!.jsonPrimitive.content,
        )
        val snapshot = canonical.deltaSince(emptyMap())
        val restored = TextCrdt(snapshotScenario["restore_peer"]!!.jsonPrimitive.long)
        assertTrue(restored.applyDelta(snapshot))
        assertEquals(canonical.value(), restored.value())
        assertEquals(snapshot, restored.deltaSince(emptyMap()), "snapshot preserves operation identity")
        canonical.insertString(canonical.len(), "A")
        restored.insertString(restored.len(), "B")
        canonical.applyDelta(restored.deltaSince(canonical.versionVector()))
        restored.applyDelta(canonical.deltaSince(restored.versionVector()))
        assertEquals(canonical.value(), restored.value())
        assertEquals(snapshotSeed["text"]!!.jsonPrimitive.content.length + 2, canonical.len())

        val steadySeed = scenarios[2].jsonObject["seed"]!!.jsonObject
        val steady = TextCrdt(steadySeed["peer"]!!.jsonPrimitive.long, steadySeed["text"]!!.jsonPrimitive.content)
        val empty = steady.deltaSince(steady.versionVector())
        assertEquals(emptyList(), empty)
        assertFalse(steady.applyDelta(empty))
    }

    @Test
    fun outboxStoreCanonicalFixtureReplay() {
        val scenarios =
            fixture("/conformance/reliable-sync/outbox_store_protocol.json")["scenarios"]!!.jsonArray
        for (scenarioElement in scenarios) {
            val scenario = scenarioElement.jsonObject
            val store = InMemoryStore()
            val outbox = Outbox(store)
            for (epochElement in scenario["put_epochs"]!!.jsonArray) {
                val epoch = epochElement.jsonPrimitive.long
                outbox.append(epoch, IpcMessage.ofDelta(Delta(epoch - 1, epoch)))
            }
            val expected = scenario["expect"]!!.jsonObject
            scenario["scan_after"]?.jsonPrimitive?.long?.let { cursor ->
                assertEquals(
                    expected["epochs"]!!.jsonArray.map { it.jsonPrimitive.long },
                    outbox.replayFrom(cursor).map { it.first },
                )
            }
            for (ack in scenario["ack_through"]?.jsonArray.orEmpty()) outbox.ackThrough(ack.jsonPrimitive.long)
            val observed = if (scenario["restart"]?.jsonPrimitive?.content == "true") Outbox(store) else outbox
            expected["cursor"]?.jsonPrimitive?.long?.let { assertEquals(it, observed.ackedThrough) }
            expected["loaded_cursor"]?.jsonPrimitive?.long?.let { assertEquals(it, observed.ackedThrough) }
            expected["retained"]?.jsonArray?.let { epochs ->
                assertEquals(epochs.map { it.jsonPrimitive.long }, observed.retainedEpochs())
            }
            (expected["replay_from_zero"] ?: expected["replay"])?.jsonArray?.let { epochs ->
                assertEquals(epochs.map { it.jsonPrimitive.long }, observed.replayFrom(0).map { it.first })
            }
        }
    }
}
