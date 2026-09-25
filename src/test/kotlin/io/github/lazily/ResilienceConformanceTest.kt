package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
    fun circuitBreaker() {
        val fx = loadFixture("circuit_breaker.json")
        val ctx = Context()
        val cfg = fx["config"]!!.jsonObject
        val cb =
            CircuitBreakerCell(
                ctx,
                cfg["window"]!!.jsonPrimitive.int,
                cfg["failure_threshold"]!!.jsonPrimitive.int,
                cfg["reset_timeout"]!!.jsonPrimitive.long,
            )
        val obs = observe(ctx, cb.stateCell)
        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            when (op["type"]!!.jsonPrimitive.content) {
                "record" -> cb.record(op["success"]!!.jsonPrimitive.boolean, op["now"]!!.jsonPrimitive.long)
                "allow" ->
                    assertEquals(
                        step["returns"]!!.jsonPrimitive.boolean,
                        cb.allow(op["now"]!!.jsonPrimitive.long),
                    )
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`). Without
                // this arm an unknown `op.type` drove NOTHING and the step's
                // `expected` block was checked against the untouched breaker — the
                // scenario books as replayed while naming behaviour never exercised.
                else -> error("circuit_breaker.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
            }
            step.getValue("expected").jsonObject.consuming(
                "resilience/circuit_breaker.json steps[$index].expected",
            ) { expected ->
                expected.assertString("state") { cb.state().name }
                checkInval(ctx, obs, expected, "state")
            }
        }
    }

    @Test
    fun retry() {
        val fx = loadFixture("retry.json")
        val ctx = Context()
        val cfg = fx["config"]!!.jsonObject
        val r = RetryPolicyCell(ctx, cfg["base"]!!.jsonPrimitive.long, cfg["cap"]!!.jsonPrimitive.long)
        val obs = observe(ctx, r.delayCell)
        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            val opType = step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content
            when (opType) {
                "next" -> assertEquals(step["returns"]!!.jsonPrimitive.long, r.nextDelay(), "delay")
                else -> error("retry.json: unknown op type '$opType'")
            }
            step.getValue("expected").jsonObject.consuming(
                "resilience/retry.json steps[$index].expected",
            ) { expected ->
                expected.assertLong("delay") { r.delay() }
                checkInval(ctx, obs, expected, "delay")
            }
        }
    }

    @Test
    fun bulkhead() {
        val fx = loadFixture("bulkhead.json")
        val ctx = Context()
        val b = BulkheadCell(ctx, fx["config"]!!.jsonObject["capacity"]!!.jsonPrimitive.long)
        val obs = observe(ctx, b.inUseCell)
        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            when (step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content) {
                // `.boolean`, never `booleanOrNull` (`#lzsiblingrunnermasking`).
                // `booleanOrNull` decodes a quoted `"true"` or a `1` to null, and
                // `assertEquals(null, someBoolean)` is a failure with a message that
                // blames the LIBRARY for a fixture-shape violation. `.boolean` throws
                // and says which key is malformed.
                "acquire" -> assertEquals(step["returns"]!!.jsonPrimitive.boolean, b.acquire())
                "release" -> b.release()
                // Fail closed on an unrecognised op (`#lzscenariobodyskip`).
                else ->
                    error(
                        "bulkhead.json: unknown op type " +
                            "'${step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content}'",
                    )
            }
            step.getValue("expected").jsonObject.consuming(
                "resilience/bulkhead.json steps[$index].expected",
            ) { expected ->
                expected.assertLong("in_use") { b.permitsInUse() }
                checkInval(ctx, obs, expected, "in_use")
            }
        }
    }

    @Test
    fun timeout() {
        val fx = loadFixture("timeout.json")
        val ctx = Context()
        val t = TimeoutCell(ctx)
        val obs = observe(ctx, t.timedOutCell)
        for ((index, element) in steps(fx).withIndex()) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            val e =
                when (op["type"]!!.jsonPrimitive.content) {
                    "arm" -> {
                        t.arm(now, op["timeout"]!!.jsonPrimitive.long)
                        false
                    }
                    "tick" -> t.tick(now)
                    // `tick` used to be the `else` arm, so ANY unrecognised op.type
                    // silently replayed as a tick — the fixture named one thing and
                    // the runner did another, and the scenario still booked as
                    // replayed (`#lzscenariobodyskip`).
                    else -> error("timeout.json: unknown op type '${op["type"]!!.jsonPrimitive.content}'")
                }
            assertEquals(step["returns"]!!.jsonPrimitive.boolean, e, "edge")
            step.getValue("expected").jsonObject.consuming(
                "resilience/timeout.json steps[$index].expected",
            ) { expected ->
                expected.assertBoolean("is_timed_out") { t.isTimedOut() }
                checkInval(ctx, obs, expected, "is_timed_out")
            }
        }
    }
}
