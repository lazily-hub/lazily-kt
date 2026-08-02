package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
 * lazily-kt now implements BOTH halves (`#lzmsgpackseven`): [MsgpackCodec] ships
 * the wire the `msgpack` token actually names, so the fixture is replayed rather
 * than carved out of `scripts/check-conformance-coverage.sh`.
 *
 * Each runner decodes `wire`, RE-ENCODES the decoded message, decodes again, and
 * checks every `expect` key against that second decode. Asserting against the
 * fixture literal would prove nothing: the literal never passed through an
 * encoder.
 *
 * The msgpack runner additionally decodes the produced bytes SCHEMA-LESSLY and
 * pins the external tag plus the sorted field names of the body and of the
 * nested structs. That is the only way to see the named-field rule: a positional
 * encoder round-trips every value correctly and is still non-conforming, so the
 * value assertions alone cannot distinguish it. Those encoded-shape assertions
 * run BEFORE the typed decode, so a positional or field-padded encoder is
 * reported as the encoding defect it is rather than as a decode crash.
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

    /**
     * Guard the fixture-level `assertions` block: the codec's identity and the
     * two distinct senses of "canonical" protocol.md keeps apart (`role` = which
     * encoding is the required interop floor, `byte_canonical` = whether this
     * codec produces one deterministic byte form per message). Unread by
     * anything until the codec runners landed — an unread block is exactly the
     * drift `#lzassertunknownkeys` exists to catch.
     */
    private fun assertFixtureBlock(
        path: String,
        fixture: JsonObject,
        codec: String,
        byteCanonical: Boolean,
        role: String,
    ) {
        assertEquals(codec, fixture.getValue("codec").jsonPrimitive.content, "$path: fixture codec field")
        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("codec") { codec }
        meta.assertBoolean("self_describing") { true }
        meta.assertBoolean("byte_canonical") { byteCanonical }
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertString("role") { role }
        meta.assertInt("scenario_count") { fixture.getValue("scenarios").jsonArray.size }
        meta.excuseKey(
            "note",
            "prose: documents the reference-vs-byte-canonical distinction, states nothing the replay observes",
        )
        meta.requireAllSatisfied()
    }

    /**
     * Field names of an encoded msgpack map, SORTED.
     *
     * A MessagePack map's key order is encoder-defined (§ Frame codecs), so
     * order is not a conformance property — membership is. The cast is the
     * named-field rule itself: a positional encoder lands here with a
     * [kotlinx.serialization.json.JsonArray] and fails, which no assertion over
     * a decoded [IpcMessage] can do.
     */
    private fun sortedFieldNames(encoded: JsonElement): List<String> =
        (
            encoded as? JsonObject
                ?: error("msgpack frames encode structs as named-field maps, not positional arrays: $encoded")
            ).keys.sorted()

    @Test
    fun `json frames round-trip through the reference codec`() {
        val path = "codec/frame_roundtrip_json.json"
        val fixture = loadFixture(path)
        assertFixtureBlock(path, fixture, codec = "json", byteCanonical = true, role = "reference")

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

    @Test
    fun `msgpack frames round-trip through the cross-language binary default`() {
        val path = "codec/frame_roundtrip_msgpack.json"
        val fixture = loadFixture(path)
        assertFixtureBlock(
            path,
            fixture,
            codec = "msgpack",
            byteCanonical = false,
            role = "cross_language_binary_default",
        )

        var replayed = 0
        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val where = scenario.getValue("id").jsonPrimitive.content
            val source = IpcMessage.fromJson(scenario.getValue("wire"))
            assertEquals(
                scenario.getValue("variant").jsonPrimitive.content,
                variantOf(source),
                "$where: fixture variant vs decoded frame",
            )

            val bytes = source.encodeMsgpack()

            // Schema-less view of the bytes actually produced — the only place
            // the named-field rule is observable. Introspected BEFORE the typed
            // decode so a positional encoder fails as an ENCODING defect rather
            // than as a decode crash three frames away from the cause.
            val generic =
                MsgpackCodec.unpack(bytes) as? JsonObject
                    ?: error("$where: IpcMessage is externally tagged — the frame must be a one-entry map")
            assertEquals(1, generic.size, "$where: external tag is a one-entry map")
            val (tag, body) = generic.entries.single()

            val keys = AssertionKeys("$path $where", scenario.getValue("expect").jsonObject)
            keys.assertString("encoded_envelope_key") { tag }
            keys.assertStrings("encoded_body_field_names") { sortedFieldNames(body) }
            when (tag) {
                // `NodeSnapshot.key` is optional and OMITTED when absent in a
                // self-describing codec (§ NodeKey) — the rule that lets a
                // pre-`key` decoder read a post-`key` frame. It has to hold
                // under msgpack exactly as under json, which is why named-field
                // maps and not positional arrays are the wire.
                "Snapshot" ->
                    keys.assertStrings("first_node_encoded_field_names") {
                        sortedFieldNames(body.jsonObject.getValue("nodes").jsonArray[0])
                    }
                // A `CrdtOp` ALWAYS writes `key` (null when unset): an
                // anti-entropy op's addressing is part of its merge identity.
                "CrdtSync" -> {
                    val ops = body.jsonObject.getValue("ops").jsonArray
                    keys.assertStrings("first_op_encoded_field_names") { sortedFieldNames(ops[0]) }
                    keys.assertStrings("second_op_encoded_field_names") { sortedFieldNames(ops[1]) }
                }
                else -> Unit
            }

            // Decode the bytes the encoder produced. The fixture literal is
            // never re-asserted, so a codec that silently drops a field cannot
            // be masked by reading the input back.
            val roundTripped = IpcMessage.decodeMsgpack(bytes)
            keys.assertBoolean("round_trip_equals_source") { roundTripped == source }
            assertValues(keys, roundTripped)
            keys.requireAllSatisfied()
            replayed += 1
        }
        assertEquals(3, replayed, "one scenario per IpcMessage variant")
    }

    /**
     * A byte payload is an ARRAY OF INTEGERS on this wire, never MessagePack
     * `bin` — that is what the reference encoder (`rmp_serde` putting `Vec<u8>`
     * through serde's default seq impl) produces and accepts. Accepting `bin`
     * here would let lazily-kt read frames no conforming peer can produce, which
     * is a private dialect wearing the `msgpack` token. The canonical fixture
     * cannot reach this: it pins decoded values, and no conforming encoder emits
     * `bin`, so the guard needs its own probe or it is unexecuted code.
     */
    @Test
    fun `msgpack decode rejects bin in a byte-payload position`() {
        // { "Delta": ... } is irrelevant here — a bare bin8 value suffices to
        // prove the reader refuses the family rather than tolerating it.
        val bin8 = byteArrayOf(0xc4.toByte(), 0x02, 0x01, 0x02)
        val failure = assertFailsWith<MsgpackCodecException> { MsgpackCodec.unpack(bin8) }
        assertTrue(
            failure.message!!.contains("arrays of integers"),
            "rejection should name the wire rule, got: ${failure.message}",
        )
    }

    /** Named-field maps require string keys; an integer-keyed map is a different wire. */
    @Test
    fun `msgpack decode rejects non-string map keys`() {
        // fixmap(1) { 0: 0 } — the positional-in-disguise shape.
        val intKeyed = byteArrayOf(0x81.toByte(), 0x00, 0x00)
        val failure = assertFailsWith<MsgpackCodecException> { MsgpackCodec.unpack(intKeyed) }
        assertTrue(
            failure.message!!.contains("string keys"),
            "rejection should name the named-field rule, got: ${failure.message}",
        )
    }
}
