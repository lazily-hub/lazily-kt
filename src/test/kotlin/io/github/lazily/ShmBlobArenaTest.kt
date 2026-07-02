package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
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
        val specPath = Path.of("../lazily-spec/conformance/arena_blob.json")
        val text = if (Files.exists(specPath)) {
            Files.readString(specPath)
        } else {
            val resource = javaClass.getResource("/conformance/arena_blob.json")
                ?: error("missing arena_blob fixture")
            resource.readText()
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun bytesOf(element: JsonObject, key: String): ByteArray =
        element.getValue(key).jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()

    @Test
    fun `conformance arena blob descriptor header and round trip`() {
        val fixture = loadArenaFixture()
        assertEquals("Arena", fixture.getValue("kind").jsonPrimitive.content)
        val input = fixture.getValue("input").jsonObject
        val expected = fixture.getValue("expected").jsonObject
        val assertions = fixture.getValue("assertions").jsonObject

        val capacity = input.getValue("capacity").jsonPrimitive.int
        val epoch = input.getValue("epoch").jsonPrimitive.long
        val payload = bytesOf(input, "payload")

        val arena = ShmBlobArena(capacity)
        val descriptor = arena.writeBlob(epoch, payload)

        // Descriptor fields.
        val expectedDescriptor = expected.getValue("descriptor").jsonObject
        assertEquals(expectedDescriptor.getValue("offset").jsonPrimitive.long, descriptor.offset)
        assertEquals(expectedDescriptor.getValue("len").jsonPrimitive.long, descriptor.len)
        assertEquals(expectedDescriptor.getValue("generation").jsonPrimitive.long, descriptor.generation)
        assertEquals(expectedDescriptor.getValue("epoch").jsonPrimitive.long, descriptor.epoch)
        // Checksum is a u64 on the wire; compare in unsigned space.
        assertEquals(
            expectedDescriptor.getValue("checksum").jsonPrimitive.content.toULong(),
            descriptor.checksum.toULong(),
        )

        // Assertion metadata mirrors the descriptor + header layout.
        assertEquals(assertions.getValue("capacity").jsonPrimitive.int, arena.capacity())
        assertEquals(assertions.getValue("epoch").jsonPrimitive.long, descriptor.epoch)
        assertEquals(assertions.getValue("header_len").jsonPrimitive.int, SHM_BLOB_HEADER_LEN)
        assertEquals("LZSH", assertions.getValue("magic").jsonPrimitive.content)
        assertEquals(payload.size, assertions.getValue("payload_len").jsonPrimitive.int)

        // 40-byte LZSH header byte-identical across bindings.
        val expectedHeader = bytesOf(expected, "header_bytes")
        val actualHeader = arena.bytes().copyOfRange(0, SHM_BLOB_HEADER_LEN)
        assertEquals(expectedHeader.toList(), actualHeader.toList())

        // Payload region immediately follows the header.
        val expectedRegion = bytesOf(expected, "payload_region")
        val actualRegion = arena.bytes()
            .copyOfRange(SHM_BLOB_HEADER_LEN, SHM_BLOB_HEADER_LEN + payload.size)
        assertEquals(expectedRegion.toList(), actualRegion.toList())

        // Round-trip read validates the header + checksum and returns the payload.
        val readBack = arena.readBlob(descriptor)
        assertEquals(payload.toList(), readBack.toList())
    }

    @Test
    fun `arena checksum is byte compatible with lazily rs fnv1a64`() {
        // Independent recomputation of FNV-1a-64 over the fixture payload.
        val fixture = loadArenaFixture()
        val payload = bytesOf(fixture.getValue("input").jsonObject, "payload")
        val expected = fixture.getValue("expected").jsonObject
            .getValue("descriptor").jsonObject.getValue("checksum").jsonPrimitive.content.toULong()

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
