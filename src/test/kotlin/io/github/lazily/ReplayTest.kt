package io.github.lazily

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the replay-equivalence proof (`#lzreplaykt`) beyond what the
 * canonical corpus pins in [ReplayConformanceTest].
 *
 * The corpus asserts the three cross-binding obligations. These assert the parts
 * that are this binding's own choices — the wire form, the non-contiguous seq
 * rule, the `prove` self-check, and the divergence kinds a fixture built out of
 * one canonical subject never reaches.
 */
class ReplayTest {
    private class Counter(
        private val labels: List<String> = listOf("total"),
        private val bias: () -> Long = { 0 },
    ) : ReplayGraph {
        private var total = 0L

        override fun apply(event: ReplayEvent) {
            total += (event.payload as Long) + bias()
        }

        override fun observe(): Map<String, Any?> = labels.associateWith { total }
    }

    private fun log() = ReplayLog.fromRecords(listOf("add" to 1L, "add" to 2L, "add" to 3L))

    // -- the log -------------------------------------------------------------

    @Test
    fun `seqs must strictly increase`() {
        assertFailsWith<IllegalArgumentException> {
            ReplayLog.of(ReplayEvent(1, "a", 1L), ReplayEvent(1, "b", 1L))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplayLog.of(ReplayEvent(2, "a", 1L), ReplayEvent(1, "b", 1L))
        }
    }

    @Test
    fun `seqs may be non-contiguous, and the gaps are checkpointed as themselves`() {
        // An ack-truncated durable outbox replays real epochs; renumbering them
        // would hide a truncated prefix that the log digest otherwise catches.
        val sparse = ReplayLog.of(ReplayEvent(7, "add", 1L), ReplayEvent(41, "add", 2L))
        val fingerprint = ReplayHarness({ Counter() }).record(sparse)
        assertEquals(listOf(REPLAY_INITIAL_SEQ, 7L, 41L), fingerprint.checkpoints.map { it.seq })
        assertNotEquals(
            sparse.digest,
            ReplayLog.of(ReplayEvent(41, "add", 2L)).digest,
            "a truncated prefix must change the log digest",
        )
    }

    @Test
    fun `a reordered log has a different digest but can settle to the same values`() {
        val forward = ReplayLog.fromRecords(listOf("add" to 1L, "add" to 2L, "add" to 3L))
        val reverse = ReplayLog.fromRecords(listOf("add" to 3L, "add" to 2L, "add" to 1L))
        assertNotEquals(forward.digest, reverse.digest)
        val harness = ReplayHarness({ Counter() })
        assertEquals(
            harness.record(forward).final.asMap(),
            harness.record(reverse).final.asMap(),
            "the premise of obligation 1: a value-only comparison would pass here",
        )
        assertFailsWith<ReplayLogMismatchException> {
            harness.verify(reverse, harness.record(forward))
        }
    }

    // -- the fingerprint -----------------------------------------------------

    @Test
    fun `a fingerprint round-trips through its wire form`() {
        val recorded = ReplayHarness({ Counter() }, stride = 2).record(log())
        val rebuilt = ReplayFingerprint.fromWire(recorded.toWire())
        assertEquals(recorded.logDigest, rebuilt.logDigest)
        assertEquals(recorded.stride, rebuilt.stride)
        assertEquals(recorded.checkpoints, rebuilt.checkpoints)
        assertEquals(recorded, rebuilt)
        assertEquals(recorded.digest, rebuilt.digest)
    }

    @Test
    fun `an unknown wire schema version is refused`() {
        val wire = ReplayHarness({ Counter() }).record(log()).toWire().toMutableMap()
        wire["schema_version"] = 99
        assertFailsWith<ReplayProofException> { ReplayFingerprint.fromWire(wire) }
    }

    @Test
    fun `a stride is part of the fingerprint`() {
        val sparse = ReplayHarness({ Counter() }, stride = 2).record(log())
        val error =
            assertFailsWith<ReplayStrideMismatchException> {
                ReplayHarness({ Counter() }, stride = 1).verify(log(), sparse)
            }
        assertEquals(2, error.expectedStride)
        assertEquals(1, error.actualStride)
    }

    // -- divergence ----------------------------------------------------------

    @Test
    fun `prove catches a graph that is not a pure function of its log`() {
        // A graph that reads something outside its log — here, how many times it has
        // been built. `prove` needs no external fingerprint to catch it: two replays
        // of the same log in the same process already disagree.
        var builds = 0
        val harness =
            ReplayHarness({
                builds += 1
                val bias = builds.toLong() - 1
                Counter(bias = { bias })
            })
        val error =
            assertFailsWith<ReplayDivergenceException> {
                harness.prove(
                    ReplayLog.of(ReplayEvent(0, "add", 1L), ReplayEvent(1, "add", 1L)),
                    replays = 2,
                )
            }
        assertEquals(2, builds, "`build` must be called once per replay, never reused")
        assertEquals(ReplayDivergenceKind.VALUE, error.first.kind)
        assertEquals(0L, error.first.seq, "the first checkpoint that parted, not the last")
    }

    @Test
    fun `a missing cell and an unexpected cell are distinct kinds`() {
        val both = ReplayHarness({ Counter(labels = listOf("total", "shadow")) }).record(log())
        val onlyTotal = ReplayHarness({ Counter(labels = listOf("total")) }).record(log())

        val missing =
            assertFailsWith<ReplayDivergenceException> {
                ReplayHarness({ Counter(labels = listOf("total")) }).verify(log(), both)
            }
        assertEquals(ReplayDivergenceKind.MISSING, missing.first.kind)
        assertEquals("shadow", missing.first.label)

        val unexpected =
            assertFailsWith<ReplayDivergenceException> {
                ReplayHarness({ Counter(labels = listOf("total", "shadow")) }).verify(log(), onlyTotal)
            }
        assertEquals(ReplayDivergenceKind.UNEXPECTED, unexpected.first.kind)
        assertEquals("shadow", unexpected.first.label)
    }

