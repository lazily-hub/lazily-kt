package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language conformance for fault-tolerance primitives (`#lzresilience`) —
 * see `lazily-spec/docs/resilience.md` and the JSON fixtures under
 * `lazily-spec/conformance/resilience/`.
 */
class ResilienceConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("resilience/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray
    private fun inval(step: JsonObject, reader: String) =
        step["expected"]!!.jsonObject["invalidates"]!!.jsonObject[reader]!!.jsonPrimitive.boolean

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
    fun circuitBreaker() {
        val fx = loadFixture("circuit_breaker.json")
        val ctx = Context()
        val cfg = fx["config"]!!.jsonObject
        val cb = CircuitBreakerCell(
            ctx,
            cfg["window"]!!.jsonPrimitive.int,
            cfg["failure_threshold"]!!.jsonPrimitive.int,
            cfg["reset_timeout"]!!.jsonPrimitive.long,
        )
        val obs = observe(ctx, cb.stateCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            when (op["type"]!!.jsonPrimitive.content) {
                "record" -> cb.record(op["success"]!!.jsonPrimitive.boolean, op["now"]!!.jsonPrimitive.long)
                "allow" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.boolean,
                    cb.allow(op["now"]!!.jsonPrimitive.long),
                )
            }
            assertEquals(step["expected"]!!.jsonObject["state"]!!.jsonPrimitive.content, cb.state().name)
            checkInval(ctx, obs, step, "state")
        }
    }

    @Test
    fun retry() {
        val fx = loadFixture("retry.json")
        val ctx = Context()
        val cfg = fx["config"]!!.jsonObject
        val r = RetryPolicyCell(ctx, cfg["base"]!!.jsonPrimitive.long, cfg["cap"]!!.jsonPrimitive.long)
        val obs = observe(ctx, r.delayCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            assertEquals(step["returns"]!!.jsonPrimitive.long, r.nextDelay(), "delay")
            assertEquals(step["expected"]!!.jsonObject["delay"]!!.jsonPrimitive.long, r.delay())
            checkInval(ctx, obs, step, "delay")
        }
    }

    @Test
    fun bulkhead() {
        val fx = loadFixture("bulkhead.json")
        val ctx = Context()
        val b = BulkheadCell(ctx, fx["config"]!!.jsonObject["capacity"]!!.jsonPrimitive.long)
        val obs = observe(ctx, b.inUseCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            when (step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content) {
                "acquire" -> assertEquals(step["returns"]!!.jsonPrimitive.booleanOrNull, b.acquire())
                "release" -> b.release()
            }
            assertEquals(step["expected"]!!.jsonObject["in_use"]!!.jsonPrimitive.long, b.permitsInUse())
            checkInval(ctx, obs, step, "in_use")
        }
    }

    @Test
    fun timeout() {
        val fx = loadFixture("timeout.json")
        val ctx = Context()
        val t = TimeoutCell(ctx)
        val obs = observe(ctx, t.timedOutCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            val e = when (op["type"]!!.jsonPrimitive.content) {
                "arm" -> {
                    t.arm(now, op["timeout"]!!.jsonPrimitive.long); false
                }
                else -> t.tick(now)
            }
            assertEquals(step["returns"]!!.jsonPrimitive.boolean, e, "edge")
            assertEquals(step["expected"]!!.jsonObject["is_timed_out"]!!.jsonPrimitive.boolean, t.isTimedOut())
            checkInval(ctx, obs, step, "is_timed_out")
        }
    }
}
