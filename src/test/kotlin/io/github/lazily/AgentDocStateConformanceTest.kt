package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Conformance replay for the agent-doc state-projection IPC fixtures under
 * `lazily-spec/conformance/agent-doc/`.
 *
 * These are ordinary lazily-spec `Snapshot` / `Delta` IPC messages whose nodes
 * carry the pinned agent-doc `type_tag` vocabulary (see the `agent-doc-state`
 * schema in lazily-spec).
 * Replaying them here locks the cross-language contract named in that schema:
 * kt / js / rs mirrors address the same nodes and decode the same phases. Each
 * fixture is decoded through [IpcMessage.fromJson], round-tripped byte-for-byte,
 * and its `assertions` (counts, type-tag vocabulary membership, and decoded
 * payload phases) are validated.
 */
class AgentDocStateConformanceTest {
    private val json = Json

    private val schemaPath: Path = ConformanceFixtures.root.resolveSibling("schemas/agent-doc-state.json")

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("agent-doc/$name")
        val fixture = json.parseToJsonElement(text).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        return fixture
    }

    /**
     * The `type_tag` vocabulary, read from the canonical lazily-spec schema so
     * adding or removing a node kind is a visible failure. There is deliberately
     * no pinned in-source fallback: a hardcoded copy silently masks schema drift
     * exactly the way the bundled fixtures did (#lzspecconf).
     */
    private fun typeTagVocabulary(): Set<String> {
        check(Files.exists(schemaPath)) {
            "canonical agent-doc-state schema missing at ${schemaPath.toAbsolutePath()} — " +
                "clone lazily-spec as a sibling or set LAZILY_SPEC_DIR"
        }
        val schema = json.parseToJsonElement(Files.readString(schemaPath)).jsonObject
        val enum =
            schema
                .getValue("\$defs")
                .jsonObject
                .getValue("TypeTag")
                .jsonObject
                .getValue("enum")
                .jsonArray
        return enum.map { it.jsonPrimitive.content }.toSet()
    }

    private fun parseWire(fixture: JsonObject): IpcMessage = IpcMessage.fromJson(fixture.getValue("wire"))

    private fun assertRoundTripJson(
        message: IpcMessage,
        fixture: JsonObject,
    ) {
        assertEquals(fixture.getValue("wire"), message.toJson())
        assertEquals(message, IpcMessage.decodeJson(message.encodeJson()))
    }

    /** Decode a `Payload`/`Inline` byte payload (`serde_json(struct)` bytes) as a JSON object. */
    private fun decodePayloadObject(bytes: ByteArray): JsonObject = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject

    private fun payloadPhase(obj: JsonObject): String? = (obj["phase"] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `conformance agent-doc snapshot decodes round-trips and satisfies assertions`() {
        val fixture = loadFixture("snapshot_agent_doc_state.json")
        assertEquals("Snapshot", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("1.0.0", fixture.getValue("schema_version").jsonPrimitive.content)

        val message = parseWire(fixture)
        val snapshot = assertIs<IpcMessage.SnapshotMessage>(message).snapshot
        val vocab = typeTagVocabulary()

        fixture
            .getValue("assertions")
            .jsonObject
            .consuming("agent-doc/snapshot_agent_doc_state.json assertions") { assertions ->
                // Structural assertions.
                assertions.assertLong("epoch") { snapshot.epoch }
                assertions.assertLong("node_count") { snapshot.nodes.size.toLong() }
                assertions.assertLong("edge_count") { snapshot.edges.size.toLong() }
                assertions.assertLong("root_count") { snapshot.roots.size.toLong() }

                // type_tag vocabulary: the snapshot's tags match the asserted list and every tag is in the vocabulary.
                val actualTags = snapshot.nodes.map { it.typeTag }.toSet()
                assertions.assertKeyWith("type_tags") { want ->
                    assertEquals(want.jsonArray.map { it.jsonPrimitive.content }.toSet(), actualTags, "type_tags")
                }
                assertions.assertBoolean("all_type_tags_in_vocabulary") { actualTags.all { tag -> tag in vocab } }

                // Decoded payload phases: closeout.cycle and queue.head carry a `phase`.
                assertions.assertString("cycle_phase") {
                    val cycle = snapshot.nodes.single { it.typeTag == "agent_doc.closeout.cycle" }
                    payloadPhase(decodePayloadObject((cycle.state as NodeState.Payload).bytes))
                }
                assertions.assertString("queue_head_phase") {
                    val queueHead = snapshot.nodes.single { it.typeTag == "agent_doc.queue.head" }
                    payloadPhase(decodePayloadObject((queueHead.state as NodeState.Payload).bytes))
                }
            }

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance agent-doc delta decodes round-trips and satisfies assertions`() {
        val fixture = loadFixture("delta_agent_doc_state.json")
        assertEquals("Delta", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("1.0.0", fixture.getValue("schema_version").jsonPrimitive.content)

        val message = parseWire(fixture)
        val delta = assertIs<IpcMessage.DeltaMessage>(message).delta
        val vocab = typeTagVocabulary()

        fixture
            .getValue("assertions")
            .jsonObject
            .consuming("agent-doc/delta_agent_doc_state.json assertions") { assertions ->
                // Structural assertions.
                assertions.assertLong("base_epoch") { delta.baseEpoch }
                assertions.assertLong("epoch") { delta.epoch }
                assertions.assertLong("op_count") { delta.ops.size.toLong() }

                // added_type_tags: every NodeAdd in the delta introduces a vocabulary tag.
                val addedTags =
                    delta.ops
                        .filterIsInstance<DeltaOp.NodeAdd>()
                        .map { it.typeTag }
                        .toSet()
                assertions.assertKeyWith("added_type_tags") { want ->
                    assertEquals(want.jsonArray.map { it.jsonPrimitive.content }.toSet(), addedTags, "added_type_tags")
                }
                assertions.assertBoolean("all_type_tags_in_vocabulary") {
                    val allTags = addedTags + delta.ops.filterIsInstance<DeltaOp.NodeAdd>().map { it.typeTag }
                    allTags.all { tag -> tag in vocab }
                }

                // Decoded payload phases after applying the delta's CellSet ops.
                assertions.assertString("cycle_phase_after") {
                    delta.ops
                        .filterIsInstance<DeltaOp.CellSet>()
                        .single { it.node == 102L }
                        .let { payloadPhase(decodePayloadObject((it.payload as IpcValue.Inline).bytes)) }
                }
                assertions.assertString("queue_head_phase_after") {
                    delta.ops
                        .filterIsInstance<DeltaOp.CellSet>()
                        .single { it.node == 103L }
                        .let { payloadPhase(decodePayloadObject((it.payload as IpcValue.Inline).bytes)) }
                }

                // The delta applies on top of the snapshot's epoch (base_epoch 3 → epoch 6, a coalesced jump).
                assertTrue(delta.epoch > delta.baseEpoch)
                assertFalse(delta.isNextAfter(delta.baseEpoch))
            }

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `agent-doc snapshot then delta mirrors identically to a fresh snapshot`() {
        // The defining IPC property: applying the snapshot then the delta's ops
        // produces the same node view as a fresh snapshot of the resulting state.
        val snapshotFixture = loadFixture("snapshot_agent_doc_state.json")
        val deltaFixture = loadFixture("delta_agent_doc_state.json")
        val snapshot = (parseWire(snapshotFixture) as IpcMessage.SnapshotMessage).snapshot
        val delta = (parseWire(deltaFixture) as IpcMessage.DeltaMessage).delta

        val nodes = sortedMapOf<Long, NodeSnapshot>()
        for (node in snapshot.nodes) nodes[node.node] = node
        val edges = (snapshot.edges.map { it.dependent to it.dependency }).toMutableSet()

        for (op in delta.ops) {
            when (op) {
                is DeltaOp.CellSet -> {
                    val existing = nodes[op.node]
                    if (existing != null) {
                        val bytes = (op.payload as IpcValue.Inline).bytes
                        nodes[op.node] = existing.copy(state = NodeState.Payload(bytes))
                    }
                }
                is DeltaOp.NodeAdd -> nodes[op.node] = NodeSnapshot(op.node, op.typeTag, op.state, op.key)
                is DeltaOp.NodeRemove -> nodes.remove(op.node)
                is DeltaOp.EdgeAdd -> edges.add(op.dependent to op.dependency)
                is DeltaOp.EdgeRemove -> edges.remove(op.dependent to op.dependency)
                is DeltaOp.SlotValue, is DeltaOp.Invalidate -> Unit
            }
        }

        // After applying the delta: 3 original + 1 added transport.patch node.
        assertEquals(4, nodes.size)
        assertTrue(nodes.values.all { it.typeTag in typeTagVocabulary() })

        val cycle = nodes.getValue(102L)
        assertEquals("agent_doc.closeout.cycle", cycle.typeTag)
        assertEquals("committed", payloadPhase(decodePayloadObject((cycle.state as NodeState.Payload).bytes)))

        val queue = nodes.getValue(103L)
        assertEquals("agent_doc.queue.head", queue.typeTag)
        assertEquals("completed", payloadPhase(decodePayloadObject((queue.state as NodeState.Payload).bytes)))

        val patch = nodes.getValue(104L)
        assertEquals("agent_doc.transport.patch", patch.typeTag)
        assertEquals("applied", payloadPhase(decodePayloadObject((patch.state as NodeState.Payload).bytes)))
    }

    @Test
    fun `GraphView folds the native fixtures to the same canonical projection`() {
        // Pin the generic GraphView (`#lzsync` 3B clean split) to the SAME canonical
        // native fixtures the hand-fold above uses: folding the native Snapshot then Delta
        // must reach the identical node projection. This is what agent-doc's plugin
        // projection now reads instead of the bespoke base64 WireDelta mirror.
        val snapshot = (parseWire(loadFixture("snapshot_agent_doc_state.json")) as IpcMessage.SnapshotMessage).snapshot
        val delta = (parseWire(loadFixture("delta_agent_doc_state.json")) as IpcMessage.DeltaMessage).delta

        val replica = GraphView()
        replica.applySnapshot(snapshot)
        assertEquals(3, replica.nodeCount)
        assertEquals(3L, replica.epoch)

        replica.applyDelta(delta)
        assertEquals(4, replica.nodeCount)
        assertEquals(6L, replica.epoch)

        fun phaseOf(id: Long): String? = payloadPhase(decodePayloadObject(replica.node(id)!!.payload!!))

        assertEquals("agent_doc.closeout.cycle", replica.node(102L)!!.typeTag)
        assertEquals("committed", phaseOf(102L))
        assertEquals("agent_doc.queue.head", replica.node(103L)!!.typeTag)
        assertEquals("completed", phaseOf(103L))
        assertEquals("agent_doc.transport.patch", replica.node(104L)!!.typeTag)
        assertEquals("applied", phaseOf(104L))
    }
}
