package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

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

        for (s in ConformanceScenarios.of("familysync/materialize_on_ingest.json", fixture)) {
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

            // Rung 0 (#lzktbindpending). This block was read key-by-key by direct
            // indexing, so nothing bound it and the rungs above were silent over
            // it. Two real holes the tracker names, both now closed:
            //
            //  - `target_epoch_bumped` was read and then asserted only when it was
            //    TRUE, so the day the corpus carries `false` the assertion would
            //    vanish and the fixture would still report replayed
            //    (#lzconsumednotasserted). Asserted in both directions now.
            //  - `target_values` is object-valued and was iterated without a
            //    key-set check, so a key the fixture declares and the run never
            //    produces was compared by nothing (#lzsubblockkeyset). [sub] moves
            //    that obligation onto the child tracker.
            val expect = s.getValue("expect").jsonObject
            expect.consuming("familysync/materialize_on_ingest.json [$name] expect") { e ->
                if (s["reingest"]?.jsonPrimitive?.boolean == true) {
                    val reapplied = target.ingest(frame)
                    e.assertInt("reingest_applied") { reapplied }
                }

                e.assertStrings("target_keys") {
                    target.familyKeys(namespace).map { suffixOf(it) }.sorted()
                }
                e.assertInt("target_present_count") { target.familyKeys(namespace).size }

                e.sub("target_values") { values ->
                    for (key in values.keys) {
                        values.assertBoolean(key) {
                            target.familyValueLww<Boolean>(namespace, key) == true
                        }
                    }
                }

                e.assertInt("target_count_true") {
                    target
                        .familyKeys(namespace)
                        .count { target.familyValueLww<Boolean>(namespace, suffixOf(it)) == true }
                }

                e.assertBoolean("target_epoch_bumped") {
                    ctxT.getCellAny(epoch.id) as Long != epochBefore
                }
            }
        }
    }
}
