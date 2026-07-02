package io.github.lazily

/** Length (in bytes) of the LZSH header that precedes every arena blob. */
const val SHM_BLOB_HEADER_LEN: Int = 40

private const val SHM_BLOB_MAGIC: UInt = 0x4c5a5348u // "LZSH" as a little-endian u32 on disk
private const val SHM_BLOB_VERSION: UInt = 1u
private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
private const val FNV_PRIME: ULong = 0x00000100000001b3uL

/** Why an [ShmBlobArena] operation failed. Mirrors lazily-rs `ShmBlobArenaError`. */
sealed class ShmBlobArenaError(message: String) : RuntimeException(message) {
    data class CapacityTooSmall(val capacity: Int, val minCapacity: Int) :
        ShmBlobArenaError("arena capacity $capacity < minimum $minCapacity")
    data class BlobTooLarge(val len: Int, val maxLen: Int) :
        ShmBlobArenaError("payload $len bytes exceeds arena max $maxLen")
    data class DescriptorOutOfBounds(val offset: Long, val len: Long, val capacity: Int) :
        ShmBlobArenaError("descriptor offset=$offset len=$len outside arena capacity=$capacity")
    data class DescriptorMismatch(val field: String) :
        ShmBlobArenaError("arena descriptor/header mismatch: $field")
    data class ChecksumMismatch(val expected: ULong, val actual: ULong) :
        ShmBlobArenaError("arena checksum mismatch: expected 0x${expected.toString(16)}, got 0x${actual.toString(16)}")
    data object GenerationOverflow : ShmBlobArenaError("arena generation counter overflowed")
}

/**
 * In-process host for the lazily shared-memory blob plane — the Kotlin
 * counterpart of `lazily-rs::ShmBlobArena`. The host writes a fixed 40-byte
 * LZSH header (`{ magic, version, header_len, generation, epoch, len, checksum }`,
 * little-endian) before each payload and validates the header on read, so
 * [IpcMessage] control frames can carry compact [ShmBlobRef] descriptors instead
 * of embedding large bytes inline. The byte layout and the FNV-1a-64 checksum
 * are identical across lazily-rs / lazily-py / lazily-zig / lazily-kt — pinned
 * by `lazily-spec/conformance/arena_blob.json`.
 *
 * This arena is a heap-backed [ByteArray]; it is **not a wire type**. A future
 * transport may back it with a memory-mapped [java.nio.ByteBuffer] for true
 * cross-process sharing without changing this contract.
 */
class ShmBlobArena private constructor(
    private val bytes: ByteArray,
) {
    private var writeOffset: Int = 0
    private var nextGeneration: ULong = 1uL

    init {
        if (bytes.size < SHM_BLOB_HEADER_LEN + 1) {
            throw ShmBlobArenaError.CapacityTooSmall(bytes.size, SHM_BLOB_HEADER_LEN + 1)
        }
    }

    constructor(capacity: Int) : this(ByteArray(capacity))

    /** Total backing-buffer capacity in bytes. */
    fun capacity(): Int = bytes.size

    /** Maximum payload length this arena can hold in a single blob. */
    fun maxBlobLen(): Int = bytes.size - SHM_BLOB_HEADER_LEN

    /** Current write-cursor offset. */
    fun writeOffset(): Int = writeOffset

    /** The backing bytes (header + payload regions live here). */
    fun bytes(): ByteArray = bytes

    /** Write [payload] tagged with [epoch] and return its wire descriptor. */
    fun writeBlob(epoch: Long, payload: ByteArray): ShmBlobRef {
        val capacity = bytes.size
        val maxLen = maxBlobLen()
        if (payload.size > maxLen) {
            throw ShmBlobArenaError.BlobTooLarge(payload.size, maxLen)
        }

        val totalLen = SHM_BLOB_HEADER_LEN + payload.size
        if (writeOffset + totalLen > capacity) {
            writeOffset = 0
        }

        val generation = nextGeneration
        val advanced = generation + 1uL
        if (advanced == 0uL) {
            throw ShmBlobArenaError.GenerationOverflow
        }
        nextGeneration = advanced

        val offset = writeOffset
        val checksum = checksum(payload)
        val descriptor = ShmBlobRef(
            offset = offset.toLong(),
            len = payload.size.toLong(),
            generation = generation.toLong(),
            epoch = epoch,
            checksum = checksum.toLong(),
        )

        val payloadOffset = offset + SHM_BLOB_HEADER_LEN
        writeHeader(bytes, offset, descriptor)
        System.arraycopy(payload, 0, bytes, payloadOffset, payload.size)

        writeOffset += totalLen
        if (writeOffset == capacity) {
            writeOffset = 0
        }
        return descriptor
    }

    /** Read and validate a previously written blob, returning its payload bytes. */
    fun readBlob(descriptor: ShmBlobRef): ByteArray {
        val capacity = bytes.size
        val offset = descriptor.offset
        val len = descriptor.len
        if (offset < 0 || len < 0 ||
            offset.toInt() !in 0..capacity ||
            len > Int.MAX_VALUE.toLong() ||
            SHM_BLOB_HEADER_LEN.toLong() + len > (capacity - offset)
        ) {
            throw ShmBlobArenaError.DescriptorOutOfBounds(offset, len, capacity)
        }

        val header = readHeader(bytes, offset.toInt())
        if (header != descriptor) {
            throw ShmBlobArenaError.DescriptorMismatch(mismatchField(header, descriptor))
        }

        val from = offset.toInt() + SHM_BLOB_HEADER_LEN
        val n = len.toInt()
        val payload = bytes.copyOfRange(from, from + n)
        val actual = checksum(payload)
        if (actual != descriptor.checksum.toULong()) {
            throw ShmBlobArenaError.ChecksumMismatch(descriptor.checksum.toULong(), actual)
        }
        return payload
    }

    private fun mismatchField(actual: ShmBlobRef, expected: ShmBlobRef): String =
        when {
            actual.generation != expected.generation -> "generation"
            actual.epoch != expected.epoch -> "epoch"
            actual.len != expected.len -> "len"
            actual.checksum != expected.checksum -> "checksum"
            else -> "offset"
        }
}

