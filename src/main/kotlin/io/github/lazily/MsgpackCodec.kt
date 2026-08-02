package io.github.lazily

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream

/**
 * lazily IPC wire codec — `msgpack`, the CROSS-LANGUAGE BINARY DEFAULT
 * (`#lzmsgpackseven`).
 *
 * protocol.md § Frame codecs makes `msgpack` MUST-level for every binding, and
 * spells out that shipping *a* MessagePack codec is not implementing it: the
 * codec token names ONE wire — the externally tagged frame (`{"Snapshot": …}`)
 * over named-field maps whose keys are the `json` field names, with the same
 * omit-when-absent rule for optional fields. A private framing that happens to
 * use MessagePack (an internally tagged `{"type": 0, "value": …}` envelope, or a
 * positional array per struct) decodes nothing a conforming peer sends and
 * produces nothing a conforming peer can read, whatever it calls itself.
 *
 * It is built ON the `json` codec's value tree rather than beside it, and that
 * is the point: the two codecs differ only in how a value tree is serialized,
 * never in the SHAPE of that tree. Deriving the msgpack frame from
 * [IpcMessage.toJson] makes the external tags, the field names, and both
 * `NodeKey` rules (`NodeSnapshot`/`NodeAdd` omit an absent key,
 * [CrdtOp] always writes it — `null` when unset) identical by construction. A
 * second hand-written transcription of the same shape is exactly the drift that
 * produces a binding whose two codecs disagree about the wire.
 *
 * Byte payloads are ARRAYS OF INTEGERS, not MessagePack `bin`. That is what the
 * reference encoder produces (`rmp_serde` serializes `Vec<u8>` through serde's
 * default seq impl) and what its decoder accepts, so emitting or accepting `bin`
 * would put lazily-kt outside the wire it claims to speak — [Unpacker] rejects
 * `bin` outright rather than quietly widening the dialect.
 *
 * NOT byte-canonical (§ Frame codecs): a MessagePack map's key order is
 * encoder-defined, so conformance is `decode(encode(m)) == m` plus a decode of a
 * peer's frame, never a golden byte string. This encoder happens to be
 * deterministic (it walks a [JsonObject], which preserves insertion order) —
 * allowed, but not a property any peer may rely on.
 *
 * No dependency is added for this. The subset of MessagePack an `IpcMessage`
 * needs is nil / bool / int / str / array / map; a library would bring its own
 * struct-mapping opinions, and every one of those opinions is a chance to fight
 * the external-tag, named-field, and omit-when-absent rules the token actually
 * names.
 */
object MsgpackCodec {
    /** Serialize a `json`-codec value tree as a MessagePack frame. */
    fun pack(value: JsonElement): ByteArray {
        val out = ByteArrayOutputStream()
        Packer(out).writeValue(value)
        return out.toByteArray()
    }

    /**
     * Schema-less view of a frame's bytes, as the same value tree the `json`
     * codec uses.
     *
     * The named-field rule is a property of the ENCODING, so it is invisible to
     * any assertion over a decoded [IpcMessage]: a positional encoder
     * round-trips every value correctly and is still non-conforming.
     * Conformance runners introspect through this.
     */
    fun unpack(bytes: ByteArray): JsonElement {
        val unpacker = Unpacker(bytes)
        val value = unpacker.readValue()
        if (!unpacker.eof()) fail("trailing bytes after frame")
        return value
    }

    internal fun fail(what: String): Nothing = throw MsgpackCodecException(what)
}

/** Every failure of the `msgpack` frame codec, encode or decode. */
class MsgpackCodecException(
    what: String,
) : RuntimeException("msgpack codec: $what")

/** Encode this frame as `msgpack` — the negotiated cross-language binary default. */
fun IpcMessage.encodeMsgpack(): ByteArray = MsgpackCodec.pack(toJson())

/** Decode a `msgpack` frame into an [IpcMessage]. */
fun IpcMessage.Companion.decodeMsgpack(bytes: ByteArray): IpcMessage = IpcMessage.fromJson(MsgpackCodec.unpack(bytes))

// -- generic value tree -> MessagePack ----------------------------------------

