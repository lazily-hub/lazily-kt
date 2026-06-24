package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
}
