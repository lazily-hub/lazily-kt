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
    fun `NodeId exact-representation bound is enforced by refusal, never rounding`() {
        val fixture = loadFixture()
        val scenarios = fixture.getValue("scenarios").jsonArray

        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertInt("scenario_count") { scenarios.size }
        meta.assertStrings("codecs") { listOf("json", "msgpack") }
        for (prose in listOf("clause", "wire_encoding", "outcomes", "anti_vacuity", "generator")) {
            meta.excuseKey(
                prose,
                "prose: it states WHY the fixture is shaped this way; the behaviour it " +
                    "describes is asserted by the per-scenario decode below",
            )
        }
        meta.requireAllSatisfied()

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
    }
}
