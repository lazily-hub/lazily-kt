package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language conformance for the rate-shaping operators (`#lzrateshape`) —
 * see `lazily-spec/docs/rate-shaping.md` and the JSON fixtures under
 * `lazily-spec/conformance/rateshape/`.
 *
 * Compute fixtures: replay each `input`/`tick` op, assert the emitted value
 * (`returns`), the projected `output`, and that the `output` reader invalidates
 * exactly on an emit (observed via `ctx.isSet` on a wrapping `computed`).
 */
class RateShapeConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("rateshape/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray
    private fun opType(step: JsonObject) = step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content
    private fun opNow(step: JsonObject) = step["op"]!!.jsonObject["now"]!!.jsonPrimitive.long
    private fun opVal(step: JsonObject) = step["op"]!!.jsonObject["value"]!!.jsonPrimitive.content
    private fun ret(step: JsonObject) = step["returns"]!!.jsonPrimitive.contentOrNull
    private fun expOutput(step: JsonObject) =
        step["expected"]!!.jsonObject["output"]!!.jsonPrimitive.contentOrNull
    private fun expInval(step: JsonObject) =
        step["expected"]!!.jsonObject["invalidates"]!!.jsonObject["output"]!!.jsonPrimitive.boolean

    /** Replay a fixture given a per-step driver returning the emitted value. */
    private fun run(
        ctx: Context,
        fx: JsonObject,
        outputCell: CellHandle<Any>,
        readOutput: () -> String?,
        drive: (JsonObject) -> String?,
    ) {
        val observed = ctx.computed { getCell(outputCell) }
        ctx.get(observed)
        for (element in steps(fx)) {
            val step = element.jsonObject
            assertEquals(ret(step), drive(step), "emit")
            assertEquals(expOutput(step), readOutput(), "output")
            val wasCached = ctx.isSet(observed)
            ctx.get(observed)
            assertEquals(expInval(step), !wasCached, "invalidation")
        }
    }

    @Test
    fun debounce() {
        val fx = loadFixture("debounce.json")
        val ctx = Context()
        val quiet = fx["initial"]!!.jsonObject["quiet"]!!.jsonPrimitive.long
        val cell = DebounceCell<String>(ctx, quiet)
        run(ctx, fx, cell.outputCell, { cell.output() }) { step ->
            if (opType(step) == "input") {
                cell.input(opNow(step), opVal(step)); null
            } else {
                cell.tick(opNow(step))
            }
        }
    }

    private fun runThrottle(name: String, edge: ThrottleEdge) {
        val fx = loadFixture(name)
        val ctx = Context()
        val window = fx["initial"]!!.jsonObject["window"]!!.jsonPrimitive.long
        val cell = ThrottleCell<String>(ctx, edge, window)
        run(ctx, fx, cell.outputCell, { cell.output() }) { step ->
            if (opType(step) == "input") cell.input(opNow(step), opVal(step))
            else cell.tick(opNow(step))
        }
    }

    @Test fun throttleLeading() = runThrottle("throttle_leading.json", ThrottleEdge.Leading)

    @Test fun throttleTrailing() = runThrottle("throttle_trailing.json", ThrottleEdge.Trailing)

    @Test
    fun sampleCount() {
        val fx = loadFixture("sample_count.json")
        val ctx = Context()
        val n = fx["initial"]!!.jsonObject["n"]!!.jsonPrimitive.long
        val cell = SampleCell<String>(ctx, SampleMode.Count(n))
        run(ctx, fx, cell.outputCell, { cell.output() }) { step -> cell.input(opVal(step)) }
    }

    @Test
    fun sampleTime() {
        val fx = loadFixture("sample_time.json")
        val ctx = Context()
        val period = fx["initial"]!!.jsonObject["period"]!!.jsonPrimitive.long
        val cell = SampleCell<String>(ctx, SampleMode.Time(period))
        run(ctx, fx, cell.outputCell, { cell.output() }) { step ->
            if (opType(step) == "input") {
                cell.input(opVal(step)); null
            } else {
                cell.tick(opNow(step))
            }
        }
    }

    @Test
    fun probabilisticSample() {
        val fx = loadFixture("probabilistic_sample.json")
        val ctx = Context()
        val rate = fx["initial"]!!.jsonObject["rate"]!!.jsonPrimitive.double
        val cell = ProbabilisticSampleCell<String>(ctx, rate, Lcg(0))
        run(ctx, fx, cell.outputCell, { cell.output() }) { step ->
            val draw = step["op"]!!.jsonObject["draw"]!!.jsonPrimitive.double
            cell.inputWithDraw(opVal(step), draw)
        }
    }
}
