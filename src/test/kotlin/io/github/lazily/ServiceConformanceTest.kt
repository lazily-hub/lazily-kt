package io.github.lazily

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
 * Cross-language conformance for the embedded-service plane (`#lzservice`) —
 * see `lazily-spec/docs/service.md` and the JSON fixtures under
 * `lazily-spec/conformance/service/`.
 */
class ServiceConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("service/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray

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
        step: JsonObject,
        reader: String,
    ) {
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        // `invalidates` is an OBJECT, so its KEY SET is the assertion, not just
        // the one reader this call names: a reader added upstream would
        // otherwise be compared by nothing (#lzsubblockkeyset). The nested
        // tracker owns the whole sub-block, so an unobserved reader kind fails
        // as an unconsumed key.
        step["expected"]!!
            .jsonObject
            .getValue("invalidates")
            .jsonObject
            .consumingNested("expected.invalidates[$reader]") { inv ->
                inv.assertBoolean(reader) { !wasCached }
            }
    }

    private fun wantMap(
        step: JsonObject,
        key: String,
    ): Map<String, String> = step["expected"]!!.jsonObject[key]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun health() {
        val fx = loadFixture("health.json")
        val ctx = Context()
        val h = HealthCell(ctx)
        val obs = observe(ctx, h.healthCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            h.set(
                op["name"]!!.jsonPrimitive.content,
                op["up"]!!.jsonPrimitive.boolean,
                op["critical"]!!.jsonPrimitive.boolean,
            )
            assertEquals(step["expected"]!!.jsonObject["health"]!!.jsonPrimitive.content, h.health().name)
            checkInval(ctx, obs, step, "health")
        }
    }

    @Test
    fun readiness() {
        val fx = loadFixture("readiness.json")
        val ctx = Context()
        val r = ReadinessCell(ctx)
        val obs = observe(ctx, r.readyCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            // The runner drives `set` unconditionally, so read the discriminator and
            // refuse anything the fixture did not name (`#lzscenariobodyskip`) —
            // ignoring `op.type` outright replays every op as a `set`.
            val opType = op["type"]!!.jsonPrimitive.content
            if (opType != "set") error("readiness.json: unknown op type '$opType'")
            r.set(op["name"]!!.jsonPrimitive.content, op["ready"]!!.jsonPrimitive.boolean)
            assertEquals(step["expected"]!!.jsonObject["ready"]!!.jsonPrimitive.boolean, r.ready())
            checkInval(ctx, obs, step, "ready")
        }
    }

    @Test
    fun discovery() {
        val fx = loadFixture("discovery.json")
        val ctx = Context()
        val d = DiscoveryCell<Long>(ctx)
        val obs = observe(ctx, d.discoveryCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            when (op["type"]!!.jsonPrimitive.content) {
                "register" ->
                    d.register(
                        op["service"]!!.jsonPrimitive.content,
                        op["endpoint"]!!.jsonPrimitive.content,
                        op["peer"]!!.jsonPrimitive.long,
                    )
                "deregister" -> d.deregister(op["service"]!!.jsonPrimitive.content)
                "evict" -> d.evict(op["peer"]!!.jsonPrimitive.long)
                "resolve" ->
                    assertEquals(
                        step["returns"]!!.jsonPrimitive.contentOrNull,
                        d.resolve(op["service"]!!.jsonPrimitive.content),
                    )
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`). Without
                // this arm an unknown `op.type` drove NOTHING and the step's
                // `expected` block was checked against the untouched cell — the
                // scenario books as replayed while naming behaviour never exercised.
                else -> error("discovery.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
            }
            assertEquals(wantMap(step, "discovery"), d.discovery())
            checkInval(ctx, obs, step, "discovery")
        }
    }

    @Test
    fun serviceRegistry() {
        val fx = loadFixture("service_registry.json")
        val ctx = Context()
        val reg = ServiceRegistry(ctx)
        val obs = observe(ctx, reg.projectionCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            when (op["type"]!!.jsonPrimitive.content) {
                "register" ->
                    reg.register(
                        op["service"]!!.jsonPrimitive.content,
                        op["endpoint"]!!.jsonPrimitive.content,
                    )
                "deregister" -> reg.deregister(op["service"]!!.jsonPrimitive.content)
                "replay" -> reg.replay()
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`).
                else -> error("service_registry.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
            }
            assertEquals(wantMap(step, "projection"), reg.projection())
            checkInval(ctx, obs, step, "projection")
        }
    }
}
