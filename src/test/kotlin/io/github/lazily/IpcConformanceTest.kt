package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IpcConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val specPath = Path.of("../lazily-spec/conformance/$name")
        val text = if (Files.exists(specPath)) {
            Files.readString(specPath)
        } else {
            val resource = javaClass.getResource("/conformance/$name")
                ?: error("missing conformance fixture: $name")
            resource.readText()
        }
        val fixture = json.parseToJsonElement(text).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        return fixture
    }

    private fun parseWire(fixture: JsonObject): IpcMessage =
        IpcMessage.fromJson(fixture.getValue("wire"))

    private fun assertRoundTripJson(message: IpcMessage, fixture: JsonObject) {
        assertEquals(fixture.getValue("wire"), message.toJson())
        assertEquals(message, IpcMessage.decodeJson(message.encodeJson()))
    }

    @Test
    fun `conformance snapshot minimal`() {
        val fixture = loadFixture("snapshot_minimal.json")
        val message = parseWire(fixture)
        val snapshot = assertIs<IpcMessage.SnapshotMessage>(message).snapshot

        assertEquals(1, snapshot.epoch)
        assertEquals(1, snapshot.nodes.size)
        assertEquals(0, snapshot.edges.size)
        assertEquals(1, snapshot.roots.size)
        assertEquals("i32", snapshot.nodes.first().typeTag)
        assertIs<NodeState.Payload>(snapshot.nodes.first().state)

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance snapshot multi node`() {
        val fixture = loadFixture("snapshot_multi_node.json")
        val message = parseWire(fixture)
        val snapshot = assertIs<IpcMessage.SnapshotMessage>(message).snapshot

        assertEquals(7, snapshot.epoch)
        assertEquals(3, snapshot.nodes.size)
        assertEquals(2, snapshot.edges.size)
        assertEquals(2, snapshot.roots.size)

        val opaque = snapshot.nodes.single { it.node == 3L }
        assertIs<NodeState.Opaque>(opaque.state)

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance snapshot shared blob`() {
        val fixture = loadFixture("snapshot_shared_blob.json")
        val message = parseWire(fixture)
        val snapshot = assertIs<IpcMessage.SnapshotMessage>(message).snapshot

        val state = assertIs<NodeState.SharedBlob>(snapshot.nodes.first().state)
        assertEquals(0, state.blob.offset)
        assertEquals(16, state.blob.len)
        assertEquals(9, state.blob.epoch)

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance delta sequential`() {
        val fixture = loadFixture("delta_sequential.json")
        val message = parseWire(fixture)
        val delta = assertIs<IpcMessage.DeltaMessage>(message).delta

        assertEquals(40, delta.baseEpoch)
        assertEquals(41, delta.epoch)
        assertTrue(delta.isNextAfter(40))
        assertFalse(delta.isNextAfter(39))
        assertEquals(7, delta.ops.size)
        assertEquals(
            setOf(
                DeltaOp.CellSet::class,
                DeltaOp.SlotValue::class,
                DeltaOp.Invalidate::class,
                DeltaOp.NodeAdd::class,
                DeltaOp.NodeRemove::class,
                DeltaOp.EdgeAdd::class,
                DeltaOp.EdgeRemove::class,
            ),
            delta.ops.map { it::class }.toSet(),
        )

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance delta non sequential`() {
        val fixture = loadFixture("delta_non_sequential.json")
        val message = parseWire(fixture)
        val delta = assertIs<IpcMessage.DeltaMessage>(message).delta

        assertEquals(12, delta.baseEpoch)
        assertEquals(13, delta.epoch)
        assertTrue(delta.isNextAfter(12))
        assertFalse(delta.isNextAfter(10))

        val status = assertIs<DeltaApplyStatus.ResyncRequired>(delta.applyStatus(10))
        assertEquals(10, status.lastEpoch)
        assertEquals(12, status.baseEpoch)
        assertEquals(13, status.epoch)

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `conformance delta shared blob`() {
        val fixture = loadFixture("delta_shared_blob.json")
        val message = parseWire(fixture)
        val delta = assertIs<IpcMessage.DeltaMessage>(message).delta

        assertEquals(8, delta.baseEpoch)
        assertEquals(9, delta.epoch)
        assertEquals(1, delta.ops.size)

        val op = assertIs<DeltaOp.SlotValue>(delta.ops.first())
        val payload = assertIs<IpcValue.SharedBlob>(op.payload)
        assertEquals(40, payload.blob.offset)
        assertEquals(17, payload.blob.len)
        assertEquals(9, payload.blob.epoch)

        assertRoundTripJson(message, fixture)
    }

    @Test
    fun `all fixtures round trip`() {
        listOf(
            "snapshot_minimal.json",
            "snapshot_multi_node.json",
            "snapshot_shared_blob.json",
            "delta_sequential.json",
            "delta_non_sequential.json",
            "delta_shared_blob.json",
        ).forEach { name ->
            val fixture = loadFixture(name)
            val message = parseWire(fixture)
            assertRoundTripJson(message, fixture)
        }
    }

    @Test
    fun `all fixtures satisfy their assertions metadata`() {
        listOf(
            "snapshot_minimal.json",
            "snapshot_multi_node.json",
            "snapshot_shared_blob.json",
            "delta_sequential.json",
            "delta_non_sequential.json",
            "delta_shared_blob.json",
        ).forEach { name ->
            val fixture = loadFixture(name)
            val message = parseWire(fixture)
            assertAssertions(message, fixture)
        }
    }

    private fun variantName(state: NodeState): String = when (state) {
        is NodeState.Payload -> "Payload"
        is NodeState.SharedBlob -> "SharedBlob"
        NodeState.Opaque -> "Opaque"
    }

    private fun variantName(op: DeltaOp): String = when (op) {
        is DeltaOp.CellSet -> "CellSet"
        is DeltaOp.SlotValue -> "SlotValue"
        is DeltaOp.Invalidate -> "Invalidate"
        is DeltaOp.NodeAdd -> "NodeAdd"
        is DeltaOp.NodeRemove -> "NodeRemove"
        is DeltaOp.EdgeAdd -> "EdgeAdd"
        is DeltaOp.EdgeRemove -> "EdgeRemove"
    }

    private fun JsonObject.assertionString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.assertionLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.jsonPrimitive?.long

    /** Generically validate a fixture's `assertions` metadata against the parsed message,
     *  so fixture drift (wrong counts, missing variants) is caught, not only the `wire` round-trip. */
    private fun assertAssertions(message: IpcMessage, fixture: JsonObject) {
        val assertions = (fixture["assertions"] as? JsonObject) ?: return
        when (message) {
            is IpcMessage.SnapshotMessage -> {
                val snapshot = message.snapshot
                assertions.assertionLong("epoch")?.let { assertEquals(it, snapshot.epoch, "epoch") }
                assertions.assertionLong("node_count")?.let { assertEquals(it, snapshot.nodes.size.toLong(), "node_count") }
                assertions.assertionLong("edge_count")?.let { assertEquals(it, snapshot.edges.size.toLong(), "edge_count") }
                assertions.assertionLong("root_count")?.let { assertEquals(it, snapshot.roots.size.toLong(), "root_count") }
                assertions.assertionString("first_node_type_tag")?.let {
                    assertEquals(it, snapshot.nodes.first().typeTag, "first_node_type_tag")
                }
                assertions.assertionString("first_node_state_kind")?.let { expected ->
                    assertEquals(expected, variantName(snapshot.nodes.first().state), "first_node_state_kind")
                }
                assertions.assertionString("has_opaque_node")?.let {
                    val has = snapshot.nodes.any { it.state is NodeState.Opaque }
                    assertEquals(it == "true", has, "has_opaque_node")
                }
                assertions.assertionLong("opaque_node_id")?.let { id ->
                    val node = snapshot.nodes.single { it.node == id }
                    assertIs<NodeState.Opaque>(node.state, "opaque_node_id $id state")
                }
                val sharedBlob = snapshot.nodes.firstNotNullOfOrNull { it.state as? NodeState.SharedBlob }
                if (sharedBlob != null) {
                    assertions.assertionLong("blob_offset")?.let { assertEquals(it, sharedBlob.blob.offset, "blob_offset") }
                    assertions.assertionLong("blob_len")?.let { assertEquals(it, sharedBlob.blob.len, "blob_len") }
                    assertions.assertionLong("blob_epoch")?.let { assertEquals(it, sharedBlob.blob.epoch, "blob_epoch") }
                }
            }
            is IpcMessage.DeltaMessage -> {
                val delta = message.delta
                assertions.assertionLong("base_epoch")?.let { assertEquals(it, delta.baseEpoch, "base_epoch") }
                assertions.assertionLong("epoch")?.let { assertEquals(it, delta.epoch, "epoch") }
                assertions.assertionLong("op_count")?.let { assertEquals(it, delta.ops.size.toLong(), "op_count") }
                assertions.assertionString("is_sequential")?.let {
                    assertEquals(it == "true", delta.epoch == delta.baseEpoch + 1, "is_sequential")
                }
                assertions.assertionString("has_all_op_variants")?.let {
                    if (it == "true") {
                        val allVariants = setOf(
                            "CellSet", "SlotValue", "Invalidate",
                            "NodeAdd", "NodeRemove", "EdgeAdd", "EdgeRemove",
                        )
                        assertEquals(allVariants, delta.ops.map { variantName(it) }.toSet(), "has_all_op_variants")
                    }
                }
                assertions.assertionString("first_op_kind")?.let {
                    assertEquals(it, variantName(delta.ops.first()), "first_op_kind")
                }
                assertions.assertionString("first_op_payload_kind")?.let { expected ->
                    val first = delta.ops.first()
                    val payload = when (first) {
                        is DeltaOp.CellSet -> first.payload
                        is DeltaOp.SlotValue -> first.payload
                        else -> error("first_op_payload_kind only valid for payload-bearing ops, got ${variantName(first)}")
                    }
                    val actual = when (payload) {
                        is IpcValue.Inline -> "Inline"
                        is IpcValue.SharedBlob -> "SharedBlob"
                    }
                    assertEquals(expected, actual, "first_op_payload_kind")
                }
                assertions.entries.firstOrNull { it.key.startsWith("resync_after_epoch_") }?.let { (key) ->
                    val lastEpoch = key.removePrefix("resync_after_epoch_").toLong()
                    assertIs<DeltaApplyStatus.ResyncRequired>(delta.applyStatus(lastEpoch), "$key for lastEpoch=$lastEpoch")
                }
            }
            is IpcMessage.CrdtSyncMessage -> {
                val sync = message.sync
                assertions.assertionLong("frontier_count")?.let {
                    assertEquals(it, sync.frontier.size.toLong(), "frontier_count")
                }
                assertions.assertionLong("op_count")?.let {
                    assertEquals(it, sync.ops.size.toLong(), "op_count")
                }
            }
            // Reliable-sync control frames carry no assertion metadata here.
            is IpcMessage.ResyncRequestMessage, is IpcMessage.OutboxAckMessage -> {}
        }
    }
}
