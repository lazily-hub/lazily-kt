package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Wire-dispatch verdicts for the decode paths another process or another binding
 * writes into.
 *
 * A decoder's default is only knowable from the outside if a test names it. Every
 * lenient outcome asserted here is a PINNED wire contract — changing it is a wire
 * break, not a refactor. Every rejection asserted here is a fail-closed verdict:
 * the value used to be absorbed silently, and the test is what stops it drifting
 * back to a silent default.
 */
class WireDispatchFailClosedTest {
    private val json = Json

    private fun blobRefJson(backend: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("offset", 0L)
            put("len", 3L)
            put("generation", 1L)
            put("epoch", 0L)
            put("checksum", 42L)
            backend()
        }

    // -- ShmBlobRef.backend: INTENTIONAL leniency, pinned --------------------

    /**
     * PINNED LENIENCY. A descriptor with no `backend` key decodes as [BlobBackendKind.Shm].
     * The writer omits the field for the default backend, so this is the shape every
     * conforming peer actually emits.
     */
    @Test
    fun absentBackendDecodesAsShm() {
        val ref = ShmBlobRef.fromJson(blobRefJson())
        assertEquals(BlobBackendKind.Shm, ref.backend)
        assertTrue(ref.backend.isDefault)
    }

    /** A known non-default backend string still round-trips exactly. */
    @Test
    fun knownBackendStringDecodesExactly() {
        assertEquals(
            BlobBackendKind.Arrow,
            ShmBlobRef.fromJson(blobRefJson { put("backend", "arrow") }).backend,
        )
        assertEquals(
            BlobBackendKind.InProcess,
            ShmBlobRef.fromJson(blobRefJson { put("backend", "in_process") }).backend,
        )
        // The redundant-but-legal explicit spelling of the default.
        assertEquals(
            BlobBackendKind.Shm,
            ShmBlobRef.fromJson(blobRefJson { put("backend", "shm") }).backend,
        )
    }

    // -- ShmBlobRef.backend: FAIL CLOSED ------------------------------------

    /**
     * FAIL CLOSED. A PRESENT `backend` outside the enum is REFUSED, naming the
     * token (`#lzblobbackendstrict`).
     *
     * This site used to normalize an unknown token to [BlobBackendKind.Shm] and
     * called it forward-compat. The forward-compatibility channel for this field is
     * its ABSENCE — asserted just above — and that is the only one: a new backend
     * enters by adding an enum value, so an unrecognised token is a corrupt or
     * non-conforming producer, never a newer peer. Normalizing routes a non-shm
     * descriptor into the shm table, which is exactly what `resolve_wrong_backend`
     * says never happens; the checksum then usually catches it, which turns a
     * structural guarantee into a probabilistic one.
     */
    @Test
    fun unknownBackendTokenIsRejectedAndNamesTheToken() {
        for (bogus in listOf("quantum", "rdma", "SHM", "Arrow", "in-process", "")) {
            val e =
                assertFailsWith<IllegalArgumentException>("backend=$bogus must be rejected") {
                    ShmBlobRef.fromJson(blobRefJson { put("backend", bogus) })
                }
            assertTrue(
                e.message!!.contains("unknown blob backend") && e.message!!.contains(bogus),
                "message must name the offending token, got: ${e.message}",
            )
        }
    }

    /**
     * FAIL CLOSED. A `backend` that is present but not a JSON string is a shape
     * violation, not a forward-compat backend name. It used to be absorbed as Shm by
     * the `as? JsonPrimitive` cast.
     */
    @Test
    fun nonStringBackendIsRejected() {
        val numeric = assertFailsWith<IllegalStateException> {
            ShmBlobRef.fromJson(blobRefJson { put("backend", 7) })
        }
        assertTrue(numeric.message!!.contains("backend must be a string"), numeric.message!!)

        assertFailsWith<IllegalStateException> {
            ShmBlobRef.fromJson(
                json.parseToJsonElement(
                    """{"offset":0,"len":3,"generation":1,"epoch":0,"checksum":42,"backend":{"name":"arrow"}}""",
                ).jsonObject,
            )
        }
        assertFailsWith<IllegalStateException> {
            ShmBlobRef.fromJson(
                json.parseToJsonElement(
                    """{"offset":0,"len":3,"generation":1,"epoch":0,"checksum":42,"backend":true}""",
                ).jsonObject,
            )
        }
    }

    /**
     * FAIL CLOSED, msgpack half. The binary codec unpacks into the same value tree
     * the `json` codec parses, so a `backend` that is an integer or a bool on the
     * MessagePack wire must reach the same refusal rather than being coerced into a
     * token. Asserted separately because "both codecs share a tree" is a property
     * of the current implementation, not of the wire contract.
     */
    @Test
    fun nonStringBackendIsRejectedOverMsgpackToo() {
        for (bogus in listOf(JsonPrimitive(7), JsonPrimitive(true), buildJsonObject { put("name", "arrow") })) {
            assertFailsWith<IllegalStateException>("msgpack backend=$bogus must be rejected") {
                ShmBlobRef.fromJson(
                    MsgpackCodec.unpack(MsgpackCodec.pack(blobRefJson { put("backend", bogus) })),
                )
            }
        }
    }

