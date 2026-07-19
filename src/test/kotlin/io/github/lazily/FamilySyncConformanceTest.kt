package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Replays the canonical `lazily-spec/conformance/familysync/` fixture against the
 * native [CrdtPlaneRuntime] family layer — the language-agnostic conformance every
 * binding MUST validate (`lazily-spec/protocol.md` § "Reactive family sync",
 * proved in `lazily-formal` `FamilySync.lean`).
 *
 * A keyed op for a family entry NOT registered locally MATERIALIZES the entry on
 * ingest instead of being dropped, so membership propagates, values are adopted, a
 * later last-writer-wins update converges, re-ingest is idempotent, and a derived
 * aggregate (count of `true` entries) converges across replicas.
 */
class FamilySyncConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("familysync/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun suffixOf(key: NodeKey): String = key.segments().last()

    @Test
    fun materializeOnIngestConformance() {
        val fixture = loadFixture("materialize_on_ingest.json")
        val namespace = fixture.getValue("namespace").jsonPrimitive.content
        assertEquals(
            "bool",
            fixture.getValue("value_type").jsonPrimitive.content,
            "this harness replays the bool value_type",
        )

        for (scenario in fixture.getValue("scenarios").jsonArray) {
            val s = scenario.jsonObject
            val name = s.getValue("name").jsonPrimitive.content
            val originPeer = s.getValue("origin_peer").jsonPrimitive.long
            val targetPeer = s.getValue("target_peer").jsonPrimitive.long

            val ctxO = Context()
            val origin = CrdtPlaneRuntime(originPeer)
            origin.registerFamilyLww(ctxO, namespace, CrdtCodec.bool)

            val ctxT = Context()
            val target = CrdtPlaneRuntime(targetPeer)
            target.registerFamilyLww(ctxT, namespace, CrdtCodec.bool)
            val epoch = target.membershipEpoch() ?: error("membership epoch")
            val epochBefore = ctxT.getCellAny(epoch.id) as Long

            for (set in s.getValue("origin_sets").jsonArray) {
                val o = set.jsonObject
                origin.familySetLww(
                    namespace,
                    o.getValue("key").jsonPrimitive.content,
                    o.getValue("value").jsonPrimitive.boolean,
                )
            }

            val frame = origin.syncFrame()
            val applied = target.ingest(frame)
            assert(applied > 0) { "[$name] ingest applied at least one op" }

            if (s["reingest"]?.jsonPrimitive?.boolean == true) {
                val reapplied = target.ingest(frame)
                assertEquals(
                    s.getValue("expect").jsonObject.getValue("reingest_applied").jsonPrimitive.int,
                    reapplied,
                    "[$name] re-ingest is idempotent",
                )
            }

            val expect = s.getValue("expect").jsonObject

            val gotKeys = target.familyKeys(namespace).map { suffixOf(it) }.sorted()
            val wantKeys = expect.getValue("target_keys").jsonArray.map { it.jsonPrimitive.content }.sorted()
            assertEquals(wantKeys, gotKeys, "[$name] materialized key set")

            assertEquals(
                expect.getValue("target_present_count").jsonPrimitive.int,
                target.familyKeys(namespace).size,
                "[$name] present count",
            )

            for ((key, want) in expect.getValue("target_values").jsonObject) {
                assertEquals(
                    want.jsonPrimitive.boolean,
                    target.familyValueLww<Boolean>(namespace, key),
                    "[$name] value for $key",
                )
            }

            val countTrue = target.familyKeys(namespace)
                .count { target.familyValueLww<Boolean>(namespace, suffixOf(it)) == true }
            assertEquals(
                expect.getValue("target_count_true").jsonPrimitive.int,
                countTrue,
                "[$name] derived count of true entries",
            )

            if (expect["target_epoch_bumped"]?.jsonPrimitive?.boolean == true) {
                assertNotEquals(
                    epochBefore,
                    ctxT.getCellAny(epoch.id) as Long,
                    "[$name] membership epoch bumped on materialize",
                )
            }
        }
    }
}
