package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Delta-sync (#lztextsync) convergence, mirroring lazily-rs / lazily-js. */
class TextCrdtDeltaSyncTest {
    @Test
    fun deltaSyncConvergesTwoReplicas() {
        val base = TextCrdt(0L, "hello\n")
        val a = base.fork(1L)
        a.insertString(a.len(), "world\n")
        val b = base.fork(2L)
        b.delete(0)

        val aDelta = a.deltaSince(b.versionVector())
        val bDelta = b.deltaSince(a.versionVector())
        assertTrue(a.applyDelta(bDelta))
        b.applyDelta(aDelta)

        assertEquals(a.text(), b.text())
        assertEquals("ello\nworld\n", a.text())
    }

    @Test
    fun fullSnapshotReconstructsMergeableReplica() {
        val canonical = TextCrdt(1L, "base\n")
        val snapshot = canonical.deltaSince(emptyMap())
        val member = TextCrdt(2L)
        member.applyDelta(snapshot)
        assertEquals("base\n", member.text())

        canonical.insertString(canonical.len(), "A\n")
        member.insertString(member.len(), "B\n")
        canonical.applyDelta(member.deltaSince(canonical.versionVector()))
        member.applyDelta(canonical.deltaSince(member.versionVector()))
        assertEquals(canonical.text(), member.text())
    }

    @Test
    fun deltaApplyIsIdempotent() {
        val a = TextCrdt(1L, "abc\n")
        val b = TextCrdt(2L)
        val delta = a.deltaSince(emptyMap())
        assertTrue(b.applyDelta(delta))
        assertFalse(b.applyDelta(delta))
        assertEquals(a.text(), b.text())
    }
}
