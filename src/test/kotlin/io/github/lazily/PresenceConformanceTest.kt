package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test

/**
 * Cross-language conformance for the presence + ephemeral plane (`#lzpresence`)
 * — see `lazily-spec/docs/presence.md` and the JSON fixtures under
 * `lazily-spec/conformance/presence/`.
 */
class PresenceConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("presence/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray

    private fun presentJson(present: Map<Long, String>): JsonObject =
        JsonObject(present.mapKeys { (peer, _) -> peer.toString() }.mapValues { (_, value) -> JsonPrimitive(value) })

    private inline fun <reified T : Any> observe(
        ctx: Context,
        cell: Source<T>,
    ): Computed<Any> {
        val obs = ctx.computed { get(cell) as Any }
        ctx.get(obs)
        return obs
    }

    private fun checkInval(
        ctx: Context,
        obs: Computed<Any>,
        expected: AssertionKeys,
        reader: String,
    ) {
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        // `invalidates` is an OBJECT, so its KEY SET is the assertion, not just
        // the one reader this call names: a reader added upstream would
        // otherwise be compared by nothing (#lzsubblockkeyset). The nested
        // tracker owns the whole sub-block, so an unobserved reader kind fails
        // as an unconsumed key.
        expected.sub("invalidates") { invalidates ->
            invalidates.assertBoolean(reader) { !wasCached }
        }
    }

    @Test
    fun presence() {
        val fixture = "presence/presence.json"
        val fx = loadFixture("presence.json")
        val ctx = Context()
        val ttl = fx["config"]!!.jsonObject["ttl"]!!.jsonPrimitive.long
        val cell = PresenceCell<Long, String>(ctx, ttl)
        val obs = observe(ctx, cell.presentCell)
        steps(fx).forEachIndexed { index, element ->
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "heartbeat" -> cell.heartbeat(op["peer"]!!.jsonPrimitive.long, op["value"]!!.jsonPrimitive.content, now)
                "evict" -> cell.evict(op["peer"]!!.jsonPrimitive.long, now)
                "tick" -> cell.tick(now)
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`). Without
                // this arm an unknown `op.type` drove NOTHING and the step's
                // `expected` block was checked against the untouched cell — the
                // scenario books as replayed while naming behaviour never exercised.
                else -> error("presence.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
            }
            val expected = AssertionKeys("$fixture steps[$index].expected", step["expected"]!!.jsonObject)
            expected.assertKeyValue("present") { presentJson(cell.present()) }
            checkInval(ctx, obs, expected, "present")
            expected.requireAllSatisfied()
        }
    }

    @Test
    fun awareness() {
        val fixture = "presence/awareness.json"
        val fx = loadFixture("awareness.json")
        val ctx = Context()
        val ttl = fx["config"]!!.jsonObject["ttl"]!!.jsonPrimitive.long
        val cell = AwarenessCell<Long, String>(ctx, ttl)
        val obs = observe(ctx, cell.presentCell)
        steps(fx).forEachIndexed { index, element ->
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "set" -> cell.set(op["peer"]!!.jsonPrimitive.long, op["value"]!!.jsonPrimitive.content, now)
                "tick" -> cell.tick(now)
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`).
                else -> error("awareness.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
            }
            val expected = AssertionKeys("$fixture steps[$index].expected", step["expected"]!!.jsonObject)
            expected.assertKeyValue("present") { presentJson(cell.present()) }
            checkInval(ctx, obs, expected, "present")
            expected.requireAllSatisfied()
        }
    }

    @Test
    fun ephemeral() {
        val fixture = "presence/ephemeral.json"
        val fx = loadFixture("ephemeral.json")
        val ctx = Context()
        val cell = EphemeralCell<String>(ctx)
        val obs = observe(ctx, cell.valueCell)
        steps(fx).forEachIndexed { index, element ->
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "set" -> cell.set(op["value"]!!.jsonPrimitive.content, now, op["ttl"]!!.jsonPrimitive.long)
                "tick" -> cell.tick(now)
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`).
                else -> error("ephemeral.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
            }
            val expected = AssertionKeys("$fixture steps[$index].expected", step["expected"]!!.jsonObject)
            expected.assertKeyValue("value") { cell.value()?.let(::JsonPrimitive) ?: JsonNull }
            checkInval(ctx, obs, expected, "value")
            expected.requireAllSatisfied()
        }
    }
}
