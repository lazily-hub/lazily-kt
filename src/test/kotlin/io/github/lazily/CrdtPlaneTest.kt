package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrdtPlaneTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("$name")
        val fixture = json.parseToJsonElement(text).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        return fixture
    }

    @Test
    fun `crdt sync frames round-trip`() {
        val fixture = loadFixture("distributed/crdt_sync_frames.json")
        val frames = fixture.getValue("frames").jsonArray
        assertTrue(frames.isNotEmpty())
        for (frameEl in frames) {
            val frame = frameEl.jsonObject
            val label = frame.getValue("label").jsonPrimitive.content
            val wire = frame.getValue("wire").jsonObject
            val message = IpcMessage.fromJson(wire)
            val sync = assertIs<IpcMessage.CrdtSyncMessage>(message).sync

            frame.getValue("assertions").jsonObject
                .consuming("distributed/crdt_sync_frames.json[$label]") { a ->
                    a.int("frontier_len")?.let {
                        assertEquals(it, sync.frontier.size, "frontier_len mismatch for $label")
                    }
                    a.boolean("frontier_omitted")?.let {
                        // An omitted frontier decodes as empty (frontier suppression).
                        assertTrue(it, "frontier_omitted must assert true for $label")
                        assertTrue(sync.frontier.isEmpty(), "frontier must decode empty for $label")
                    }
                    a.int("op_count")?.let {
                        assertEquals(it, sync.ops.size, "op_count mismatch for $label")
                    }
                    // `key` is nullable on the wire, and the two spellings decode
                    // through different branches. Both were carried by the fixture
                    // and read by nothing (#lzassertunknownkeys): a binding that
                    // dropped the key on decode round-tripped and passed.
                    a.boolean("has_keyed_op")?.let {
                        assertEquals(it, sync.ops.any { op -> op.key != null }, "has_keyed_op for $label")
                    }
                    a.boolean("has_keyless_op")?.let {
                        assertEquals(it, sync.ops.any { op -> op.key == null }, "has_keyless_op for $label")
                    }
                }
            // JSON round-trip through the externally-tagged envelope. Byte-for-byte
            // except for schema-declared-equivalent encodings (conformance.md §
            // Round-trip equivalence exemptions): `CrdtSync.frontier` omitted ≡ [].
            assertEquals(canonicalizeCrdtSyncWire(wire), message.toJson(), "wire round-trip mismatch for $label")
            assertEquals(message, IpcMessage.decodeJson(message.encodeJson()))
        }
    }

    /**
     * Fill in the declared default for `CrdtSync.frontier` so an omitted frontier
     * compares equal to the canonical empty encoding (#lzspecfrontiersuppress).
     */
    private fun canonicalizeCrdtSyncWire(wire: JsonObject): JsonObject {
        val inner = wire["CrdtSync"]?.jsonObject ?: return wire
        if ("frontier" in inner) return wire
        return buildJsonObject {
            put("CrdtSync", buildJsonObject {
                put("frontier", JsonArray(emptyList()))
                inner.forEach { (k, v) -> put(k, v) }
            })
        }
    }

    private fun parseOps(scenario: JsonObject): List<CrdtOp> =
        scenario.getValue("ops").jsonArray.map { CrdtOp.fromJson(it) }

    private fun assertConverged(runtime: CrdtPlaneRuntime, converged: JsonArray) {
        for (entryEl in converged) {
            val entry = entryEl.jsonObject
            val node = entry.getValue("node").jsonPrimitive.long
            val expectedState = IpcValue.fromJson(entry.getValue("state"))
            assertEquals(expectedState, runtime.value(node), "converged state mismatch for node $node")
        }
    }

    /** Lexicographic `(wall_time, logical, peer)` order — the plane's decisive stamp order. */
    private val stampOrder: Comparator<WireStamp> =
        compareBy<WireStamp> { it.wallTime }.thenBy { it.logical }.thenBy { it.peer }

    @Test
    fun `anti-entropy scenarios converge and are idempotent`() {
        val fixture = loadFixture("distributed/anti_entropy_converge.json")
        val scenarios = fixture.getValue("scenarios").jsonArray
        assertTrue(scenarios.isNotEmpty())

        for (scenarioEl in scenarios) {
            val scenario = scenarioEl.jsonObject
            val name = scenario.getValue("name").jsonPrimitive.content
            val ops = parseOps(scenario)

            scenario.getValue("expect").jsonObject
                .consuming("distributed/anti_entropy_converge.json[$name] expect") { a ->
                    val converged = a.array("converged")
                        ?: error("$name: `converged` is required")
                    val expectedApplied = a.int("applied_count")
                        ?: error("$name: `applied_count` is required")

                    val runtime = CrdtPlaneRuntime(peer = 99)
                    val frame = CrdtSync(frontier = emptyList(), ops = ops)
                    val applied = runtime.ingest(frame)
                    assertEquals(expectedApplied, applied, "applied_count mismatch for $name")
                    assertConverged(runtime, converged)

                    // The conflict-resolution rule the fixture names. Carried and
                    // read by nothing until #lzassertunknownkeys: every other key
                    // here is satisfied by *a* deterministic winner, so a binding
                    // resolving by arrival order rather than by stamp could pass
                    // the whole scenario on a fixture whose op order happens to
                    // agree. Stated here, the rule itself is the assertion.
                    a.string("resolution")?.let { rule ->
                        assertEquals("max_stamp", rule, "$name: unsupported resolution rule '$rule'")
                        for (entryEl in converged) {
                            val node = entryEl.jsonObject.getValue("node").jsonPrimitive.long
                            val winner = ops.filter { it.node == node }.maxWithOrNull(
                                compareBy(stampOrder) { it.stamp },
                            ) ?: error("$name: no op targets node $node")
                            assertEquals(
                                winner.state,
                                runtime.value(node),
                                "$name: resolution=max_stamp must select the greatest-stamp op for node $node",
                            )
                        }
                    }

                    // State-based CvRDT idempotence: re-delivering applies 0 new ops.
                    val reApplied = runtime.ingest(frame)
                    assertEquals(0, reApplied, "re-delivery must apply 0 ops for $name")
                    a.int("redeliver_applied_count")?.let {
                        assertEquals(it, reApplied, "redeliver_applied_count mismatch for $name")
                    }
                    assertConverged(runtime, converged)

                    // Order independence: reversed delivery converges to the same
                    // state. Always exercised; the key turns it into a claim the
                    // fixture makes rather than a habit of this runner.
                    a.boolean("order_independent")?.let {
                        assertTrue(it, "$name: order_independent=false is not a supported claim")
                    }
                    val reversed = CrdtPlaneRuntime(peer = 99)
                    val revApplied = reversed.ingest(CrdtSync(frontier = emptyList(), ops = ops.reversed()))
                    assertEquals(expectedApplied, revApplied, "reversed applied_count mismatch for $name")
                    assertConverged(reversed, converged)
                }
        }
    }

    @Test
    fun `two-replica fork and merge converge via the runtime`() {
        val codec = CrdtCodec.string
        val node: NodeId = 7
        val key = NodeKey.from("greetings/alice")

        val rtA = CrdtPlaneRuntime(peer = 1)
        val ctxA = Context()
        rtA.register(node, key, ctxA.replicatedCell("init", LwwRegister(codec), codec, rtA.clock))

        val rtB = CrdtPlaneRuntime(peer = 2)
        val ctxB = Context()
        rtB.register(node, key, ctxB.replicatedCell("init", LwwRegister(codec), codec, rtB.clock))

        // Fork: each replica edits the same cell independently.
        val opA = rtA.localUpdate<String>(node, "alpha") ?: error("A edit yields an op")
        val opB = rtB.localUpdate<String>(node, "bravo") ?: error("B edit yields an op")
        assertEquals(node, opA.node)
        assertEquals("greetings/alice", opA.key?.path)

        // Merge: mutual anti-entropy exchange.
        val bApplied = rtB.ingest(CrdtSync(rtA.wireFrontier(), listOf(opA)))
        val aApplied = rtA.ingest(CrdtSync(rtB.wireFrontier(), listOf(opB)))
        assertEquals(1, bApplied)
        assertEquals(1, aApplied)

        // Converged: both replicas agree on the LWW winner.
        val a = rtA.typedValue<String>(node)
        val b = rtB.typedValue<String>(node)
        assertEquals(a, b, "replicas must converge to the same value")
        assertTrue(a == "alpha" || a == "bravo")
        // Raw plane state agrees with the typed cell.
        assertEquals(IpcValue.Inline(codec.encode(a!!)), rtA.value(node))

        // Membership expanded to both peers after the exchange.
        assertEquals(setOf<PeerId>(1, 2), rtA.membership())
        assertEquals(setOf<PeerId>(1, 2), rtB.membership())

        // Re-ingest is idempotent.
        assertEquals(0, rtB.ingest(CrdtSync(rtA.wireFrontier(), listOf(opA))))
    }

    @Test
    fun `value-preserving local update emits no op`() {
        val codec = CrdtCodec.int
        val node: NodeId = 3
        val rt = CrdtPlaneRuntime(peer = 1)
        val ctx = Context()
        rt.register(node, null, ctx.replicatedCell(5, LwwRegister(codec), codec, rt.clock))

        // Re-writing the current value advances the stamp but changes nothing.
        val op = rt.localUpdate<Int>(node, 5)
        assertTrue(op == null, "a value-preserving write emits no op")
        assertEquals(5, rt.typedValue<Int>(node))
    }
}
