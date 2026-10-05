package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The generated `delta` wire codec (`DeltaWireGen.kt`, `#lzwiremodel7`).
 *
 * Each refusal below is a frame `schemas/delta.json` rejects and the
 * hand-written decoder this file replaced accepted (unknown keys, negative or
 * string-typed ids and epochs, a string byte, the `{"Opaque": ...}` dict form, a
 * number `type_tag` or `key`). Every one is refused through
 * [IpcDecodeException.Malformed], so a caller's single catch still covers it.
 */
class DeltaWireGenTest {
    private val blob = """{"offset":8,"len":3,"generation":2,"epoch":5,"checksum":99}"""

    private fun frame(vararg ops: String): String = """{"Delta":{"base_epoch":4,"epoch":5,"ops":[${ops.joinToString(",")}]}}"""

    /** One canonical frame per DeltaOp variant, in the encoder's own key order. */
    private val canonicalOps =
        listOf(
            """{"CellSet":{"node":1,"payload":{"Inline":[0,1,255]}}}""",
            """{"SlotValue":{"node":2,"payload":{"SharedBlob":$blob}}}""",
            """{"Invalidate":{"node":3}}""",
            """{"NodeAdd":{"node":4,"type_tag":"t","state":{"Payload":[7]},"key":"scores/alice"}}""",
            """{"NodeRemove":{"node":5}}""",
            """{"EdgeAdd":{"dependent":6,"dependency":7}}""",
            """{"EdgeRemove":{"dependent":8,"dependency":9}}""",
            """{"QueuePush":{"node":10,"payload":{"Inline":[]}}}""",
            """{"QueuePop":{"node":11}}""",
            """{"QueueClose":{"node":12}}""",
        )

    private fun refused(wire: String): IpcDecodeException.Malformed =
        assertFailsWith<IpcDecodeException.Malformed>(wire) { IpcMessage.decodeJson(wire) }

