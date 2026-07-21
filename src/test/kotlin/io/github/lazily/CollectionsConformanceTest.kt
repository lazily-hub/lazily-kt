package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replays the canonical `lazily-spec/conformance/collections/` fixtures against
 * the native keyed cell collections layer — the language-agnostic conformance
 * every binding MUST validate
 * (`lazily-spec/cell-model.md` § Keyed cell collections).
 *
 * Each compute fixture loads `initial`, replays each `step`'s `op`, and asserts
 * the `expected` observable effects (resulting `order`, `values`, `membership`,
 * and which reader classes — `value` / `membership` / `order` — invalidate). The
 * reconciliation fixture diffs `prior` → `target` and asserts the emitted
 * minimal op set.
 */
class CollectionsConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("collections/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun strings(element: JsonArray): List<String> =
        element.map { it.jsonPrimitive.content }

    /** Set up a CellMap seeded from a fixture `initial` block + reader memos. */
    private class Harness(ctx: Context, map: CellMap<String, Int>) {
        val ctx = ctx
        val map = map
        val handles: MutableMap<String, Int> = HashMap()
    }

    private fun harness(initial: JsonObject): Harness {
        val ctx = Context()
        val order = strings(initial.getValue("order").jsonArray)
        val values = initial.getValue("values").jsonObject
        val entries = order.map { it to values.getValue(it).jsonPrimitive.int }
        val map: CellMap<String, Int> = CellMap(ctx, entries)
        return Harness(ctx, map).also { h ->
            for (k in order) h.handles[k] = h.map.value(k).id
        }
    }

    /** A memo reading [key]'s value cell (a value-class reader). */
    private fun Harness.valueReader(key: String) = ctx.computed { map.get(key) }

    /** A memo reading membership (len) — a membership-class reader. */
    private fun Harness.membershipReader() = ctx.computed { ctx.get(map.len()) }

    /** A memo reading the order list — an order-class reader. */
    private fun Harness.orderReader() = ctx.computed { ctx.get(map.keys()) }

    private fun applyOp(h: Harness, op: JsonObject) {
        val type = op.getValue("type").jsonPrimitive.content
        when (type) {
            "set_value" -> h.map.setValue(op.getValue("key").jsonPrimitive.content, op.getValue("value").jsonPrimitive.int)
            "insert" -> {
                val key = op.getValue("key").jsonPrimitive.content
                h.map.insert(key, op.getValue("value").jsonPrimitive.int, InsertAt.End)
                h.handles[key] = h.map.value(key).id // minted by insert above
            }
            "remove" -> h.map.remove(op.getValue("key").jsonPrimitive.content)
            "move_to" -> h.map.moveTo(op.getValue("key").jsonPrimitive.content, op.getValue("index").jsonPrimitive.int)
            "move_before" -> h.map.moveBefore(
                op.getValue("key").jsonPrimitive.content,
                op.getValue("before").jsonPrimitive.content,
            )
            "move_after" -> h.map.moveAfter(
                op.getValue("key").jsonPrimitive.content,
                op.getValue("after").jsonPrimitive.content,
            )
            else -> error("unknown collection op: $type")
        }
    }

    private fun assertExpected(h: Harness, expected: JsonObject, readers: Readers) {
        if ("order" in expected) {
            assertEquals(strings(expected.getValue("order").jsonArray), h.map.keysNow(), "order")
        }
        if ("membership" in expected) {
            val want = strings(expected.getValue("membership").jsonArray).toSet()
            assertEquals(want, h.map.keysNow().toSet(), "membership")
        }
        if ("values" in expected) {
            val vals = expected.getValue("values").jsonObject
            for ((k, v) in vals) assertEquals(v.jsonPrimitive.int, h.map.get(k), "value $k")
        }
        val inv = expected["invalidates"]?.jsonObject
        if (inv != null) {
            val membershipInvalidated = inv.getValue("membership").jsonPrimitive.boolean
            val orderInvalidated = inv.getValue("order").jsonPrimitive.boolean
            val valueKeys = inv["value"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            for (key in readers.valueReaders.keys) {
                val invalidated = key in valueKeys
                assertEquals(
                    !invalidated,
                    h.ctx.isSet(readers.valueReaders.getValue(key)),
                    "value reader '$key' invalidated=$invalidated",
                )
            }
            assertEquals(!membershipInvalidated, h.ctx.isSet(readers.membership), "membership invalidate mismatch")
            assertEquals(!orderInvalidated, h.ctx.isSet(readers.order), "order invalidate mismatch")
        }
        val handleStable = expected["handle_stable"]?.jsonObject
        if (handleStable != null) {
            for ((key, stable) in handleStable) {
                if (stable.jsonPrimitive.boolean) {
                    assertEquals(h.handles.getValue(key), h.map.value(key).id, "handle stable for $key")
                }
            }
        }
    }

    private class Readers(
        val valueReaders: Map<String, SlotHandle<Int>>,
        val membership: SlotHandle<Int>,
        val order: SlotHandle<List<String>>,
    )

    /** Build reader memos for every key currently present and read them once. */
    private fun primeReaders(h: Harness): Readers {
        val valueReaders = h.map.keysNow().associateWith { h.valueReader(it) }
        val membership = h.membershipReader()
        val order = h.orderReader()
        for (r in valueReaders.values) h.ctx.get(r)
        h.ctx.get(membership)
        h.ctx.get(order)
        return Readers(valueReaders, membership, order)
    }

    @Test
    fun `conformance cellmap independence`() {
        val fixture = loadFixture("cellmap_independence.json")
        val h = harness(fixture.getValue("initial").jsonObject)
        for (step in fixture.getValue("steps").jsonArray) {
            val stepObj = step.jsonObject
            val readers = primeReaders(h) // fresh readers per step: effect measured from a clean state
            applyOp(h, stepObj.getValue("op").jsonObject)
            assertExpected(h, stepObj.getValue("expected").jsonObject, readers)
        }
    }

    @Test
    fun `conformance cellmap atomic move`() {
        val fixture = loadFixture("cellmap_atomic_move.json")
        val h = harness(fixture.getValue("initial").jsonObject)
        for (step in fixture.getValue("steps").jsonArray) {
            val stepObj = step.jsonObject
            val readers = primeReaders(h)
            applyOp(h, stepObj.getValue("op").jsonObject)
            assertExpected(h, stepObj.getValue("expected").jsonObject, readers)
        }
    }

    @Test
    fun `conformance keyed reconciliation lis`() {
        val fixture = loadFixture("keyed_reconciliation_lis.json")
        val recon = fixture.getValue("reconcile").jsonObject
        val prior = reconState(recon.getValue("prior").jsonObject)
        val target = reconState(recon.getValue("target").jsonObject)

        val ops = reconcile(prior, target)
        val expected = fixture.getValue("expected").jsonObject

        // Assert the op set matches the canonical fixture (remove d, move a after c).
        val expectedOps = expected.getValue("ops").jsonArray.map { opEl ->
            val op = opEl.jsonObject
            when (op.getValue("type").jsonPrimitive.content) {
                "remove" -> ReconOp.Remove(op.getValue("key").jsonPrimitive.content)
                "move" -> {
                    val key = op.getValue("key").jsonPrimitive.content
                    val anchor = if ("after" in op) {
                        ReconOp.Anchor.After(op.getValue("after").jsonPrimitive.content)
                    } else {
                        ReconOp.Anchor.Before(op.getValue("before").jsonPrimitive.content)
                    }
                    ReconOp.Move(key, anchor)
                }
                "insert" -> ReconOp.Insert(op.getValue("key").jsonPrimitive.content)
                "update" -> ReconOp.Update(op.getValue("key").jsonPrimitive.content)
                else -> error("unknown reconcile op")
            }
        }
        assertEquals(expectedOps, ops)

        // Result order.
        assertEquals(
            strings(expected.getValue("result_order").jsonArray),
            target.order,
        )

        // Stable keys not invalidated.
        val stableKeys = expected.getValue("stable_keys_not_invalidated").jsonArray.map { it.jsonPrimitive.content }
        val priorIndex = prior.order.withIndex().associate { (i, k) -> k to i }
        val kept = target.order.filter { it in priorIndex }
        val lis = longestIncreasingSubsequenceIndices(kept.map { priorIndex.getValue(it) }).toSet()
        val computedStable = kept.mapIndexedNotNull { i, k -> if (i in lis) k else null }
        assertEquals(stableKeys, computedStable, "stable (LIS) keys match fixture")
    }

    private fun reconState(obj: JsonObject): ReconcileState {
        val order = strings(obj.getValue("order").jsonArray)
        val values = obj.getValue("values").jsonObject.entries.associate { (k, v) -> k to v.jsonPrimitive.int }
        return ReconcileState(order, values)
    }

    @Test
    fun `reconcile round trips through a live cellmap`() {
        // End-to-end: the op set applied to a live CellMap yields the target order
        // and keeps stable entries' value cells un-invalidated.
        val fixture = loadFixture("keyed_reconciliation_lis.json")
        val recon = fixture.getValue("reconcile").jsonObject
        val target = reconState(recon.getValue("target").jsonObject)

        val ctx = Context()
        val prior = reconState(recon.getValue("prior").jsonObject)
        val map: CellMap<String, Int> =
            CellMap(ctx, prior.order.map { it to prior.values.getValue(it) })
        val readerB = ctx.computed { map.get("b") }
        val readerC = ctx.computed { map.get("c") }
        ctx.get(readerB); ctx.get(readerC)

        map.reconcile(target.order, target.values)

        assertEquals(target.order, map.keysNow())
        assertTrue(ctx.isSet(readerB), "stable entry b NOT invalidated")
        assertTrue(ctx.isSet(readerC), "stable entry c NOT invalidated")
        assertFalse(map.containsNow("d"))
    }
}
