package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The coverage/value asymmetry an ACCUMULATING conformance runner needs
 * (`#lzktreactivemodelfailures`).
 *
 * ReactiveGraphConformanceTest replays the corpus once per execution model and
 * collects divergences into a report rather than throwing at the first one,
 * because a value divergence is a property of ONE model and all three should
 * speak. A COVERAGE defect is a property of the RUNNER, identical across all
 * three passes, so it should be raised once and should throw.
 *
 * These tests pin the two halves of that decision against a stand-in accumulator
 * rather than against the runner itself, so they hold for any future caller with
 * the same shape — and so they cannot rot into folklore if that runner is later
 * restructured.
 */
class AccumulatingRunnerCoverageTest {
    /** A stand-in for `ReactiveGraphConformanceTest.Report.failures`. */
    private class Accumulator {
        val failures = mutableListOf<String>()

        /** Record a divergence and report whether the comparison PASSED. */
        fun compare(
            label: String,
            got: Any?,
            want: Any?,
        ): Boolean {
            if (got == want) return true
            failures.add("$label — got $got, want $want")
            return false
        }
    }

    private fun block(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /**
     * A key the runner never reads must fail the tracker, even though the
     * accumulator is empty and every comparison the runner DID make passed.
     *
     * This is the coverage half, and it is the whole reason the pass throws: an
     * accumulator has nothing to say about an assertion nobody attempted.
     */
    @Test
    fun `an unread key throws even when the accumulator is clean`() {
        val accumulator = Accumulator()
        val expect = block("""{"observed_count": 2, "value": "v"}""")

        val failure =
            assertFailsWith<IllegalStateException> {
                val keys = AssertionKeys("probe expect", expect, rungZeroBind = false)
                // `value` is compared; `observed_count` is never touched — the
                // defect this rung exists to name.
                keys.assertKeyOutcome("value") { want ->
                    accumulator.compare("value", "v", want.jsonPrimitive.content)
                }
                keys.requireAllSatisfied()
            }

        assertTrue(
            failure.message!!.contains("observed_count"),
            "the coverage failure must NAME the unread key, got: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("never consumed"),
            "expected the unread-key rung, got: ${failure.message}",
        )
        assertEquals(emptyList(), accumulator.failures, "no value divergence was staged")
    }

    /**
     * A SWALLOWED divergence must not book the key as asserted.
     *
     * `assertKeyWith` marks a key asserted the moment the comparison runs,
     * whatever it concluded. In an accumulating runner that degrades rung 3 to "a
     * comparison happened" and makes the tracker's signal a restatement of the
     * accumulator's — so if nobody reads the accumulator, nothing fails.
     * `assertKeyOutcome` takes the outcome, so the tracker fails on its own.
     */
    @Test
    fun `a failed comparison leaves the key unasserted`() {
        val accumulator = Accumulator()
        val expect = block("""{"observed_count": 2}""")

        val failure =
            assertFailsWith<IllegalStateException> {
                val keys = AssertionKeys("probe expect", expect, rungZeroBind = false)
                keys.assertKeyOutcome("observed_count") { want ->
                    // The run produced 3, the fixture says 2. The accumulator
                    // records it and does NOT throw — exactly the swallowing case.
                    accumulator.compare("observed_count", 3, want.jsonPrimitive.content.toInt())
                }
                keys.requireAllSatisfied()
            }

        assertTrue(
            failure.message!!.contains("READ but never asserted"),
            "expected rung 3 to fire on the swallowed divergence, got: ${failure.message}",
        )
        assertEquals(1, accumulator.failures.size, "the accumulator still staged the divergence")
    }

    /**
     * The control, and the reason this is not simply "make everything throw": a
     * comparison that PASSED books the key, so an accumulating runner that reads
     * and asserts every key finishes clean.
     */
    @Test
    fun `a passing comparison satisfies the tracker`() {
        val accumulator = Accumulator()
        val expect = block("""{"observed_count": 2, "value": "v"}""")
        val keys = AssertionKeys("probe expect", expect, rungZeroBind = false)
        keys.assertKeyOutcome("observed_count") { want ->
            accumulator.compare("observed_count", 2, want.jsonPrimitive.content.toInt())
        }
        keys.assertKeyOutcome("value") { want ->
            accumulator.compare("value", "v", want.jsonPrimitive.content)
        }
        keys.requireAllSatisfied()
        assertEquals(emptyList(), accumulator.failures)
    }

    /**
     * The weakness this replaces, demonstrated rather than described: through
     * `assertKeyWith` the very same swallowed divergence finishes GREEN.
     *
     * Pinned so the reason `assertKeyOutcome` exists cannot be lost — if
     * `assertKeyWith` is ever changed to consider an outcome, this test fails and
     * says so.
     */
    @Test
    fun `assertKeyWith books a swallowed divergence as asserted`() {
        val accumulator = Accumulator()
        val expect = block("""{"observed_count": 2}""")
        val keys = AssertionKeys("probe expect", expect, rungZeroBind = false)
        keys.assertKeyWith("observed_count") { want ->
            accumulator.compare("observed_count", 3, want.jsonPrimitive.content.toInt())
        }
        // No throw: the tracker is satisfied while the run disagreed with the
        // fixture. Only the accumulator knows, and only if someone reads it.
        keys.requireAllSatisfied()
        assertEquals(1, accumulator.failures.size)
    }
}
