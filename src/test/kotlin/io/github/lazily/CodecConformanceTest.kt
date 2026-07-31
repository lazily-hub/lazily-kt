package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Frame-codec round-trip conformance (`#lzmsgpackparity`).
 *
 * protocol.md § Frame codecs makes `json` (the reference codec) and `msgpack`
 * (the cross-language binary default) MUST-level for every binding, and
 * requires every frame to round-trip through both for all three [IpcMessage]
 * variants. That requirement lived only in prose. The four conformance rungs —
 * was the fixture OPENED, were its keys CONSUMED, were they ASSERTED, was every
 * SCENARIO replayed — all reason about fixture *content*, and content replay
 * never exercises a codec, so a binding could carve out a MUST-level codec and
 * stay green on every rung.
 *
 * lazily-kt implements the `json` half. `msgpack` is an explicit carve-out
 * (declared in [InteropPeer] and now in
 * `scripts/check-conformance-coverage.sh`), so
 * `codec/frame_roundtrip_msgpack.json` is listed as known-uncovered rather than
 * silently ignored.
 *
 * The runner decodes `wire`, RE-ENCODES the decoded message, decodes again, and
 * checks every `expect` key against that second decode. Asserting against the
 * fixture literal would prove nothing: the literal never passed through an
 * encoder.
 */
class CodecConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val fixture = json.parseToJsonElement(ConformanceFixtures.read(name)).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        assertEquals("FrameCodecRoundTrip", fixture.getValue("kind").jsonPrimitive.content)
        return fixture
    }

    private fun deltaOpVariant(op: DeltaOp): String =
        when (op) {
            is DeltaOp.CellSet -> "CellSet"
            is DeltaOp.SlotValue -> "SlotValue"
            is DeltaOp.Invalidate -> "Invalidate"
            is DeltaOp.NodeAdd -> "NodeAdd"
            is DeltaOp.NodeRemove -> "NodeRemove"
            is DeltaOp.EdgeAdd -> "EdgeAdd"
            is DeltaOp.EdgeRemove -> "EdgeRemove"
        }

    private fun variantOf(message: IpcMessage): String =
        when (message) {
            is IpcMessage.SnapshotMessage -> "Snapshot"
            is IpcMessage.DeltaMessage -> "Delta"
            is IpcMessage.CrdtSyncMessage -> "CrdtSync"
            else -> error("codec fixture pins no runner for $message")
        }

    /** Compare an int-array assertion key; [AssertionKeys.assertStrings] only covers string lists. */
    private fun AssertionKeys.assertInts(
        key: String,
        actual: () -> List<Int>,
    ) = assertKeyWith(key) { want ->
        assertEquals(want.jsonArray.map { it.jsonPrimitive.int }, actual(), key)
    }

    private fun assertSnapshot(
        keys: AssertionKeys,
        snap: Snapshot,
    ) {
        keys.assertLong("epoch") { snap.epoch }
        keys.assertInt("node_count") { snap.nodes.size }
        keys.assertInt("edge_count") { snap.edges.size }
        keys.assertInt("root_count") { snap.roots.size }
        keys.assertString("first_node_type_tag") { snap.nodes.first().typeTag }
        val payload = snap.nodes.first().state as NodeState.Payload
        keys.assertInts("first_node_payload") { payload.bytes.map { it.toInt() and 0xff } }

        val opaque = snap.nodes.first { it.state is NodeState.Opaque }
        keys.assertLong("opaque_node_id") { opaque.node }
        // The externally-tagged UNIT variant is the shape most likely to decay
        // into `{"Opaque": null}` under a re-encode, so name it rather than
        // infer it.
        keys.assertKeyWith("opaque_node_state_tag") { want ->
            assertEquals(want, opaque.state.toJson(), "opaque_node_state_tag")
        }

        keys.assertInts("first_edge") {
            listOf(snap.edges.first().dependent.toInt(), snap.edges.first().dependency.toInt())
        }
        keys.assertInts("roots") { snap.roots.map { it.toInt() } }
    }

    private fun assertDelta(
        keys: AssertionKeys,
        delta: Delta,
    ) {
        keys.assertLong("base_epoch") { delta.baseEpoch }
        keys.assertLong("epoch") { delta.epoch }
        keys.assertInt("op_count") { delta.ops.size }
        keys.assertStrings("op_variants") { delta.ops.map(::deltaOpVariant) }

        val first = delta.ops.first() as DeltaOp.CellSet
        val inline = first.payload as IpcValue.Inline
        keys.assertInts("first_op_payload") { inline.bytes.map { it.toInt() and 0xff } }

        val nodeAdd = delta.ops.filterIsInstance<DeltaOp.NodeAdd>().first()
        keys.assertString("node_add_type_tag") { nodeAdd.typeTag }
    }

    private fun assertCrdtSync(
        keys: AssertionKeys,
        sync: CrdtSync,
    ) {
        keys.assertInt("frontier_len") { sync.frontier.size }
        keys.assertLong("frontier_first_peer") { sync.frontier.first().first }
        keys.assertLong("frontier_first_stamp_wall_time") { sync.frontier.first().second.wallTime }
        keys.assertInt("op_count") { sync.ops.size }
        keys.assertLong("first_op_node") { sync.ops[0].node }
        // Decoded-value assertion, not an encoding one: both self-describing
        // codecs WRITE `key` for a CrdtOp (null when unset — an anti-entropy
        // op's addressing is part of its merge identity). What must survive the
        // round trip is that the decoder reads that null back as absent.
        keys.assertBoolean("first_op_key_absent") { sync.ops[0].key == null }
        keys.assertLong("second_op_node") { sync.ops[1].node }
        keys.assertString("second_op_key") { sync.ops[1].key?.path }
        keys.assertLong("second_op_stamp_peer") { sync.ops[1].stamp.peer }
    }

    private fun assertValues(
        keys: AssertionKeys,
        message: IpcMessage,
    ) = when (message) {
        is IpcMessage.SnapshotMessage -> assertSnapshot(keys, message.snapshot)
        is IpcMessage.DeltaMessage -> assertDelta(keys, message.delta)
        is IpcMessage.CrdtSyncMessage -> assertCrdtSync(keys, message.sync)
        else -> error("codec fixture pins no runner for $message")
    }

    @Test
    fun `json frames round-trip through the reference codec`() {
        val path = "codec/frame_roundtrip_json.json"
        val fixture = loadFixture(path)
        assertEquals("json", fixture.getValue("codec").jsonPrimitive.content)

        // The fixture-level block pins the codec's identity and the two
        // distinct senses of "canonical" protocol.md keeps apart (`role` = the
        // required interop floor, `byte_canonical` = one deterministic byte
        // form per message).
        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("codec") { "json" }
        meta.assertBoolean("self_describing") { true }
        meta.assertBoolean("byte_canonical") { true }
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertString("role") { "reference" }
        meta.assertInt("scenario_count") { fixture.getValue("scenarios").jsonArray.size }
        meta.excuseKey(
            "note",
            "prose: documents the reference-vs-byte-canonical distinction, states nothing the replay observes",
        )
        meta.requireAllSatisfied()

        var replayed = 0
        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val where = scenario.getValue("id").jsonPrimitive.content
            val source = IpcMessage.fromJson(scenario.getValue("wire"))
            assertEquals(
                scenario.getValue("variant").jsonPrimitive.content,
                variantOf(source),
                "$where: fixture variant vs decoded frame",
            )

            // Encode the DECODED message and decode the result. The fixture
            // literal is never re-asserted, so a codec that silently drops a
            // field cannot be masked by reading the input back.
            val roundTripped = IpcMessage.decodeJson(source.encodeJson())

            val keys = AssertionKeys("$path $where", scenario.getValue("expect").jsonObject)
            keys.assertBoolean("round_trip_equals_source") { roundTripped == source }
            assertValues(keys, roundTripped)
            keys.requireAllSatisfied()
            replayed += 1
        }
        assertEquals(3, replayed, "one scenario per IpcMessage variant")
    }
}
