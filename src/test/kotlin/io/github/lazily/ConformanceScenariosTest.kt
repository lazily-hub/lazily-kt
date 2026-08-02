package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Self-tests for the scenario ledger's identity resolution (`#lzscenariocoverage`,
 * `#lzspecscenarioids`).
 *
 * These exist rather than a comment because [ConformanceScenarios.idOf] used to end its
 * `id` -> `name` resolution in a positional `#<n>` fallback. A ledger entry recorded BY
 * POSITION silently rebinds to a different scenario when the corpus array is reordered,
 * and nothing turns red: the coverage guard compares "index 1 was replayed" against
 * whatever now sits at index 1 and agrees with itself. The corpus identifies every
 * scenario now, so the fallback is a hard failure — and a rule enforced only by the corpus
 * happening to be well-formed is not enforced at all.
 *
 * Getting the ORDER wrong fails just as quietly in the other direction: it renames every
 * scenario of a fixture at once, so the guard reports the whole fixture unreplayed and the
 * diagnosis points at the runner instead of at this function.
 */
class ConformanceScenariosTest {
    private fun scenario(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `id wins over name`() {
        val resolved = ConformanceScenarios.idOf(scenario("""{"id":"keep_latest","name":"ignored"}"""), 7)
        assertEquals("keep_latest", resolved.value)
        assertEquals(ConformanceScenarios.IdSource.ID, resolved.source)
    }

    @Test
    fun `name is the fallback`() {
        val resolved = ConformanceScenarios.idOf(scenario("""{"name":"repair_converges"}"""), 7)
        assertEquals("repair_converges", resolved.value)
        assertEquals(ConformanceScenarios.IdSource.NAME, resolved.source)
    }

    @Test
    fun `an unidentified scenario is refused`() {
        val error = assertFailsWith<IllegalStateException> {
            ConformanceScenarios.idOf(scenario("""{"policy":"Sum"}"""), 1)
        }
        assertTrue(
            error.message!!.contains("carries neither `id` nor `name`"),
            "message should name the defect: ${error.message}",
        )
        assertTrue(error.message!!.contains("index 1"), "message should name the position")
    }

    @Test
    fun `a blank identifier is refused`() {
        // A blank id is not an identifier. Accepting it would file every blank-id scenario
        // in the corpus under the SAME ledger entry, which reads as "replayed" the moment
        // any one of them runs.
        assertFailsWith<IllegalStateException> {
            ConformanceScenarios.idOf(scenario("""{"id":"  ","name":""}"""), 2)
        }
    }
}