/** FNV-1a-64 over the payload, identical to every lazily binding. */
fun checksum(payload: ByteArray): ULong {
    var hash = FNV_OFFSET_BASIS
    for (b in payload) {
        hash = (hash xor (b.toInt() and 0xff).toULong()) * FNV_PRIME
    }
    return hash
}

private fun writeHeader(bytes: ByteArray, offset: Int, descriptor: ShmBlobRef) {
    writeU32(bytes, offset, SHM_BLOB_MAGIC)
    writeU16(bytes, offset + 4, SHM_BLOB_VERSION)
    writeU16(bytes, offset + 6, SHM_BLOB_HEADER_LEN.toUInt())
    writeU64(bytes, offset + 8, descriptor.generation.toULong())
    writeU64(bytes, offset + 16, descriptor.epoch.toULong())
    writeU64(bytes, offset + 24, descriptor.len.toULong())
    writeU64(bytes, offset + 32, descriptor.checksum.toULong())
}

private fun readHeader(bytes: ByteArray, offset: Int): ShmBlobRef {
    val magic = readU32(bytes, offset)
    if (magic != SHM_BLOB_MAGIC) throw ShmBlobArenaError.DescriptorMismatch("magic")
    val version = readU16(bytes, offset + 4)
    if (version != SHM_BLOB_VERSION) throw ShmBlobArenaError.DescriptorMismatch("version")
    val headerLen = readU16(bytes, offset + 6)
    if (headerLen.toInt() != SHM_BLOB_HEADER_LEN) throw ShmBlobArenaError.DescriptorMismatch("header_len")
    return ShmBlobRef(
        offset = offset.toLong(),
        generation = readU64(bytes, offset + 8).toLong(),
        epoch = readU64(bytes, offset + 16).toLong(),
        len = readU64(bytes, offset + 24).toLong(),
        checksum = readU64(bytes, offset + 32).toLong(),
    )
}

private fun writeU16(bytes: ByteArray, offset: Int, value: UInt) {
    val v = value.toInt()
    bytes[offset] = (v and 0xff).toByte()
    bytes[offset + 1] = ((v shr 8) and 0xff).toByte()
}

private fun writeU32(bytes: ByteArray, offset: Int, value: UInt) {
    val v = value.toInt()
    bytes[offset] = (v and 0xff).toByte()
    bytes[offset + 1] = ((v shr 8) and 0xff).toByte()
    bytes[offset + 2] = ((v shr 16) and 0xff).toByte()
    bytes[offset + 3] = ((v shr 24) and 0xff).toByte()
}

private fun writeU64(bytes: ByteArray, offset: Int, value: ULong) {
    var v = value
    for (i in 0 until 8) {
        bytes[offset + i] = (v and 0xffuL).toByte()
        v = v shr 8
    }
}

private fun readU16(bytes: ByteArray, offset: Int): UInt =
    ((bytes[offset].toInt() and 0xff).toUInt()) or
        (((bytes[offset + 1].toInt() and 0xff).toUInt()) shl 8)

private fun readU32(bytes: ByteArray, offset: Int): UInt =
    ((bytes[offset].toInt() and 0xff).toUInt()) or
        (((bytes[offset + 1].toInt() and 0xff).toUInt()) shl 8) or
        (((bytes[offset + 2].toInt() and 0xff).toUInt()) shl 16) or
        (((bytes[offset + 3].toInt() and 0xff).toUInt()) shl 24)

private fun readU64(bytes: ByteArray, offset: Int): ULong {
    var v = 0uL
    for (i in 0 until 8) {
        v = v or (((bytes[offset + i].toInt() and 0xff).toULong()) shl (8 * i))
    }
    return v
}