    // -- StateChart state `kind` discriminator -------------------------------

    private fun chart(states: String): JsonObject =
        json.parseToJsonElement("""{"initial":"root","states":$states}""").jsonObject

    /**
     * PINNED LENIENCY. An ABSENT `kind` is the normal case and is still inferred
     * structurally: no `initial` key makes a state atomic, an `initial` key makes it
     * compound. Every canonical statechart fixture depends on this.
     */
    @Test
    fun absentKindIsStillInferredStructurally() {
        val def = ChartDef.fromJson(chart("""{"root":{"initial":"a"},"a":{"parent":"root"}}"""))
        assertEquals(Kind.Compound, def.states.getValue("root").kind)
        assertEquals(Kind.Atomic, def.states.getValue("a").kind)
    }

    /** The one defined `kind` value still decodes. */
    @Test
    fun finalKindDecodes() {
        val def =
            ChartDef.fromJson(
                chart("""{"root":{"initial":"a"},"a":{"parent":"root","kind":"final"}}"""),
            )
        assertEquals(Kind.Final, def.states.getValue("a").kind)
    }

    /**
     * FAIL CLOSED. `kind` is a closed discriminator whose only defined value is
     * `"final"`. An unrecognised value used to fall through to Compound/Atomic, which
     * runs a different chart than the author wrote with no diagnostic anywhere.
     */
    @Test
    fun unknownStateKindIsRejected() {
        for (bogus in listOf("atomic", "compound", "parallel", "history", "")) {
            val e =
                assertFailsWith<IllegalStateException> {
                    ChartDef.fromJson(
                        chart("""{"root":{"initial":"a"},"a":{"parent":"root","kind":"$bogus"}}"""),
                    )
                }
            assertTrue(
                e.message!!.contains("unknown state kind") && e.message!!.contains(bogus),
                "message must name the offending value, got: ${e.message}",
            )
        }
    }

    /**
     * FAIL CLOSED. An explicit `"kind": null` is malformed, not "kind absent"; so is a
     * `kind` of any non-string shape.
     */
    @Test
    fun malformedStateKindShapesAreRejected() {
        for (bogus in listOf("null", "1", "true", "{}", "[]")) {
            assertFailsWith<IllegalStateException>("kind=$bogus must be rejected") {
                ChartDef.fromJson(
                    chart("""{"root":{"initial":"a"},"a":{"parent":"root","kind":$bogus}}"""),
                )
            }
        }
    }

    // -- StateChart transition `internal` flag -------------------------------

    /** PINNED LENIENCY. An absent `internal` flag means an external transition. */
    @Test
    fun absentInternalFlagDefaultsToExternal() {
        val def =
            ChartDef.fromJson(
                chart("""{"root":{"initial":"a"},"a":{"parent":"root","on":{"E":{"target":"a"}}}}"""),
            )
        assertEquals(false, def.states.getValue("a").transitions.getValue("E").internal)
    }

    /** A real boolean still decodes on both sides. */
    @Test
    fun booleanInternalFlagDecodes() {
        val def =
            ChartDef.fromJson(
                chart(
                    """{"root":{"initial":"a"},"a":{"parent":"root",""" +
                        """"on":{"E":{"target":"a","internal":true},"F":{"target":"a","internal":false}}}}""",
                ),
            )
        val transitions = def.states.getValue("a").transitions
        assertEquals(true, transitions.getValue("E").internal)
        assertEquals(false, transitions.getValue("F").internal)
    }

    /**
     * FAIL CLOSED. A non-boolean `internal` used to read as false, quietly flipping
     * exit/re-entry semantics for that transition — visible only as a wrong `actions`
     * list several steps later, never as a decode error.
     */
    @Test
    fun nonBooleanInternalFlagIsRejected() {
        // "true" is listed first on purpose: kotlinx `booleanOrNull` parses the
        // QUOTED string as `true`, so a schema-violating JSON string used to be
        // silently coerced into a boolean the fixture never declared.
        for (bogus in listOf("\"true\"", "\"false\"", "1", "0", "{}", "[]", "null")) {
            val e =
                assertFailsWith<IllegalStateException> {
                    ChartDef.fromJson(
                        chart(
                            """{"root":{"initial":"a"},"a":{"parent":"root",""" +
                                """"on":{"E":{"target":"a","internal":$bogus}}}}""",
                        ),
                    )
                }
            assertTrue(
                e.message!!.contains("`internal` must be a boolean"),
                "unexpected message for internal=$bogus: ${e.message}",
            )
        }
    }
}
