package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

    private var fixtureOverride: ((String) -> JsonObject)? = null
    private var expectationTransform: (String, JsonObject) -> JsonObject = { _, expected -> expected }
    private var bindCanonicalBlocks = true

    private fun loadFixture(name: String): JsonObject {
        fixtureOverride?.let { return it(name) }
        val text = ConformanceFixtures.read("collections/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun assertionKeys(
        fixturePath: String,
        sitePath: String,
        expected: JsonObject,
        visitedSites: MutableSet<String>,
    ): AssertionKeys {
        val siteId = "$fixturePath|$sitePath"
        check(visitedSites.add(siteId)) { "$siteId: assertion block visited more than once" }
        return AssertionKeys(
            siteId,
            expectationTransform(siteId, expected),
            fixturePath,
            rungZeroBind = bindCanonicalBlocks,
        )
    }

    private fun strings(element: JsonArray): List<String> = element.map { it.jsonPrimitive.content }

    /** Set up a SourceMap seeded from a fixture `initial` block + reader memos. */
    private class Harness(
        ctx: Context,
        map: SourceMap<String, Int>,
    ) {
        val ctx = ctx
        val map = map
        val handles: MutableMap<String, Int> = HashMap()
    }

    private fun harness(initial: JsonObject): Harness {
        val ctx = Context()
        val order = strings(initial.getValue("order").jsonArray)
        val values = initial.getValue("values").jsonObject
        val entries = order.map { it to values.getValue(it).jsonPrimitive.int }
        val map: SourceMap<String, Int> = SourceMap(ctx, entries)
        return Harness(ctx, map).also { h ->
            for (k in order) h.handles[k] = h.map.value(k).id
        }
    }

    /**
     * A memo reading [key]'s value cell (a value-class reader). Value-threaded
     * tracking (#lzcellkernel): read the entry's [Source] handle through the
     * compute view so the reader actually subscribes to that one cell.
     */
    private fun Harness.valueReader(key: String) = ctx.computed { get(map.value(key)) }

    /** A memo reading membership (len) — a membership-class reader. */
    private fun Harness.membershipReader() = ctx.computed { get(map.len()) }

    /** A memo reading the order list — an order-class reader. */
    private fun Harness.orderReader() = ctx.computed { get(map.keys()) }

    private fun applyOp(
        h: Harness,
        op: JsonObject,
    ) {
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
            "move_before" ->
                h.map.moveBefore(
                    op.getValue("key").jsonPrimitive.content,
                    op.getValue("before").jsonPrimitive.content,
                )
            "move_after" ->
                h.map.moveAfter(
                    op.getValue("key").jsonPrimitive.content,
                    op.getValue("after").jsonPrimitive.content,
                )
            else -> error("unknown collection op: $type")
        }
    }

    private fun assertExpected(
        h: Harness,
        expected: AssertionKeys,
        readers: Readers,
    ) {
        // REQUIRED, not presence-gated (`#lzsiblingrunnermasking`). `if ("order" in
        // expected)` made an `order` key dropped upstream read as "this step makes
        // no claim about ordering" — over the two fixtures whose whole subject is
        // ordering. CollectionsFamilyConformanceTest reads it as
        // `expected["order"]!!` over the SAME two fixtures, so the family runner
        // was the only thing standing between that drop and a green suite.
        expected.assertStrings("order") { h.map.keysNow() }
        expected.assertKeyWith("membership") { want ->
            assertEquals(strings(want.jsonArray).toSet(), h.map.keysNow().toSet(), "membership")
        }
        expected.sub("values") { values ->
            for (key in values.keys.sorted()) values.assertInt(key) { h.map.get(key)!! }
        }
        // The matrix is the contract, so its ABSENCE is a fixture-shape violation
        // and never a step that checks no invalidation (`#lzsiblingrunnermasking`).
        // CollectionsFamilyConformanceTest already errors on a missing
        // `expected.invalidates` and counts the matrices it ran; this runner
        // skipped the whole block silently, so a fixture that lost it would have
        // reddened only the family runner.
        expected.sub("invalidates") { inv ->
            // Required, not defaulted: `?: emptyList()` made a `value` key dropped
            // upstream read as "nothing was invalidated", so the whole per-key half
            // of the matrix could vanish and every reader would be asserted to have
            // stayed cached (#lzflagcoercion).
            inv.assertKeyWith("value") { rawValueKeys ->
                val valueKeys = rawValueKeys.jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertTrue(
                    valueKeys.all(readers.valueReaders::containsKey),
                    "expected.invalidates.value names unknown reader(s): ${valueKeys - readers.valueReaders.keys}",
                )
                for (key in readers.valueReaders.keys) {
                    val invalidated = key in valueKeys
                    assertEquals(
                        !invalidated,
                        h.ctx.isSet(readers.valueReaders.getValue(key)),
                        "value reader '$key' invalidated=$invalidated",
                    )
                }
            }
            inv.assertBoolean("membership") { !h.ctx.isSet(readers.membership) }
            inv.assertBoolean("order") { !h.ctx.isSet(readers.order) }
        }
        expected.sub("handle_stable") { handleStable ->
            // BOTH directions (`#lzflagcoercion`). The true-only arm left
            // `handle_stable: { k: false }` compared by nothing — the fixture read
            // as "this handle was re-minted" and the runner asserted neither that
            // nor its opposite. CollectionsFamilyConformanceTest already ran both
            // halves over the same fixtures, which is the only reason a planted
            // `false` reddened anything at all.
            for (key in handleStable.keys.sorted()) {
                val before = h.handles.getValue(key)
                val after = h.map.value(key).id
                handleStable.assertBoolean(key) { before == after }
            }
        }
    }

    private class Readers(
        val valueReaders: Map<String, Computed<Int>>,
        val membership: Computed<Int>,
        val order: Computed<List<String>>,
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

    /**
     * Replay one ordering fixture, asserting that every step it DECLARES ran.
     *
     * The two tests below were byte-identical but for the filename, and neither
     * carried a floor: `for (step in ...)` over an empty `steps` array is a
     * passing test that replayed nothing, and no assertion inside the loop can
     * see that. CollectionsFamilyConformanceTest counts its steps and its
     * matrices over these same two fixtures (`#lzsiblingrunnermasking`), so a
     * fixture emptied upstream would have reddened the family runner alone.
     * The count is read back from the fixture, so there is no number to re-pin.
     */
    private fun replayOrdering(name: String) {
        val fixturePath = "collections/$name"
        val fixture = loadFixture(name)
        val declaredSites = ConformanceFixtures.blockSitesOf(fixturePath, fixture).keys
        val visitedSites = linkedSetOf<String>()
        val h = harness(fixture.getValue("initial").jsonObject)
        val steps = fixture.getValue("steps").jsonArray
        assertTrue(steps.isNotEmpty(), "$name declares no steps — a zero-step replay is not a pass")
        var executed = 0
        for ((stepIndex, step) in steps.withIndex()) {
            val stepObj = step.jsonObject
            // Fresh readers per step: the effect is measured from a clean state.
            val readers = primeReaders(h)
            applyOp(h, stepObj.getValue("op").jsonObject)
            val expected = stepObj.getValue("expected").jsonObject
            val keys = assertionKeys(fixturePath, "steps[$stepIndex].expected", expected, visitedSites)
            assertExpected(h, keys, readers)
            keys.requireAllSatisfied()
            executed++
        }
        assertEquals(steps.size, executed, "$name: loaded ${steps.size} steps but executed $executed")
        assertEquals(declaredSites, visitedSites, "$fixturePath: exact assertion-block sites")
        val expectedSiteCount =
            when (name) {
                "cellmap_atomic_move.json" -> 3
                "cellmap_independence.json" -> 4
                else -> error("no assertion-site pin for $name")
            }
        assertEquals(expectedSiteCount, visitedSites.size, "$fixturePath: assertion-block site pin")
    }

    @Test
    fun `conformance cellmap independence`() = replayOrdering("cellmap_independence.json")

    @Test
    fun `conformance cellmap atomic move`() = replayOrdering("cellmap_atomic_move.json")

    @Test
    fun `conformance keyed reconciliation lis`() {
        val fixturePath = "collections/keyed_reconciliation_lis.json"
        val fixture = loadFixture("keyed_reconciliation_lis.json")
        val recon = fixture.getValue("reconcile").jsonObject
        val prior = reconState(recon.getValue("prior").jsonObject)
        val target = reconState(recon.getValue("target").jsonObject)

        val ops = reconcile(prior, target)
        val expected = fixture.getValue("expected").jsonObject
        val siteId = "$fixturePath|expected"
        val visitedSites = linkedSetOf<String>()
        val keys = assertionKeys(fixturePath, "expected", expected, visitedSites)

        // Assert the op set matches the canonical fixture (remove d, move a after c).
        keys.assertKeyWith("ops") { rawOps ->
            val expectedOps = rawOps.jsonArray.map { opEl ->
                val op = opEl.jsonObject
                when (op.getValue("type").jsonPrimitive.content) {
                    "remove" -> {
                        assertEquals(setOf("type", "key"), op.keys, "remove op fields")
                        ReconOp.Remove(op.getValue("key").jsonPrimitive.content)
                    }
                    "move" -> {
                        val key = op.getValue("key").jsonPrimitive.content
                        val anchor =
                            if ("after" in op) {
                                assertEquals(setOf("type", "key", "after"), op.keys, "move-after op fields")
                                ReconOp.Anchor.After(op.getValue("after").jsonPrimitive.content)
                            } else {
                                assertEquals(setOf("type", "key", "before"), op.keys, "move-before op fields")
                                ReconOp.Anchor.Before(op.getValue("before").jsonPrimitive.content)
                            }
                        ReconOp.Move(key, anchor)
                    }
                    "insert" -> {
                        assertEquals(setOf("type", "key"), op.keys, "insert op fields")
                        ReconOp.Insert(op.getValue("key").jsonPrimitive.content)
                    }
                    "update" -> {
                        assertEquals(setOf("type", "key"), op.keys, "update op fields")
                        ReconOp.Update(op.getValue("key").jsonPrimitive.content)
                    }
                    else -> error("unknown reconcile op")
                }
            }
            assertEquals(expectedOps, ops, "$siteId: ops")
        }

        // Result order.
        keys.assertStrings("result_order") { target.order }

        // Stable keys not invalidated.
        val priorIndex = prior.order.withIndex().associate { (i, k) -> k to i }
        val kept = target.order.filter { it in priorIndex }
        val lis = longestIncreasingSubsequenceIndices(kept.map { priorIndex.getValue(it) }).toSet()
        val computedStable = kept.mapIndexedNotNull { i, k -> if (i in lis) k else null }
        keys.assertStrings("stable_keys_not_invalidated") { computedStable }
        keys.requireAllSatisfied()
        assertEquals(setOf(siteId), visitedSites, "$fixturePath: exact assertion-block sites")
        assertEquals(setOf(siteId), ConformanceFixtures.blockSitesOf(fixturePath, fixture).keys)
    }

    @Test
    fun `collections expectation families reject fixture value mutations`() {
        data class Mutation(
            val family: String,
            val fixture: String,
            val siteId: String,
            val replacement: String,
            val run: CollectionsConformanceTest.() -> Unit,
        )

        val mutations =
            listOf(
                Mutation(
                    "order",
                    "cellmap_atomic_move.json",
                    "collections/cellmap_atomic_move.json|steps[0].expected",
                    """["wrong"]""",
                ) { `conformance cellmap atomic move`() },
                Mutation(
                    "membership",
                    "cellmap_atomic_move.json",
                    "collections/cellmap_atomic_move.json|steps[0].expected",
                    """["wrong"]""",
                ) { `conformance cellmap atomic move`() },
                Mutation(
                    "values",
                    "cellmap_atomic_move.json",
                    "collections/cellmap_atomic_move.json|steps[2].expected",
                    """{"a":999}""",
                ) { `conformance cellmap atomic move`() },
                Mutation(
                    "invalidates",
                    "cellmap_atomic_move.json",
                    "collections/cellmap_atomic_move.json|steps[0].expected",
                    """{"value":[],"membership":true,"order":true}""",
                ) { `conformance cellmap atomic move`() },
                Mutation(
                    "handle_stable",
                    "cellmap_atomic_move.json",
                    "collections/cellmap_atomic_move.json|steps[0].expected",
                    """{"b":false}""",
                ) { `conformance cellmap atomic move`() },
                Mutation(
                    "ops",
                    "keyed_reconciliation_lis.json",
                    "collections/keyed_reconciliation_lis.json|expected",
                    "[]",
                ) { `conformance keyed reconciliation lis`() },
                Mutation(
                    "result_order",
                    "keyed_reconciliation_lis.json",
                    "collections/keyed_reconciliation_lis.json|expected",
                    """["wrong"]""",
                ) { `conformance keyed reconciliation lis`() },
                Mutation(
                    "stable_keys_not_invalidated",
                    "keyed_reconciliation_lis.json",
                    "collections/keyed_reconciliation_lis.json|expected",
                    """["wrong"]""",
                ) { `conformance keyed reconciliation lis`() },
            )
        assertEquals(
            setOf("order", "membership", "values", "invalidates", "handle_stable", "ops", "result_order", "stable_keys_not_invalidated"),
            mutations.map { it.family }.toSet(),
            "collections mutation matrix",
        )

        try {
            fixtureOverride = { name ->
                val path = "collections/$name"
                json.parseToJsonElement(Files.readString(ConformanceFixtures.path(path))).jsonObject
            }
            bindCanonicalBlocks = false
            for (mutation in mutations) {
                val replacement: JsonElement = json.parseToJsonElement(mutation.replacement)
                val canonical =
                    ConformanceFixtures
                        .blockSitesOf(
                            "collections/${mutation.fixture}",
                            Files.readString(ConformanceFixtures.path("collections/${mutation.fixture}")),
                        ).getValue(mutation.siteId)
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
                val failure = assertFails("${mutation.family}: changed fixture value survived production replay") { mutation.run(this) }
                assertEquals(1, hits, "${mutation.family}: target site visited exactly once")
                assertTrue(
                    failure.message.orEmpty().contains(mutation.family),
                    "${mutation.family}: production failure must name family, got ${failure.message}",
                )
            }
        } finally {
            fixtureOverride = null
            expectationTransform = { _, expected -> expected }
            bindCanonicalBlocks = true
        }
    }

    @Test
    fun `primary collections fixture site pin`() {
        val names =
            listOf(
                "cellmap_atomic_move.json",
                "cellmap_independence.json",
                "keyed_reconciliation_lis.json",
            )
        val declared =
            names.sumOf { name ->
                val path = "collections/$name"
                ConformanceFixtures.blockSitesOf(path, loadFixture(name)).size
            }
        assertEquals(8, declared, "primary collections assertion-block site pin")
    }

    private fun reconState(obj: JsonObject): ReconcileState {
        val order = strings(obj.getValue("order").jsonArray)
        val values =
            obj
                .getValue("values")
                .jsonObject.entries
                .associate { (k, v) -> k to v.jsonPrimitive.int }
        return ReconcileState(order, values)
    }

    @Test
    fun `reconcile round trips through a live cellmap`() {
        // End-to-end: the op set applied to a live SourceMap yields the target order
        // and keeps stable entries' value cells un-invalidated.
        val fixture = loadFixture("keyed_reconciliation_lis.json")
        val recon = fixture.getValue("reconcile").jsonObject
        val target = reconState(recon.getValue("target").jsonObject)

        val ctx = Context()
        val prior = reconState(recon.getValue("prior").jsonObject)
        val map: SourceMap<String, Int> =
            SourceMap(ctx, prior.order.map { it to prior.values.getValue(it) })
        val readerB = ctx.computed { map.get("b") }
        val readerC = ctx.computed { map.get("c") }
        ctx.get(readerB)
        ctx.get(readerC)

        map.reconcile(target.order, target.values)

        assertEquals(target.order, map.keysNow())
        assertTrue(ctx.isSet(readerB), "stable entry b NOT invalidated")
        assertTrue(ctx.isSet(readerC), "stable entry c NOT invalidated")
        assertFalse(map.containsNow("d"))
    }
}
