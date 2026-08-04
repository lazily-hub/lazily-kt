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
 * protocol.md § Shared-memory payload path splits `ShmBlobRef.backend` into facts
 * that look like one and get opposite answers:
 *
 * - an **OMITTED** `backend` MUST decode as `shm` — the forward-compatibility
 *   channel, and the only one, because every descriptor minted before the field
 *   existed has that shape. A conforming encoder MUST omit it for `shm` too, so a
 *   pre-field descriptor round-trips byte-identically.
 * - an explicit **NULL** `backend` is the same ABSENT form (`#lzkeynullstrict`),
 *   not a present-unknown one: a serde-style peer that did not apply
 *   `skip_serializing_if` emits `null` where a conforming encoder omits, so
 *   refusing it is stricter than the reference implementation on a frame the
 *   reference implementation produces.
 * - a **PRESENT** `backend` outside the enum MUST be rejected, naming the token,
 *   and MUST NOT be normalized to `shm`, to another backend, or to a sentinel.
 * - a **PRESENT NON-STRING** `backend` MUST be rejected too, and — the half that
 *   is easy to miss — through the SAME decode-error family, so the one catch a
 *   caller already wraps a decode in covers both refusals.
 *
 * lazily-kt normalized the unknown token and documented that as forward-compat.
 * The clause overturns it: a new backend enters by adding an enum value — a spec
 * change carrying a fixture — so an unknown token is a corrupt or non-conforming
 * producer, never a newer peer. Normalizing routes a non-`shm` descriptor into the
 * `shm` table, which is exactly what `resolve_wrong_backend` says never happens.
 *
 * The wire is carried as raw text (json) and lowercase hex (msgpack) on purpose.
 * `schemas/defs.json` closes `backend` to an enum, so the reject frames — and the
 * null frames, which are accepts — are schema-INVALID by design and cannot be
 * carried as parsed objects; the runner decodes the raw form rather than
 * re-serializing something the fixture parsed.
 *
 * Fixture v2 also SPLIT the epoch. `expect.epoch` is gone; `frame_epoch` (the
 * Delta's) and `blob_epoch` (the descriptor's) are different numbers, so a runner
 * reading the wrong one now fails instead of satisfying a single key from either
 * source.
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

    private fun isJson(scenario: JsonObject): Boolean = scenario.getValue("codec").jsonPrimitive.content == "json"

    /**
     * Whether the wire frame physically carries a `backend` map entry.
     *
     * The anti-vacuity control for a probe aimed at something the input never
     * carries: `backend_form` is a CLAIM about the bytes, and a scenario whose
     * bytes disagree with it would let an omitted-vs-present assertion pass while
     * testing the other case.
     */
    private fun wireCarriesBackendField(scenario: JsonObject): Boolean =
        if (isJson(scenario)) {
            rawWire(scenario).contains("\"backend\"")
        } else {
            rawWire(scenario).contains(hexOf("backend"))
        }

    /**
     * Whether the wire frame physically carries the VALUE that [form] claims.
     *
     * Three of the seven forms are not tokens, so a token substring probe does not
     * reach them: `omitted` writes no entry, `null` writes a nil and `non_string`
     * writes an integer where a token belongs. The latter two are matched against
     * the exact bytes instead, because `contains("null")` would pass on frames that
     * carry no null at all.
     */
    private fun wireCarriesForm(
        scenario: JsonObject,
        form: String,
    ): Boolean {
        val wire = rawWire(scenario)
        return when (form) {
            "null" ->
                if (isJson(scenario)) {
                    wire.contains("\"backend\": null")
                } else {
                    wire.endsWith(hexOf("backend") + "c0")
                }
            "non_string" ->
                if (isJson(scenario)) {
                    wire.contains("\"backend\": 7")
                } else {
                    wire.endsWith(hexOf("backend") + "07")
                }
            else -> if (isJson(scenario)) wire.contains("\"$form\"") else wire.contains(hexOf(form))
        }
    }

    /**
     * Decode a scenario's wire frame through the codec it names, from the RAW
     * form, so the parse under test is inside the library rather than in the
     * runner.
     */
    private fun decode(
        scenario: JsonObject,
        keys: AssertionKeys,
        /**
         * Records the codec entry point this call really dispatched into.
         *
         * `assertions.codecs` used to be compared against a set tallied from the
         * scenario's own `codec` LABEL, which agrees with the fixture whatever the
         * runner did with the token — green over a runner that reads it and enters
         * no branch. Booking inside the arm makes the set a claim about work
         * (`#lznullformblind`).
         */
        driven: MutableSet<String>,
    ): IpcMessage =
        when (val codec = scenario.getValue("codec").jsonPrimitive.content) {
            "json" -> {
                driven += "json"
                val raw = rawWire(scenario)
                keys.assertString("wire_input_fnv1a64") {
                    wireInputFnv1a64Hex(raw.toByteArray(Charsets.UTF_8))
                }
                IpcMessage.decodeJson(raw)
            }
            "msgpack" -> {
                driven += "msgpack"
                val raw = hexToBytes(rawWire(scenario))
                keys.assertString("wire_input_fnv1a64") { wireInputFnv1a64Hex(raw) }
                IpcMessage.decodeMsgpack(raw)
            }
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
     * Which refusal the library actually raised, in the fixture's own vocabulary.
     *
     * `rejection_kind` is asserted against THIS, never used to select an assertion
     * arm. A discriminator that only picks a branch proves nothing — both reject
     * scenarios would pass on one undifferentiated "it threw". Answering correctly
     * requires the library to distinguish the two refusals by TYPE, which is the
     * same fact `rejection_is_decode_error` needs one level up.
     */
    private fun rejectionKindOf(error: Throwable?): String =
        when (error) {
            null -> "none — the frame decoded"
            is IpcDecodeException.UnknownBlobBackend -> "unknown_token"
            is IpcDecodeException.NonStringBlobBackend -> "non_string"
            else -> "other(${error::class.simpleName})"
        }

    /**
     * What the replay observed across the whole fixture, so the anti-vacuity
     * counters are computed from the run rather than declared by it.
     */
    private class Tally {
        var accepted = 0
        var rejected = 0
        val decodedBackends = mutableListOf<String>()
        val backendForms = linkedSetOf<String>()
        val rejectionKinds = linkedSetOf<String>()
        val codecs = linkedSetOf<String>()
        val outcomes = linkedSetOf<String>()
        val frameEpochs = linkedSetOf<Long>()
        val blobEpochs = linkedSetOf<Long>()

        val replayed: Int get() = accepted + rejected
    }

    /** Replay one scenario; throws on the first assertion IT fails. */
    private fun replay(
        scenario: JsonObject,
        tally: Tally,
    ) {
        val where = scenario.getValue("id").jsonPrimitive.content
        val codec = scenario.getValue("codec").jsonPrimitive.content
        val form = scenario.getValue("backend_form").jsonPrimitive.content
        val outcome = scenario.getValue("outcome").jsonPrimitive.content
        val expect = scenario.getValue("expect").jsonObject
        val keys = AssertionKeys("$path $where", expect)

        // Booked before the first assertion, so a scenario that FAILS still counts
        // as replayed: the vocabulary checks at the end are about what the runner
        // drove, and a failing scenario dropping out of them would turn one red
        // into several unrelated ones.
        tally.backendForms += form

        // `expect.epoch` was REMOVED in fixture v2 and split into `frame_epoch`
        // and `blob_epoch`. It carried 9 in both places, so a runner reading the
        // Delta's epoch and one reading the descriptor's both satisfied it. If it
        // ever comes back, fail here rather than silently re-fusing the two.
        assertTrue(
            "epoch" !in expect,
            "$where: `expect.epoch` is ambiguous and was removed — assert `frame_epoch` and `blob_epoch`",
        )

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
                wireCarriesForm(scenario, form),
                "$where: the wire frame does not carry the `$form` form of `backend`",
            )
        }

        val attempt = runCatching { decode(scenario, keys, tally.codecs) }

        // Decode ONCE, then assert the label against the real verdict — before
        // the label is allowed to select anything (`#lznullformblind`). A label
        // used as a selector is not an assertion: a reject frame this binding
        // wrongly ACCEPTED took the reject arm anyway and was caught only
        // indirectly, by whichever key inside it happened to disagree. Now the
        // contradiction is named where it happens, and the anti-vacuity counters
        // below are keyed off the verdict rather than off the same label they
        // are supposed to be guarding.
        val verdict = if (attempt.isFailure) "reject" else "accept"
        assertEquals(
            outcome,
            verdict,
            "$where: the scenario declares outcome `$outcome` but the decoder returned `$verdict` " +
                "— the label and the run disagree",
        )

        // Booked inside the arm the verdict really selected, never from the
        // label: the `else` below fails closed, so a vocabulary this runner has
        // no branch for cannot appear in the set.
        when (verdict) {
            "reject" -> {
                tally.outcomes += "reject"
                tally.rejected += 1
                val error = attempt.exceptionOrNull()
                tally.rejectionKinds += rejectionKindOf(error)

                keys.assertBoolean("rejected") { attempt.isFailure }
                // The refusal must land in the ONE family a caller guards a decode
                // with. A frame refused by an exception outside it still fails —
                // past the handler, where the peer never sees it, which is a
                // refusal that behaves like a crash.
                keys.assertBoolean("rejection_is_decode_error") { error is IpcDecodeException }
                keys.assertString("rejection_kind") { rejectionKindOf(error) }
                keys.assertKeyWith("error_names_token") { want ->
                    val token = want.jsonPrimitive.content
                    val raised =
                        error ?: fail("$where: the frame decoded; a `backend` of `$token` must be refused")
                    val text = raised.message ?: ""
                    // Not a bare is-error assertion: a decoder that refused the
                    // frame because it mis-parsed `checksum` implements none of
                    // the clause and would pass one.
                    assertTrue(
                        text.contains(token),
                        "$where: the error must name the offending token `$token`, got: $text",
                    )
                    assertEquals(
                        token,
                        (raised as IpcDecodeException.UnknownBlobBackend).token,
                        "$where: the refusal must CARRY the token, not only mention it in prose",
                    )
                }
            }

            "accept" -> {
                tally.outcomes += "accept"
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
                tally.frameEpochs += delta.epoch
                tally.blobEpochs += blob.epoch

                keys.assertLong("node") { op.node }
                keys.assertLong("offset") { blob.offset }
                keys.assertLong("len") { blob.len }
                keys.assertLong("generation") { blob.generation }
                keys.assertLong("checksum") { blob.checksum }
                // Two epochs, two sources. The Delta's orders deltas; the
                // descriptor's is the arena incarnation the blob was written into.
                keys.assertLong("frame_epoch") { delta.epoch }
                keys.assertLong("blob_epoch") { blob.epoch }
                keys.assertString("decoded_backend") { blob.backend.wire }
                keys.assertBoolean("reencoded_backend_field_present") {
                    reencodedBlobObject(message, codec).containsKey("backend")
                }
            }

            else -> fail("$where: unknown outcome `$verdict`")
        }

        keys.requireAllSatisfied()
    }

    @Test
    fun `an omitted or null blob backend decodes as shm and a bad backend is refused`() =
        proseScope(path).use { replayFixture() }

    /**
     * The replay, inside the prose scope that arms [verifyProse].
     *
     * The scope is the seam: leaving it with a discharge claim still pending
     * fails, so a run that names discharging assertions and never checks the
     * naming is reported rather than trusted (`#lzprosekeyconvention`).
     */
    private fun replayFixture() {
        val fixture = loadFixture()
        val scenarios = fixture.getValue("scenarios").jsonArray
        val tally = Tally()

        // Every scenario is replayed and REPORTED, rather than the first failure
        // aborting the loop. A fixture whose halves differ only by codec is
        // otherwise half-unfalsifiable: one defect throws on the `json` scenario
        // and the `msgpack` twin never runs, so nothing distinguishes a msgpack
        // assertion that holds from one that is never reached. lazily-kt hit
        // exactly that while implementing v1.
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

        // The fixture's vocabulary blocks, asserted against what the run OBSERVED
        // — after the replay, so each one is a claim about work that happened
        // rather than about the file on disk.
        val meta = AssertionKeys("$path assertions", fixture.getValue("assertions").jsonObject)
        // `required_of_binding` stays fixture-vs-literal ON PURPOSE, with
        // `byte_canonical`, `self_describing`, `codec` and `role` in
        // [CodecConformanceTest]. They are corpus DECLARATIONS a binding pins by
        // agreement, not observations a single run can produce a comparable value
        // for, so leaving them is a real limit on the rule rather than an instance
        // of the vacuity the keys below were fixed for (`#lznullformblind`).
        meta.assertString("required_of_binding") { "MUST" }
        meta.assertInt("scenario_count") { tally.replayed }
        meta.assertStrings("codecs") { tally.codecs.sorted() }
        meta.assertStrings("outcomes") { tally.outcomes.sorted() }
        meta.assertKeyWith("backend_forms") { want ->
            assertEquals(
                want.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                tally.backendForms,
                "$path: every declared wire form of `backend` must have been replayed",
            )
        }
        meta.assertKeyWith("rejection_kinds") { want ->
            assertEquals(
                want.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                tally.rejectionKinds,
                "$path: every declared refusal must have been raised, distinguishably",
            )
        }
        // The enum the clause closes, and the VOCABULARY-COMPLETENESS check. The
        // first half is a wire contract: a binding that grew or lost a backend is
        // a wire break. The second half is what v1 could not see — it declared
        // three backends and carried scenarios for two, so a binding knowing only
        // {shm, arrow} rejected `in_process`, conformingly by the letter of the
        // clause, and passed all eight. It is a SET DIFFERENCE against what
        // actually decoded; no count of scenarios reaches it.
        meta.assertKeyWith("backends") { want ->
            val declared = want.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(BlobBackendKind.entries.map { it.wire }, declared, "$path: the closed enum")
            assertEquals(
                emptySet(),
                declared.toSet() - tally.decodedBackends.toSet(),
                "$path: every declared backend must appear as the decoded_backend of some accept " +
                    "scenario — a backend nothing decodes to is a vocabulary the binding does not have",
            )
        }
        // The nine paragraphs the corpus declares in `assertions.prose`, each
        // DISCHARGED by the executable keys that carry its obligation
        // (`#lzprosekeyconvention`). Not excused: a reason naming the
        // discharging assertion in words is unfalsifiable, and this fixture is
        // the one that proved it — nine bindings replayed these same paragraphs
        // and produced four different treatments. Every key named below is
        // asserted by this run, and `verifyProse` at the end checks that.
        meta.proseKey(
            // omitted/null decode as shm; a present unknown is REFUSED through
            // the documented family, naming the token, never normalized.
            "clause",
            listOf("decoded_backend", "rejected", "rejection_is_decode_error", "error_names_token"),
        )
        meta.proseKey(
            // Executable proof that the exact raw text / decoded-hex byte
            // buffer reaches the library decoder rather than a reconstructed
            // proxy.
            "wire_encoding",
            listOf("wire_input_fnv1a64"),
        )
        meta.proseKey(
            // its own words: "a runner MUST check that every backend in
            // `assertions.backends` appears as the `decoded_backend` of some
            // accept scenario" — the set difference inside `backends`.
            "backend_form_vocabulary",
            listOf("backend_forms", "backends", "decoded_backend"),
        )
        meta.proseKey(
            // its own words: "`error_names_token` is the assertion that
            // separates them" — a refusal for the stated reason, not a bare
            // is-error.
            "reject_obligation",
            listOf("error_names_token", "rejection_kind"),
        )
        meta.proseKey(
            // null is the ABSENT form: it decodes as shm and does not survive
            // the re-encode.
            "null_form",
            listOf("decoded_backend", "reencoded_backend_field_present"),
        )
        meta.proseKey(
            // refused, and refused through the SAME family — `rejection_kind`
            // is which of the two refusals, `rejection_is_decode_error` is the
            // family.
            "non_string_form",
            listOf("rejected", "rejection_is_decode_error", "rejection_kind"),
        )
        meta.proseKey(
            // the spec's own worked example: two epochs, two sources, asserted
            // separately per scenario.
            "epoch_disambiguation",
            listOf("frame_epoch", "blob_epoch"),
        )
        meta.proseKey(
            // its four controls, in order: a real decode and the field actually
            // READ (`decoded_backend`), the encoder half
            // (`reencoded_backend_field_present`), the vocabulary (`backends`),
            // and that every scenario ran (`scenario_count`).
            "anti_vacuity",
            listOf("decoded_backend", "reencoded_backend_field_present", "backends", "scenario_count"),
        )
        meta.proseKey(
            // `resolve_wrong_backend`: an unknown kind is refused rather than
            // routed. Observably, nothing decodes to a backend it did not
            // carry, and the unknown token never decodes at all.
            "theorem",
            listOf("rejected", "decoded_backend"),
        )
        meta.requireAllSatisfied()

        // Anti-vacuity. A runner that refused everything would satisfy all four
        // reject scenarios, and one that decoded everything as the default would
        // satisfy six of the ten accepts.
        assertEquals(
            10,
            tally.accepted,
            "ten scenarios decode (omitted, explicit shm, arrow, in_process, null — in both codecs)",
        )
        assertEquals(4, tally.rejected, "both unknown-token and both non-string scenarios are refused")
        // A decoder that ignores the discriminator and hardcodes the default
        // passes every accept scenario except the arrow and in_process ones.
        assertEquals(
            listOf("arrow", "arrow", "in_process", "in_process", "shm", "shm", "shm", "shm", "shm", "shm"),
            tally.decodedBackends.sorted(),
            "the discriminator is READ, not assumed",
        )
        // The two epochs are DIFFERENT numbers from DIFFERENT places. A runner
        // reading the Delta's epoch where the descriptor's is expected fails the
        // per-scenario assertion; this pins that they never coincided by accident,
        // which is exactly what made v1's single `epoch` key unfalsifiable.
        assertEquals(setOf(9L), tally.frameEpochs, "every frame carries Delta epoch 9")
        assertEquals(setOf(5L), tally.blobEpochs, "every descriptor carries arena epoch 5")
        assertEquals(
            emptySet(),
            tally.frameEpochs intersect tally.blobEpochs,
            "the frame epoch and the descriptor epoch must not be the same number, or reading " +
                "either one satisfies both assertions",
        )

        // Rule 6, and the whole point of the convention: every key named by a
        // discharge above must be one this fixture's run really ASSERTED.
        verifyProse(path)
    }
}
