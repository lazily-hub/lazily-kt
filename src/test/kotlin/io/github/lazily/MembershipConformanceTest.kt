package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
        val config = MembershipConfig(
            phiThreshold = cfg["phi_threshold"]!!.jsonPrimitive.double,
            suspectTimeout = cfg["suspect_timeout"]!!.jsonPrimitive.long,
            maxSamples = cfg["max_samples"]!!.jsonPrimitive.int,
            minStd = cfg["min_std"]!!.jsonPrimitive.double,
        )
        val ctx = Context()
        val m = MembershipCell<Long>(ctx, config)
        val observed = ctx.computed { get(m.peerSetCell) }
        ctx.get(observed)

        for (element in fx["steps"]!!.jsonArray) {
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

            val exp = step["expected"]!!.jsonObject
            for ((peer, want) in exp["states"]!!.jsonObject) {
                assertEquals(
                    want.jsonPrimitive.content,
                    m.state(peer.toLong())?.name,
                    "state of peer $peer",
                )
            }
            val wantSet = exp["alive_set"]!!.jsonArray.map { it.jsonPrimitive.long }.toSortedSet()
            assertEquals(wantSet, m.peerSet().toSortedSet(), "alive_set")

            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            assertEquals(exp["invalidates"]!!.jsonPrimitive.boolean, !wasCached, "invalidation")
        }
    }
}
