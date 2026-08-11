package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the diff ORDER contract for [LosslessTreeCrdt] (`#lzdifforderallbindings`).
 *
 * [LosslessTreeCrdt.diff] returns ops sorted by `(id.counter, id.peer)`. That
 * order is a CROSS-BINDING contract, not an implementation detail: the shared
 * corpus addresses diff output POSITIONALLY — `lossless-tree/non_contiguous_anti_entropy.json`
 * carries `deliver.only: [0, 2]`, indexing into whatever `diff` hands back — so
 * the fixture only means the same thing in every binding while every binding
 * returns the same sequence.
 *
 * The corpus cannot police it. Measured in lazily-zig: replacing the sort with a
 * reverse, or deleting it outright, left the ENTIRE suite green, because two
 * indices select the same SET either way and `applyUpdate` is order-tolerant by
 * design. Only a direct test catches it.
 *
 * The trap in writing that direct test is vacuity: if ops enter the log already
 * in canonical order, "strictly increasing" holds for a reversed or absent sort
 * by luck. So the state below is built so ARRIVAL order and CANONICAL order
 * genuinely DIFFER — `a` runs ahead to counter 4 while `b`'s single op stays at
 * counter 3, and that lower-counter remote op arrives LAST while sorting fourth
 * — and the difference is asserted EXPLICITLY before the order check. If a
 * refactor ever makes the two coincide, this fails loudly instead of decaying
 * into a tautology.
 *
 * `log` is private here, so arrival order is reconstructed from the ids
 * `createNode` returns (a node id IS its op id) and the canonical order is
 * computed by the TEST. That keeps the two failure modes separated: "wrong
 * order" and "test went vacuous" cannot masquerade as each other.
 */
class LosslessTreeCrdtDiffOrderTest {
    private fun key(id: TreeOpId): String = "${id.counter}:${id.peer}"

    @Test
    fun `diff returns ops in canonical (counter, peer) order`() {
        val a = LosslessTreeCrdt(1L)
        val para = a.createNode(TreeNodeId.ROOT, null, NodeSeed.Element("para"))
        val base = a.createNode(para, null, NodeSeed.Leaf(LeafKind.Trivia, "0"))

        val b = a.fork(2L)

        // `a` runs ahead to counter 4; `b`'s single op stays at counter 3.
        val one = a.createNode(para, base, NodeSeed.Leaf(LeafKind.Trivia, "1"))
        val two = a.createNode(para, one, NodeSeed.Leaf(LeafKind.Trivia, "2"))
        val remote = b.createNode(para, base, NodeSeed.Leaf(LeafKind.Trivia, "9"))

        val fromB = b.diff(a.frontier())
        assertEquals(1, fromB.ops.size, "only b's own op is unknown to a")
        a.applyUpdate(fromB)

        val all = a.diff(TreeVersionFrontier())

        // The order the ops entered `a`: its four local ops as committed, then
        // the remote op, which lands last despite sorting fourth. Built from the
        // ids the library actually minted, not from hardcoded counters.
        val arrival = listOf(para, base, one, two, remote).map { it.op }

        // Same ops on both sides, so every comparison below is about ORDER and
        // nothing else. If the library ever mints a different op set here, this
        // fails rather than letting the order checks run against a stale list.
        assertEquals(
            arrival.map(::key).sorted(),
            all.ops.map { key(it.id) }.sorted(),
            "diff returns exactly the ops that entered the replica",
        )

        // Non-vacuity, against a canonical order the TEST computes rather than
        // one the library hands back. If a refactor ever made ops arrive already
        // sorted, everything below would hold for an unsorted or reversed `diff`
        // too and this test would silently pin nothing.
        val canonical = arrival.sortedWith(compareBy({ it.counter }, { it.peer }))
        assertTrue(
            arrival.map(::key) != canonical.map(::key),
            "arrival order must differ from canonical order or this test pins nothing",
        )

        // The contract: strictly increasing by (counter, peer).
        for (i in 1 until all.ops.size) {
            val prev = all.ops[i - 1].id
            val curr = all.ops[i].id
            val ordered =
                if (prev.counter != curr.counter) prev.counter < curr.counter else prev.peer < curr.peer
            assertTrue(
                ordered,
                "diff op ${i - 1} (${key(prev)}) must sort before op $i (${key(curr)})",
            )
        }
    }
}
