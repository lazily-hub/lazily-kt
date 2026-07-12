package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

    private val conformanceDir: Path = Path.of("../lazily-spec/conformance/agent-doc")
    private val schemaPath: Path = Path.of("../lazily-spec/schemas/agent-doc-state.json")

    private fun loadFixture(name: String): JsonObject {
        val specPath = conformanceDir.resolve(name)
        val text = if (Files.exists(specPath)) {
            Files.readString(specPath)
        } else {
            val resource = javaClass.getResource("/conformance/agent-doc/$name")
                ?: error("missing agent-doc conformance fixture: $name")
            resource.readText()
        }
        val fixture = json.parseToJsonElement(text).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        return fixture
    }

    /**
     * The pinned eight-value `type_tag` vocabulary. Loaded from the canonical
     * schema when the spec checkout is alongside (so adding a node kind is a
     * visible failure), with the pinned 1.0.0 set as the resource fallback.
     */
    private fun typeTagVocabulary(): Set<String> {
        if (Files.exists(schemaPath)) {
            val schema = json.parseToJsonElement(Files.readString(schemaPath)).jsonObject
            val enum = schema
                .getValue("\$defs").jsonObject
                .getValue("TypeTag").jsonObject
                .getValue("enum").jsonArray
            return enum.map { it.jsonPrimitive.content }.toSet()
        }
        return setOf(
            "agent_doc.document.baseline",
            "agent_doc.queue",
            "agent_doc.queue.head",
            "agent_doc.closeout.cycle",
            "agent_doc.transport.patch",
            "agent_doc.supervisor.owner",
            "agent_doc.route",
            "agent_doc.proof.marker",
        )
    }

    private fun parseWire(fixture: JsonObject): IpcMessage =
        IpcMessage.fromJson(fixture.getValue("wire"))

    private fun assertRoundTripJson(message: IpcMessage, fixture: JsonObject) {
        assertEquals(fixture.getValue("wire"), message.toJson())
        assertEquals(message, IpcMessage.decodeJson(message.encodeJson()))
    }

    private fun JsonObject.assertionString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.assertionLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.jsonPrimitive?.long

    private fun JsonObject.assertionBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.boolean

    private fun JsonObject.assertionStringList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    /** Decode a `Payload`/`Inline` byte list (`serde_json(struct)` bytes) as a JSON object. */
    private fun decodePayloadObject(bytes: List<Int>): JsonObject {
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        return json.parseToJsonElement(String(arr, Charsets.UTF_8)).jsonObject
    }

    private fun payloadPhase(obj: JsonObject): String? =
        (obj["phase"] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `conformance agent-doc snapshot decodes round-trips and satisfies assertions`() {
        val fixture = loadFixture("snapshot_agent_doc_state.json")
        assertEquals("Snapshot", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("1.0.0", fixture.getValue("schema_version").jsonPrimitive.content)

        val message = parseWire(fixture)
        val snapshot = assertIs<IpcMessage.SnapshotMessage>(message).snapshot
        val assertions = fixture.getValue("assertions").jsonObject
        val vocab = typeTagVocabulary()

        // Structural assertions.
        assertions.assertionLong("epoch")?.let { assertEquals(it, snapshot.epoch, "epoch") }
        assertions.assertionLong("node_count")?.let { assertEquals(it, snapshot.nodes.size.toLong(), "node_count") }
        assertions.assertionLong("edge_count")?.let { assertEquals(it, snapshot.edges.size.toLong(), "edge_count") }
        assertions.assertionLong("root_count")?.let { assertEquals(it, snapshot.roots.size.toLong(), "root_count") }

        // type_tag vocabulary: the snapshot's tags match the asserted list and every tag is in the vocabulary.
        val expectedTags = assertions.assertionStringList("type_tags").toSet()
        val actualTags = snapshot.nodes.map { it.typeTag }.toSet()
        assertEquals(expectedTags, actualTags, "type_tags")
        assertions.assertionBoolean("all_type_tags_in_vocabulary")?.let {
            assertEquals(it, actualTags.all { tag -> tag in vocab }, "all_type_tags_in_vocabulary")
        }

        // Decoded payload phases: closeout.cycle and queue.head carry a `phase`.
        val cycle = snapshot.nodes.single { it.typeTag == "agent_doc.closeout.cycle" }
        val cyclePhase = payloadPhase(decodePayloadObject((cycle.state as NodeState.Payload).bytes))
        assertions.assertionString("cycle_phase")?.let { assertEquals(it, cyclePhase, "cycle_phase") }

        val queueHead = snapshot.nodes.single { it.typeTag == "agent_doc.queue.head" }
        val queuePhase = payloadPhase(decodePayloadObject((queueHead.state as NodeState.Payload).bytes))
        assertions.assertionString("queue_head_phase")?.let { assertEquals(it, queuePhase, "queue_head_phase") }

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance agent-doc delta decodes round-trips and satisfies assertions`() {
        val fixture = loadFixture("delta_agent_doc_state.json")
        assertEquals("Delta", fixture.getValue("kind").jsonPrimitive.content)
        assertEquals("1.0.0", fixture.getValue("schema_version").jsonPrimitive.content)

        val message = parseWire(fixture)
        val delta = assertIs<IpcMessage.DeltaMessage>(message).delta
        val assertions = fixture.getValue("assertions").jsonObject
        val vocab = typeTagVocabulary()

        // Structural assertions.
        assertions.assertionLong("base_epoch")?.let { assertEquals(it, delta.baseEpoch, "base_epoch") }
        assertions.assertionLong("epoch")?.let { assertEquals(it, delta.epoch, "epoch") }
        assertions.assertionLong("op_count")?.let { assertEquals(it, delta.ops.size.toLong(), "op_count") }

        // added_type_tags: every NodeAdd in the delta introduces a vocabulary tag.
        val addedTags = delta.ops
            .filterIsInstance<DeltaOp.NodeAdd>()
            .map { it.typeTag }
            .toSet()
        val expectedAdded = assertions.assertionStringList("added_type_tags").toSet()
        assertEquals(expectedAdded, addedTags, "added_type_tags")
        assertions.assertionBoolean("all_type_tags_in_vocabulary")?.let {
            val allTags = addedTags + delta.ops.filterIsInstance<DeltaOp.NodeAdd>().map { it.typeTag }
            assertEquals(it, allTags.all { tag -> tag in vocab }, "all_type_tags_in_vocabulary")
        }

        // Decoded payload phases after applying the delta's CellSet ops.
        val cycleAfter = delta.ops
            .filterIsInstance<DeltaOp.CellSet>()
            .single { it.node == 102L }
            .let { payloadPhase(decodePayloadObject((it.payload as IpcValue.Inline).bytes)) }
        assertions.assertionString("cycle_phase_after")?.let { assertEquals(it, cycleAfter, "cycle_phase_after") }

        val queueAfter = delta.ops
            .filterIsInstance<DeltaOp.CellSet>()
            .single { it.node == 103L }
            .let { payloadPhase(decodePayloadObject((it.payload as IpcValue.Inline).bytes)) }
        assertions.assertionString("queue_head_phase_after")?.let { assertEquals(it, queueAfter, "queue_head_phase_after") }

        // The delta applies on top of the snapshot's epoch (base_epoch 3 → epoch 6, a coalesced jump).
        assertTrue(delta.epoch > delta.baseEpoch)
        assertFalse(delta.isNextAfter(assertions.assertionLong("base_epoch") ?: delta.baseEpoch))

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

        fun phaseOf(id: Long): String? =
            payloadPhase(decodePayloadObject(replica.node(id)!!.payload!!.map { it.toInt() and 0xff }))

        assertEquals("agent_doc.closeout.cycle", replica.node(102L)!!.typeTag)
        assertEquals("committed", phaseOf(102L))
        assertEquals("agent_doc.queue.head", replica.node(103L)!!.typeTag)
        assertEquals("completed", phaseOf(103L))
        assertEquals("agent_doc.transport.patch", replica.node(104L)!!.typeTag)
        assertEquals("applied", phaseOf(104L))
    }
}
