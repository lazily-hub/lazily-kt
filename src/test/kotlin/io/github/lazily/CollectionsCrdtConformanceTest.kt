package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays the canonical `lazily-spec/conformance/collections/` CRDT and semantic
 * fixtures against the native runtime — the language-agnostic conformance every
 * binding MUST validate (`lazily-spec/cell-model.md` § Move-aware sequence order,
 * § Free-text CRDT, § Memoized semantic tree, § Manufactured identity).
 *
 * These are **compute** fixtures: each loads the model, replays the `steps`, and
 * asserts the `expect` observable effects identically to every other binding.
 */
class CollectionsCrdtConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("collections/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    // -- StableId -----------------------------------------------------------

    private fun block(obj: JsonObject): Block {
        val text = obj.getValue("text").jsonPrimitive.content
        val anchor = obj["anchor"]?.jsonPrimitive?.contentOrNull
        return if (anchor != null) Block.anchored(anchor, text) else Block.text(text)
    }

    @Test
    fun `conformance stableid alignment`() {
        val fixture = loadFixture("stableid_alignment.json")
        for (scenario in fixture.getValue("scenarios").jsonArray) {
            val s = scenario.jsonObject
            val name = s.getValue("name").jsonPrimitive.content

            // Scenario 1 & 2: key equality over a single `blocks` list.
            val blocksEl = s["blocks"]
            if (blocksEl != null) {
                val blocks = blocksEl.jsonArray.map { block(it.jsonObject) }
                val keys = blocks.map { blockKey(it) }
                val expect = s.getValue("expect").jsonObject
                expect["key_equal"]?.jsonArray?.forEach { pair ->
                    val (i, j) = pair.jsonArray.map { it.jsonPrimitive.int }
                    assertEquals(keys[i], keys[j], "$name: key_equal[$i,$j]")
                }
                expect["key_not_equal"]?.jsonArray?.forEach { pair ->
                    val (i, j) = pair.jsonArray.map { it.jsonPrimitive.int }
                    assertFalse(keys[i] == keys[j], "$name: key_not_equal[$i,$j]")
                }
                continue
            }

            val oldBlocks = s["old"]?.jsonArray?.map { block(it.jsonObject) } ?: emptyList()
            val newBlocks = s["new"]?.jsonArray?.map { block(it.jsonObject) } ?: emptyList()
            val expect = s.getValue("expect").jsonObject

            // Scenario 6: assign_stable_keys flows identity through edit.
            val keyFlow = expect["new_key_equals_old_key"]
            if (keyFlow != null) {
                val oldKeys = oldBlocks.map { blockKey(it).asString() }
                val newKeys = assignStableKeys(oldBlocks, newBlocks)
                keyFlow.jsonArray.forEach { pair ->
                    val (ni, oi) = pair.jsonArray.map { it.jsonPrimitive.int }
                    assertEquals(oldKeys[oi], newKeys[ni], "$name: new[$ni] key == old[$oi] key")
                }
                continue
            }

            // Scenarios 3/4/5: align(old, new) → matches + removed.
            val alignment = align(oldBlocks, newBlocks)
            expect["matches"]?.jsonArray?.forEachIndexed { ni, mEl ->
                val want = mEl.jsonPrimitive.content
                val got = when (val m = alignment.newMatches[ni]) {
                    is Match.Same -> "Same:${m.old}"
                    is Match.Edited -> "Edited:${m.old}"
                    is Match.Inserted -> "Inserted"
                }
                assertEquals(want, got, "$name: match[$ni]")
                if (want.startsWith("Edited")) {
                    val sim = (alignment.newMatches[ni] as Match.Edited).similarity
                    val min = expect["similarity_min"]?.jsonPrimitive?.floatOrNull
                    if (min != null) assertTrue(sim >= min, "$name: similarity $sim >= $min")
                }
            }
            expect["removed"]?.jsonArray?.let { wantRemoved ->
                val gotRemoved = alignment.removed
                assertEquals(wantRemoved.map { it.jsonPrimitive.int }, gotRemoved, "$name: removed")
            }
        }
    }

    private val JsonPrimitive.floatOrNull: Float?
        get() = contentOrNull?.trim()?.let { it.toFloatOrNull() }

    // -- TextCrdt -----------------------------------------------------------

    private class TextRepl(val crdt: TextCrdt)

    private fun seedTextCrdt(scenario: JsonObject): Pair<String, TextRepl> {
        val replicaPeer = scenario["replica"]?.jsonObject?.get("peer")?.jsonPrimitive?.long ?: 1L
        val seedEl = scenario["seed"]
        val crdt = when {
            seedEl == null -> TextCrdt(replicaPeer)
            seedEl is JsonPrimitive && seedEl.isString -> TextCrdt(replicaPeer, seedEl.content)
            seedEl is JsonObject -> TextCrdt(
                seedEl["peer"]?.jsonPrimitive?.long ?: replicaPeer,
                seedEl.getValue("text").jsonPrimitive.content,
            )
            else -> error("unexpected text-crdt seed: $seedEl")
        }
        return "a" to TextRepl(crdt)
    }

    @Test
    fun `conformance textcrdt convergence`() {
        val fixture = loadFixture("textcrdt_convergence.json")
        for (scenario in fixture.getValue("scenarios").jsonArray) {
            val s = scenario.jsonObject
            val name = s.getValue("name").jsonPrimitive.content
            val replicas = LinkedHashMap<String, TextRepl>()
            val (defaultName, defaultRepl) = seedTextCrdt(s)
            replicas[defaultName] = defaultRepl

            for (stepEl in s.getValue("steps").jsonArray) {
                val step = stepEl.jsonObject
                when {
                    step["fork"] != null -> {
                        val newName = step.getValue("fork").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        replicas[newName] = TextRepl(replicas.getValue(defaultName).crdt.fork(newPeer))
                    }
                    step["clone"] != null -> {
                        val newName = step.getValue("clone").jsonPrimitive.content
                        val from = step["from"]?.jsonPrimitive?.content ?: defaultName
                        replicas[newName] = TextRepl(replicas.getValue(from).crdt.clone())
                    }
                    step["merge"] != null -> {
                        val m = step.getValue("merge").jsonObject
                        val into = m.getValue("into").jsonPrimitive.content
                        val from = m.getValue("from").jsonPrimitive.content
                        replicas.getValue(into).crdt.merge(replicas.getValue(from).crdt)
                    }
                    step["on"] != null -> {
                        val target = step.getValue("on").jsonPrimitive.content
                        applyTextOp(replicas.getValue(target).crdt, step)
                    }
                    step["op"] != null -> applyTextOp(replicas.getValue(defaultName).crdt, step)
                }
            }

            val expect = s.getValue("expect").jsonObject
            val primary = replicas.getValue(defaultName).crdt
            expect["text"]?.let { assertEquals(it.jsonPrimitive.content, primary.text(), "$name: text") }
            expect["len"]?.let { assertEquals(it.jsonPrimitive.int, primary.len(), "$name: len") }
            expect["tombstone_count"]?.let {
                assertEquals(it.jsonPrimitive.int, primary.tombstoneCount(), "$name: tombstone_count")
            }
            expect["a_starts_with"]?.let {
                assertTrue(primary.text().startsWith(it.jsonPrimitive.content), "$name: a_starts_with")
            }
            expect["a_ends_with"]?.let {
                assertTrue(primary.text().endsWith(it.jsonPrimitive.content), "$name: a_ends_with")
            }
            expect["texts_equal"]?.jsonArray?.forEach { pair ->
                val (x, y) = pair.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(
                    replicas.getValue(x).crdt.text(),
                    replicas.getValue(y).crdt.text(),
                    "$name: texts_equal[$x,$y]",
                )
            }
        }
    }

    // -- TextCrdt delta sync (#lztextsync) ----------------------------------

    @Test
    fun `conformance textcrdt delta sync`() {
        val fixture = loadFixture("textcrdt_delta_sync.json")
        for (scenario in fixture.getValue("scenarios").jsonArray) {
            val s = scenario.jsonObject
            val name = s.getValue("name").jsonPrimitive.content
            val replicas = LinkedHashMap<String, TextRepl>()
            val (defaultName, defaultRepl) = seedTextCrdt(s)
            replicas[defaultName] = defaultRepl

            for (stepEl in s.getValue("steps").jsonArray) {
                val step = stepEl.jsonObject
                when {
                    step["fork"] != null -> {
                        val newName = step.getValue("fork").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        val forked = replicas.getValue(defaultName).crdt.fork(newPeer)
                        replicas[newName] = TextRepl(forked)
                    }
                    step["new"] != null -> {
                        val newName = step.getValue("new").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        replicas[newName] = TextRepl(TextCrdt(newPeer))
                    }
                    step["snapshot"] != null -> {
                        val snap = step.getValue("snapshot").jsonObject
                        val from = snap.getValue("from").jsonPrimitive.content
                        val into = snap.getValue("into").jsonPrimitive.content
                        val peer = snap.getValue("peer").jsonPrimitive.long
                        val delta = replicas.getValue(from).crdt.deltaSince(emptyMap())
                        val rebuilt = TextCrdt(peer)
                        val changed = rebuilt.applyDelta(delta)
                        assertEquals(
                            step.getValue("expect_changed").jsonPrimitive.boolean,
                            changed,
                            "$name: snapshot expect_changed",
                        )
                        replicas[into] = TextRepl(rebuilt)
                    }
                    step["delta"] != null -> {
                        val d = step.getValue("delta").jsonObject
                        val into = d.getValue("into").jsonPrimitive.content
                        val from = d.getValue("from").jsonPrimitive.content
                        val theirVv = replicas.getValue(into).crdt.versionVector()
                        val delta = replicas.getValue(from).crdt.deltaSince(theirVv)
                        val changed = replicas.getValue(into).crdt.applyDelta(delta)
                        step["expect_changed"]?.let {
                            assertEquals(
                                it.jsonPrimitive.boolean,
                                changed,
                                "$name: delta expect_changed",
                            )
                        }
                    }
                    step["exchange"] != null -> {
                        val (x, y) = step.getValue("exchange").jsonArray.map { it.jsonPrimitive.content }
                        val rx = replicas.getValue(x).crdt
                        val ry = replicas.getValue(y).crdt
                        val xToY = rx.deltaSince(ry.versionVector())
                        val yToX = ry.deltaSince(rx.versionVector())
                        ry.applyDelta(xToY)
                        rx.applyDelta(yToX)
                    }
                    step["on"] != null -> {
                        val target = step.getValue("on").jsonPrimitive.content
                        applyTextOp(replicas.getValue(target).crdt, step)
                    }
                    step["op"] != null -> applyTextOp(replicas.getValue(defaultName).crdt, step)
                }
            }

            val expect = s.getValue("expect").jsonObject
            expect["text_on"]?.jsonObject?.forEach { (repl, textEl) ->
                assertEquals(
                    textEl.jsonPrimitive.content,
                    replicas.getValue(repl).crdt.text(),
                    "$name: text_on[$repl]",
                )
            }
            expect["texts_equal"]?.jsonArray?.forEach { pair ->
                val (x, y) = pair.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(
                    replicas.getValue(x).crdt.text(),
                    replicas.getValue(y).crdt.text(),
                    "$name: texts_equal[$x,$y]",
                )
            }
            expect["version_vector_on"]?.jsonObject?.forEach { (repl, vvEl) ->
                val want = vvEl.jsonObject.entries.associate { (peer, counter) ->
                    peer.toLong() to counter.jsonPrimitive.long
                }
                assertEquals(
                    want,
                    replicas.getValue(repl).crdt.versionVector(),
                    "$name: version_vector_on[$repl]",
                )
            }
        }
    }

    private fun applyTextOp(crdt: TextCrdt, step: JsonObject) {
        when (step.getValue("op").jsonPrimitive.content) {
            "insert" -> crdt.insert(step.getValue("index").jsonPrimitive.int, step.getValue("ch").jsonPrimitive.content.first())
            "insert_str" -> crdt.insertString(step.getValue("index").jsonPrimitive.int, step.getValue("str").jsonPrimitive.content)
            "delete" -> crdt.delete(step.getValue("index").jsonPrimitive.int)
            "gc" -> {
                val stable = step.getValue("stable").jsonPrimitive.boolean
                val expectCollected = step.getValue("expect_collected").jsonPrimitive.int
                assertEquals(expectCollected, crdt.gcWith { stable }, "gc expect_collected")
            }
            else -> error("unknown text-crdt op: ${step.getValue("op")}")
        }
    }

    // -- SeqCrdt ------------------------------------------------------------

    private class SeqRepl(val crdt: SeqCrdt<String, Any>)

    private fun seqValue(el: kotlinx.serialization.json.JsonElement): Any =
        if (el.jsonPrimitive.isString) el.jsonPrimitive.content else el.jsonPrimitive.int

    @Test
    fun `conformance seqcrdt convergence`() {
        val fixture = loadFixture("seqcrdt_convergence.json")
        for (scenario in fixture.getValue("scenarios").jsonArray) {
            val s = scenario.jsonObject
            val name = s.getValue("name").jsonPrimitive.content
            val replicas = LinkedHashMap<String, SeqRepl>()
            val defaultPeer = s["replica"]?.jsonObject?.get("peer")?.jsonPrimitive?.long
            val seedEl = s["seed"]
            val basePeer = when {
                defaultPeer != null -> defaultPeer
                seedEl is JsonObject -> seedEl["peer"]?.jsonPrimitive?.long ?: 1L
                else -> 1L
            }
            val base = SeqCrdt<String, Any>(basePeer)
            if (seedEl is JsonObject) {
                for (ins in seedEl.getValue("inserts").jsonArray) {
                    val o = ins.jsonObject
                    base.insertBack(o.getValue("id").jsonPrimitive.content, seqValue(o.getValue("value")), o.getValue("now").jsonPrimitive.long)
                }
            }
            replicas["a"] = SeqRepl(base)

            for (stepEl in s.getValue("steps").jsonArray) {
                val step = stepEl.jsonObject
                when {
                    step["fork"] != null -> {
                        val newName = step.getValue("fork").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        replicas[newName] = SeqRepl(replicas.getValue("a").crdt.cloneStateAs(newPeer))
                    }
                    step["clone"] != null -> {
                        val newName = step.getValue("clone").jsonPrimitive.content
                        val from = step["from"]?.jsonPrimitive?.content ?: "a"
                        replicas[newName] = SeqRepl(replicas.getValue(from).crdt.cloneState())
                    }
                    step["merge"] != null -> {
                        val m = step.getValue("merge").jsonObject
                        val into = m.getValue("into").jsonPrimitive.content
                        val from = m.getValue("from").jsonPrimitive.content
                        val now = step["now"]?.jsonPrimitive?.long ?: 0L
                        replicas.getValue(into).crdt.merge(replicas.getValue(from).crdt, now)
                    }
                    step["on"] != null -> {
                        val target = step.getValue("on").jsonPrimitive.content
                        applySeqOp(replicas.getValue(target).crdt, step)
                    }
                    step["op"] != null -> applySeqOp(replicas.getValue("a").crdt, step)
                }
            }

            val expect = s.getValue("expect").jsonObject
            // Default target: an explicit `on`, else the first orders_equal
            // replica (the merged result), else the main replica "a".
            val defaultTarget = when {
                expect["on"] != null -> expect.getValue("on").jsonPrimitive.content
                expect["orders_equal"] != null ->
                    expect.getValue("orders_equal").jsonArray.first().jsonArray.first().jsonPrimitive.content
                else -> "a"
            }
            val primary = replicas.getValue(defaultTarget).crdt
            expect["order"]?.jsonArray?.let {
                assertEquals(it.map { e -> e.jsonPrimitive.content }, primary.order(), "$name: order")
            }
            expect["get"]?.jsonObject?.forEach { (k, v) ->
                assertEquals(seqValue(v), primary.get(k), "$name: get[$k]")
            }
            expect["len"]?.let { assertEquals(it.jsonPrimitive.int, primary.order().size, "$name: len") }
            expect["contains_all"]?.jsonArray?.forEach { id ->
                assertTrue(primary.contains(id.jsonPrimitive.content), "$name: contains_all ${id.jsonPrimitive.content}")
            }
            expect["order_on"]?.jsonObject?.forEach { (repl, orderEl) ->
                assertEquals(
                    orderEl.jsonArray.map { it.jsonPrimitive.content },
                    replicas.getValue(repl).crdt.order(),
                    "$name: order_on[$repl]",
                )
            }
            expect["get_on"]?.jsonObject?.forEach { (repl, gets) ->
                gets.jsonObject.forEach { (k, v) ->
                    assertEquals(seqValue(v), replicas.getValue(repl).crdt.get(k), "$name: get_on[$repl][$k]")
                }
            }
            expect["orders_equal"]?.jsonArray?.forEach { pair ->
                val (x, y) = pair.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(
                    replicas.getValue(x).crdt.order(),
                    replicas.getValue(y).crdt.order(),
                    "$name: orders_equal[$x,$y]",
                )
            }
            expect["not_contains_on"]?.jsonObject?.forEach { (repl, ids) ->
                ids.jsonArray.forEach { id ->
                    assertFalse(
                        replicas.getValue(repl).crdt.contains(id.jsonPrimitive.content),
                        "$name: not_contains_on[$repl][${id.jsonPrimitive.content}]",
                    )
                }
            }
        }
    }

    private fun applySeqOp(crdt: SeqCrdt<String, Any>, step: JsonObject) {
        val now = step["now"]?.jsonPrimitive?.long ?: 0L
        when (step.getValue("op").jsonPrimitive.content) {
            "insert_back" -> crdt.insertBack(step.getValue("id").jsonPrimitive.content, seqValue(step.getValue("value")), now)
            "insert_front" -> crdt.insertFront(step.getValue("id").jsonPrimitive.content, seqValue(step.getValue("value")), now)
            "move_after" -> crdt.moveAfter(step.getValue("id").jsonPrimitive.content, step.getValue("anchor").jsonPrimitive.content, now)
            "set_value" -> crdt.setValue(step.getValue("id").jsonPrimitive.content, seqValue(step.getValue("value")), now)
            "remove" -> crdt.remove(step.getValue("id").jsonPrimitive.content, now)
            else -> error("unknown seq-crdt op: ${step.getValue("op")}")
        }
    }

    // -- SemTree ------------------------------------------------------------

    @Test
    fun `conformance semtree incremental`() {
        val fixture = loadFixture("semtree_incremental.json")
        for (scenario in fixture.getValue("scenarios").jsonArray) {
            val s = scenario.jsonObject
            val name = s.getValue("name").jsonPrimitive.content
            val foldName = s.getValue("fold").jsonPrimitive.content
            val fold = semFold(foldName)

            val ctx = Context()
            val tree = CellTree<String, Int>(ctx)
            buildTree(ctx, tree, "root", s.getValue("tree").jsonObject, null)
            val sums = SemTree.build(ctx, tree, "root", fold)

            // expect_initial
            val expectInitial = s.getValue("expect_initial").jsonObject
            for ((node, v) in expectInitial) {
                assertEquals(v.jsonPrimitive.int, sums.nodeValue(ctx, node), "$name: initial $node")
            }

            val edit = s["edit"]?.jsonObject
            val expectAfter = s["expect_after"]?.jsonObject
            val memoGuard = expectAfter?.get("downstream_consumer_reran") != null

            if (edit != null && memoGuard) {
                // An edit that does not change the folded result must NOT re-run a
                // downstream consumer (memo equality guard). Wire an instrumented
                // observer BEFORE the edit, then assert its call count is unchanged.
                var calls = 0
                val observer = ctx.computed {
                    calls++
                    sums.value(ctx)
                }
                assertEquals(
                    expectInitial.getValue("root").jsonPrimitive.int,
                    ctx.get(observer),
                    "$name: observer primed",
                )
                assertEquals(1, calls, "$name: observer primed once")

                tree.setValue(edit.getValue("id").jsonPrimitive.content, edit.getValue("value").jsonPrimitive.int)
                ctx.get(observer)
                val reran = expectAfter!!.getValue("downstream_consumer_reran").jsonPrimitive.boolean
                assertEquals(if (reran) 2 else 1, calls, "$name: downstream_consumer_reran=$reran")
                assertEquals(
                    expectAfter.getValue("root").jsonPrimitive.int,
                    sums.value(ctx),
                    "$name: memo-guard root unchanged",
                )
            } else if (edit != null) {
                val siblingA = sums.node("a")
                tree.setValue(edit.getValue("id").jsonPrimitive.content, edit.getValue("value").jsonPrimitive.int)
                for ((node, v) in expectAfter!!) {
                    when (node) {
                        "sibling_a_cached" -> if (v.jsonPrimitive.boolean) {
                            assertNotNull(siblingA)
                            assertTrue(ctx.isSet(siblingA), "$name: sibling 'a' derived slot stayed cached")
                        }
                        else -> assertEquals(v.jsonPrimitive.int, sums.nodeValue(ctx, node), "$name: after $node")
                    }
                }
            }

            s["remove_child"]?.jsonObject?.let { rc ->
                tree.remove(rc.getValue("child").jsonPrimitive.content)
                assertEquals(
                    expectAfter!!.getValue("root").jsonPrimitive.int,
                    sums.value(ctx),
                    "$name: after remove root",
                )
            }
        }
    }

    private fun semFold(name: String): SemFold<Int, Int> = when (name) {
        "sum" -> SemFold { v, kids -> v + kids.sum() }
        "count_positive" -> SemFold { v, kids -> (if (v < 0) 1 else 0) + kids.sum() }
        else -> error("unknown semtree fold: $name")
    }

    /** Build a [CellTree] node from a fixture tree object, attaching it under [parent] (or as a root). */
    private fun buildTree(
        ctx: Context,
        tree: CellTree<String, Int>,
        id: String,
        obj: JsonObject,
        parent: String?,
    ) {
        val value = obj.getValue("value").jsonPrimitive.int
        if (parent == null) tree.addRoot(id, value) else tree.insertChild(parent, id, value)
        val children = obj["children"]?.jsonObject ?: return
        val order = children.getValue("order").jsonArray.map { it.jsonPrimitive.content }
        val values = children.getValue("values").jsonObject
        for (cid in order) buildTree(ctx, tree, cid, values.getValue(cid).jsonObject, id)
    }
}