private class Packer(
    private val out: ByteArrayOutputStream,
) {
    fun writeValue(value: JsonElement) {
        when (value) {
            is JsonNull -> byte(0xc0)
            is JsonPrimitive -> primitive(value)
            is JsonArray -> {
                arrayHeader(value.size)
                for (element in value) writeValue(element)
            }
            is JsonObject -> {
                mapHeader(value.size)
                for ((name, element) in value) {
                    str(name)
                    writeValue(element)
                }
            }
        }
    }

    private fun primitive(value: JsonPrimitive) {
        if (value.isString) return str(value.content)
        value.booleanOrNull?.let { return bool(it) }
        // No `IpcMessage` field is floating point (§ IpcMessage: every field is
        // an integer, string, or byte sequence). Refusing here keeps a future
        // double-valued field from silently acquiring a wire form nothing
        // agreed on, rather than encoding one this packer cannot read back.
        val number = value.longOrNull ?: MsgpackCodec.fail("frames carry no floating-point fields: ${value.content}")
        int(number)
    }

    private fun bool(value: Boolean) = byte(if (value) 0xc3 else 0xc2)

    private fun int(value: Long) {
        when {
            value >= 0L && value <= 0x7fL -> byte(value.toInt())
            value >= -32L && value < 0L -> byte(value.toInt() and 0xff)
            value >= 0L && value <= 0xffL -> {
                byte(0xcc)
                raw(value, 1)
            }
            value >= 0L && value <= 0xffffL -> {
                byte(0xcd)
                raw(value, 2)
            }
            value >= 0L && value <= 0xffffffffL -> {
                byte(0xce)
                raw(value, 4)
            }
            value >= 0L -> {
                byte(0xcf)
                raw(value, 8)
            }
            value >= Byte.MIN_VALUE.toLong() -> {
                byte(0xd0)
                raw(value, 1)
            }
            value >= Short.MIN_VALUE.toLong() -> {
                byte(0xd1)
                raw(value, 2)
            }
            value >= Int.MIN_VALUE.toLong() -> {
                byte(0xd2)
                raw(value, 4)
            }
            else -> {
                byte(0xd3)
                raw(value, 8)
            }
        }
    }

    private fun str(value: String) {
        val bytes = value.encodeToByteArray()
        when {
            bytes.size < 32 -> byte(0xa0 or bytes.size)
            bytes.size <= 0xff -> {
                byte(0xd9)
                raw(bytes.size.toLong(), 1)
            }
            bytes.size <= 0xffff -> {
                byte(0xda)
                raw(bytes.size.toLong(), 2)
            }
            else -> {
                byte(0xdb)
                raw(bytes.size.toLong(), 4)
            }
        }
        out.write(bytes)
    }

    private fun arrayHeader(count: Int) {
        when {
            count < 16 -> byte(0x90 or count)
            count <= 0xffff -> {
                byte(0xdc)
                raw(count.toLong(), 2)
            }
            else -> {
                byte(0xdd)
                raw(count.toLong(), 4)
            }
        }
    }

    private fun mapHeader(count: Int) {
        when {
            count < 16 -> byte(0x80 or count)
            count <= 0xffff -> {
                byte(0xde)
                raw(count.toLong(), 2)
            }
            else -> {
                byte(0xdf)
                raw(count.toLong(), 4)
            }
        }
    }

    private fun byte(value: Int) = out.write(value and 0xff)

    /** Big-endian [width]-byte tail of [value]. */
    private fun raw(
        value: Long,
        width: Int,
    ) {
        for (shift in (width - 1) downTo 0) {
            out.write(((value ushr (shift * 8)) and 0xffL).toInt())
        }
    }
}

// -- MessagePack -> generic value tree ----------------------------------------

private class Unpacker(
    private val buf: ByteArray,
) {
    private var pos = 0

    fun eof(): Boolean = pos >= buf.size

    fun readValue(): JsonElement {
        val tag = u8()
        return when {
            tag <= 0x7f -> JsonPrimitive(tag.toLong())
            tag >= 0xe0 -> JsonPrimitive(tag.toByte().toLong())
            tag in 0x80..0x8f -> map(tag and 0x0f)
            tag in 0x90..0x9f -> array(tag and 0x0f)
            tag in 0xa0..0xbf -> JsonPrimitive(str(tag and 0x1f))
            tag == 0xc0 -> JsonNull
            tag == 0xc2 -> JsonPrimitive(false)
            tag == 0xc3 -> JsonPrimitive(true)
            // A byte payload arrives as an array of integers on this wire. The
            // reference decoder rejects `bin` in the same position, so accepting
            // it here would make lazily-kt read frames no conforming peer can
            // produce and no conforming peer can read — a private extension
            // wearing the `msgpack` token.
            tag in 0xc4..0xc6 ->
                MsgpackCodec.fail(
                    "byte payloads are arrays of integers on this wire, not msgpack `bin`",
                )
            tag == 0xcc -> JsonPrimitive(uint(1))
            tag == 0xcd -> JsonPrimitive(uint(2))
            tag == 0xce -> JsonPrimitive(uint(4))
            tag == 0xcf -> JsonPrimitive(unsigned64())
            tag == 0xd0 -> JsonPrimitive(uint(1).toByte().toLong())
            tag == 0xd1 -> JsonPrimitive(uint(2).toShort().toLong())
            tag == 0xd2 -> JsonPrimitive(uint(4).toInt().toLong())
            tag == 0xd3 -> JsonPrimitive(uint(8))
            tag == 0xd9 -> JsonPrimitive(str(uint(1).toInt()))
            tag == 0xda -> JsonPrimitive(str(uint(2).toInt()))
            tag == 0xdb -> JsonPrimitive(str(uint(4).toInt()))
            tag == 0xdc -> array(uint(2).toInt())
            tag == 0xdd -> array(uint(4).toInt())
            tag == 0xde -> map(uint(2).toInt())
            tag == 0xdf -> map(uint(4).toInt())
            else -> MsgpackCodec.fail("unsupported MessagePack value in frame: 0x${tag.toString(16)}")
        }
    }

    private fun array(count: Int): JsonArray {
        val out = ArrayList<JsonElement>(count)
        repeat(count) { out.add(readValue()) }
        return JsonArray(out)
    }

    private fun map(count: Int): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(count.coerceAtLeast(1))
        repeat(count) {
            val key = readValue()
            if (key !is JsonPrimitive || !key.isString) {
                MsgpackCodec.fail("named-field maps require string keys, got $key")
            }
            out[key.content] = readValue()
        }
        return JsonObject(out)
    }

    private fun u8(): Int {
        if (pos >= buf.size) MsgpackCodec.fail("frame truncated at byte $pos")
        return buf[pos++].toInt() and 0xff
    }

    private fun uint(width: Int): Long {
        var value = 0L
        repeat(width) { value = (value shl 8) or u8().toLong() }
        return value
    }

    private fun unsigned64(): Long {
        val value = uint(8)
        if (value < 0) MsgpackCodec.fail("uint64 field exceeds the signed 64-bit range this binding stores")
        return value
    }

    private fun str(len: Int): String {
        if (pos + len > buf.size) MsgpackCodec.fail("frame truncated inside a string of $len bytes")
        val text = buf.decodeToString(pos, pos + len)
        pos += len
        return text
    }
}
