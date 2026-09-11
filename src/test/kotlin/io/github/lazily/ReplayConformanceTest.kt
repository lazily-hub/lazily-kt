package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the canonical replay-equivalence corpus against `Replay.kt` (`#lzreplaykt`).
 *
 * Three fixtures, one obligation each (`lazily-spec/docs/replay-equivalence.md`):
 * the fingerprint is bound to its log and that binding is revalidated before any
 * value compare; a divergence is reported at the first checkpoint where the
 * values parted; the observation encoding agrees with the family on which
 * differences are differences.
 *
 * The corpus declares its subjects in prose, because a JSON fixture cannot carry
 * a reactive graph. [Accumulator] below is this binding's copy of that
 * declaration, kept to the letter — including that `observe` exposes `sum` and
 * `names` under exactly those labels.
 */
class ReplayConformanceTest {
    // -- the corpus's canonical subjects -------------------------------------

    /** `accumulator`, and `drifting_accumulator` when a drift is configured. */
    private class Accumulator(
        private val driftAt: Long? = null,
        private val drift: Long = 0,
    ) : ReplayGraph {
        private var sum = 0L
        private val names = mutableListOf<String>()

        override fun apply(event: ReplayEvent) {
            sum += event.payload as Long
            names += event.name
            if (driftAt != null && event.seq == driftAt) sum += drift
        }

        override fun observe(): Map<String, Any?> = mapOf("sum" to sum, "names" to names.toList())
    }

    // -- obligations 1 and 2 -------------------------------------------------

    @Test
    fun `canonical fingerprint log binding`() {
        driveHarnessFixture("fingerprint_log_binding.json", minimumSteps = 8)
    }

    @Test
    fun `canonical divergence localization`() {
        driveHarnessFixture("divergence_localization.json", minimumSteps = 7)
    }

    private fun driveHarnessFixture(
        name: String,
        minimumSteps: Int,
    ) {
        val rel = "replay/$name"
        val fx = Json.parseToJsonElement(ConformanceFixtures.read(rel)).jsonObject
        assertEquals("Replay", fx.getValue("kind").jsonPrimitive.content)
        assertEquals("ReplayHarness", fx.getValue("model").jsonPrimitive.content)
        val config = fx.getValue("config").jsonObject
        val logs = config.getValue("logs").jsonObject.mapValues { (_, entries) -> logOf(entries.jsonArray) }
        val fingerprints = mutableMapOf<String, ReplayFingerprint>()
        val steps = fx.getValue("steps").jsonArray
        assertTrue(
            steps.size >= minimumSteps,
            "$rel carries ${steps.size} steps, fewer than the $minimumSteps this runner was written against",
        )
        var replayed = 0

        for ((index, element) in steps.withIndex()) {
            val step = element.jsonObject
            val op = step.getValue("op").jsonObject
            val type = op.getValue("type").jsonPrimitive.content
            val where = "$rel step $index ($type)"
            val expected = step.getValue("expected").jsonObject

            if (type == "log_digest_equal") {
                val left = logs.getValue(op.getValue("left").jsonPrimitive.content)
                val right = logs.getValue(op.getValue("right").jsonPrimitive.content)
                assertEquals(
                    step.getValue("returns").jsonPrimitive.boolean,
                    left.digest == right.digest,
                    "$where: returns",
                )
                expected.consuming(where) { }
                replayed++
                continue
            }

            val build = subjectBuilder(config, op)
            val stride =
                op["stride"]?.jsonPrimitive?.long?.toInt()
                    ?: config["stride"]?.jsonPrimitive?.long?.toInt()
                    ?: 1
            val harness = ReplayHarness(build, stride)
            val log = logs.getValue(op.getValue("log").jsonPrimitive.content)

            when (type) {
                "record" -> {
                    val fingerprint = harness.record(log)
                    fingerprints[op.getValue("into").jsonPrimitive.content] = fingerprint
                    val finalSum = finalSum(build, log)
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { "recorded" }
                        a.assertKeyWith("checkpoint_seqs") { want ->
                            assertEquals(
                                want.jsonArray.map { it.jsonPrimitive.long },
                                fingerprint.checkpoints.map { it.seq },
                                "$where: checkpoint_seqs",
                            )
                        }
                        a.assertInt("stride") { fingerprint.stride }
                        a.assertLong("final_sum") { finalSum }
                    }
                    // The one place the digest and the declared state meet. Without it the
                    // fixture accepts a harness that recorded SOME value — the initial 0, a
                    // constant, the log's length — as long as it recorded it consistently.
                    assertEquals(
                        canonicalDigest(finalSum),
                        fingerprint.final.asMap()["sum"],
                        "$where: the recorded `sum` digest is not the digest of the subject's final sum",
                    )
                }

                "prove" -> {
                    var outcome = "ok"
                    var divergences = 0
                    try {
                        harness.prove(log, op.getValue("replays").jsonPrimitive.long.toInt())
                    } catch (error: ReplayDivergenceException) {
                        outcome = "divergent"
                        divergences = error.divergences.size
                    }
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { outcome }
                        a.assertInt("divergences") { divergences }
                    }
                }

                "verify" -> {
                    val fingerprint = fingerprints.getValue(op.getValue("fingerprint").jsonPrimitive.content)
                    var outcome = "ok"
                    var divergences = 0
                    var first: ReplayDivergence? = null
                    try {
                        harness.verify(log, fingerprint)
                    } catch (error: ReplayLogMismatchException) {
                        outcome = "log_mismatch"
                    } catch (error: ReplayStrideMismatchException) {
                        outcome = "stride_mismatch"
                    } catch (error: ReplayDivergenceException) {
                        outcome = "divergent"
                        divergences = error.divergences.size
                        first = error.first
                    }
                    val divergence = first
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { outcome }
                        a.assertInt("divergences") { divergences }
                        a.assertLong("first_divergent_seq") { requireNotNull(divergence).seq }
                        a.assertString("first_divergent_label") { requireNotNull(divergence).label }
                        a.assertString("first_divergent_kind") { requireNotNull(divergence).kind.wireName }
                    }
                }

