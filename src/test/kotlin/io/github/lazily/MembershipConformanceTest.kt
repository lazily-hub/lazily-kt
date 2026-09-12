package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language conformance for membership + failure detection (`#lzmemb`) —
 * see `lazily-spec/docs/membership.md` and the JSON fixture under
 * `lazily-spec/conformance/membership/`.
 */
class MembershipConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("membership/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun membershipLifecycle() {
        val fx = loadFixture("membership_lifecycle.json")
        val cfg = fx["config"]!!.jsonObject
        val config =
            MembershipConfig(
                phiThreshold = cfg["phi_threshold"]!!.jsonPrimitive.double,
                suspectTimeout = cfg["suspect_timeout"]!!.jsonPrimitive.long,
                maxSamples = cfg["max_samples"]!!.jsonPrimitive.int,
                minStd = cfg["min_std"]!!.jsonPrimitive.double,
            )
        val ctx = Context()
        val m = MembershipCell<Long>(ctx, config)
        val observed = ctx.computed { get(m.peerSetCell) }
        ctx.get(observed)

        for ((i, element) in fx["steps"]!!.jsonArray.withIndex()) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "join" -> m.join(op["peer"]!!.jsonPrimitive.long, now)
                "heartbeat" -> m.heartbeat(op["peer"]!!.jsonPrimitive.long, now)
                "leave" -> m.leave(op["peer"]!!.jsonPrimitive.long, now)
                "tick" -> m.tick(now)
                else -> error("unknown op")
            }

            // Rung 0 (#lzktbindpending). Read key-by-key by direct indexing
            // before, so nothing bound it and the rungs above saw none of these
            // nine blocks. `states` is object-valued and was iterated without a
            // key-set check, so a peer the fixture names and the run never
            // tracked was compared by nothing (#lzsubblockkeyset) — it DESCENDS
            // now, and the child tracker owns every peer beneath.
            val exp = step["expected"]!!.jsonObject
            exp.consuming("membership/membership_lifecycle.json steps[$i].expected") { e ->
                e.sub("states") { states ->
                    for (peer in states.keys) {
                        states.assertString(peer) { m.state(peer.toLong())?.name }
                    }
                }
                e.assertKeyWith("alive_set") { want ->
                    assertEquals(
                        want.jsonArray.map { it.jsonPrimitive.long }.toSortedSet(),
                        m.peerSet().toSortedSet(),
                        "alive_set",
                    )
                }

                val wasCached = ctx.isSet(observed)
                ctx.get(observed)
                e.assertBoolean("invalidates") { !wasCached }
            }
        }
    }
}