    @Test
    fun `every variant round trips byte for byte through json and msgpack`() {
        val wire = frame(*canonicalOps.toTypedArray())
        val decoded = IpcMessage.decodeJson(wire)
        val ops = assertIs<IpcMessage.DeltaMessage>(decoded).delta.ops
        assertEquals(10, ops.size)
        assertEquals(wire, decoded.encodeJson().decodeToString())
        assertEquals(decoded, IpcMessage.decodeMsgpack(decoded.encodeMsgpack()))
        assertEquals(wire, IpcMessage.decodeMsgpack(decoded.encodeMsgpack()).encodeJson().decodeToString())
        // The extra NodeState shapes: the bare unit tag and a shared blob.
        val states = frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":"Opaque"}}""", """{"NodeAdd":{"node":2,"type_tag":"t","state":{"SharedBlob":$blob}}}""")
        assertEquals(states, IpcMessage.decodeJson(states).encodeJson().decodeToString())
    }

    @Test
    fun `strict decoding refuses what the schema rejects`() {
        val rejected =
            listOf(
                """{"Delta":{"base_epoch":4,"epoch":5,"ops":[],"extra":1}}""",
                """{"Delta":{"base_epoch":4,"epoch":5}}""",
                """{"Delta":{"base_epoch":4,"epoch":5,"ops":null}}""",
                """{"Delta":{"base_epoch":4,"epoch":-1,"ops":[]}}""",
                """{"Delta":{"base_epoch":4,"epoch":"5","ops":[]}}""",
                frame("""{"CellSet":{"node":1,"payload":{"Inline":[1]},"x":1}}"""),
                frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":"Opaque","extra":null}}"""),
                frame("""{"Invalidate":{}}"""),
                frame("""{"Invalidate":1}"""),
                frame("""{"Invalidate":{"node":1},"NodeRemove":{"node":1}}"""),
                frame("""{"Unknown":{"node":1}}"""),
                frame("""{"Invalidate":{"node":"1"}}"""),
                frame("""{"Invalidate":{"node":-1}}"""),
                frame("""{"Invalidate":{"node":1.0}}"""),
                frame("""{"Invalidate":{"node":1e2}}"""),
                frame("""{"Invalidate":{"node":true}}"""),
                frame("""{"Invalidate":{"node":null}}"""),
                frame("""{"CellSet":{"node":1,"payload":{"Inline":[256]}}}"""),
                frame("""{"CellSet":{"node":1,"payload":{"Inline":[-1]}}}"""),
                frame("""{"CellSet":{"node":1,"payload":{"Inline":["1"]}}}"""),
                frame("""{"CellSet":{"node":1,"payload":{"Inline":"AQI="}}}"""),
                frame("""{"CellSet":{"node":1,"payload":{"Inline":[1],"SharedBlob":$blob}}}"""),
                frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":{"Opaque":null}}}"""),
                frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":{"Opaque":{}}}}"""),
                frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":"Payload"}}"""),
                frame("""{"NodeAdd":{"node":1,"type_tag":5,"state":"Opaque"}}"""),
                frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":"Opaque","key":5}}"""),
            )
        for (wire in rejected) refused(wire)
        assertEquals("Delta: unknown field \"extra\"", refused(rejected[0]).message)
    }

    @Test
    fun `a u64 past the signed range is refused, never wrapped`() {
        val max = Long.MAX_VALUE
        val atMax = frame("""{"Invalidate":{"node":$max}}""")
        assertEquals(atMax, IpcMessage.decodeJson(atMax).encodeJson().decodeToString())
        val past = "9223372036854775808"
        val error = refused(frame("""{"Invalidate":{"node":$past}}"""))
        assertEquals("DeltaOp.Invalidate.node: $past exceeds this binding's range 0..=2^63-1", error.message)
        refused("""{"Delta":{"base_epoch":$past,"epoch":5,"ops":[]}}""")
        refused(frame("""{"EdgeAdd":{"dependent":1,"dependency":18446744073709551615}}"""))
    }

    @Test
    fun `an optional key is absent when omitted or null, and the encoder omits it`() {
        for (key in listOf("", ""","key":null""")) {
            val wire = frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":"Opaque"$key}}""")
            val delta = assertIs<IpcMessage.DeltaMessage>(IpcMessage.decodeJson(wire)).delta
            assertNull(assertIs<DeltaOp.NodeAdd>(delta.ops.single()).key)
            assertEquals(frame("""{"NodeAdd":{"node":1,"type_tag":"t","state":"Opaque"}}"""), IpcMessage.ofDelta(delta).encodeJson().decodeToString())
        }
    }

    @Test
    fun `a null blob backend still reads as shm`() {
        val wire = frame("""{"CellSet":{"node":1,"payload":{"SharedBlob":{"offset":8,"len":3,"generation":2,"epoch":5,"checksum":99,"backend":null}}}}""")
        val op = assertIs<IpcMessage.DeltaMessage>(IpcMessage.decodeJson(wire)).delta.ops.single()
        val payload = assertIs<IpcValue.SharedBlob>(assertIs<DeltaOp.CellSet>(op).payload)
        assertEquals(BlobBackendKind.Shm, payload.blob.backend)
        assertEquals(frame("""{"CellSet":{"node":1,"payload":{"SharedBlob":$blob}}}"""), IpcMessage.decodeJson(wire).encodeJson().decodeToString())
    }

    @Test
    fun `a negative id or epoch is refused on encode`() {
        assertFailsWith<IllegalArgumentException> { DeltaOp.Invalidate(-1).toJson() }
        assertFailsWith<IllegalArgumentException> { DeltaOp.EdgeAdd(1, -2).toJson() }
        assertFailsWith<IllegalArgumentException> { Delta(-1, 0).toJson() }
        assertFailsWith<IllegalArgumentException> { IpcMessage.ofDelta(Delta.next(0, listOf(DeltaOp.NodeRemove(-5)))).encodeJson() }
    }

    @Test
    fun `hand-written constructors and helpers keep their call syntax`() {
        val bytes = byteArrayOf(1, 2)
        assertEquals(DeltaOp.CellSet(1, IpcValue.Inline(byteArrayOf(1, 2))), DeltaOp.CellSet(1, bytes))
        assertEquals(DeltaOp.SlotValue(1, IpcValue.inline(bytes)), DeltaOp.slotValue(1, bytes))
        assertEquals(DeltaOp.QueuePush(1, bytes), DeltaOp.queuePush(1, IpcValue.Inline(bytes)))
        assertContentEquals(bytes, IpcValue.Inline(bytes).toByteArray())
        assertEquals(NodeState.Payload(byteArrayOf(1, 2)).hashCode(), NodeState.Payload(bytes).hashCode())
        val delta = Delta.next(3, listOf(DeltaOp.invalidate(1), DeltaOp.edgeAdd(1, 2)))
        assertEquals(DeltaApplyStatus.Apply, delta.applyStatus(3))
        assertEquals(1, delta.span())
        val permissions = PeerPermissions()
        permissions.allow(9, RemoteOp.read(1))
        assertEquals(listOf<DeltaOp>(DeltaOp.Invalidate(1)), delta.filterReadable(permissions, 9).ops)
    }
}