                "check" -> {
                    val fingerprint = fingerprints.getValue(op.getValue("fingerprint").jsonPrimitive.content)
                    var outcome = "ok"
                    var found: List<ReplayDivergence> = emptyList()
                    try {
                        found = harness.check(log, fingerprint)
                    } catch (error: ReplayLogMismatchException) {
                        outcome = "log_mismatch"
                    } catch (error: ReplayStrideMismatchException) {
                        outcome = "stride_mismatch"
                    }
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { outcome }
                        // Empty because the refusal happened BEFORE any value was compared —
                        // the reporting form still refuses a stale fingerprint.
                        a.assertInt("divergences") { found.size }
                    }
                }

                else -> error("unknown canonical replay operation '$type'")
            }
            replayed++
        }

        assertEquals(
            steps.size,
            replayed,
            "$rel: every step must replay — a skipped step is a fixture that proves less than it claims",
        )
    }

    private fun subjectBuilder(
        config: JsonObject,
        op: JsonObject,
    ): () -> ReplayGraph =
        when (val subject = config.getValue("subject").jsonPrimitive.content) {
            "accumulator" -> {
                { Accumulator() }
            }
            "drifting_accumulator" -> {
                val driftAt = config.getValue("drift_at").jsonPrimitive.long
                val drift = op["drift"]?.jsonPrimitive?.long ?: 0L
                { Accumulator(driftAt = driftAt, drift = drift) }
            }
            else -> error("unknown canonical replay subject '$subject'")
        }

    private fun logOf(entries: JsonArray): ReplayLog =
        ReplayLog(
            entries.map { element ->
                val entry = element.jsonObject
                ReplayEvent(
                    seq = entry.getValue("seq").jsonPrimitive.long,
                    name = entry.getValue("name").jsonPrimitive.content,
                    payload = entry.getValue("payload").jsonPrimitive.long,
                )
            },
        )

    private fun finalSum(
        build: () -> ReplayGraph,
        log: ReplayLog,
    ): Long {
        val subject = build()
        for (event in log) subject.apply(event)
        return subject.observe().getValue("sum") as Long
    }

    // -- obligation 3 --------------------------------------------------------

    /** A value the encoding does not define, as the corpus's `opaque` tag. */
    private class OpaqueValue

    @Test
    fun `canonical encoding equality classes`() {
        val rel = "replay/canonical_encoding_equality.json"
        val fx = Json.parseToJsonElement(ConformanceFixtures.read(rel)).jsonObject
        assertEquals("Replay", fx.getValue("kind").jsonPrimitive.content)
        assertEquals("CanonicalEncoding", fx.getValue("model").jsonPrimitive.content)
        val values = fx.getValue("config").jsonObject.getValue("values").jsonObject
        val steps = fx.getValue("steps").jsonArray
        // 14 = every step a CI clone of published lazily-spec carries today (three of
        // them the member/container-framing rows added by #lzreplayframing). Exact, not
        // a margin: a floor with slack lets a row stop replaying in the dark.
        assertTrue(steps.size >= 14, "$rel carries ${steps.size} steps, fewer than the 14 expected")
        val outcomes = mutableSetOf<Boolean>()
        var replayed = 0

        for ((index, element) in steps.withIndex()) {
            val step = element.jsonObject
            val op = step.getValue("op").jsonObject
            val type = op.getValue("type").jsonPrimitive.content
            val where = "$rel step $index ($type)"
            val expected = step.getValue("expected").jsonObject

            when (type) {
                "digest_equal" -> {
                    val left = canonicalDigest(decode(values.getValue(op.getValue("left").jsonPrimitive.content)))
                    val right = canonicalDigest(decode(values.getValue(op.getValue("right").jsonPrimitive.content)))
                    val equal = left == right
                    assertEquals(step.getValue("returns").jsonPrimitive.boolean, equal, "$where: returns")
                    outcomes += equal
                    expected.consuming(where) { }
                }

                "digest_defined" -> {
                    var defined = true
                    try {
                        canonicalDigest(decode(values.getValue(op.getValue("value").jsonPrimitive.content)))
                    } catch (error: ReplayEncodingException) {
                        defined = false
                    }
                    assertEquals(step.getValue("returns").jsonPrimitive.boolean, defined, "$where: returns")
                    expected.consuming(where) { a ->
                        a.assertString("outcome") { if (defined) "defined" else "encoding_error" }
                    }
                }

                else -> error("unknown canonical encoding operation '$type'")
            }
            replayed++
        }

        assertEquals(steps.size, replayed, "$rel: every step must replay")
        // Both outcomes really occurred: a runner that only ever saw `false` would
        // pass every inequality claim with a thoroughly broken encoding.
        assertEquals(setOf(true, false), outcomes, "$rel: the equality probe never produced both outcomes")
    }

    /**
     * Decode the corpus's tagged value form.
     *
     * Values are type-tagged in the fixture because JSON cannot distinguish int
     * `1` from float `1.0`, and integers carry decimal STRINGS so a value beyond
     * 2^53 stays exact — decoded to [BigInteger] here for the same reason.
     */
    private fun decode(tagged: JsonElement): Any? {
        val value = tagged.jsonObject
        val raw = value["v"]
        return when (val tag = value.getValue("t").jsonPrimitive.content) {
            "int" -> BigInteger(raw!!.jsonPrimitive.content)
            "str" -> raw!!.jsonPrimitive.content
            "float" -> raw!!.jsonPrimitive.content.toDouble()
            "bool" -> raw!!.jsonPrimitive.boolean
            "bytes" -> hexToBytes(raw!!.jsonPrimitive.content)
            "seq" -> raw!!.jsonArray.map { decode(it) }
            "set" -> raw!!.jsonArray.map { decode(it) }.toSet()
            "map" ->
                raw!!.jsonArray.associate { pair ->
                    val entry = pair.jsonArray
                    entry[0].jsonPrimitive.content to decode(entry[1])
                }
            "opaque" -> OpaqueValue()
            else -> error("unknown canonical value tag '$tag'")
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
