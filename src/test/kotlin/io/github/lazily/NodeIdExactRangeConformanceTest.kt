package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `NodeId` exact-representation bound (`#lzspecdecoderbound`).
 *
 * protocol.md § NodeId / PeerId stated the 2^53 bound as a PRODUCER obligation
 * and said nothing about what a decoder does when it receives a violation. That
 * left the receiving half undefined, which is exactly where the bindings
 * diverged. The clause is now normative: a decoder that cannot represent a
 * received identifier exactly MUST reject the frame rather than round it.
 *
 * lazily-kt's [NodeId] is a `Long`, so its exact range is `[0, 2^63)` — narrower
 * than the u64 wire type. That is conforming: the clause does not require a
 * binding to widen, only to refuse rather than substitute. The refusal comes
 * from `JsonPrimitive.long`, which re-parses the literal TEXT rather than a
 * pre-parsed double, so it throws instead of rounding — a property that was
 * true by inheritance from kotlinx.serialization and held in place by nothing
 * until this runner.
 *
 * The fixture carries its wire frames as raw text (json) and hex (msgpack) and
 * its expected identifier as a decimal STRING. The JVM would not round a bare
 * 9007199254740993 while loading the file, but the contract is uniform across
 * the nine bindings on purpose — the double-backed ones would, and a fixture
 * that reads differently per runtime is not one fixture.
 */
class NodeIdExactRangeConformanceTest {
    private val json = Json

    private val path = "codec/nodeid_exact_range.json"

    /** Largest identifier a `Long` NodeId represents exactly. */
    private val maxExact = BigInteger.valueOf(Long.MAX_VALUE)

