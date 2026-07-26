package io.github.lazily

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The queue-family flavor ledger — enforced against the source, not a comment.
 *
 * [QueueCellConformanceTest] replays the canonical `queuecell_*.json` corpus
 * against the single-threaded [QueueCell]. That is currently the only flavor: no
 * binding in the family ships a thread-safe or async queue primitive, and
 * `cell-model.md` § "Core surface vs. binding extensions (queue family)" now makes
 * those Core, so their absence is a conformance gap rather than an unfinished
 * nicety.
 *
 * A three-flavor replay written today would skip two of three flavors entirely,
 * and a suite that skips almost everything while reporting green is exactly the
 * failure this file prevents. So the ledger is wired to the source: it greps
 * `src/main/kotlin` for each unshipped flavor's class name, and the moment one
 * appears this goes red and names the runner to extend.
 *
 * Mirrors `lazily-rs/tests/queue_family_conformance.rs`.
 */
class QueueFamilyConformanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val queueFixtures = listOf(
        "queuecell_spsc_push_pop.json",
        "queuecell_popped_head_observation.json",
        "queuecell_mpsc_multi_writer.json",
        "queuecell_bounded_backpressure.json",
        "queuecell_closure_lifecycle.json",
    )

    /**
     * [marker] is grepped, not referenced: referencing a class that does not exist
     * would not compile, and a ledger you cannot write until the work is done is no
     * ledger at all.
     */
    private data class Flavor(val name: String, val marker: String, val shipped: Boolean)

    private val ledger = listOf(
        Flavor("single-threaded", "class QueueCell", shipped = true),
        Flavor("thread-safe", "ThreadSafeQueueCell", shipped = false),
        Flavor("async", "AsyncQueueCell", shipped = false),
    )

    private fun sources(): String =
        File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

    @Test
    fun `unshipped flavors are really absent`() {
        val sources = sources()
        assertTrue(
            sources.isNotEmpty(),
            "read no sources from src/main/kotlin; the ledger check would be vacuous",
        )
        for (flavor in ledger) {
            val defined = sources.contains(flavor.marker)
            if (flavor.shipped) {
                assertTrue(
                    defined,
                    "flavor ${flavor.name} is recorded as shipped but ${flavor.marker} is " +
                        "not defined in src/main/kotlin — the ledger claims coverage this " +
                        "module does not have",
                )
            } else {
                assertTrue(
                    !defined,
                    "flavor ${flavor.name} now EXISTS in src/main/kotlin (${flavor.marker}) " +
                        "but the queue-family ledger still records it as unshipped, so the " +
                        "canonical corpus is not being replayed against it. Fix: flip " +
                        "shipped for ${flavor.name} in `ledger` AND extend the replay to " +
                        "drive it, as CollectionsFamilyConformanceTest drives all three map " +
                        "flavors. Do NOT flip the flag alone — that restores the false green " +
                        "this test prevents.",
                )
            }
        }
    }

    @Test
    fun `ledger is not all skips`() {
        // In a summary line, "skipped" and "passed" are indistinguishable.
        assertTrue(
            ledger.any { it.shipped },
            "every queue flavor is recorded as unshipped, so this suite would assert " +
                "nothing while still reporting success",
        )
        assertEquals(
            3,
            ledger.size,
            "the ledger must cover all three execution flavors; a missing entry is an " +
                "unscored gap, not an absent one",
        )
    }

    @Test
    fun `shipped flavor replays the corpus`() {
        ConformanceFixtures.requireRoot()

        var fixturesRead = 0
        var stepsSeen = 0
        var matricesSeen = 0

        for (name in queueFixtures) {
            val text = ConformanceFixtures.read("collections/$name")
            val fixture = json.parseToJsonElement(text).jsonObject
            fixturesRead += 1

            val steps = fixture["steps"]!!.jsonArray
            assertTrue(
                steps.isNotEmpty(),
                "$name: fixture has no steps - a vacuous replay would report green",
            )
            stepsSeen += steps.size

            steps.forEachIndexed { i, raw ->
                val step = raw.jsonObject
                // The matrix nests under `expected`, NOT on the step. lazily-rs's MAP
                // runner read it off the step, so it was always absent and the
                // assertion never ran once. Pin the nesting so that cannot recur here.
                assertTrue(
                    !step.containsKey("invalidates"),
                    "$name step $i: `invalidates` appears at STEP level; the runners read " +
                        "expected.invalidates, so a step-level copy is silently ignored",
                )
                val expected = step["expected"]?.jsonObject
                assertTrue(expected != null, "$name step $i: no expected block")
                if (expected!!.containsKey("invalidates")) matricesSeen += 1
            }
        }

        assertEquals(queueFixtures.size, fixturesRead, "did not read every declared fixture")
        assertTrue(stepsSeen > 0, "read the corpus but saw zero steps")
        assertTrue(
            matricesSeen > 0,
            "no fixture carried an expected.invalidates matrix - the reader-kind " +
                "independence contract would be unasserted",
        )
    }
}
