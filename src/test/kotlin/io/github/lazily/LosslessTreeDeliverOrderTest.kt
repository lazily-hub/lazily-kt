package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the `deliver` step CONTRACT of the lossless-tree replay runner
 * (`#lzspecoutoforderfixtures`).
 *
 * A fixture cannot assert on how its own step was interpreted. `deliver.order`
 * exists to hand `applyUpdate` a batch in an order its dependencies do NOT
 * permit, so that a binding without a dependency buffer loses ops — but a runner
 * that re-sorts the listed indexes selects the same op SET, applies it in
 * dependency order, and the fixture passes anyway. That is not hypothetical: a
 * sibling binding that re-sorted was MEASURED GREEN on
 * `out_of_order_delivery_buffers.json` against a library with NO dependency
 * buffer at all. The corpus is structurally blind to it, so the assertion has to
 * live here.
 *
 * What is pinned:
 *  1. `order` reaches [LosslessTreeCrdt.applyUpdate] as the LISTED sequence,
 *     unsorted, in exactly ONE call — asserted against a canonical order the
 *     test computes itself, plus an explicit non-vacuity check that the two
 *     genuinely differ.
 *  2. An out-of-range index FAILS rather than being clamped or skipped.
 *  3. `only` and `order` are mutually exclusive and one is required.
 *  4. `only` still means "that subset, in canonical order", whatever order it
 *     was listed in — otherwise the two selectors collapse into each other and
 *     `order` pins nothing.
 */
class LosslessTreeDeliverOrderTest {
    private val json = Json

    private fun step(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun key(id: TreeOpId): String = "${id.counter}:${id.peer}"

    /**
     * The `out_of_order_delivery_buffers.json` state, built through the same step
     * interpreter the fixtures run: `b` holds only the empty `para` shell while
     * `a` creates `outer`, creates `inner` inside it, then edits `inner`. Three
     * ops, each depending on the one before it.
     */
    private fun world(): LosslessTreeReplayWorld {
        val world = LosslessTreeReplayWorld()
        world.replicas["a"] = LosslessTreeCrdt(1L)
        world.buildChildren(
            step("""{"children": [{"label": "para", "element": "para", "children": []}]}"""),
            TreeNodeId.ROOT,
        )
        world.applyStep(step("""{"fork": "b", "peer": 2}"""))
        world.applyStep(
            step("""{"on": "a", "op": "create", "parent": "para", "after": null, "label": "outer", "element": "wrap"}"""),
        )
        world.applyStep(
            step(
                """{"on": "a", "op": "create", "parent": "outer", "after": null, "label": "inner",""" +
                    """ "leaf": {"kind": "raw", "text": "deep"}}""",
            ),
        )
        world.applyStep(
            step("""{"on": "a", "op": "edit_leaf", "node": "inner", "at_byte": 4, "delete_bytes": 0, "insert": "X"}"""),
        )
        return world
    }

    @Test
    fun `deliver order reaches applyUpdate as the listed sequence, unsorted, in one call`() {
        val world = world()
        val canonical = world.canonicalDiff("a", "b").ops.map { key(it.id) }
        assertEquals(3, canonical.size, "the fixture's indexes address a three-op canonical diff")

        val recorded = mutableListOf<Pair<String, TreeUpdate>>()
        world.deliveries = recorded
        world.applyStep(step("""{"deliver": {"from": "a", "to": "b", "order": [2, 1, 0]}}"""))

        // ONE call. Splitting the batch would let each op be applied while its
        // dependency is already present, which is the buffer bypassed by
        // scheduling rather than by re-sorting.
        assertEquals(1, recorded.size, "a deliver step is exactly one applyUpdate call")
        assertEquals("b", recorded[0].first, "delivered to the step's `to` replica")

        val delivered = recorded[0].second.ops.map { key(it.id) }
        val expected = listOf(canonical[2], canonical[1], canonical[0])

        // Non-vacuity FIRST: if the listed order ever coincided with the
        // canonical one, every assertion below would hold for a runner that
        // re-sorts and this test would pin nothing.
        assertTrue(
            expected != canonical,
            "the listed order must differ from the canonical order or this test pins nothing",
        )
        assertEquals(
            expected.sorted(),
            delivered.sorted(),
            "deliver.order selects exactly the indexed ops",
        )
        assertEquals(expected, delivered, "deliver.order delivers the LISTED sequence, unsorted")
    }

    @Test
    fun `deliver order need not be a permutation`() {
        val world = world()
        val canonical = world.canonicalDiff("a", "b").ops.map { key(it.id) }

        val recorded = mutableListOf<Pair<String, TreeUpdate>>()
        world.deliveries = recorded
        world.applyStep(step("""{"deliver": {"from": "a", "to": "b", "order": [1, 0]}}"""))

        assertEquals(1, recorded.size)
        assertEquals(
            listOf(canonical[1], canonical[0]),
            recorded[0].second.ops.map { key(it.id) },
            "a proper subset in the listed order is a valid `order`",
        )
    }

    @Test
    fun `deliver order fails on an out-of-range index rather than clamping`() {
        val world = world()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                world.applyStep(step("""{"deliver": {"from": "a", "to": "b", "order": [3]}}"""))
            }
        assertTrue(
            failure.message!!.contains("out of range"),
            "an index the canonical diff cannot satisfy is the finding, not something to clamp: ${failure.message}",
        )
    }

    @Test
    fun `deliver rejects both selectors and neither`() {
        val both =
            assertFailsWith<IllegalArgumentException> {
                world().applyStep(step("""{"deliver": {"from": "a", "to": "b", "only": [0], "order": [0]}}"""))
            }
        assertTrue(both.message!!.contains("both"), "both selectors present: ${both.message}")

        val neither =
            assertFailsWith<IllegalArgumentException> {
                world().applyStep(step("""{"deliver": {"from": "a", "to": "b"}}"""))
            }
        assertTrue(neither.message!!.contains("neither"), "no selector present: ${neither.message}")
    }

    @Test
    fun `deliver only keeps canonical order whatever order it was listed in`() {
        val world = world()
        val canonical = world.canonicalDiff("a", "b").ops.map { key(it.id) }

        val recorded = mutableListOf<Pair<String, TreeUpdate>>()
        world.deliveries = recorded
        world.applyStep(step("""{"deliver": {"from": "a", "to": "b", "only": [2, 0]}}"""))

        assertEquals(1, recorded.size)
        assertEquals(
            listOf(canonical[0], canonical[2]),
            recorded[0].second.ops.map { key(it.id) },
            "`only` is a SUBSET in canonical order — the listing order carries no meaning",
        )
    }
}
