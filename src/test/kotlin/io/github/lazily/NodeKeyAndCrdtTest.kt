package io.github.lazily

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Spec-compliance tests for the additive wire features added for full
 * `lazily-spec` compliance: the optional [NodeKey] `key` field
 * (`lazily-spec/protocol.md` § NodeKey) and the `CrdtSync` multi-writer plane
 * (`lazily-spec/protocol.md` § Distributed). Mirrors the lazily-rs
 * `node_key_*` / `crdt_sync_*` tests so the bindings stay byte-compatible.
 */
class NodeKeyAndCrdtTest {
    @Test
    fun `node_key validates path bounds`() {
        assertEquals("scores/alice", NodeKey.from("scores/alice").path)

        assertIs<NodeKeyError.Empty>(assertFailsWith<NodeKeyError> { NodeKey.from("") })
        assertIs<NodeKeyError.EmptySegment>(assertFailsWith<NodeKeyError> { NodeKey.from("a//b") })
        assertIs<NodeKeyError.EmptySegment>(assertFailsWith<NodeKeyError> { NodeKey.from("/leading") })
        assertIs<NodeKeyError.EmptySegment>(assertFailsWith<NodeKeyError> { NodeKey.from("trailing/") })

        val tooMany = (1..(NODE_KEY_MAX_SEGMENTS + 1)).joinToString("/") { "s" }
        assertIs<NodeKeyError.TooManySegments>(assertFailsWith<NodeKeyError> { NodeKey.from(tooMany) })

        val tooLong = "x".repeat(NODE_KEY_MAX_LEN + 1)
        assertIs<NodeKeyError.TooLong>(assertFailsWith<NodeKeyError> { NodeKey.from(tooLong) })
    }

    @Test
    fun `node_key segments round trip`() {
        val key = NodeKey.fromSegments(listOf("outer", "k1", "inner", "k2"))
        assertEquals("outer/k1/inner/k2", key.path)
        assertEquals(listOf("outer", "k1", "inner", "k2"), key.segments())
    }

    @Test
    fun `node_key rejects invalid input on the wire`() {
        val bad = """{"node":1,"type_tag":"i32","state":{"Payload":[1]},"key":"a//b"}"""
        assertFailsWith<NodeKeyError> { NodeSnapshot.fromJson(Json.parseToJsonElement(bad)) }
    }

    @Test
    fun `keyed node snapshot round trips through json`() {
        val key = NodeKey.from("scores/alice")
        val snapshot = Snapshot(
            epoch = 1,
            nodes = listOf(NodeSnapshot.payload(1, "i32", byteArrayOf(1)).withKey(key)),
            edges = emptyList(),
            roots = listOf(1),
        )
        val message = IpcMessage.ofSnapshot(snapshot)
        val json = message.encodeJson().decodeToString()

        assertTrue(json.contains("scores/alice"), "keyed snapshot must carry the path: $json")
        assertEquals(message, IpcMessage.decodeJson(json))
        assertEquals(
            key,
            (IpcMessage.decodeJson(json) as IpcMessage.SnapshotMessage)
                .snapshot.nodes.first().key,
        )
    }

    @Test
    fun `unkeyed node omits key in json`() {
        // Cross-language guarantee: a null key is omitted from JSON, so pre-key
        // decoders and existing conformance fixtures round-trip unchanged.
        val snapshot = Snapshot(
            epoch = 1,
            nodes = listOf(NodeSnapshot.payload(1, "i32", byteArrayOf(1))),
            edges = emptyList(),
            roots = listOf(1),
        )
        val snapshotJson = IpcMessage.ofSnapshot(snapshot).encodeJson().decodeToString()
        assertTrue(
            !snapshotJson.contains("\"key\""),
            "unkeyed node must omit the key field in JSON: $snapshotJson",
        )

        // A keyless NodeAdd in a delta omits its key too.
        val delta = Delta.next(
            1,
            listOf(DeltaOp.NodeAdd(node = 2, typeTag = "i32", state = NodeState.Payload(byteArrayOf(2)))),
        )
        val deltaJson = IpcMessage.ofDelta(delta).encodeJson().decodeToString()
        assertTrue(
            !deltaJson.contains("\"key\""),
            "unkeyed NodeAdd must omit the key field in JSON: $deltaJson",
        )
    }

    @Test
    fun `keyed node add round trips through json`() {
        val key = NodeKey.from("sheet/A1")
        val delta = Delta.next(
            1,
            listOf(
                DeltaOp.nodeAdd(2, "i32", NodeState.Payload(byteArrayOf(2)), key),
            ),
        )
        val message = IpcMessage.ofDelta(delta)
        val json = message.encodeJson().decodeToString()
        assertTrue(json.contains("sheet/A1"), "keyed NodeAdd must carry the path: $json")

        val back = IpcMessage.decodeJson(json) as IpcMessage.DeltaMessage
        val op = back.delta.ops.first() as DeltaOp.NodeAdd
        assertEquals(key, op.key)
        assertEquals(message, IpcMessage.decodeJson(json))
    }

