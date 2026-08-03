package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `NodeKey` null-leniency on decode (`#lzkeynullstrict`).
 *
 * protocol.md § NodeKey said a self-describing codec OMITS an absent `key`, and
 * that a decoder seeing no `key` field treats it as absent. That settled the
 * omitted form and left an explicit `key: null` undefined — and three bindings
 * diverged there. The clause is now explicit: omit-when-absent binds the
 * ENCODER, and a decoder MUST accept both forms as absent, refusing neither and
 * **constructing a key from neither.**
 *
 * That last clause exists because of lazily-kt. It did not refuse the null form;
 * it did something worse. `JsonNull` IS a [kotlinx.serialization.json.JsonPrimitive]
 * and its `content` is the string `"null"`, so `(obj["key"] as? JsonPrimitive)?.let { … }`
 * matched, and the node decoded carrying a real, wire-stable key literally named
 * `null` — which then re-encoded as `"key": "null"` and would have addressed a
 * phantom collection entry on the peer. A refusal-only rule would have called
 * that conforming.
 *
 * Note where this binding was already right: [CrdtOp] tested `is JsonNull`
 * explicitly, in the same file, because a `CrdtOp` ALWAYS writes `key: null`
 * when unset. Every binding that got `NodeSnapshot`/`NodeAdd` wrong had `CrdtOp`
 * right.
 *
 * The runner checks both halves. Reading the null form as absent is only half
 * the rule — a binding that writes it straight back out has a correct decoded
 * value and a non-conforming encoder — so each scenario re-encodes under its own
 * codec and inspects the produced frame's field set.
 */
class NodeKeyNullLeniencyConformanceTest {
    private val json = Json

    private val path = "codec/nodekey_null_leniency.json"