    @Test
    fun `check reports value divergences without throwing, but still refuses a stale fingerprint`() {
        val clean = ReplayHarness({ Counter() }).record(log())
        val drifted = ReplayHarness({ Counter(bias = { 5L }) })
        val divergences = drifted.check(log(), clean)
        assertEquals(1, divergences.size)
        assertEquals(0L, divergences.first().seq, "the FIRST checkpoint that parted, not the last")
        assertFailsWith<ReplayLogMismatchException> {
            drifted.check(ReplayLog.fromRecords(listOf("add" to 9L)), clean)
        }
    }

    // -- the canonical encoding ----------------------------------------------

    @Test
    fun `members are length-framed so a concatenation is unambiguous`() {
        assertNotEquals(canonicalDigest(listOf("a", "bc")), canonicalDigest(listOf("ab", "c")))
        assertNotEquals(canonicalDigest(listOf(1L, 2L)), canonicalDigest(listOf(12L)))
        // Two DIFFERENT framing properties are at stake here, and a pair that pins one
        // says nothing about the other.
        //
        // 1. MEMBER length — the length inside each leaf frame. The corpus's plain pair
        //    above is not enough to pin it: with a type tag in front of every member,
        //    `s|a s|bc` and `s|ab s|c` already differ as strings, so an encoder that
        //    dropped the length still passes it. These two collide the moment the member
        //    length goes away, because the member content spells the TAG of the member
        //    after it (`s`) — the collision is specific to this layout's string tag, so
        //    a binding whose tags differ must carry its own colliding pair.
        assertNotEquals(canonicalDigest(listOf("a", "sbc")), canonicalDigest(listOf("as", "bc")))
        assertNotEquals(canonicalDigest(mapOf("a" to "sb")), canonicalDigest(mapOf("as" to "b")))
        // 2. CONTAINER length — the length on the container's own frame, which the pairs
        //    above leave completely free: every one of them is a flat two-member sequence
        //    or a one-entry mapping, so both sides carry the same container framing and an
        //    encoder that emitted `l` with no length at all still tells them apart. A
        //    nested container is what pins it, because a container boundary has no tag to
        //    hide behind: drop the nested container's length and both sides concatenate to
        //    the identical `l l s1:a s1:b`. This collides in EVERY layout, not just one
        //    whose string tag is `s`.
        assertNotEquals(canonicalDigest(listOf(listOf("a"), "b")), canonicalDigest(listOf(listOf("a", "b"))))
    }

    @Test
    fun `integers are exact beyond two to the fifty-three`() {
        val big = BigInteger("9007199254740993")
        assertNotEquals(canonicalDigest(big), canonicalDigest(big.add(BigInteger.ONE)))
        assertEquals(canonicalDigest(BigInteger.ONE), canonicalDigest(1L), "width is not part of an integer")
    }

    @Test
    fun `a type tag survives an equal shape`() {
        class Alpha(
            private val n: Long,
        ) : ReplayCanonical {
            override val canonicalTypeName: String get() = "Alpha"

            override fun canonicalForm(): Any? = listOf(n)
        }

        class Beta(
            private val n: Long,
        ) : ReplayCanonical {
            override val canonicalTypeName: String get() = "Beta"

            override fun canonicalForm(): Any? = listOf(n)
        }

        assertNotEquals(canonicalDigest(Alpha(1)), canonicalDigest(Beta(1)))
        assertEquals(canonicalDigest(Alpha(1)), canonicalDigest(Alpha(1)))
    }

    @Test
    fun `an enum entry is not its name`() {
        assertNotEquals(
            canonicalDigest(ReplayDivergenceKind.VALUE),
            canonicalDigest(ReplayDivergenceKind.VALUE.name),
        )
    }

    @Test
    fun `an undefined value fails loudly instead of using the host rendering`() {
        class Undefined

        val error = assertFailsWith<ReplayEncodingException> { canonicalDigest(Undefined()) }
        assertTrue(
            "no canonical encoding" in error.message.orEmpty(),
            "the message must say what to do instead, got ${error.message}",
        )
    }

    @Test
    fun `byte arrays are compared by content, not identity`() {
        assertEquals(canonicalDigest(byteArrayOf(1, 2)), canonicalDigest(byteArrayOf(1, 2)))
        assertNotEquals(canonicalDigest(byteArrayOf(1, 2)), canonicalDigest(byteArrayOf(2, 1)))
    }

    // -- the outbox bridge ---------------------------------------------------

    @Test
    fun `a durable outbox is a fingerprinted replay source`() {
        val outbox = InMemoryOutbox()
        for (epoch in 1L..3L) outbox.append(epoch, IpcMessage.ofDelta(Delta(epoch - 1, epoch)))
        val log = replayLogFromOutbox(outbox)
        assertEquals(listOf(1L, 2L, 3L), log.events.map { it.seq })
        assertContentEquals(
            IpcMessage.ofDelta(Delta(0, 1)).encodeJson(),
            log[0].payload as ByteArray,
        )

        // A truncated prefix changes the log digest, so a fingerprint recorded over
        // the untruncated log is refused rather than silently compared against a
        // shorter replay. Epochs stay the outbox's own, so the truncation is visible
        // instead of shifting every event down by one.
        assertNotEquals(log.digest, replayLogFromOutbox(outbox, cursor = 1).digest)
        assertEquals(listOf(2L, 3L), replayLogFromOutbox(outbox, cursor = 1).events.map { it.seq })
    }
}
