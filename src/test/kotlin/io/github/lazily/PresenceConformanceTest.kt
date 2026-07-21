package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

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
    private fun inval(step: JsonObject, reader: String) =
        step["expected"]!!.jsonObject["invalidates"]!!.jsonObject[reader]!!.jsonPrimitive.boolean

    private fun wantMap(step: JsonObject): Map<Long, String> =
        step["expected"]!!.jsonObject["present"]!!.jsonObject
            .entries.associate { (k, v) -> k.toLong() to v.jsonPrimitive.content }

    private inline fun <reified T : Any> observe(ctx: Context, cell: Source<T>): Computed<Any> {
        val obs = ctx.computed { getCell(cell) as Any }
        ctx.get(obs)
        return obs
    }

    private fun checkInval(ctx: Context, obs: Computed<Any>, step: JsonObject, reader: String) {
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        assertEquals(inval(step, reader), !wasCached, "$reader invalidation")
    }

    @Test
    fun presence() {
        val fx = loadFixture("presence.json")
        val ctx = Context()
        val ttl = fx["config"]!!.jsonObject["ttl"]!!.jsonPrimitive.long
        val cell = PresenceCell<Long, String>(ctx, ttl)
        val obs = observe(ctx, cell.presentCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "heartbeat" -> cell.heartbeat(op["peer"]!!.jsonPrimitive.long, op["value"]!!.jsonPrimitive.content, now)
                "evict" -> cell.evict(op["peer"]!!.jsonPrimitive.long, now)
                "tick" -> cell.tick(now)
            }
            assertEquals(wantMap(step), cell.present())
            checkInval(ctx, obs, step, "present")
        }
    }

    @Test
    fun awareness() {
        val fx = loadFixture("awareness.json")
        val ctx = Context()
        val ttl = fx["config"]!!.jsonObject["ttl"]!!.jsonPrimitive.long
        val cell = AwarenessCell<Long, String>(ctx, ttl)
        val obs = observe(ctx, cell.presentCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "set" -> cell.set(op["peer"]!!.jsonPrimitive.long, op["value"]!!.jsonPrimitive.content, now)
                "tick" -> cell.tick(now)
            }
            assertEquals(wantMap(step), cell.present())
            checkInval(ctx, obs, step, "present")
        }
    }

    @Test
    fun ephemeral() {
        val fx = loadFixture("ephemeral.json")
        val ctx = Context()
        val cell = EphemeralCell<String>(ctx)
        val obs = observe(ctx, cell.valueCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "set" -> cell.set(op["value"]!!.jsonPrimitive.content, now, op["ttl"]!!.jsonPrimitive.long)
                "tick" -> cell.tick(now)
            }
            assertEquals(step["expected"]!!.jsonObject["value"]!!.jsonPrimitive.contentOrNull, cell.value())
            checkInval(ctx, obs, step, "value")
        }
    }
}
