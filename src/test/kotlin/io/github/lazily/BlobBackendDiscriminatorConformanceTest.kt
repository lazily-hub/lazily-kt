package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Blob-backend discriminator strictness on decode (`#lzblobbackendstrict`).
 *
 * protocol.md § Shared-memory payload path splits `ShmBlobRef.backend` into two
 * facts that look like one and get opposite answers:
 *
 * - an **OMITTED** `backend` MUST decode as `shm` — the forward-compatibility
 *   channel, and the only one, because every descriptor minted before the field
 *   existed has that shape. A conforming encoder MUST omit it for `shm` too, so a
 *   pre-field descriptor round-trips byte-identically.
 * - a **PRESENT** `backend` outside the enum MUST be rejected, naming the token,
 *   and MUST NOT be normalized to `shm`, to another backend, or to a sentinel.
 *
 * lazily-kt normalized the unknown token and documented that as forward-compat.
 * The clause overturns it: a new backend enters by adding an enum value — a spec
 * change carrying a fixture — so an unknown token is a corrupt or non-conforming
 * producer, never a newer peer. Normalizing routes a non-`shm` descriptor into the
 * `shm` table, which is exactly what `resolve_wrong_backend` says never happens.
 *
 * The wire is carried as raw text (json) and lowercase hex (msgpack) on purpose.
 * `schemas/defs.json` closes `backend` to an enum, so the reject frames are
 * schema-INVALID by design and cannot be carried as parsed objects; the runner
 * decodes the raw form rather than re-serializing something the fixture parsed.
 */
class BlobBackendDiscriminatorConformanceTest {
    private val json = Json

    private val path = "codec/blob_backend_discriminator.json"

