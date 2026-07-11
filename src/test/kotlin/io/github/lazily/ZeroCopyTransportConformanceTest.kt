package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for the cross-process zero-copy transport
 * (`BlobBackend` / shm / arrow, `#lzzcpy`) — the pluggable blob-backend layer
 * required of every binding that ships it. Each test names the `lazily-formal`
 * `ZeroCopyTransport` proof it exercises and mirrors the lazily-rs `transport`
 * tests.
 */
class ZeroCopyTransportConformanceTest {
    private val arenaCap = 1 shl 16
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** `resolve_write`: bytes spilled to a backend resolve zero-copy to exactly what was written. */
    @Test
    fun inProcessResolveWrite() {
        val backend = InProcessBackend.withCapacity(arenaCap)
        val payload = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        val desc = backend.write(payload)
        assertEquals(BlobBackendKind.InProcess, desc.backend)
        assertContentEquals(payload, backend.readView(desc))
    }

    /** Each backend stamps its own kind on the descriptors it mints. */
    @Test
    fun eachBackendStampsItsKind() {
        assertEquals(BlobBackendKind.Shm, ShmBackend.withCapacity(arenaCap).write(bytes(1)).backend)
        assertEquals(BlobBackendKind.Arrow, ArrowBackend.withCapacity(arenaCap).write(bytes(1)).backend)
        assertEquals(
            BlobBackendKind.InProcess,
            InProcessBackend.withCapacity(arenaCap).write(bytes(1)).backend,
        )
    }

    /**
     * `resolve_wrong_backend`: a descriptor of one backend kind never resolves
     * against a backend of a different kind — resolution must route by `kind`.
     */
    @Test
    fun wrongBackendDoesNotResolve() {
        val arrow = ArrowBackend.withCapacity(arenaCap)
        val inproc = InProcessBackend.withCapacity(arenaCap)
        val arrowDesc = arrow.write(bytes(9, 9, 9))
        // The in-process backend rejects a foreign (arrow) descriptor.
        assertNull(inproc.readView(arrowDesc))
        // Its own backend still resolves it.
        assertContentEquals(bytes(9, 9, 9), arrow.readView(arrowDesc))
    }

    /**
     * `resolve_wrong_backend` at the router: a descriptor routes to the backend
     * matching its discriminator, not to whichever backend was registered first.
     */
    @Test
    fun routerRoutesByBackendKind() {
        val inproc = InProcessBackend.withCapacity(arenaCap)
        val arrow = ArrowBackend.withCapacity(arenaCap)
        val router = BlobRouter().register(inproc).register(arrow)

        val inDesc = inproc.write(bytes(1, 1, 1, 1))
        val arDesc = arrow.write(bytes(2, 2))
        assertContentEquals(bytes(1, 1, 1, 1), router.readView(inDesc))
        assertContentEquals(bytes(2, 2), router.readView(arDesc))

        // A descriptor whose backend has no registered resolver returns null.
        val shmDesc = inDesc.copy(backend = BlobBackendKind.Shm)
        assertNull(router.readView(shmDesc))
    }

    /**
     * `resolve_stale_generation`: a stale descriptor to a reused slot does not
     * resolve against the new occupant (ABA safety via the arena generation).
     */
    @Test
    fun staleGenerationDoesNotResolve() {
        // A tiny arena so the second write wraps to the same offset (slot reuse).
        val backend = InProcessBackend.withCapacity(SHM_BLOB_HEADER_LEN + 8)
        val first = backend.write(bytes(1, 2, 3, 4))
        val second = backend.write(bytes(5, 6, 7, 8))
        assertEquals(first.offset, second.offset) // reused slot
        assertTrue(first.generation != second.generation)
        assertNull(backend.readView(first)) // stale generation rejected
        assertContentEquals(bytes(5, 6, 7, 8), backend.readView(second))
    }

    /** `resolve_corrupt_checksum`: a descriptor with a tampered checksum is rejected, not mis-resolved. */
    @Test
    fun corruptChecksumDoesNotResolve() {
        val backend = ShmBackend.withCapacity(arenaCap)
        val desc = backend.write(bytes(4, 5, 6))
        assertNull(backend.readView(desc.copy(checksum = desc.checksum xor 0x5a5aL)))
        assertContentEquals(bytes(4, 5, 6), backend.readView(desc))
    }

    /**
     * `transport_roundtrip`: spilling an `IpcValue` above the threshold and
     * resolving it back yields the original bytes; a small value stays inline.
     */
    @Test
    fun spillValueRoundTrip() {
        val backend = ShmBackend.withCapacity(arenaCap)
        val big = ByteArray(64) { (it and 0xff).toByte() }
        val (spilled, moved) = spillValue(IpcValue.inline(big), backend, threshold = 16)
        assertEquals(64, moved)
        assertTrue(spilled is IpcValue.SharedBlob)
        assertContentEquals(big, resolveValue(spilled, backend))

        val small = bytes(1, 2, 3)
        val (kept, keptMoved) = spillValue(IpcValue.inline(small), backend, threshold = 16)
        assertEquals(0, keptMoved)
        assertTrue(kept is IpcValue.Inline)
        assertContentEquals(small, resolveValue(kept, backend))
    }

    /** Spilling a whole Delta message replaces large payloads with descriptors and round-trips via the router. */
    @Test
    fun spillMessageDeltaRoundTrip() {
        val backend = InProcessBackend.withCapacity(arenaCap)
        val router = BlobRouter().register(backend)
        val big = ByteArray(48) { (it and 0xff).toByte() }
        val delta = Delta.next(
            baseEpoch = 1,
            ops = listOf(DeltaOp.cellSet(node = 7L, payload = IpcValue.inline(big))),
        )
        val (spilled, moved) = spillMessage(IpcMessage.ofDelta(delta), backend, threshold = 16)
        assertEquals(48, moved)
        val op = (spilled as IpcMessage.DeltaMessage).delta.ops.single() as DeltaOp.CellSet
        assertTrue(op.payload is IpcValue.SharedBlob)
        assertContentEquals(big, router.resolve(op.payload))
    }

    /** The `backend` discriminator is wire-omitted for the default (Shm) and round-trips for others. */
    @Test
    fun backendDiscriminatorWireRoundTrip() {
        val shm = ShmBlobRef(offset = 0L, len = 3L, generation = 1L, epoch = 0L, checksum = 42L)
        assertTrue(shm.backend.isDefault)
        assertTrue(!shm.toJson().containsKey("backend")) // omitted on the wire when default
        assertEquals(shm, ShmBlobRef.fromJson(shm.toJson()))

        val arrow = shm.copy(backend = BlobBackendKind.Arrow)
        assertEquals("arrow", arrow.toJson()["backend"].toString().trim('"'))
        assertEquals(arrow, ShmBlobRef.fromJson(arrow.toJson()))
        // Unknown backend strings fall back to the default (forward-compat).
        assertEquals(BlobBackendKind.Shm, BlobBackendKind.fromWire("quantum"))
    }
}
