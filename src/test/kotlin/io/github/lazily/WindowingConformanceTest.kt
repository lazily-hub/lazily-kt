package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    private fun expOut(step: JsonObject) = step["expected"]!!.jsonObject["output"]!!.jsonPrimitive.longOrNull

    /**
     * Assert the step's `invalidates` sub-block by its KEY SET, not just the one
     * reader this runner observes (#lzsubblockkeyset): a reader kind added
     * upstream would otherwise be compared by nothing. The nested tracker owns
     * the whole sub-block, so an unobserved reader fails as an unconsumed key.
     */
    private fun checkInval(
        step: JsonObject,
        invalidated: Boolean,
    ) = step["expected"]!!
        .jsonObject
        .getValue("invalidates")
        .jsonObject
        .consumingNested("windowing expected.invalidates") { inv ->
            inv.assertBoolean("output") { invalidated }
        }

    private fun observe(
        ctx: Context,
        cell: Source<Any>,
    ): Computed<Any> {
        val obs = ctx.computed { get(cell) }
        ctx.get(obs)
        return obs
    }

    private fun check(
        ctx: Context,
        obs: Computed<Any>,
        step: JsonObject,
        out: Long?,
    ) {
        assertEquals(expOut(step), out, "output")
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        checkInval(step, !wasCached)
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
            // The runner drives `push` unconditionally, so read the discriminator and
            // refuse anything the fixture did not name (`#lzscenariobodyskip`).
            val opType = step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content
            if (opType != "push") error("tumbling_count.json: unknown op type '$opType'")
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
            // `tick` used to be the bare `else`, so ANY unrecognised op.type
            // replayed as a tick — the fixture named one thing and the runner did
            // another while the scenario booked as replayed (`#lzscenariobodyskip`).
            val e =
                when (val opType = op["type"]!!.jsonPrimitive.content) {
                    "push" -> {
                        w.push(now, op["value"]!!.jsonPrimitive.long)
                        null
                    }
                    "tick" -> w.tick(now)
                    else -> error("tumbling_time.json: unknown op type '$opType'")
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
            // The runner drives `push` unconditionally, so read the discriminator and
            // refuse anything the fixture did not name (`#lzscenariobodyskip`).
            val opType = step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content
            if (opType != "push") error("sliding_count.json: unknown op type '$opType'")
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
            // `flush` used to be the bare `else` (`#lzscenariobodyskip`).
            val e =
                when (val opType = op["type"]!!.jsonPrimitive.content) {
                    "push" -> w.push(now, op["value"]!!.jsonPrimitive.long)
                    "flush" -> w.flush(now)
                    else -> error("session.json: unknown op type '$opType'")
                }
            assertEquals(ret(step), e, "emit")
            check(ctx, obs, step, w.output())
        }
    }
}
