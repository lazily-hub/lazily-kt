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
 * Cross-language conformance for the temporal source primitives (`#lztime`) —
 * see `lazily-spec/docs/temporal-sources.md` and the JSON fixtures under
 * `lazily-spec/conformance/temporal/`.
 *
 * These are **compute** fixtures: lazily-kt loads the `initial` state, replays
 * each `tick(now)` op, and asserts the fire edge (`returns`), the projected
 * reader values, and — the core of the spec — that the primary reader
 * invalidates exactly on the fire edge (observed via `ctx.isSet` on a wrapping
 * `computed`).
 */
class TemporalConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("temporal/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray
    private fun now(step: JsonObject) = step["op"]!!.jsonObject["now"]!!.jsonPrimitive.long
    private fun edge(step: JsonObject) = step["returns"]!!.jsonPrimitive.boolean
    private fun expected(step: JsonObject) = step["expected"]!!.jsonObject
    private fun invalidates(step: JsonObject, reader: String) =
        expected(step)["invalidates"]!!.jsonObject[reader]!!.jsonPrimitive.boolean

    @Test
    fun timerSingleShot() {
        val fx = loadFixture("timer_single_shot.json")
        val ctx = Context()
        val fireAt = fx["initial"]!!.jsonObject["fire_at"]!!.jsonPrimitive.long
        val timer = TimerCell(ctx, fireAt)
        val observed = ctx.computed { getCell(timer.firedCell) }
        ctx.get(observed)

        for (element in steps(fx)) {
            val step = element.jsonObject
            assertEquals(edge(step), timer.tick(now(step)), "fire edge")
            val exp = expected(step)
            assertEquals(exp["fired"]!!.jsonPrimitive.boolean, timer.hasFired())
            if (exp["value"]!!.jsonPrimitive.let { it.longOrNull == null && it.content == "()" }) {
                assertEquals(Unit, timer.value())
            } else {
                assertEquals(null, timer.value())
            }
            assertEquals(exp["next_fire"].let { if (it == null || it.jsonPrimitive.longOrNull == null) null else it.jsonPrimitive.long }, timer.nextFire())

            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            assertEquals(invalidates(step, "fired"), !wasCached, "invalidation")
        }
    }

    @Test
    fun intervalPeriodic() {
        val fx = loadFixture("interval_periodic.json")
        val ctx = Context()
        val period = fx["initial"]!!.jsonObject["period"]!!.jsonPrimitive.long
        val iv = IntervalCell(ctx, period)
        val observed = ctx.computed { getCell(iv.countCell) }
        ctx.get(observed)

        for (element in steps(fx)) {
            val step = element.jsonObject
            assertEquals(edge(step), iv.tick(now(step)), "fire edge")
            val exp = expected(step)
            assertEquals(exp["count"]!!.jsonPrimitive.long, iv.count())
            assertEquals(exp["next_fire"]!!.jsonPrimitive.long, iv.nextFire())

            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            assertEquals(invalidates(step, "count"), !wasCached, "invalidation")
        }
    }

    @Test
    fun cronPattern() {
        val fx = loadFixture("cron_pattern.json")
        val ctx = Context()
        val init = fx["initial"]!!.jsonObject
        val cycle = init["cycle"]!!.jsonPrimitive.long
        val offsets = init["offsets"]!!.jsonArray.map { it.jsonPrimitive.long }
        val cron = CronCell(ctx, cycle, offsets)
        val observed = ctx.computed { getCell(cron.countCell) }
        ctx.get(observed)

        for (element in steps(fx)) {
            val step = element.jsonObject
            assertEquals(edge(step), cron.tick(now(step)), "fire edge")
            val exp = expected(step)
            assertEquals(exp["count"]!!.jsonPrimitive.long, cron.count())
            assertEquals(exp["next_fire"]!!.jsonPrimitive.longOrNull, cron.nextFire())

            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            assertEquals(invalidates(step, "count"), !wasCached, "invalidation")
        }
    }

    @Test
    fun deadlineExpiry() {
        val fx = loadFixture("deadline_expiry.json")
        val ctx = Context()
        val init = fx["initial"]!!.jsonObject
        val value = init["value"]!!.jsonPrimitive.content
        val deadline = init["deadline"]!!.jsonPrimitive.long
        val d = DeadlineCell(ctx, value, deadline)
        val observed = ctx.computed { getCell(d.expiredCell) }
        ctx.get(observed)

        for (element in steps(fx)) {
            val step = element.jsonObject
            assertEquals(edge(step), d.tick(now(step)), "expiry edge")
            val exp = expected(step)
            val state = d.state()
            assertEquals(exp["state"]!!.jsonPrimitive.content == "Expired", state.isExpired)
            assertEquals(exp["value"]!!.jsonPrimitive.content, state.value) // value preserved

            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            assertEquals(invalidates(step, "state"), !wasCached, "invalidation")
        }
    }
}
