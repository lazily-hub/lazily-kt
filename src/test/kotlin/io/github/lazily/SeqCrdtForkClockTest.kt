package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fork-clock regression tests for [SeqCrdt] (`#lzzigforkhlcpeer`).
 *
 * `cloneStateAs` has to get **two independent things** right, and getting
 * either one wrong silently corrupts a replica rather than throwing:
 *
 *  1. **Carry the causal POSITION.** A fork has already observed everything the
 *     source holds, so its clock must not restart at zero. If it does, an
 *     ordinary skewed `now` below the source's `lastWall` mints a stamp
 *     causally BEHIND state the fork already carries; [SeqLww] adopts only on
 *     `>`, so the fork's own local write is silently dropped.
 *  2. **Do NOT carry the PEER.** The peer is the stamp's final tiebreaker. Two
 *     replicas stamping under one peer id can mint the identical
 *     `(wall, logical, peer)` triple — and because LWW adopts only on `>`, a
 *     tie means NEITHER side adopts and the replicas diverge permanently, the
 *     one outcome a CRDT exists to make impossible. lazily-zig shipped exactly
 *     that bug while fixing (1).
 *
 * `seqcrdt_convergence.json` cannot reach either failure: every fork in the
 * canonical corpus is followed by an op whose `now` EXCEEDS the source's
 * `lastWall`, so [SeqHlc.send] takes the `nowMicros > lastWall` branch and the
 * logical counter resets to 0 no matter which clock the fork started from.
 * These tests are the reachability the corpus is missing.
 */
class SeqCrdtForkClockTest {
    @Test
    fun `a fork carries the source clock forward, so a skewed local write is not dropped`() {
        // The reproduction: b's `now` is BELOW a's lastWall — ordinary clock
        // skew, which is the entire reason HLCs exist. With a reset clock b
        // mints (50, 0, 2), which is not > (100, 0, 1), and b's own write
        // vanishes into its own state.
        val a = SeqCrdt<String, Long>(1L)
        a.insertBack("x", 1L, 100L) // value stamp (100, 0, 1)

        val b = a.cloneStateAs(2L)
        b.setValue("x", 99L, 50L) // carried clock -> (100, 1, 2), beats (100, 0, 1)

        // The fork's OWN write must survive in the fork.
        assertEquals(99L, b.get("x"))

        // ...and the two replicas must agree once they exchange state.
        a.merge(b, 200L)
        b.merge(a, 200L)
        assertEquals(a.get("x"), b.get("x"))
        assertEquals(99L, a.get("x"))
    }

    @Test
    fun `a fork stamps with its OWN peer, so equal-wall edits still converge`() {
        // Both replicas write at the same wall time, so the logical counter
        // decides and the PEER breaks the remaining tie:
        //
        //   a@peer1  insert x @ now=10  -> (10, 0, 1)
        //   b = a.cloneStateAs(2)       -> clock at (10, 0)
        //   b        set x=99 @ now=10  -> (10, 1, 2)
        //   a        set x=55 @ now=10  -> (10, 1, 1)
        //
        // If the fork inherited peer 1 both stamps would be (10, 1, 1),
        // neither merge would adopt, and a=55 / b=99 would stand forever.
        val a = SeqCrdt<String, Long>(1L)
        a.insertBack("x", 1L, 10L)

        val b = a.cloneStateAs(2L)
        b.setValue("x", 99L, 10L)
        a.setValue("x", 55L, 10L)

        a.merge(b, 20L)
        b.merge(a, 20L)

        // Convergence FIRST: the replicas must agree at all, before which value
        // won is even a meaningful question.
        assertEquals(a.get("x"), b.get("x"))
        // And the winner is b's write, because peer 2 outranks peer 1.
        assertEquals(99L, a.get("x"))
    }

    @Test
    fun `cloneState keeps the source peer and its clock, so the copy is a true snapshot`() {
        // `cloneState` delegates to `cloneStateAs(peer)` with the SOURCE's own
        // peer, which is the one case where carrying the peer is correct: it is
        // the same logical replica, not a second writer. It must still carry the
        // clock, or the copy regresses exactly like a fork would.
        val a = SeqCrdt<String, Long>(1L)
        a.insertBack("x", 1L, 100L)

        val copy = a.cloneState()
        copy.setValue("x", 42L, 50L) // skewed `now`, same peer

        assertEquals(42L, copy.get("x"))
        assertEquals(listOf("x" to 42L), copy.values())
    }
}
