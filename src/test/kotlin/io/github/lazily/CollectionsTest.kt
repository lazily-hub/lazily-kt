package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the keyed cell collections layer
 * (`lazily-spec/cell-model.md` § Keyed cell collections): the value /
 * set-membership / order reactivity independence, stable handles, atomic move,
 * `CellTree` per-level reactivity, and move-minimized keyed reconciliation.
 *
 * The canonical fixture replay lives in [CollectionsConformanceTest].
 */
class CollectionsTest {
    @Test
    fun `value write invalidates only that key's value readers`() {
        val ctx = Context()
        val map: CellMap<String, Int> = CellMap(
            ctx,
            listOf("a" to 1, "b" to 2, "c" to 3),
        )
        val readerA = ctx.memo { map.get("a") }
        val readerB = ctx.memo { map.get("b") }
        val membership = ctx.memo { ctx.get(map.len()) }
        val order = ctx.memo { ctx.get(map.keys()) }
        // establish deps
        ctx.get(readerA); ctx.get(readerB); ctx.get(membership); ctx.get(order)

        map.setValue("a", 10)

        assertTrue(ctx.isSet(readerA).not(), "value reader A invalidated")
        assertTrue(ctx.isSet(readerB), "unrelated value reader B NOT invalidated")
        assertTrue(ctx.isSet(membership), "membership reader NOT invalidated by value write")
        assertTrue(ctx.isSet(order), "order reader NOT invalidated by value write")
        assertEquals(10, map.get("a"))
    }

