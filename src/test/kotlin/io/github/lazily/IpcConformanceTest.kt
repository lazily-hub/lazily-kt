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
        val text = ConformanceFixtures.read("$name")
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
            "delta_zero_copy_arrow.json",
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
            "delta_zero_copy_arrow.json",
        ).forEach { name ->
            val fixture = loadFixture(name)
            val message = parseWire(fixture)
            assertAssertions(name, message, fixture)
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

    /**
     * Generically validate a fixture's `assertions` metadata against the parsed
     * message, so fixture drift (wrong counts, missing variants) is caught, not
     * only the `wire` round-trip.
     *
     * Every arm reads through [AssertionKeys], and the block closes by refusing
     * any key it did not consume (`#lzassertunknownkeys`). Named-key reads alone
     * are fail-open: `delta_zero_copy_arrow`'s `first_op_payload_backend` was
     * carried by the fixture and read by nothing, so the fixture round-tripped
     * and "passed" while never checking the one field it exists to validate.
     * Adding that arm fixed the instance; refusing unconsumed keys fixes the
     * property.
     */
    private fun assertAssertions(fixtureName: String, message: IpcMessage, fixture: JsonObject) {
        val assertions = (fixture["assertions"] as? JsonObject) ?: return
        assertions.consuming("$fixtureName assertions") { a ->
            when (message) {
                is IpcMessage.SnapshotMessage -> {
                    val snapshot = message.snapshot
                    a.long("epoch")?.let { assertEquals(it, snapshot.epoch, "epoch") }
                    a.long("node_count")?.let { assertEquals(it, snapshot.nodes.size.toLong(), "node_count") }
                    a.long("edge_count")?.let { assertEquals(it, snapshot.edges.size.toLong(), "edge_count") }
                    a.long("root_count")?.let { assertEquals(it, snapshot.roots.size.toLong(), "root_count") }
                    a.string("first_node_type_tag")?.let {
                        assertEquals(it, snapshot.nodes.first().typeTag, "first_node_type_tag")
                    }
                    a.string("first_node_state_kind")?.let { expected ->
                        assertEquals(expected, variantName(snapshot.nodes.first().state), "first_node_state_kind")
                    }
                    a.boolean("has_opaque_node")?.let {
                        val has = snapshot.nodes.any { it.state is NodeState.Opaque }
                        assertEquals(it, has, "has_opaque_node")
                    }
                    a.long("opaque_node_id")?.let { id ->
                        val node = snapshot.nodes.single { it.node == id }
                        assertIs<NodeState.Opaque>(node.state, "opaque_node_id $id state")
                    }
                    // A blob assertion with no SharedBlob node to evaluate it
                    // against is a skipped assertion, not a satisfied one — the
                    // conditional used to swallow the whole group.
                    if (a.has("blob_offset") || a.has("blob_len") || a.has("blob_epoch")) {
                        val sharedBlob = snapshot.nodes.firstNotNullOfOrNull { it.state as? NodeState.SharedBlob }
                            ?: error("$fixtureName: blob_* assertions need a SharedBlob node, snapshot has none")
                        a.long("blob_offset")?.let { assertEquals(it, sharedBlob.blob.offset, "blob_offset") }
                        a.long("blob_len")?.let { assertEquals(it, sharedBlob.blob.len, "blob_len") }
                        a.long("blob_epoch")?.let { assertEquals(it, sharedBlob.blob.epoch, "blob_epoch") }
                    }
                }
                is IpcMessage.DeltaMessage -> {
                    val delta = message.delta
                    a.long("base_epoch")?.let { assertEquals(it, delta.baseEpoch, "base_epoch") }
                    a.long("epoch")?.let { assertEquals(it, delta.epoch, "epoch") }
                    a.long("op_count")?.let { assertEquals(it, delta.ops.size.toLong(), "op_count") }
                    a.boolean("is_sequential")?.let {
                        assertEquals(it, delta.epoch == delta.baseEpoch + 1, "is_sequential")
                    }
                    a.boolean("has_all_op_variants")?.let {
                        if (it) {
                            val allVariants = setOf(
                                "CellSet", "SlotValue", "Invalidate",
                                "NodeAdd", "NodeRemove", "EdgeAdd", "EdgeRemove",
                            )
                            assertEquals(allVariants, delta.ops.map { variantName(it) }.toSet(), "has_all_op_variants")
                        }
                    }
                    a.string("first_op_kind")?.let {
                        assertEquals(it, variantName(delta.ops.first()), "first_op_kind")
                    }
                    a.string("first_op_payload_kind")?.let { expected ->
                        val actual = when (firstPayload(delta, "first_op_payload_kind")) {
                            is IpcValue.Inline -> "Inline"
                            is IpcValue.SharedBlob -> "SharedBlob"
                        }
                        assertEquals(expected, actual, "first_op_payload_kind")
                    }
                    // The pluggable blob-backend discriminator. Without this arm the
                    // arrow fixture would round-trip and "pass" while never checking
                    // the one field it exists to validate — a replay that asserts
                    // nothing is the failure mode this corpus is meant to prevent.
                    a.string("first_op_payload_backend")?.let { expected ->
                        val payload = firstPayload(delta, "first_op_payload_backend")
                        val blob = assertIs<IpcValue.SharedBlob>(payload, "first_op_payload_backend needs a SharedBlob payload")
                        assertEquals(expected, blob.blob.backend.wire, "first_op_payload_backend")
                    }
                    // `resync_after_epoch_<N>` parameterises the epoch in the key
                    // itself, so the suffix is data, not a distinct assertion.
                    for ((key, _) in a.withPrefix("resync_after_epoch_")) {
                        val lastEpoch = key.removePrefix("resync_after_epoch_").toLong()
                        assertIs<DeltaApplyStatus.ResyncRequired>(delta.applyStatus(lastEpoch), "$key for lastEpoch=$lastEpoch")
                    }
                }
                is IpcMessage.CrdtSyncMessage -> {
                    val sync = message.sync
                    a.long("frontier_count")?.let {
                        assertEquals(it, sync.frontier.size.toLong(), "frontier_count")
                    }
                    a.long("op_count")?.let {
                        assertEquals(it, sync.ops.size.toLong(), "op_count")
                    }
                }
                // Reliable-sync control frames carry no assertion metadata here;
                // `requireAllConsumed` turns "carries none" into a checked claim.
                is IpcMessage.ResyncRequestMessage, is IpcMessage.OutboxAckMessage -> {}
            }
        }
    }

    private fun firstPayload(delta: Delta, key: String): IpcValue = when (val first = delta.ops.first()) {
        is DeltaOp.CellSet -> first.payload
        is DeltaOp.SlotValue -> first.payload
        else -> error("$key only valid for payload-bearing ops, got ${variantName(first)}")
    }
}