    private fun loadFixture(): JsonObject {
        val fixture = json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        assertEquals("BlobBackendDiscriminator", fixture.getValue("kind").jsonPrimitive.content)
        return fixture
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string has an odd length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun hexOf(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    /** The scenario's wire frame in whichever raw form it carries. */
    private fun rawWire(scenario: JsonObject): String =
        when (val codec = scenario.getValue("codec").jsonPrimitive.content) {
            "json" -> scenario.getValue("wire_json").jsonPrimitive.content
            "msgpack" -> scenario.getValue("wire_msgpack_hex").jsonPrimitive.content
            else -> error("unknown codec: $codec")
        }

    /**
     * Whether the wire frame physically carries a `backend` map entry.
     *
     * The anti-vacuity control for a probe aimed at something the input never
     * carries: `backend_form` is a CLAIM about the bytes, and a scenario whose
     * bytes disagree with it would let an omitted-vs-present assertion pass while
     * testing the other case.
     */
    private fun wireCarriesBackendField(scenario: JsonObject): Boolean =
        when (scenario.getValue("codec").jsonPrimitive.content) {
            "json" -> rawWire(scenario).contains("\"backend\"")
            else -> rawWire(scenario).contains(hexOf("backend"))
        }

    /** Whether the wire frame physically carries [token] as a string. */
    private fun wireCarriesToken(
        scenario: JsonObject,
        token: String,
    ): Boolean =
        when (scenario.getValue("codec").jsonPrimitive.content) {
            "json" -> rawWire(scenario).contains("\"$token\"")
            else -> rawWire(scenario).contains(hexOf(token))
        }

    /**
     * Decode a scenario's wire frame through the codec it names, from the RAW
     * form, so the parse under test is inside the library rather than in the
     * runner.
     */
    private fun decode(scenario: JsonObject): IpcMessage =
        when (val codec = scenario.getValue("codec").jsonPrimitive.content) {
            "json" -> IpcMessage.decodeJson(rawWire(scenario))
            "msgpack" -> IpcMessage.decodeMsgpack(hexToBytes(rawWire(scenario)))
            else -> error("unknown codec: $codec")
        }

    /**
     * Re-encode [message] through [codec] and return the `SharedBlob` map exactly
     * as it lands on the wire.
     *
     * The encoder half of the clause. Reading [ShmBlobRef.backend] off the decoded
     * object would prove nothing about what gets emitted, and round-tripping
     * whatever arrived is the specific defect `reencoded_backend_field_present`
     * exists to catch — so the msgpack arm goes through the real binary encoder
     * and unpacks the bytes back.
     */
    private fun reencodedBlobObject(
        message: IpcMessage,
        codec: String,
    ): JsonObject {
        val tree =
            when (codec) {
                "json" -> json.parseToJsonElement(message.encodeJson().decodeToString())
                "msgpack" -> MsgpackCodec.unpack(message.encodeMsgpack())
                else -> error("unknown codec: $codec")
            }
        return tree.jsonObject
            .getValue("Delta")
            .jsonObject
            .getValue("ops")
            .jsonArray
            .single()
            .jsonObject
            .getValue("SlotValue")
            .jsonObject
            .getValue("payload")
            .jsonObject
            .getValue("SharedBlob")
            .jsonObject
    }

    /**
     * What the replay observed across the whole fixture, so the anti-vacuity
     * counters are computed from the run rather than declared by it.
     */
    private class Tally {
        var accepted = 0
        var rejected = 0
        val decodedBackends = mutableListOf<String>()
    }

    /** Replay one scenario; throws on the first assertion it fails. */
    private fun replay(
        scenario: JsonObject,
        tally: Tally,
    ) {
        val where = scenario.getValue("id").jsonPrimitive.content
        val codec = scenario.getValue("codec").jsonPrimitive.content
        val form = scenario.getValue("backend_form").jsonPrimitive.content
        val outcome = scenario.getValue("outcome").jsonPrimitive.content
        val keys = AssertionKeys("$path $where", scenario.getValue("expect").jsonObject)

        // The scenario's declared wire form must match the bytes it carries, in
        // both directions. Without this an omitted-backend assertion could be
        // running against a frame that carries the field, or a token probe
        // against a frame that never mentions the token.
        assertEquals(
            form != "omitted",
            wireCarriesBackendField(scenario),
            "$where: backend_form=$form disagrees with the wire frame",
        )
        if (form != "omitted") {
            assertTrue(
                wireCarriesToken(scenario, form),
                "$where: the wire frame does not carry the token `$form`",
            )
        }

        val attempt = runCatching { decode(scenario) }

        when (outcome) {
            "reject" -> {
                tally.rejected += 1
                keys.assertBoolean("rejected") { attempt.isFailure }
                keys.assertKeyWith("error_names_token") { want ->
                    val token = want.jsonPrimitive.content
                    val error =
                        attempt.exceptionOrNull()
                            ?: fail("$where: the frame decoded; a `backend` of `$token` must be refused")
                    val text = error.message ?: ""
                    // Not a bare is-error assertion: a decoder that refused the
                    // frame because it mis-parsed `checksum` implements none of
                    // the clause and would pass one.
                    assertTrue(
                        text.contains(token),
                        "$where: the error must name the offending token `$token`, got: $text",
                    )
                }
            }

            "accept" -> {
                tally.accepted += 1
                val message =
                    attempt.getOrElse { fail("$where: the frame must decode, but it was refused: $it") }
                assertEquals(
                    "Delta",
                    scenario.getValue("variant").jsonPrimitive.content,
                    "$where: this runner only drives the Delta variant",
                )
                val delta = assertIs<IpcMessage.DeltaMessage>(message, "$where: Delta frame").delta
                val op = assertIs<DeltaOp.SlotValue>(delta.ops.single(), "$where: one SlotValue op")
                val blob = assertIs<IpcValue.SharedBlob>(op.payload, "$where: SharedBlob payload").blob
                tally.decodedBackends += blob.backend.wire

                keys.assertLong("node") { op.node }
                keys.assertLong("offset") { blob.offset }
                keys.assertLong("len") { blob.len }
                keys.assertLong("generation") { blob.generation }
                keys.assertLong("epoch") { blob.epoch }
                keys.assertLong("checksum") { blob.checksum }
                keys.assertString("decoded_backend") { blob.backend.wire }
                keys.assertBoolean("reencoded_backend_field_present") {
                    reencodedBlobObject(message, codec).containsKey("backend")
                }
            }

            else -> fail("$where: unknown outcome `$outcome`")
        }

        keys.requireAllSatisfied()
    }

    @Test
    fun `an omitted blob backend decodes as shm and an unknown token is refused`() {
        val fixture = loadFixture()
        val scenarios = fixture.getValue("scenarios").jsonArray

        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertInt("scenario_count") { scenarios.size }
        meta.assertStrings("codecs") {
            scenarios.map { it.jsonObject.getValue("codec").jsonPrimitive.content }.distinct().sorted()
        }
        meta.assertStrings("outcomes") {
            scenarios.map { it.jsonObject.getValue("outcome").jsonPrimitive.content }.distinct().sorted()
        }
        // The enum the clause closes, in the spec's own order — a binding that
        // grew or lost a backend is a wire break, not a local detail.
        meta.assertStrings("backends") { BlobBackendKind.entries.map { it.wire } }
        for (prose in listOf(
            "clause",
            "wire_encoding",
            "reject_obligation",
            "anti_vacuity",
            "theorem",
            "generator",
        )) {
            meta.excuseKey(
                prose,
                "prose: it states WHY the fixture is shaped this way; the behaviour it " +
                    "describes is asserted by the per-scenario decode below",
            )
        }
        meta.requireAllSatisfied()

        // Anti-vacuity. A runner that refused everything would satisfy both reject
        // scenarios, and one that decoded everything as the default would satisfy
        // four of the six accepts. Both halves are pinned at the end.
        val tally = Tally()

        // Every scenario is replayed and REPORTED, rather than the first failure
        // aborting the loop. A fixture whose halves differ only by codec is
        // otherwise half-unfalsifiable: one defect throws on the `json` scenario
        // and the `msgpack` one never runs, so nothing distinguishes a msgpack
        // assertion that holds from one that is never reached.
        val failures = mutableListOf<String>()
        for (scenario in ConformanceScenarios.of(path, fixture)) {
            try {
                replay(scenario, tally)
            } catch (e: Throwable) {
                failures += "${scenario.getValue("id").jsonPrimitive.content}: ${e.message}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "$path: ${failures.size} of ${scenarios.size} scenarios failed:\n" +
                failures.joinToString("\n"),
        )

        assertEquals(6, tally.accepted, "six scenarios decode (omitted, explicit shm, arrow — in both codecs)")
        assertEquals(2, tally.rejected, "both unknown-token scenarios are refused")
        // A decoder that ignores the discriminator and hardcodes the default
        // passes every scenario above except this one.
        assertEquals(
            listOf("arrow", "arrow", "shm", "shm", "shm", "shm"),
            tally.decodedBackends.sorted(),
            "the discriminator is READ, not assumed",
        )
    }
}
