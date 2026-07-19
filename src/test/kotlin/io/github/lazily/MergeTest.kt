package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Phase 1 law-tests for the merge algebra (#relaycell). Every policy MUST be
 * associative; commutativity/idempotency are asserted per flag. Replays the
 * cross-language `mergecell_algebra.json` fixture — lazily-kt converges
 * identically to lazily-rs / lazily-js / lazily-py / lazily-go / lazily-zig.
 */
class MergeTest {
    private fun loadFixture(name: String): String {
        return ConformanceFixtures.read("collections/$name")
    }

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
        val cell = ctx.cell(0L)
        val mc = ctx.mergeCell(0L, keepLatest())
        for (v in listOf(3L, 3L, 7L, 7L, 1L)) {
            ctx.setCell(cell, v)
            mc.merge(v)
            assertEquals(ctx.getCell(cell), mc.get())
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
            mc.get()
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
        val fixture = Json.parseToJsonElement(loadFixture("mergecell_algebra.json")).jsonObject
        val byName = mapOf("KeepLatest" to keepLatest<Long>(), "Sum" to sum(), "Max" to max())
        var seen = 0
        for (scenarioEl in fixture["scenarios"]!!.jsonArray) {
            val scenario = scenarioEl.jsonObject
            val policy = byName[scenario["policy"]!!.jsonPrimitive.content]!!
            val flags = scenario["flags"]!!.jsonObject
            assertEquals(flags["commutative"]!!.jsonPrimitive.boolean, policy.commutative)
            assertEquals(flags["idempotent"]!!.jsonPrimitive.boolean, policy.idempotent)

            val ctx = Context()
            val mc = ctx.mergeCell(scenario["initial"]!!.jsonPrimitive.int.toLong(), policy)
            var runs = 0
            ctx.effect {
                mc.get()
                runs++
                null
            }
            for (stepEl in scenario["steps"]!!.jsonArray) {
                val step = stepEl.jsonObject
                val before = runs
                mc.merge(step["merge"]!!.jsonPrimitive.int.toLong())
                val fired = runs > before
                val expected = step["expected"]!!.jsonObject
                assertEquals(expected["value"]!!.jsonPrimitive.int.toLong(), mc.get(), policy.name)
                assertEquals(expected["invalidates"]!!.jsonPrimitive.boolean, fired, policy.name)
            }
            seen++
        }
        assertEquals(3, seen)
    }
}