    private fun loadFixture(): JsonObject {
        val fixture = json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        assertEquals("NodeIdExactRange", fixture.getValue("kind").jsonPrimitive.content)
        return fixture
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string has an odd length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Decode a scenario's wire frame with the codec it names.
     *
     * `null` means the decoder REFUSED it — the conforming outcome for an
     * identifier outside `Long`. The caller decides which of the two is
     * correct, because that split is the whole point of the fixture.
     */
    private fun decode(scenario: JsonObject): IpcMessage? =
        try {
            when (val codec = scenario.getValue("codec").jsonPrimitive.content) {
                // The raw TEXT through the codec's own entry point, so the parse
                // that would round on a narrower runtime is inside the code
                // under test rather than in the runner.
                "json" -> IpcMessage.decodeJson(scenario.getValue("wire_json").jsonPrimitive.content)
                "msgpack" ->
                    IpcMessage.decodeMsgpack(
                        hexToBytes(scenario.getValue("wire_msgpack_hex").jsonPrimitive.content),
                    )
                else -> error("unknown codec: $codec")
            }
        } catch (e: NumberFormatException) {
            // kotlinx.serialization's `JsonPrimitive.long` re-parses the LITERAL
            // TEXT, so an over-range identifier throws here rather than arriving
            // as a rounded double. That is the json half of the refusal.
            null
        } catch (e: MsgpackCodecException) {
            // The msgpack half: `Unpacker.unsigned64` already refuses a `uint 64`
            // above Long.MAX_VALUE instead of wrapping it to a negative id.
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    @Test
    fun `NodeId exact-representation bound is enforced by refusal, never rounding`() =
        proseScope(path).use { replayFixture() }

    /**
     * The replay, inside the prose scope that arms [verifyProse]: leaving it
     * with a discharge claim still pending fails, so a run that names
     * discharging assertions and never checks the naming is reported rather
     * than trusted (`#lzprosekeyconvention`).
     */
    private fun replayFixture() {
        val fixture = loadFixture()
        // The outcome vocabulary the run actually drove, so `assertions.outcomes`
        // is a claim about work that happened rather than about the file on disk.
        val observedOutcomes = linkedSetOf<String>()

        // Anti-vacuity. `exact_or_reject` is satisfied by a runner that decodes
        // nothing and calls everything refused — and lazily-kt really does
        // refuse part of this corpus, so a broken runner resembles a working
        // one. The two counters, pinned at the end, are what separate them.
        var accepted = 0
        var refused = 0

        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val where = scenario.getValue("id").jsonPrimitive.content
            val expect = scenario.getValue("expect").jsonObject
            val expected = BigInteger(expect.getValue("node_id_decimal").jsonPrimitive.content)
            val representable = expected <= maxExact

            val keys = AssertionKeys("$path $where", expect)

            // `outcome` is the corpus-wide statement of what a decoder may do.
            // lazily-kt reads it as a constraint on the FIXTURE: an `exact`
            // scenario it cannot represent would be a fixture bug.
            keys.assertKeyWith("outcome") { want ->
                val outcome = want.jsonPrimitive.content
                assertTrue(
                    outcome == "exact" || outcome == "exact_or_reject",
                    "$where: unknown outcome $outcome",
                )
                if (outcome == "exact") {
                    assertTrue(representable, "$where: fixture marks an unrepresentable identifier `exact`")
                }
                observedOutcomes += outcome
            }

            val message = decode(scenario)

            if (message == null) {
                assertTrue(
                    !representable,
                    "$where: lazily-kt represents $expected exactly, so the frame must decode",
                )
                refused += 1
                for (key in listOf(
                    "node_id_decimal",
                    "root_id_decimal",
                    "epoch",
                    "node_count",
                    "type_tag",
                    "payload",
                )) {
                    keys.excuseKey(
                        key,
                        "a Long NodeId cannot represent this identifier, so the frame is REFUSED — " +
                            "the conforming outcome, and the whole point of the scenario. These keys " +
                            "are asserted by the scenarios inside [0, 2^63).",
                    )
                }
                keys.requireAllSatisfied()
                continue
            }

            assertTrue(
                representable,
                "$where: lazily-kt cannot represent $expected exactly, so decoding it means the " +
                    "identifier was rounded, truncated, or wrapped",
            )
            accepted += 1

            val snapshot = (message as IpcMessage.SnapshotMessage).snapshot
            assertEquals("Snapshot", scenario.getValue("variant").jsonPrimitive.content, where)

            keys.assertLong("epoch") { snapshot.epoch }
            keys.assertInt("node_count") { snapshot.nodes.size }

            val node = snapshot.nodes.first()
            // The discriminating assertion: the decimal rendering, so a decoder
            // that returned a neighbouring identifier is visible rather than
            // approximately right.
            keys.assertString("node_id_decimal") { node.node.toString() }
            keys.assertString("type_tag") { node.typeTag }
            keys.assertKeyWith("payload") { want ->
                val payload = (node.state as NodeState.Payload).bytes
                assertEquals(
                    want.jsonArray.map { it.jsonPrimitive.content.toInt() }.toString(),
                    payload.map { it.toInt() and 0xff }.toString(),
                    "$where: payload",
                )
            }
            assertEquals(1, snapshot.roots.size, "$where: one root")
            keys.assertString("root_id_decimal") { snapshot.roots.first().toString() }
            keys.requireAllSatisfied()
        }

        // Four scenarios (2^53-1 and 2^53+1, in both codecs) are inside Long;
        // the two at u64::MAX are not. Pinning both halves means a decoder that
        // stopped refusing, and one that stopped decoding, are each a failure.
        assertEquals(4, accepted, "lazily-kt decodes the four scenarios inside [0, 2^63)")
        assertEquals(2, refused, "lazily-kt refuses both u64::MAX identifiers")

        // The fixture-level block, asserted AFTER the replay so each key is a
        // claim about what the run observed.
        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertInt("scenario_count") { accepted + refused }
        meta.assertStrings("codecs") { listOf("json", "msgpack") }
        // NOT a prose key, and the corpus does not declare it one: `outcomes`
        // maps a VOCABULARY to English glosses, so the assertion is the key set
        // and the glosses ride along (`#lzprosekeyconvention` § Definition). It
        // used to be excused as prose, which left the vocabulary unchecked — a
        // scenario carrying a third outcome would have passed.
        meta.assertKeyWith("outcomes") { want ->
            assertEquals(
                want.jsonObject.keys,
                observedOutcomes,
                "$path: every declared outcome must have been driven by some scenario",
            )
        }

        // The three paragraphs the corpus declares in `assertions.prose`, each
        // DISCHARGED by the executable keys carrying its obligation.
        meta.proseKey(
            // reject rather than round: `node_id_decimal` is the discriminating
            // comparison — a neighbouring identifier is visible rather than
            // approximately right — and `outcome` is which half applies.
            "clause",
            listOf("node_id_decimal", "outcome"),
        )
        meta.proseKey(
            // its own words: a runner "MUST compare the decoded identifier by
            // its decimal rendering", through the codec under test, in both.
            "wire_encoding",
            listOf("node_id_decimal", "codecs"),
        )
        meta.proseKey(
            // its own words: "the two `exact` scenarios are the control" — the
            // outcome vocabulary plus the decimal comparison, over every
            // scenario.
            "anti_vacuity",
            listOf("outcome", "node_id_decimal", "outcomes", "scenario_count"),
        )
        // NOT prose: `generator` names an upstream script, not an obligation,
        // and the corpus does not declare it.
        meta.excuseKey("generator", "names the upstream script that mints the fixture, not an obligation")
        meta.requireAllSatisfied()

        // Rule 6: every key named by a discharge above must be one this
        // fixture's run really ASSERTED.
        verifyProse(path)
    }
}
