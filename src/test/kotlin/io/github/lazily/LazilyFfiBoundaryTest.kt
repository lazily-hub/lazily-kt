package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Spec-compliance tests for the C-ABI FFI host boundary
 * (`lazily-spec/protocol.md` § FFI Boundary, `lazily-spec/schemas/ffi.json`):
 * the `LazilyFfiBytes` / `LazilyFfiStatus` / `LazilyFfiMessageKind` contract,
 * the `CrdtSync = 3` discriminant, the decode→`IpcMessage`→canonical-JSON
 * re-encode channel, and the panic guard.
 */
class LazilyFfiBoundaryTest {
    @Test
    fun `message kind enum matches ffi dot json discriminant with crdt sync equals 3`() {
        assertEquals(0, LazilyFfiMessageKind.Unknown.code)
        assertEquals(1, LazilyFfiMessageKind.Snapshot.code)
        assertEquals(2, LazilyFfiMessageKind.Delta.code)
        // The CrdtSync = 3 discriminant is required of every conforming host.
        assertEquals(3, LazilyFfiMessageKind.CrdtSync.code)
        assertEquals(LazilyFfiMessageKind.CrdtSync, LazilyFfiMessageKind.fromCode(3))
    }

    @Test
    fun `status enum matches ffi dot json 0 through 5`() {
        assertEquals(0, LazilyFfiStatus.Ok.code)
        assertEquals(1, LazilyFfiStatus.Empty.code)
        assertEquals(2, LazilyFfiStatus.NullPointer.code)
        assertEquals(3, LazilyFfiStatus.InvalidMessage.code)
        assertEquals(4, LazilyFfiStatus.EncodeFailed.code)
        assertEquals(5, LazilyFfiStatus.Panic.code)
    }

    @Test
    fun `message kind of each ipc variant`() {
        val snap = IpcMessage.ofSnapshot(Snapshot(epoch = 1))
        val delta = IpcMessage.ofDelta(Delta.next(1, emptyList()))
        val crdt = IpcMessage.ofCrdtSync(CrdtSync(emptyList(), emptyList()))
        assertEquals(LazilyFfiMessageKind.Snapshot, LazilyFfiChannel.messageKindOf(snap))
        assertEquals(LazilyFfiMessageKind.Delta, LazilyFfiChannel.messageKindOf(delta))
        assertEquals(LazilyFfiMessageKind.CrdtSync, LazilyFfiChannel.messageKindOf(crdt))
    }

    @Test
    fun `encode and decode round trip re-encodes canonical json bytes`() {
        val original = IpcMessage.ofSnapshot(
            Snapshot(
                epoch = 1,
                nodes = listOf(NodeSnapshot.payload(7, "i32", byteArrayOf(1, 2, 3))),
                edges = emptyList(),
                roots = listOf(7),
            ),
        )
        val encoded = LazilyFfiChannel.encode(original)
        assertEquals(LazilyFfiStatus.Ok, encoded.status)

        val decoded = LazilyFfiChannel.decode(LazilyFfiMessageKind.Snapshot, LazilyFfiBytes(encoded.canonicalBytes))
        assertEquals(LazilyFfiStatus.Ok, decoded.status)
        assertNotNull(decoded.message)
        // The decoded frame re-encodes to byte-identical canonical JSON.
        assertContentEquals(encoded.canonicalBytes, decoded.canonicalBytes)
        assertEquals(original, decoded.message)
    }

    @Test
    fun `crdt sync frame crosses the ffi channel`() {
        val frame = CrdtSync(
            frontier = listOf(1L to WireStamp(100, 0, 1)),
            ops = listOf(
                CrdtOp(node = 1, key = NodeKey.from("scores/alice"), stamp = WireStamp(100, 0, 1), state = IpcValue.Inline(byteArrayOf(9))),
            ),
        )
        val encoded = LazilyFfiChannel.encode(IpcMessage.ofCrdtSync(frame))
        val decoded = LazilyFfiChannel.decode(LazilyFfiMessageKind.CrdtSync, LazilyFfiBytes(encoded.canonicalBytes))
        assertEquals(LazilyFfiStatus.Ok, decoded.status)
        assertEquals(LazilyFfiMessageKind.CrdtSync, LazilyFfiChannel.messageKindOf(decoded.message!!))
        assertContentEquals(encoded.canonicalBytes, decoded.canonicalBytes)
    }

    @Test
    fun `empty input returns Empty status without throwing`() {
        val decoded = LazilyFfiChannel.decode(LazilyFfiMessageKind.Snapshot, LazilyFfiBytes(ByteArray(0)))
        assertEquals(LazilyFfiStatus.Empty, decoded.status)
        assertNull(decoded.message)
    }

    @Test
    fun `malformed input returns InvalidMessage without throwing`() {
        val decoded = LazilyFfiChannel.decode(
            LazilyFfiMessageKind.Snapshot,
            LazilyFfiBytes("{not valid json".encodeToByteArray()),
        )
        assertEquals(LazilyFfiStatus.InvalidMessage, decoded.status)
        assertNull(decoded.message)
    }

    @Test
    fun `kind mismatch returns InvalidMessage`() {
        val encoded = LazilyFfiChannel.encode(IpcMessage.ofDelta(Delta.next(1, emptyList())))
        val decoded = LazilyFfiChannel.decode(LazilyFfiMessageKind.Snapshot, LazilyFfiBytes(encoded.canonicalBytes))
        assertEquals(LazilyFfiStatus.InvalidMessage, decoded.status)
    }

    @Test
    fun `native entry table panic guard maps failures to status codes`() {
        val out = arrayOfNulls<LazilyFfiBytes>(1)
        val len = IntArray(1)

        // A well-formed snapshot encode succeeds.
        val status = LazilyFfiNative.lazilyFfiEncode(
            IpcMessage.ofSnapshot(Snapshot(epoch = 1)),
            out, len,
        )
        assertEquals(LazilyFfiStatus.Ok, status)
        assertEquals(out[0]!!.len, len[0])

        // Decoding valid bytes re-encodes them canonically.
        val decStatus = LazilyFfiNative.lazilyFfiDecode(
            LazilyFfiMessageKind.Snapshot,
            out[0]!!,
            out, len,
        )
        assertEquals(LazilyFfiStatus.Ok, decStatus)

        // An empty decode returns Empty (panic guard stays out of the happy path).
        assertEquals(
            LazilyFfiStatus.Empty,
            LazilyFfiNative.lazilyFfiDecode(LazilyFfiMessageKind.Snapshot, LazilyFfiBytes(ByteArray(0)), out, len),
        )

        // free is a safe no-op on the JVM-backed buffer.
        LazilyFfiNative.lazilyFfiFree(out[0])
        LazilyFfiNative.lazilyFfiFree(null)
    }
}
