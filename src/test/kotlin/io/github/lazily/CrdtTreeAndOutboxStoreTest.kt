package io.github.lazily

import io.github.lazily.outbox.Outbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrdtTreeAndOutboxStoreTest {
    private class FakeRoomDao : RoomOutboxDao {
        private val frames = sortedMapOf<Pair<String, Long>, ByteArray>(compareBy({ it.first }, { it.second }))
        private val cursors = mutableMapOf<String, Long>()

        override fun upsert(
            channel: String,
            epoch: Long,
            frame: ByteArray,
        ) {
            frames[channel to epoch] = frame.copyOf()
        }

        override fun deleteThrough(
            channel: String,
            epoch: Long,
        ) {
            frames.keys.removeAll { (storedChannel, storedEpoch) ->
                storedChannel == channel && storedEpoch <= epoch
            }
        }

        override fun scanAfter(
            channel: String,
            epoch: Long,
        ): List<Pair<Long, ByteArray>> =
            frames
                .filterKeys { (storedChannel, storedEpoch) -> storedChannel == channel && storedEpoch > epoch }
                .map { (key, frame) -> key.second to frame.copyOf() }

        override fun loadCursor(channel: String): Long? = cursors[channel]

        override fun saveCursor(
            channel: String,
            epoch: Long,
        ) {
            cursors[channel] = maxOf(cursors[channel] ?: 0L, epoch)
        }
    }

    /** Spec-relative fixture path, e.g. `crdt-tree/algebra.json` (#lzspecconf). */
    private fun fixture(rel: String) = Json.parseToJsonElement(ConformanceFixtures.read(rel)).jsonObject

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
    fun staleRoomHandleCannotRegressSerializedCursor() {
        val dao = FakeRoomDao()
        val stale = Outbox(RoomStore(dao, "doc"))
        val current = Outbox(RoomStore(dao, "doc"))
        current.ackThrough(9)
        stale.ackThrough(3)
        assertEquals(9, stale.ackedThrough)
        assertEquals(9, Outbox(RoomStore(dao, "doc")).ackedThrough)
    }

    @Test
    fun crdtTreeCanonicalFixtureReplay() {
        val fx = fixture("crdt-tree/algebra.json")
        val mergeScenario =
            ConformanceScenarios.pick(
                "crdt-tree/algebra.json",
                fx,
                "merge_is_order_and_duplication_independent",
            )
        val seed = mergeScenario["seed"]!!.jsonObject
        val base = TextCrdt(seed["peer"]!!.jsonPrimitive.long, seed["text"]!!.jsonPrimitive.content)
        val replicas =
            mergeScenario["replicas"]!!.jsonArray.associate { definitionElement ->
                val definition = definitionElement.jsonObject
                val replica = base.fork(definition["peer"]!!.jsonPrimitive.long)
                replica.insertString(replica.len(), definition["insert"]!!.jsonPrimitive.content)
                definition["name"]!!.jsonPrimitive.content to replica
            }
        val folds =
            mergeScenario["merge_orders"]!!.jsonArray.mapIndexed { index, orderElement ->
                base.fork(100L + index).also { folded ->
                    for (name in orderElement.jsonArray) {
                        folded.mergeFrom(replicas.getValue(name.jsonPrimitive.content))
                    }
                }
            }
        // The `expect` blocks were hand-mirrored by the assertions below and read
        // by nothing, so the fixture could rename or flip a claim and this replay
        // would keep reporting green (#lzassertunknownkeys).
        mergeScenario["expect"]!!.jsonObject.consuming("crdt-tree/algebra.json[merge] expect") { e ->
            e.assertBoolean("texts_equal") { folds.drop(1).all { it.value() == folds.first().value() } }
            e.assertBoolean("version_vectors_equal") {
                folds.drop(1).all { it.versionVector() == folds.first().versionVector() }
            }
        }
        for (folded in folds.drop(1)) {
            assertEquals(folds.first().value(), folded.value())
            assertEquals(folds.first().versionVector(), folded.versionVector())
        }

        val snapshotScenario =
            ConformanceScenarios.pick(
                "crdt-tree/algebra.json",
                fx,
                "empty_frontier_snapshot_preserves_lineage",
            )
        val snapshotSeed = snapshotScenario["seed"]!!.jsonObject
        val canonical =
            TextCrdt(
                snapshotSeed["peer"]!!.jsonPrimitive.long,
                snapshotSeed["text"]!!.jsonPrimitive.content,
            )
        val snapshot = canonical.deltaSince(emptyMap())
        val restored = TextCrdt(snapshotScenario["restore_peer"]!!.jsonPrimitive.long)
        assertTrue(restored.applyDelta(snapshot))
        val restoredText = restored.value()
        val restoredOps = restored.deltaSince(emptyMap())
        canonical.insertString(canonical.len(), "A")
        restored.insertString(restored.len(), "B")
        val duplicates = canonical.len() - (snapshotSeed["text"]!!.jsonPrimitive.content.length + 1)
        canonical.applyDelta(restored.deltaSince(canonical.versionVector()))
        restored.applyDelta(canonical.deltaSince(restored.versionVector()))
        snapshotScenario["expect"]!!.jsonObject.consuming("crdt-tree/algebra.json[snapshot] expect") { e ->
            e.assertBoolean("restored_text_equal") {
                restoredText == snapshotSeed["text"]!!.jsonPrimitive.content
            }
            // Restoring from a snapshot must preserve operation IDENTITY, not
            // merely the rendered text: a restore that re-minted ids would round
            // -trip the same string and then duplicate on the next merge.
            e.assertBoolean("op_ids_equal") { restoredOps == snapshot }
            // ... which is exactly what this counts: the later merge must add no
            // duplicated seed characters.
            e.assertInt("later_merge_duplicates") { duplicates }
            if (e.has("later_merge_duplicates")) {
                assertEquals(canonical.value(), restored.value(), "later merge must converge")
                assertEquals(snapshotSeed["text"]!!.jsonPrimitive.content.length + 2, canonical.len())
            }
        }

        val steadyScenario =
            ConformanceScenarios.pick(
                "crdt-tree/algebra.json",
                fx,
                "own_frontier_emits_empty_delta",
            )
        val steadySeed = steadyScenario["seed"]!!.jsonObject
        val steady = TextCrdt(steadySeed["peer"]!!.jsonPrimitive.long, steadySeed["text"]!!.jsonPrimitive.content)
        val empty = steady.deltaSince(steady.versionVector())
        val changed = steady.applyDelta(empty)
        steadyScenario["expect"]!!.jsonObject.consuming("crdt-tree/algebra.json[steady] expect") { e ->
            e.assertKeyWith("delta") { want ->
                val wantOps = want.jsonArray
                // The corpus only ever pins the EMPTY delta here and this runner
                // has no decoder for a TextCrdt op literal, so refuse a non-empty
                // expectation rather than quietly comparing only its length.
                assertTrue(
                    wantOps.isEmpty(),
                    "delta: a non-empty expected delta is not decodable by this runner",
                )
                assertEquals(wantOps.size, empty.size, "delta")
            }
            e.assertBoolean("apply_changed") { changed }
        }
        assertEquals(emptyList(), empty)
        assertFalse(changed)
    }

    @Test
    fun outboxStoreCanonicalFixtureReplay() {
        val fx = fixture("reliable-sync/outbox_store_protocol.json")
        for (scenario in ConformanceScenarios.of("reliable-sync/outbox_store_protocol.json", fx)) {
            val store = InMemoryStore()
            scenario["save_cursor"]?.jsonArray?.let { writes ->
                val handles = mapOf("stale" to Outbox(store), "current" to Outbox(store))
                for (writeElement in writes) {
                    val write = writeElement.jsonObject
                    handles
                        .getValue(write["handle"]!!.jsonPrimitive.content)
                        .ackThrough(write["epoch"]!!.jsonPrimitive.long)
                }
                scenario["expect"]!!.jsonObject.consuming("outbox_store_protocol.json[save_cursor] expect") { e ->
                    e.assertLong("loaded_cursor") { Outbox(store).ackedThrough }
                }
                return@let
            }
            if (scenario["save_cursor"] != null) continue
            val outbox = Outbox(store)
            for (epochElement in scenario["put_epochs"]!!.jsonArray) {
                val epoch = epochElement.jsonPrimitive.long
                outbox.append(epoch, IpcMessage.ofDelta(Delta(epoch - 1, epoch)))
            }
            val name = scenario["name"]?.jsonPrimitive?.content ?: "?"
            scenario["expect"]!!.jsonObject.consuming("outbox_store_protocol.json[$name] expect") { expected ->
                scenario["scan_after"]?.jsonPrimitive?.long?.let { cursor ->
                    expected.assertKeyWith("epochs") { want ->
                        assertEquals(
                            want.jsonArray.map { it.jsonPrimitive.long },
                            outbox.replayFrom(cursor).map { it.first },
                            "epochs",
                        )
                    }
                }
                for (ack in scenario["ack_through"]?.jsonArray.orEmpty()) outbox.ackThrough(ack.jsonPrimitive.long)
                val observed = if (scenario["restart"]?.jsonPrimitive?.content == "true") Outbox(store) else outbox
                expected.assertLong("cursor") { observed.ackedThrough }
                expected.assertLong("loaded_cursor") { observed.ackedThrough }
                expected.assertKeyWith("retained") { want ->
                    assertEquals(
                        want.jsonArray.map { it.jsonPrimitive.long },
                        observed.retainedEpochs(),
                        "retained",
                    )
                }
                // Both spellings, so whichever the fixture carries is asserted and
                // neither can be read past. `?:` consumed both and asserted one.
                for (key in listOf("replay_from_zero", "replay")) {
                    expected.assertKeyWith(key) { want ->
                        assertEquals(
                            want.jsonArray.map { it.jsonPrimitive.long },
                            observed.replayFrom(0).map { it.first },
                            key,
                        )
                    }
                }
            }
        }
    }
}
