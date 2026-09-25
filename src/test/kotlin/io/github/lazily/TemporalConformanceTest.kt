package io.github.lazily

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

    /**
     * Assert the step's `invalidates` sub-block by its KEY SET, not just the one
     * reader this call names (#lzsubblockkeyset): a reader kind added upstream
     * would otherwise be compared by nothing. The nested tracker owns the whole
     * sub-block, so an unobserved reader fails as an unconsumed key.
     */
    private fun checkInval(
        expected: AssertionKeys,
        reader: String,
        invalidated: Boolean,
    ) = expected.sub("invalidates") { inv ->
        inv.assertBoolean(reader) { invalidated }
    }

    @Test
    fun timerSingleShot() {
        val fx = loadFixture("timer_single_shot.json")
        val ctx = Context()
        val fireAt = fx["initial"]!!.jsonObject["fire_at"]!!.jsonPrimitive.long
        val timer = TimerCell(ctx, fireAt)
        val observed = ctx.computed { get(timer.firedCell) }
        ctx.get(observed)

        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            assertEquals(edge(step), timer.tick(now(step)), "fire edge")
            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            step.getValue("expected").jsonObject.consuming(
                "temporal/timer_single_shot.json steps[$index].expected",
            ) { exp ->
                exp.assertBoolean("fired") { timer.hasFired() }
                exp.assertKeyOutcome("value") { want ->
                    if (want.jsonPrimitive.let { it.longOrNull == null && it.content == "()" }) {
                        timer.value() == Unit
                    } else {
                        timer.value() == null
                    }
                }
                exp.assertKeyOutcome("next_fire") { want -> want.jsonPrimitive.longOrNull == timer.nextFire() }
                checkInval(exp, "fired", !wasCached)
            }
        }
    }

    @Test
    fun intervalPeriodic() {
        val fx = loadFixture("interval_periodic.json")
        val ctx = Context()
        val period = fx["initial"]!!.jsonObject["period"]!!.jsonPrimitive.long
        val iv = IntervalCell(ctx, period)
        val observed = ctx.computed { get(iv.countCell) }
        ctx.get(observed)

        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            assertEquals(edge(step), iv.tick(now(step)), "fire edge")
            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            step.getValue("expected").jsonObject.consuming(
                "temporal/interval_periodic.json steps[$index].expected",
            ) { exp ->
                exp.assertLong("count") { iv.count() }
                exp.assertLong("next_fire") { iv.nextFire() }
                checkInval(exp, "count", !wasCached)
            }
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
        val observed = ctx.computed { get(cron.countCell) }
        ctx.get(observed)

        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            assertEquals(edge(step), cron.tick(now(step)), "fire edge")
            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            step.getValue("expected").jsonObject.consuming(
                "temporal/cron_pattern.json steps[$index].expected",
            ) { exp ->
                exp.assertLong("count") { cron.count() }
                exp.assertKeyOutcome("next_fire") { want -> want.jsonPrimitive.longOrNull == cron.nextFire() }
                checkInval(exp, "count", !wasCached)
            }
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
        val observed = ctx.computed { get(d.expiredCell) }
        ctx.get(observed)

        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            assertEquals(edge(step), d.tick(now(step)), "expiry edge")
            val state = d.state()
            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            step.getValue("expected").jsonObject.consuming(
                "temporal/deadline_expiry.json steps[$index].expected",
            ) { exp ->
                exp.assertString("state") { if (state.isExpired) "Expired" else "Live" }
                exp.assertString("value") { state.value } // value preserved
                checkInval(exp, "state", !wasCached)
            }
        }
    }
}