    @Test
    fun `crdt sync round trips through json`() {
        val stamp1 = WireStamp(wallTime = 200, logical = 0, peer = 1)
        val stamp2 = WireStamp(wallTime = 180, logical = 3, peer = 2)
        val sync = CrdtSync(
            frontier = listOf(1L to stamp1, 2L to stamp2),
            ops = listOf(
                CrdtOp(node = 1, key = null, stamp = stamp1, state = IpcValue.Inline(byteArrayOf(10, 20))),
                CrdtOp(
                    node = 2,
                    key = NodeKey.from("scores/alice"),
                    stamp = stamp2,
                    state = IpcValue.Inline(byteArrayOf(30)),
                ),
            ),
        )
        val message = IpcMessage.ofCrdtSync(sync)
        val json = message.encodeJson().decodeToString()

        // frontier tuple encodes as [peer, {wall_time,logical,peer}] and a null
        // CrdtOp key serializes as `null` (mirroring lazily-rs).
        assertTrue(json.contains("[1,{\"wall_time\":200"), "frontier tuple shape: $json")
        assertTrue(json.contains("\"key\":null"), "null CrdtOp key must serialize as null: $json")

        val back = IpcMessage.decodeJson(json)
        assertIs<IpcMessage.CrdtSyncMessage>(back)
        assertEquals(message, back, "CrdtSync must round-trip through JSON")
        assertEquals(sync, back.sync)
    }

    @Test
    fun `crdt sync accepts an absent crdt_op key`() {
        val json = """{"CrdtSync":{"frontier":[[1,{"wall_time":5,"logical":0,"peer":1}]],"ops":[{"node":1,"stamp":{"wall_time":5,"logical":0,"peer":1},"state":{"Inline":[1]}}]}}"""
        val back = IpcMessage.decodeJson(json) as IpcMessage.CrdtSyncMessage
        assertEquals(null, back.sync.ops.first().key, "absent key must decode to null")
    }

    @Test
    fun `crdt sync filter omits non readable ops but keeps frontier`() {
        val frontier = listOf(
            1L to WireStamp(wallTime = 200, logical = 0, peer = 1),
            2L to WireStamp(wallTime = 200, logical = 0, peer = 2),
        )
        val sync = CrdtSync(
            frontier = frontier,
            ops = listOf(1L, 2L, 3L).map { n ->
                CrdtOp(
                    node = n,
                    key = null,
                    stamp = WireStamp(wallTime = n, logical = 0, peer = 1),
                    state = IpcValue.Inline(byteArrayOf(n.toByte())),
                )
            },
        )
        val permissions = PeerPermissions()
        permissions.allowMany(peer = 7, kind = OpKind.Read, nodes = listOf(1L, 3L))

        val filtered = sync.filterReadable(permissions, peer = 7)

        // Node 2's op is dropped entirely (omission, not redaction); 1 and 3 stay.
        assertEquals(listOf(1L, 3L), filtered.ops.map { it.node })
        // The stamp-frontier advertisement is metadata, retained in full.
        assertEquals(frontier, filtered.frontier)
    }

    @Test
    fun `wire json is byte compatible with lazily-rs`() {
        // Canonical strings captured from lazily-rs (`serde_json::to_string`),
        // the reference JSON codec. lazily-kt must emit byte-identical frames so
        // every binding round-trips the same wire interchangeably.
        val crdt = IpcMessage.ofCrdtSync(
            CrdtSync(
                frontier = listOf(
                    1L to WireStamp(200, 0, 1),
                    2L to WireStamp(180, 3, 2),
                ),
                ops = listOf(
                    CrdtOp(1, null, WireStamp(200, 0, 1), IpcValue.Inline(byteArrayOf(10, 20))),
                    CrdtOp(2, NodeKey.from("scores/alice"), WireStamp(180, 3, 2), IpcValue.Inline(byteArrayOf(30))),
                ),
            ),
        )
        assertEquals(
            """{"CrdtSync":{"frontier":[[1,{"wall_time":200,"logical":0,"peer":1}],[2,{"wall_time":180,"logical":3,"peer":2}]],""" +
                """"ops":[{"node":1,"key":null,"stamp":{"wall_time":200,"logical":0,"peer":1},"state":{"Inline":[10,20]}},""" +
                """{"node":2,"key":"scores/alice","stamp":{"wall_time":180,"logical":3,"peer":2},"state":{"Inline":[30]}}]}}""",
            crdt.toJson().toString(),
        )

        val unkeyed = IpcMessage.ofSnapshot(
            Snapshot(1, listOf(NodeSnapshot.payload(1, "i32", byteArrayOf(1))), emptyList(), listOf(1)),
        )
        assertEquals(
            """{"Snapshot":{"epoch":1,"nodes":[{"node":1,"type_tag":"i32","state":{"Payload":[1]}}],"edges":[],"roots":[1]}}""",
            unkeyed.toJson().toString(),
        )

        val keyed = IpcMessage.ofSnapshot(
            Snapshot(
                1,
                listOf(NodeSnapshot.payload(1, "i32", byteArrayOf(1)).withKey(NodeKey.from("scores/alice"))),
                emptyList(),
                listOf(1),
            ),
        )
        assertEquals(
            """{"Snapshot":{"epoch":1,"nodes":[{"node":1,"type_tag":"i32","state":{"Payload":[1]},"key":"scores/alice"}],"edges":[],"roots":[1]}}""",
            keyed.toJson().toString(),
        )
    }
}
