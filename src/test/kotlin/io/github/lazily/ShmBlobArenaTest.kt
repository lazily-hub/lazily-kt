package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Replays `lazily-spec/conformance/arena_blob.json` — the canonical host
 * contract pinned across lazily-rs / lazily-py / lazily-zig / lazily-kt. The
 * arena is **not a wire type**, so this fixture carries `input`/`expected`
 * instead of `wire`; we write one blob and assert the descriptor, the 40-byte
 * LZSH header, the payload region, and the round-trip read are byte-identical.
 */
class ShmBlobArenaTest {
    private val json = Json

    private fun loadArenaFixture(): JsonObject {
        val text = ConformanceFixtures.read("arena_blob.json")
        return json.parseToJsonElement(text).jsonObject
    }

    /** Decode a JSON byte array handed in by a tracker, without re-reading the block. */
    private fun byteList(element: JsonElement): List<Byte> =
        element.jsonArray.map { it.jsonPrimitive.int.toByte() }

    private fun bytesOf(
        element: JsonObject,
        key: String,
    ): ByteArray =
        element
            .getValue(key)
            .jsonArray
            .map { it.jsonPrimitive.int.toByte() }
            .toByteArray()

    @Test
    fun `conformance arena blob descriptor header and round trip`() {
        val fixture = loadArenaFixture()
        assertEquals("Arena", fixture.getValue("kind").jsonPrimitive.content)
        val input = fixture.getValue("input").jsonObject
        val expected = fixture.getValue("expected").jsonObject

        val capacity = input.getValue("capacity").jsonPrimitive.int
        val epoch = input.getValue("epoch").jsonPrimitive.long
        val payload = bytesOf(input, "payload")

        val arena = ShmBlobArena(capacity)
        val descriptor = arena.writeBlob(epoch, payload)

        // Rung 0 (#lzktbindpending). `expected` is a second assertion-bearing
        // block in this fixture and nothing bound it, so the rungs above were
        // silent over it while its `assertions` sibling one screen down was fully
        // guarded. Its three keys are read here through a tracker, and its
        // object-valued `descriptor` DESCENDS rather than being indexed, so a
        // sixth sub-field added upstream is an unconsumed key instead of a field
        // compared by nothing (#lzsubblockkeyset).
        //
        // The block is consumed in two stages because `header_bytes` and
        // `payload_region` can only be compared after the arena has been written
        // and read back, so the tracker is held open across the round trip and
        // finished at the end.
        val expectedKeys = AssertionKeys("arena_blob.json expected", expected)
        val expectedDescriptor = expected.getValue("descriptor").jsonObject
        expectedKeys.sub("descriptor") { d ->
            d.assertLong("offset") { descriptor.offset }
            d.assertLong("len") { descriptor.len }
            d.assertLong("generation") { descriptor.generation }
            d.assertLong("epoch") { descriptor.epoch }
            // u64 on the wire, wider than Long — compare in unsigned space.
            d.assertKeyWith("checksum") { want ->
                assertEquals(
                    want.jsonPrimitive.content.toULong(),
                    descriptor.checksum.toULong(),
                    "expected.descriptor.checksum",
                )
            }
        }

        // Assertion metadata mirrors the descriptor + header layout.
        val assertions = fixture.getValue("assertions").jsonObject
        assertions.consuming("arena_blob.json assertions") { a ->
            a.assertInt("capacity") { arena.capacity() }
            a.assertLong("epoch") { descriptor.epoch }
            a.assertInt("header_len") { SHM_BLOB_HEADER_LEN }
            // `assertEquals("LZSH", it)` compared the fixture against a literal in
            // the runner, so the arena's own header bytes never entered the
            // comparison and a binding that wrote a different magic passed
            // (#lzconsumednotasserted). The magic occupies the first 4 bytes of
            // the 40-byte header as a little-endian u32, so the on-disk order is
            // `H S Z L` and the spelled name is its big-endian rendering.
            a.assertString("magic") {
                String(arena.bytes().copyOfRange(0, 4).reversedArray(), Charsets.US_ASCII)
            }
            a.assertInt("payload_len") { payload.size }
            // The `assertions` block carries its OWN copy of the descriptor. Only
            // `expected.descriptor` was ever read, so this one could disagree with
            // it — and with the binding — and nothing would notice
            // (#lzassertunknownkeys).
            //
            // DESCEND rather than assertKeyWith (#lzsubblockkeyset). The old arm
            // compared five named sub-fields and stopped, so a sixth field added
            // to the fixture's descriptor upstream was compared by nothing while
            // every scalar sibling of `descriptor` stayed guarded. The child
            // tracker owns the unread/unasserted checks for everything beneath,
            // so a planted sub-key fails exactly the way a planted top-level key
            // does.
            a.sub("descriptor") { d ->
                d.assertLong("offset") { descriptor.offset }
                d.assertLong("len") { descriptor.len }
                d.assertLong("generation") { descriptor.generation }
                d.assertLong("epoch") { descriptor.epoch }
                // u64 on the wire, wider than Long — compare in unsigned space.
                d.assertKeyWith("checksum") { want ->
                    assertEquals(
                        want.jsonPrimitive.content.toULong(),
                        descriptor.checksum.toULong(),
                        "assertions.descriptor.checksum",
                    )
                }
            }
            // The two copies must also agree with each OTHER: a fixture that
            // contradicts itself is a corpus bug no per-field comparison against
            // the binding can see.
            assertEquals(
                expectedDescriptor,
                assertions.getValue("descriptor").jsonObject,
                "assertions.descriptor must agree with expected.descriptor",
            )
        }

        // 40-byte LZSH header byte-identical across bindings.
        //
        // The callback compares the value the TRACKER hands in, never a second
        // read of the same key off `expected`. A callback that ignores its
        // argument and re-fetches is the defect `assertKeyWith` exists to name —
        // it looks like an assertion on the fixture's value while the path the
        // tracker booked and the path the comparison used can drift apart — and
        // lazily-spec's cross-binding ordering guard fails it by name.
        expectedKeys.assertKeyWith("header_bytes") { want ->
            assertEquals(
                byteList(want),
                arena.bytes().copyOfRange(0, SHM_BLOB_HEADER_LEN).toList(),
                "expected.header_bytes",
            )
        }

        // Payload region immediately follows the header.
        expectedKeys.assertKeyWith("payload_region") { want ->
            assertEquals(
                byteList(want),
                arena
                    .bytes()
                    .copyOfRange(SHM_BLOB_HEADER_LEN, SHM_BLOB_HEADER_LEN + payload.size)
                    .toList(),
                "expected.payload_region",
            )
        }
        expectedKeys.requireAllSatisfied()

        // Round-trip read validates the header + checksum and returns the payload.
        val readBack = arena.readBlob(descriptor)
        assertEquals(payload.toList(), readBack.toList())
    }

