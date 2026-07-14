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
 * Cross-language conformance for the embedded-service plane (`#lzservice`) —
 * see `lazily-spec/docs/service.md` and the JSON fixtures under
 * `lazily-spec/conformance/service/`.
 */
class ServiceConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val specPath = Path.of("../lazily-spec/conformance/service/$name")
        val text = if (Files.exists(specPath)) {
            Files.readString(specPath)
        } else {
            javaClass.getResource("/conformance/service/$name")?.readText()
                ?: error("missing conformance fixture: $name")
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray
    private fun inval(step: JsonObject, reader: String) =
        step["expected"]!!.jsonObject["invalidates"]!!.jsonObject[reader]!!.jsonPrimitive.boolean

    private inline fun <reified T : Any> observe(ctx: Context, cell: CellHandle<T>): SlotHandle<Any> {
        val obs = ctx.computed { getCell(cell) as Any }
        ctx.get(obs)
        return obs
    }

    private fun checkInval(ctx: Context, obs: SlotHandle<Any>, step: JsonObject, reader: String) {
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        assertEquals(inval(step, reader), !wasCached, "$reader invalidation")
    }

    private fun wantMap(step: JsonObject, key: String): Map<String, String> =
        step["expected"]!!.jsonObject[key]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

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
                "register" -> d.register(
                    op["service"]!!.jsonPrimitive.content,
                    op["endpoint"]!!.jsonPrimitive.content,
                    op["peer"]!!.jsonPrimitive.long,
                )
                "deregister" -> d.deregister(op["service"]!!.jsonPrimitive.content)
                "evict" -> d.evict(op["peer"]!!.jsonPrimitive.long)
                "resolve" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.contentOrNull,
                    d.resolve(op["service"]!!.jsonPrimitive.content),
                )
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
                "register" -> reg.register(
                    op["service"]!!.jsonPrimitive.content,
                    op["endpoint"]!!.jsonPrimitive.content,
                )
                "deregister" -> reg.deregister(op["service"]!!.jsonPrimitive.content)
                "replay" -> reg.replay()
            }
            assertEquals(wantMap(step, "projection"), reg.projection())
            checkInval(ctx, obs, step, "projection")
        }
    }
}