    @Test
    fun `insert invalidates membership and order only`() {
        val ctx = Context()
        val map: CellMap<String, Int> = CellMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3))
        val readerA = ctx.memo { map.get("a") }
        val membership = ctx.memo { ctx.get(map.len()) }
        val order = ctx.memo { ctx.get(map.keys()) }
        ctx.get(readerA); ctx.get(membership); ctx.get(order)

        map.insert("d", 4, InsertAt.End)

        assertTrue(ctx.isSet(readerA), "unrelated value reader NOT invalidated by insert")
        assertTrue(ctx.isSet(membership).not(), "membership reader invalidated by insert")
        assertTrue(ctx.isSet(order).not(), "order reader invalidated by insert")
        assertEquals(listOf("a", "b", "c", "d"), map.keysNow())
    }

    @Test
    fun `atomic move keeps handle + bumps order only`() {
        val ctx = Context()
        val map: CellMap<String, Int> = CellMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4))
        val handleB = map.value("b").id
        val readerB = ctx.memo { map.get("b") }
        val membership = ctx.memo { ctx.get(map.contains("c")) }
        val order = ctx.memo { ctx.get(map.keys()) }
        ctx.get(readerB); ctx.get(membership); ctx.get(order)

        map.moveTo("b", 3) // a c d b

        assertEquals(handleB, map.value("b").id, "atomic move keeps the same cell handle")
        assertEquals(listOf("a", "c", "d", "b"), map.keysNow())
        assertTrue(ctx.isSet(readerB), "moved key's value reader NOT invalidated")
        assertTrue(ctx.isSet(membership), "membership reader NOT invalidated by pure reorder")
        assertTrue(ctx.isSet(order).not(), "order reader invalidated by pure reorder")
    }

    @Test
    fun `move_before and move_after`() {
        val ctx = Context()
        val map: CellMap<String, Int> = CellMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4))
        map.moveBefore("d", "a")
        assertEquals(listOf("d", "a", "b", "c"), map.keysNow())
        map.moveAfter("c", "a")
        assertEquals(listOf("d", "a", "c", "b"), map.keysNow())
    }

    @Test
    fun `remove invalidates membership and order only`() {
        val ctx = Context()
        val map: CellMap<String, Int> = CellMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3))
        val readerA = ctx.memo { map.get("a") }
        val membership = ctx.memo { ctx.get(map.len()) }
        val order = ctx.memo { ctx.get(map.keys()) }
        ctx.get(readerA); ctx.get(membership); ctx.get(order)

        map.remove("b")

        assertTrue(ctx.isSet(readerA), "unrelated value reader NOT invalidated by remove")
        assertTrue(ctx.isSet(membership).not(), "membership reader invalidated by remove")
        assertTrue(ctx.isSet(order).not(), "order reader invalidated by remove")
        assertEquals(listOf("a", "c"), map.keysNow())
    }

    @Test
    fun `cell family mints typed maps`() {
        val ctx = Context()
        val fam = CellFamily<Int>()
        val map = fam.map<String>(ctx)
        map.insert("x", 7)
        assertEquals(7, map.get("x"))
    }

    @Test
    fun `cell tree per-level reactivity and atomic child move`() {
        val ctx = Context()
        val tree = CellTree<String, Int>(ctx)
        tree.addRoot("root", 0)
        tree.insertChild("root", "a", 1)
        tree.insertChild("root", "b", 2)
        tree.insertChild("root", "c", 3)

        val rootValueReader = ctx.memo { tree.get("root") }
        val childAValueReader = ctx.memo { tree.get("a") }
        val rootChildrenOrder = ctx.memo { ctx.get(tree.children("root").keys()) }
        ctx.get(rootValueReader); ctx.get(childAValueReader); ctx.get(rootChildrenOrder)

        // edit a node value -> only that node's readers invalidate.
        tree.setValue("a", 11)
        assertTrue(ctx.isSet(rootValueReader), "unrelated node value reader NOT invalidated")
        assertTrue(ctx.isSet(childAValueReader).not(), "edited node value reader invalidated")
        assertTrue(ctx.isSet(rootChildrenOrder), "child order NOT invalidated by node value edit")
        assertEquals(11, tree.get("a"))

        // Re-prime readers so the next op's effect is measured from a fresh state.
        val rootValueReader2 = ctx.memo { tree.get("root") }
        val childAValueReader2 = ctx.memo { tree.get("a") }
        val rootChildrenOrder2 = ctx.memo { ctx.get(tree.children("root").keys()) }
        ctx.get(rootValueReader2); ctx.get(childAValueReader2); ctx.get(rootChildrenOrder2)

        // atomic child move -> only child order invalidates; node values untouched.
        val handleA = tree.value("a").id
        tree.moveChildTo("root", "a", 2) // b c a
        assertEquals(handleA, tree.value("a").id, "child move keeps node value handle")
        assertEquals(listOf("b", "c", "a"), tree.children("root").keysNow())
        assertTrue(ctx.isSet(rootValueReader2), "node value reader NOT invalidated by child reorder")
        assertTrue(ctx.isSet(childAValueReader2), "child value reader NOT invalidated by child reorder")
        assertTrue(ctx.isSet(rootChildrenOrder2).not(), "child order invalidated by child reorder")
    }

    @Test
    fun `lis picks longest strictly increasing subsequence indices`() {
        assertEquals(listOf(0, 1), longestIncreasingSubsequenceIndices(listOf(1, 2, 0)))
        assertEquals(listOf(0, 1, 3, 4), longestIncreasingSubsequenceIndices(listOf(0, 1, 5, 3, 4)))
        assertEquals(emptyList(), longestIncreasingSubsequenceIndices(emptyList()))
        assertEquals(1, longestIncreasingSubsequenceIndices(listOf(5, 4, 3)).size)
    }

    @Test
    fun `reconcile emits move-minimized op set for the canonical case`() {
        val prior = ReconcileState(listOf("a", "b", "c", "d"), mapOf("a" to 1, "b" to 2, "c" to 3, "d" to 4))
        val target = ReconcileState(listOf("b", "c", "a"), mapOf("a" to 1, "b" to 2, "c" to 3))
        val ops = reconcile(prior, target)
        // b and c are LIS-stable (prior indices 1,2) -> must NOT move; d removed; a moved after c.
        assertEquals(listOf(ReconOp.Remove("d"), ReconOp.Move("a", ReconOp.Anchor.After("c"))), ops)
    }

    @Test
    fun `reconcile applied to cellmap keeps stable entries uninvalidated`() {
        val ctx = Context()
        val map: CellMap<String, Int> =
            CellMap(ctx, listOf("a" to 1, "b" to 2, "c" to 3, "d" to 4))
        val readerB = ctx.memo { map.get("b") }
        val readerC = ctx.memo { map.get("c") }
        val readerA = ctx.memo { map.get("a") }
        ctx.get(readerB); ctx.get(readerC); ctx.get(readerA)

        map.reconcile(listOf("b", "c", "a"), mapOf("a" to 1, "b" to 2, "c" to 3))

        assertEquals(listOf("b", "c", "a"), map.keysNow())
        assertTrue(ctx.isSet(readerB), "stable entry b NOT invalidated by sibling reorder")
        assertTrue(ctx.isSet(readerC), "stable entry c NOT invalidated by sibling reorder")
        assertTrue(ctx.isSet(readerA), "stable entry a (unchanged value) NOT invalidated")
        assertNotEquals(listOf("a", "b", "c", "d"), map.keysNow())
        assertFalse(map.containsNow("d"))
    }
}
