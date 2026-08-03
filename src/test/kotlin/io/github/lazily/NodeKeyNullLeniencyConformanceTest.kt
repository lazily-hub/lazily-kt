package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
 *
 * And it opens the frame TWICE. Every key in this fixture's `expect` blocks is
 * byte-identical for the `omitted` and `null` families, because reading the
 * explicit null as absent is the leniency itself — so after the decoder has run
 * the four `null` scenarios are the four `omitted` ones under a different id, and
 * no assertion downstream can separate them (`#lznullformblind`). The separator
 * is [wireKeyForm], which classifies the `key` slot straight off the raw wire —
 * the `wire_json` text, the `wire_msgpack_hex` bytes — BEFORE any decode, and is
 * compared against each scenario's declared `key_form`.
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

    /** The frame variant the decoder really produced, in the fixture's vocabulary. */
    private fun variantOf(message: IpcMessage): String =
        when (message) {
            is IpcMessage.SnapshotMessage -> "Snapshot"
            is IpcMessage.DeltaMessage -> "Delta"
            is IpcMessage.CrdtSyncMessage -> "CrdtSync"
            else -> error("this fixture pins no runner for $message")
        }

    /**
     * The scenario's own frame, unpacked SCHEMA-LESSLY from the raw wire it
     * carries — the `wire_json` TEXT or the `wire_msgpack_hex` BYTES — before
     * [IpcMessage]'s typed decoder has seen it.
     *
     * Schema-less on purpose. `JsonObject` keeps "no entry" and "entry holding
     * [JsonNull]" apart, and `MsgpackCodec.unpack` maps msgpack nil (`0xc0`) to
     * [JsonNull] rather than dropping the entry, so the one distinction this
     * fixture is about survives the read in both codecs. Fails closed on a codec
     * this runner does not implement (`#lzscenariobodyskip`).
     */
    private fun rawWire(scenario: JsonObject): JsonObject =
        when (val codec = scenario.getValue("codec").jsonPrimitive.content) {
            "json" ->
                json
                    .parseToJsonElement(scenario.getValue("wire_json").jsonPrimitive.content)
                    .jsonObject
            "msgpack" ->
                MsgpackCodec
                    .unpack(hexToBytes(scenario.getValue("wire_msgpack_hex").jsonPrimitive.content))
                    .jsonObject
            else -> error("unknown codec: $codec")
        }

    /**
     * Navigate a generic frame tree to the map that carries the `key` slot,
     * dispatching on which of the two optional-key sites the scenario exercises.
     *
     * Shared by the RAW-WIRE control and the RE-ENCODED inspection so both read
     * the same slot of the same shape. Fails closed on an unknown field.
     */
    private fun nodeKeySite(scenario: JsonObject, wire: JsonObject): JsonObject =
        when (val field = scenario.getValue("field").jsonPrimitive.content) {
            "snapshot" -> wire.getValue("Snapshot").jsonObject.getValue("nodes").jsonArray[0].jsonObject
            "node_add" ->
                wire
                    .getValue("Delta")
                    .jsonObject
                    .getValue("ops")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("NodeAdd")
                    .jsonObject
            else -> error("unknown field: $field")
        }

    /**
     * Classify the `key` slot off the RAW frame, BEFORE the decoder runs, into
     * one of the three wire forms.
     *
     * This control is the whole reason `wire_encoding` is dischargeable here.
     * Every key in this fixture's `expect` blocks is BYTE-IDENTICAL for the
     * `omitted` and `null` families — `decoded_key` is null for both, by design,
     * because reading the explicit null as absent IS the leniency under test — so
     * the four `null` scenarios are the four `omitted` ones wearing a different
     * id as far as any post-decode assertion can tell. A decoder that collapses
     * the two the instant it touches the value satisfies all twelve scenarios
     * while never once distinguishing them, and the manifest rung, the
     * scenario-replay rung and both assertion-key rungs are blind to it at the
     * same time (`#lznullformblind`).
     *
     * lazily-kt is the binding that most needs it. [JsonNull] IS a
     * [kotlinx.serialization.json.JsonPrimitive] here, which is exactly how this
     * repo once decoded a node carrying a real key literally named `null`, and
     * the same trap bit the sibling `backend` discriminator under
     * `#lzblobbackendstrict`. So the classification tests `is JsonNull` FIRST and
     * absence by map membership, never by a primitive read.
     *
     * Fails closed on a form it cannot name: a slot holding something that is
     * neither absent, nor nil, nor a readable key is not silently bucketed.
     */
    private fun wireKeyForm(scenario: JsonObject): String {
        val site = nodeKeySite(scenario, rawWire(scenario))
        val slot = site["key"]
        val form =
            when {
                // Map membership, not a null-valued read: an absent entry and an
                // entry holding nil are the two things under test.
                !site.containsKey("key") -> "omitted"
                slot is JsonNull -> "null"
                else -> "present"
            }
        check(form in KNOWN_KEY_FORMS) { "unclassifiable `key` slot on the raw wire: $slot" }
        return form
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
        return nodeKeySite(scenario, wire)
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

        // Anti-vacuity in both directions. A runner that never decodes reports
        // "absent" for everything and satisfies all eight omitted/null scenarios;
        // the `present` count is what only a real decode can produce. It is also
        // what would have caught the old behaviour from the other side — a key
        // named "null" is neither absent nor the expected path.
        var replayed = 0
        var keysDecoded = 0

        // The three vocabularies the run really dispatched on. `key_forms` is
        // filled from the RAW WIRE, never from the fixture's own labels, so the
        // set assertion at the end is a claim about bytes that reached the
        // decoder rather than about the file on disk (`#lznullformblind`).
        val codecsReplayed = linkedSetOf<String>()
        val fieldsReplayed = linkedSetOf<String>()
        val formsReplayed = linkedSetOf<String>()

        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val where = scenario.getValue("id").jsonPrimitive.content
            val expect = scenario.getValue("expect").jsonObject
            val keys = AssertionKeys("$path $where", expect)
            // The scenario's OWN keys, tracked to the same three rungs as its
            // `expect` block. `key_form` and `variant` were carried by the
            // fixture and read by nothing here, which is how the raw-wire form
            // stayed unobserved.
            val sc = AssertionKeys("$path $where scenario", scenario)
            replayed += 1

            codecsReplayed += scenario.getValue("codec").jsonPrimitive.content
            fieldsReplayed += scenario.getValue("field").jsonPrimitive.content

            // THE RAW-WIRE CONTROL. Classified off the scenario's own bytes
            // before the typed decoder runs, then compared with the form the
            // scenario DECLARES. A scenario tagged `null` whose frame omits the
            // entry — or a corpus edit that quietly made the two families the
            // same bytes — reddens here, and here is the only place it can
            // (`#lznullformblind`).
            val onWire = wireKeyForm(scenario)
            formsReplayed += onWire
            sc.assertString("key_form") { onWire }

            sc.assertString("name") { where }
            sc.excuseKey("id", "the ledger key this loop records; it names the scenario rather than asserting it")
            sc.excuseKey("expect", "container: asserted key-by-key against the DECODED and RE-ENCODED frames below")
            sc.excuseKey(
                "codec",
                "a selector: it chooses which decoder this scenario drives, and a token this runner " +
                    "does not implement fails closed in `rawWire`/`decode`. The vocabulary itself is " +
                    "asserted as a set in `assertions.codecs`, against the branches really dispatched into",
            )
            sc.excuseKey(
                "field",
                "a selector: it chooses which of the two optional-key sites this scenario exercises, " +
                    "and an unknown one fails closed in `nodeKeySite`. The vocabulary itself is " +
                    "asserted as a set in `assertions.fields`",
            )
            sc.excuseKey(
                if (scenario.getValue("codec").jsonPrimitive.content == "json") "wire_json" else "wire_msgpack_hex",
                "the frame under test: this runner's INPUT, classified by `key_form` above and proven " +
                    "by the decoded and re-encoded values asserted below",
            )

            val message = decode(scenario)
            sc.assertString("variant") { variantOf(message) }
            sc.requireAllSatisfied()

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

        // The fixture-level block, asserted AFTER the replay so every key is a
        // claim about work that happened rather than about the file on disk.
        // `scenario_count` used to be compared against the fixture's own
        // `scenarios.size` and the three vocabularies against hand-written
        // literals: all four were green over a runner that decodes nothing,
        // which is the exact vacuity `anti_vacuity` exists to name.
        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertInt("scenario_count") { replayed }
        meta.assertKeyWith("codecs") { want ->
            assertEquals(
                want.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                codecsReplayed,
                "$path: every declared codec must have been driven by some scenario",
            )
        }
        meta.assertKeyWith("fields") { want ->
            assertEquals(
                want.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                fieldsReplayed,
                "$path: every declared optional-key site must have been exercised by some scenario",
            )
        }
        // Off the RAW WIRE, not off the fixture's labels. This is what makes the
        // `omitted`/`null` split observable at all: with the forms read from
        // `key_form` the set would agree with itself, and with the literal it
        // replaced it agreed with the runner (`#lznullformblind`).
        meta.assertKeyWith("key_forms") { want ->
            assertEquals(
                want.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                formsReplayed,
                "$path: every declared wire form of `key` must have been classified off some " +
                    "scenario's own bytes before its decoder ran",
            )
        }
        // The four paragraphs the corpus declares in `assertions.prose`, each
        // DISCHARGED by the executable keys carrying its obligation
        // (`#lzprosekeyconvention`). The blanket "it states WHY the fixture is
        // shaped this way" reason these replaced was true of all four and
        // checked by nothing.
        meta.proseKey(
            // "accept both an omitted `key` and an explicit `key: null` and read
            // both as absent". `key_form` is what proves the two forms were
            // DISTINCT going in — it is the raw-wire classification, not the
            // label — and `decoded_key` is what proves they arrive the same.
            "clause",
            listOf("key_form", "decoded_key", "fields"),
        )
        meta.proseKey(
            // PROXY. The paragraph is a claim about how the CORPUS carries its
            // bytes — raw text and lowercase hex rather than a pre-parsed object
            // — and no assertion a run makes observes that choice directly. The
            // honest proxy is the raw-wire control: had the carriage collapsed
            // an absent map entry into an explicit nil, the three-way `key_form`
            // split could not have survived into this runner at all, in either
            // codec.
            "wire_encoding",
            listOf("key_form", "key_forms", "codecs"),
        )
        meta.proseKey(
            // its own words: "`expect.reencoded_key_field_present` is the half
            // a decode assertion cannot reach".
            "reencode_obligation",
            listOf("reencoded_key_field_present"),
        )
        meta.proseKey(
            // its own words: "`present` forces a real key through and `omitted`
            // forces a real decode". Both are counted off the raw wire rather
            // than off the fixture's labels, and `scenario_count` is what the
            // loop replayed rather than what the file carries.
            "anti_vacuity",
            listOf("key_form", "key_forms", "decoded_key", "reencoded_key_field_present", "scenario_count"),
        )
        // NOT prose: `generator` names an upstream script, not an obligation,
        // and the corpus does not declare it.
        meta.excuseKey("generator", "names the upstream script that mints the fixture, not an obligation")
        meta.requireAllSatisfied()

        // Rule 6: every key named by a discharge above must be one this
        // fixture's run really ASSERTED.
        verifyProse(path)
    }

    private companion object {
        /** The wire forms of an optional `key` this runner can name. */
        val KNOWN_KEY_FORMS = setOf("omitted", "null", "present")
    }
}
