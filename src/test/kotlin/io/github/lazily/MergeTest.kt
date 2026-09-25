package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 1 law-tests for the merge algebra (#relaycell). Every policy MUST be
 * associative; commutativity/idempotency are asserted per flag. Replays the
 * cross-language `mergecell_algebra.json` fixture — lazily-kt converges
 * identically to lazily-rs / lazily-js / lazily-py / lazily-go / lazily-zig.
 */
class MergeTest {
    private var fixtureOverride: ((String) -> String)? = null
    private var expectationTransform: (String, JsonObject) -> JsonObject = { _, expected -> expected }
    private var bindCanonicalBlocks = true
    private var skippedSite: String? = null
    private var duplicatedSite: String? = null

    private fun loadFixture(name: String): String = fixtureOverride?.invoke(name) ?: ConformanceFixtures.read("collections/$name")

    @Test
    fun every_policy_is_associative() {
        assertEquals(
            keepLatest<Long>().let { it.merge(it.merge(5, -3), 8) },
            keepLatest<Long>().let { it.merge(5, it.merge(-3, 8)) },
        )
        for (p in listOf(sum(), max())) {
            val (a, b, c) = listOf(5L, -3L, 8L)
            assertEquals(p.merge(p.merge(a, b), c), p.merge(a, p.merge(b, c)), p.name)
        }
        val rf = rawFifo<Int>()
        assertEquals(
            rf.merge(rf.merge(listOf(1), listOf(2)), listOf(3)),
            rf.merge(listOf(1), rf.merge(listOf(2), listOf(3))),
        )
    }

    @Test
    fun commutativity_matches_flag() {
        for (p in listOf(sum(), max())) {
            assertTrue(p.commutative)
            assertEquals(p.merge(p.merge(5, -3), 8), p.merge(p.merge(5, 8), -3), p.name)
        }
        val kl = keepLatest<Long>()
        assertFalse(kl.commutative)
        assertTrue(kl.merge(kl.merge(0, 1), 2) != kl.merge(kl.merge(0, 2), 1))
        assertFalse(rawFifo<Int>().commutative)
    }

    @Test
    fun idempotency_matches_flag() {
        val m = max()
        assertTrue(m.idempotent)
        assertEquals(m.merge(m.merge(3, 9), 9), m.merge(3, 9))
        val s = sum()
        assertFalse(s.idempotent)
        assertTrue(s.merge(s.merge(0, 5), 5) != s.merge(0, 5))
        assertTrue(setUnion<Int>().idempotent)
        assertFalse(rawFifo<Int>().idempotent)
    }

    @Test
    fun cell_is_merge_cell_keep_latest() {
        val ctx = Context()
        val cell = ctx.source(0L)
        val mc = ctx.mergeCell(0L, keepLatest())
        for (v in listOf(3L, 3L, 7L, 7L, 1L)) {
            cell.set(ctx, v)
            mc.merge(v)
            assertEquals(ctx.get(cell), mc.get())
        }
        assertEquals(1L, mc.get())
    }

    @Test
    fun sum_converges_regardless_of_order() {
        val ctx = Context()
        val ops = listOf(5L, -3L, 8L, 2L, -1L)
        val a = ctx.mergeCell(0L, sum())
        for (d in ops) a.merge(d)
        val b = ctx.mergeCell(0L, sum())
        for (d in ops.reversed()) b.merge(d)
        assertEquals(a.get(), b.get())
        assertEquals(11L, a.get())
    }

    @Test
    fun idempotent_merge_no_ops_via_guard() {
        val ctx = Context()
        val mc = ctx.mergeCell(10L, max())
        var runs = 0
        ctx.effect {
            mc.get(this)
            runs++
            null
        }
        assertEquals(1, runs)
        mc.merge(5)
        mc.merge(10)
        mc.merge(0)
        assertEquals(1, runs) // merges at/below max fire no cascade
        mc.merge(42)
        assertEquals(42L, mc.get())
        assertEquals(2, runs)
    }

    @Test
    fun mergecell_algebra_fixture() {
        val fixturePath = "collections/mergecell_algebra.json"
        val fixture = Json.parseToJsonElement(loadFixture("mergecell_algebra.json")).jsonObject
        val declaredSites = ConformanceFixtures.blockSitesOf(fixturePath, fixture).keys
        val visitedSites = linkedSetOf<String>()
        val byName = mapOf("KeepLatest" to keepLatest<Long>(), "Sum" to sum(), "Max" to max())
        var seen = 0
        // `collections/mergecell_algebra.json` is the one fixture in the corpus
        // whose scenarios carry NO identifier — they differ only by `policy` — so
        // the ledger records them by positional fallback (`#0`/`#1`/`#2`) and the
        // guard reports that fallback rather than accepting it silently
        // (#lzscenariocoverage).
        for ((scenarioIndex, scenario) in ConformanceScenarios.indexed(fixturePath, fixture)) {
            val policy = byName[scenario["policy"]!!.jsonPrimitive.content]!!
            val flags = scenario["flags"]!!.jsonObject
            assertEquals(flags["commutative"]!!.jsonPrimitive.boolean, policy.commutative)
            assertEquals(flags["idempotent"]!!.jsonPrimitive.boolean, policy.idempotent)

            val ctx = Context()
            val mc = ctx.mergeCell(scenario["initial"]!!.jsonPrimitive.int.toLong(), policy)
            var runs = 0
            ctx.effect {
                mc.get(this)
                runs++
                null
            }
            for ((stepIndex, stepEl) in scenario["steps"]!!.jsonArray.withIndex()) {
                val step = stepEl.jsonObject
                val before = runs
                mc.merge(step["merge"]!!.jsonPrimitive.int.toLong())
                val fired = runs > before
                val expected = step["expected"]!!.jsonObject
                val siteId = "$fixturePath|scenarios[$scenarioIndex].steps[$stepIndex].expected"
                val skip = siteId == skippedSite
                if (!skip) {
                    check(visitedSites.add(siteId)) { "$siteId: assertion block visited more than once" }
                    if (siteId == duplicatedSite) {
                        check(visitedSites.add(siteId)) { "$siteId: assertion block visited more than once" }
                    }
                }
                val keys =
                    AssertionKeys(
                        siteId,
                        if (skip) JsonObject(emptyMap()) else expectationTransform(siteId, expected),
                        fixturePath,
                        rungZeroBind = bindCanonicalBlocks,
                    )
                keys.assertLong("value") { mc.get() }
                keys.assertBoolean("invalidates") { fired }
                keys.requireAllSatisfied()
            }
            seen++
        }
        assertEquals(3, seen)
        assertEquals(declaredSites, visitedSites, "$fixturePath: exact assertion-block sites")
        assertEquals(15, visitedSites.size, "$fixturePath: assertion-block site pin")
    }

    @Test
    fun `merge expectation families reject fixture value mutations`() {
        data class Mutation(
            val family: String,
            val siteId: String,
            val replacement: String,
        )

        val fixturePath = "collections/mergecell_algebra.json"
        val mutations =
            listOf(
                Mutation("value", "$fixturePath|scenarios[0].steps[0].expected", "999"),
                Mutation("invalidates", "$fixturePath|scenarios[0].steps[0].expected", "false"),
            )
        assertEquals(setOf("value", "invalidates"), mutations.map { it.family }.toSet(), "merge mutation matrix")

        try {
            val raw = Files.readString(ConformanceFixtures.path(fixturePath))
            fixtureOverride = { raw }
            bindCanonicalBlocks = false
            for (mutation in mutations) {
                val replacement: JsonElement = Json.parseToJsonElement(mutation.replacement)
                val canonical = ConformanceFixtures.blockSitesOf(fixturePath, raw).getValue(mutation.siteId)
                assertTrue(canonical.getValue(mutation.family) != replacement, "${mutation.family}: mutation changes value")
                var hits = 0
                expectationTransform = { siteId, expected ->
                    if (siteId == mutation.siteId) {
                        hits++
                        JsonObject(expected + (mutation.family to replacement))
                    } else {
                        expected
                    }
                }
                val failure = assertFails("${mutation.family}: changed fixture value survived production replay") {
                    mergecell_algebra_fixture()
                }
                assertEquals(1, hits, "${mutation.family}: target site visited exactly once")
                assertTrue(
                    failure.message.orEmpty().contains(mutation.family),
                    "${mutation.family}: production failure must name family, got ${failure.message}",
                )
            }
        } finally {
            resetMutationMode()
        }
    }

    @Test
    fun `merge structural guards reject unknown skipped duplicate and repeated twin sites`() {
        val fixturePath = "collections/mergecell_algebra.json"
        val raw = Files.readString(ConformanceFixtures.path(fixturePath))
        val twinSite = "$fixturePath|scenarios[2].steps[1].expected"
        try {
            fixtureOverride = { raw }
            bindCanonicalBlocks = false

            expectationTransform = { siteId, expected ->
                if (siteId == "$fixturePath|scenarios[0].steps[0].expected") {
                    JsonObject(expected + ("unknown_top_level" to Json.parseToJsonElement("true")))
                } else {
                    expected
                }
            }
            assertFails("unknown top-level expectation key must fail") { mergecell_algebra_fixture() }

            expectationTransform = { _, expected -> expected }
            skippedSite = twinSite
            val skipped = assertFails("skipping one repeated-content twin must fail exact-site equality") {
                mergecell_algebra_fixture()
            }
            assertTrue(skipped.message.orEmpty().contains("exact assertion-block sites"), skipped.message)

            skippedSite = null
            duplicatedSite = twinSite
            val duplicated = assertFails("duplicate structural site must fail") { mergecell_algebra_fixture() }
            assertTrue(duplicated.message.orEmpty().contains("visited more than once"), duplicated.message)
        } finally {
            resetMutationMode()
        }
    }

    private fun resetMutationMode() {
        fixtureOverride = null
        expectationTransform = { _, expected -> expected }
        bindCanonicalBlocks = true
        skippedSite = null
        duplicatedSite = null
    }
}