    private fun loadFixture(): JsonObject {
        val fixture = json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        assertEquals("NodeKeyNullLeniency", fixture.getValue("kind").jsonPrimitive.content)
        return fixture
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string has an odd length" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun decode(scenario: JsonObject): IpcMessage =
        when (val codec = scenario.getValue("codec").jsonPrimitive.content) {
            "json" -> IpcMessage.decodeJson(scenario.getValue("wire_json").jsonPrimitive.content)
            "msgpack" ->
                IpcMessage.decodeMsgpack(
                    hexToBytes(scenario.getValue("wire_msgpack_hex").jsonPrimitive.content),
                )
            else -> error("unknown codec: $codec")
        }

    /**
     * Re-encode under the scenario's own codec and read the field set back off
     * the WIRE tree, not off the typed object — a typed object cannot
     * distinguish "field absent" from "field present and null", which is the
     * whole distinction under test.
     */
    private fun reencodedNode(scenario: JsonObject, message: IpcMessage): JsonObject {
        val wire =
            if (scenario.getValue("codec").jsonPrimitive.content == "msgpack") {
                // Through the msgpack codec specifically. Both codecs derive from
                // the same `toJson()` tree, but that is worth proving rather than
                // assuming: the `#lzmsgpackparity` defect was a msgpack encoder
                // writing `key: null` while json omitted it.
                MsgpackCodec.unpack(message.encodeMsgpack()).jsonObject
            } else {
                message.toJson()
            }
        return if (scenario.getValue("field").jsonPrimitive.content == "snapshot") {
            wire.getValue("Snapshot").jsonObject.getValue("nodes").jsonArray[0].jsonObject
        } else {
            wire
                .getValue("Delta")
                .jsonObject
                .getValue("ops")
                .jsonArray[0]
                .jsonObject
                .getValue("NodeAdd")
                .jsonObject
        }
    }

    private fun decodedKey(scenario: JsonObject, message: IpcMessage): String? =
        if (scenario.getValue("field").jsonPrimitive.content == "snapshot") {
            (message as IpcMessage.SnapshotMessage).snapshot.nodes[0].key?.path
        } else {
            ((message as IpcMessage.DeltaMessage).delta.ops[0] as DeltaOp.NodeAdd).key?.path
        }

    @Test
    fun `NodeKey null-leniency - both wire forms decode as absent, the encoder still omits`() =
        proseScope(path).use { replayFixture() }

    /**
     * The replay, inside the prose scope that arms [verifyProse]: leaving it
     * with a discharge claim still pending fails, so a run that names
     * discharging assertions and never checks the naming is reported rather
     * than trusted (`#lzprosekeyconvention`).
     */
    private fun replayFixture() {
        val fixture = loadFixture()
        val scenarios = fixture.getValue("scenarios").jsonArray

        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertInt("scenario_count") { scenarios.size }
        meta.assertStrings("codecs") { listOf("json", "msgpack") }
        meta.assertStrings("fields") { listOf("snapshot", "node_add") }
        meta.assertStrings("key_forms") { listOf("omitted", "null", "present") }
        // The four paragraphs the corpus declares in `assertions.prose`, each
        // DISCHARGED by the executable keys carrying its obligation
        // (`#lzprosekeyconvention`). The blanket "it states WHY the fixture is
        // shaped this way" reason these replaced was true of all four and
        // checked by nothing.
        meta.proseKey(
            // a decoder accepts BOTH forms as absent and constructs a key from
            // neither — `decoded_key` is the comparison, `key_forms` is what
            // proves all three forms were driven.
            "clause",
            listOf("decoded_key", "key_forms"),
        )
        meta.proseKey(
            // the raw-text / raw-hex carriage is what lets an ABSENT map entry
            // and an explicit nil arrive as different bytes, in both codecs.
            "wire_encoding",
            listOf("key_forms", "codecs"),
        )
        meta.proseKey(
            // its own words: "`expect.reencoded_key_field_present` is the half
            // a decode assertion cannot reach".
            "reencode_obligation",
            listOf("reencoded_key_field_present"),
        )
        meta.proseKey(
            // its own words: "`present` forces a real key through and `omitted`
            // forces a real decode" — both are `key_forms` entries whose
            // `decoded_key` is asserted, across every scenario.
            "anti_vacuity",
            listOf("decoded_key", "key_forms", "scenario_count"),
        )
        // NOT prose: `generator` names an upstream script, not an obligation,
        // and the corpus does not declare it.
        meta.excuseKey("generator", "names the upstream script that mints the fixture, not an obligation")
        meta.requireAllSatisfied()

        // Anti-vacuity in both directions. A runner that never decodes reports
        // "absent" for everything and satisfies all eight omitted/null scenarios;
        // the `present` count is what only a real decode can produce. It is also
        // what would have caught the old behaviour from the other side — a key
        // named "null" is neither absent nor the expected path.
        var replayed = 0
        var keysDecoded = 0

        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val expect = scenario.getValue("expect").jsonObject
            val keys = AssertionKeys("$path ${scenario.getValue("id").jsonPrimitive.content}", expect)
            replayed += 1

            val message = decode(scenario)
            val key = decodedKey(scenario, message)
            if (key != null) keysDecoded += 1

            // The decode half: omitted and explicit-null must both arrive absent.
            keys.assertKeyWith("decoded_key") { want ->
                val expected = if (want is kotlinx.serialization.json.JsonNull) null else want.jsonPrimitive.content
                assertEquals(expected, key, "decoded_key")
            }

            val node = reencodedNode(scenario, message)
            // The encode half, which no assertion over the decoded value reaches.
            keys.assertKeyWith("reencoded_key_field_present") { want ->
                val encoded = node["key"]
                val present = encoded != null && encoded !is kotlinx.serialization.json.JsonNull
                assertEquals(want.jsonPrimitive.content.toBoolean(), present, "reencoded_key_field_present")
            }

            keys.assertLong("node") { node.getValue("node").jsonPrimitive.content.toLong() }
            keys.assertString("type_tag") { node.getValue("type_tag").jsonPrimitive.content }
            keys.assertKeyWith("payload") { want ->
                assertEquals(
                    want.jsonArray.map { it.jsonPrimitive.content.toInt() }.toString(),
                    node
                        .getValue("state")
                        .jsonObject
                        .getValue("Payload")
                        .jsonArray
                        .map { it.jsonPrimitive.content.toInt() }
                        .toString(),
                    "payload",
                )
            }
            keys.assertLong("epoch") {
                when (message) {
                    is IpcMessage.SnapshotMessage -> message.snapshot.epoch
                    is IpcMessage.DeltaMessage -> message.delta.epoch
                    else -> error("unexpected variant")
                }
            }
            keys.requireAllSatisfied()
        }

        assertEquals(12, replayed, "two fields x three key forms x two codecs")
        assertEquals(
            4,
            keysDecoded,
            "only the `present` scenarios carry a key; a runner reporting absent for " +
                "everything satisfies the null cases trivially",
        )

        // Rule 6: every key named by a discharge above must be one this
        // fixture's run really ASSERTED.
        verifyProse(path)
    }
}
