package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language conformance for stream windowing (`#lzwindow`) — see
 * `lazily-spec/docs/windowing.md` and the JSON fixtures under
 * `lazily-spec/conformance/windowing/`. All use Sum (Long) aggregates.
 */
class WindowingConformanceTest {
    private val json = Json
    private val sum: (Long, Long) -> Long = { a, b -> a + b }

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("windowing/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray
    private fun ret(step: JsonObject) = step["returns"]!!.jsonPrimitive.longOrNull
    private fun expOut(step: JsonObject) =
        step["expected"]!!.jsonObject["output"]!!.jsonPrimitive.longOrNull
    private fun inval(step: JsonObject) =
        step["expected"]!!.jsonObject["invalidates"]!!.jsonObject["output"]!!.jsonPrimitive.boolean

    private fun observe(ctx: Context, cell: Source<Any>): Computed<Any> {
        val obs = ctx.computed { get(cell) }
        ctx.get(obs)
        return obs
    }

    private fun check(ctx: Context, obs: Computed<Any>, step: JsonObject, out: Long?) {
        assertEquals(expOut(step), out, "output")
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        assertEquals(inval(step), !wasCached, "invalidation")
    }

    @Test
    fun tumblingCount() {
        val fx = loadFixture("tumbling_count.json")
        val ctx = Context()
        val n = fx["config"]!!.jsonObject["n"]!!.jsonPrimitive.long
        val w = TumblingCountWindow(ctx, n, sum)
        val obs = observe(ctx, w.outputCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val e = w.push(step["op"]!!.jsonObject["value"]!!.jsonPrimitive.long)
            assertEquals(ret(step), e, "emit")
            check(ctx, obs, step, w.output())
        }
    }

    @Test
    fun tumblingTime() {
        val fx = loadFixture("tumbling_time.json")
        val ctx = Context()
        val period = fx["config"]!!.jsonObject["period"]!!.jsonPrimitive.long
        val w = TumblingTimeWindow(ctx, period, sum)
        val obs = observe(ctx, w.outputCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            val e = if (op["type"]!!.jsonPrimitive.content == "push") {
                w.push(now, op["value"]!!.jsonPrimitive.long); null
            } else {
                w.tick(now)
            }
            assertEquals(ret(step), e, "emit")
            check(ctx, obs, step, w.output())
        }
    }

    @Test
    fun slidingCount() {
        val fx = loadFixture("sliding_count.json")
        val ctx = Context()
        val cfg = fx["config"]!!.jsonObject
        val w = SlidingWindow(ctx, cfg["size"]!!.jsonPrimitive.long, cfg["slide"]!!.jsonPrimitive.long, sum)
        val obs = observe(ctx, w.outputCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val e = w.push(step["op"]!!.jsonObject["value"]!!.jsonPrimitive.long)
            assertEquals(ret(step), e, "emit")
            check(ctx, obs, step, w.output())
        }
    }

    @Test
    fun session() {
        val fx = loadFixture("session.json")
        val ctx = Context()
        val gap = fx["config"]!!.jsonObject["gap"]!!.jsonPrimitive.long
        val w = SessionWindow(ctx, gap, sum)
        val obs = observe(ctx, w.outputCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            val e = if (op["type"]!!.jsonPrimitive.content == "push") {
                w.push(now, op["value"]!!.jsonPrimitive.long)
            } else {
                w.flush(now)
            }
            assertEquals(ret(step), e, "emit")
            check(ctx, obs, step, w.output())
        }
    }
}