    @Test
    fun `arena checksum is byte compatible with lazily rs fnv1a64`() {
        // Independent recomputation of FNV-1a-64 over the fixture payload.
        val fixture = loadArenaFixture()
        val payload = bytesOf(fixture.getValue("input").jsonObject, "payload")
        val expected =
            fixture
                .getValue("expected")
                .jsonObject
                .getValue("descriptor")
                .jsonObject
                .getValue("checksum")
                .jsonPrimitive.content
                .toULong()

        var hash = 0xcbf29ce484222325uL
        val prime = 0x00000100000001b3uL
        for (b in payload) {
            hash = (hash xor (b.toInt() and 0xff).toULong()) * prime
        }
        assertEquals(expected, hash)
        assertEquals(expected, checksum(payload))
    }

    @Test
    fun `arena rejects oversized payloads`() {
        val arena = ShmBlobArena(SHM_BLOB_HEADER_LEN + 4)
        assertFailsWith<ShmBlobArenaError.BlobTooLarge> {
            arena.writeBlob(epoch = 1, payload = ByteArray(5))
        }
    }

    @Test
    fun `arena detects checksum tampering on read`() {
        val arena = ShmBlobArena(128)
        val descriptor = arena.writeBlob(epoch = 1, payload = byteArrayOf(1, 2, 3, 4))
        arena.bytes()[SHM_BLOB_HEADER_LEN] = (arena.bytes()[SHM_BLOB_HEADER_LEN].toInt() xor 0xff).toByte()
        assertFailsWith<ShmBlobArenaError.ChecksumMismatch> { arena.readBlob(descriptor) }
    }

    @Test
    fun `arena detects descriptor offset mismatch on read`() {
        val arena = ShmBlobArena(128)
        val descriptor = arena.writeBlob(epoch = 1, payload = byteArrayOf(1, 2, 3, 4))
        val bad = descriptor.copy(offset = descriptor.offset + 1)
        assertFailsWith<ShmBlobArenaError> { arena.readBlob(bad) }
    }

    @Test
    fun `arena minimum capacity is header plus one byte`() {
        assertFailsWith<ShmBlobArenaError.CapacityTooSmall> { ShmBlobArena(SHM_BLOB_HEADER_LEN) }
        assertIs<ShmBlobArena>(ShmBlobArena(SHM_BLOB_HEADER_LEN + 1))
    }
}
