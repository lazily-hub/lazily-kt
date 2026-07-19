package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays the canonical `lazily-spec/conformance/lossless-tree/` compute fixtures
 * against the native [LosslessTreeCrdt] — the same `{scenarios: [{seed, steps,
 * expect}]}` shape and the same `label`→id addressing the Rust reference harness
 * uses. Each scenario builds `seed.tree` on replica `a`, replays the schedule of
 * ops / forks / anti-entropy syncs across named replicas, and asserts exact
 * rendered text, live-node counts, and convergence across delivery orders. The
 * lossless invariant `render(tree) == source_text` is what every assertion checks.
 */
class LosslessTreeCrdtConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("lossless-tree/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private class World {
        val replicas = HashMap<String, LosslessTreeCrdt>()
        val ids = HashMap<String, TreeNodeId>()

        fun id(label: String): TreeNodeId = ids[label] ?: error("unknown node label `$label`")

        fun afterOf(op: JsonObject): TreeNodeId? = when (val after = op["after"]) {
            null, JsonNull -> null
            is JsonPrimitive -> id(after.content)
            else -> error("bad `after`: $after")
        }

        fun buildChildren(spec: JsonObject, parent: TreeNodeId) {
            val children = spec["children"]?.jsonArray ?: return
            var prev: TreeNodeId? = null
            for (childEl in children) {
                val child = childEl.jsonObject
                val label = child.getValue("label").jsonPrimitive.content
                val id = replicas.getValue("a").createNode(parent, prev, nodeSeed(child))
                ids[label] = id
                buildChildren(child, id)
                prev = id
            }
        }
    }

    private fun applyStep(world: World, step: JsonObject) {
        val fork = step["fork"]?.jsonPrimitive?.content
        val clone = step["clone"]?.jsonPrimitive?.content
        val sync = step["sync"]?.jsonObject
        val deliver = step["deliver"]?.jsonObject
        val on = step["on"]?.jsonPrimitive?.content
        when {
            fork != null -> {
                val peer = step.getValue("peer").jsonPrimitive.long
                world.replicas[fork] = world.replicas.getValue("a").fork(peer)
            }
            clone != null -> {
                val from = step.getValue("from").jsonPrimitive.content
                // No public clone(): a same-peer fork reproduces the state deep-copy.
                error("clone step unused by fixtures; from=$from")
            }
            sync != null -> {
                val from = sync.getValue("from").jsonPrimitive.content
                val to = sync.getValue("to").jsonPrimitive.content
                val update = world.replicas.getValue(from).diff(world.replicas.getValue(to).frontier())
                world.replicas.getValue(to).applyUpdate(update)
            }
            deliver != null -> {
                val from = deliver.getValue("from").jsonPrimitive.content
                val to = deliver.getValue("to").jsonPrimitive.content
                val full = world.replicas.getValue(from).diff(world.replicas.getValue(to).frontier())
                val only = deliver.getValue("only").jsonArray.map { it.jsonPrimitive.int }
                world.replicas.getValue(to).applyUpdate(TreeUpdate(only.map { full.ops[it] }))
            }
            on != null -> applyOp(world, on, step)
            else -> error("unrecognized step: $step")
        }
    }

    private fun applyOp(world: World, on: String, op: JsonObject) {
        val replica = world.replicas.getValue(on)
        when (val kind = op.getValue("op").jsonPrimitive.content) {
            "create" -> {
                val parent = world.id(op.getValue("parent").jsonPrimitive.content)
                val after = world.afterOf(op)
                val label = op.getValue("label").jsonPrimitive.content
                world.ids[label] = replica.createNode(parent, after, nodeSeed(op))
            }
            "edit_leaf" -> {
                val node = world.id(op.getValue("node").jsonPrimitive.content)
                val at = op.getValue("at_byte").jsonPrimitive.int
                val del = op["delete_bytes"]?.jsonPrimitive?.int ?: 0
                val insert = op["insert"]?.jsonPrimitive?.content ?: ""
                replica.editLeaf(node, at, del, insert)
            }
            "split" -> {
                val node = world.id(op.getValue("node").jsonPrimitive.content)
                val at = op.getValue("at_byte").jsonPrimitive.int
                val label = op.getValue("new_label").jsonPrimitive.content
                world.ids[label] = replica.splitLeaf(node, at)
            }
            "merge_leaves" -> {
                val left = world.id(op.getValue("left").jsonPrimitive.content)
                val right = world.id(op.getValue("right").jsonPrimitive.content)
                replica.mergeAdjacentLeaves(left, right)
            }
            "reorder" -> {
                val node = world.id(op.getValue("node").jsonPrimitive.content)
                replica.reorderChild(node, world.afterOf(op))
            }
            "tombstone" -> {
                val node = world.id(op.getValue("node").jsonPrimitive.content)
                replica.tombstoneNode(node)
            }
            else -> error("unknown op: $kind")
        }
    }

    private fun assertExpect(world: World, expect: JsonObject, scenario: String) {
        expect["render"]?.jsonPrimitive?.content?.let {
            assertEquals(it, world.replicas.getValue("a").render(), "$scenario: render on `a`")
        }
        expect["render_on"]?.jsonObject?.forEach { (name, text) ->
            assertEquals(text.jsonPrimitive.content, world.replicas.getValue(name).render(), "$scenario: render on `$name`")
        }
        expect["live_nodes"]?.jsonPrimitive?.int?.let {
            assertEquals(it, world.replicas.getValue("a").liveNodeCount(), "$scenario: live_nodes on `a`")
        }
        expect["converged"]?.jsonArray?.let { names ->
            val labels = names.map { it.jsonPrimitive.content }
            val first = world.replicas.getValue(labels[0]).render()
            for (name in labels.drop(1)) {
                assertEquals(first, world.replicas.getValue(name).render(), "$scenario: `${labels[0]}`/`$name` should converge")
            }
        }
    }

    private fun runFixture(name: String) {
        val fixture = loadFixture(name)
        for ((i, scenarioEl) in fixture.getValue("scenarios").jsonArray.withIndex()) {
            val scenario = scenarioEl.jsonObject
            val label = scenario["name"]?.jsonPrimitive?.content?.let { "$name[$it]" } ?: "$name[$i]"
            val seed = scenario.getValue("seed").jsonObject
            val peer = seed.getValue("peer").jsonPrimitive.long
            val world = World()
            world.replicas["a"] = LosslessTreeCrdt(peer)
            world.buildChildren(seed.getValue("tree").jsonObject, TreeNodeId.ROOT)
            scenario["steps"]?.jsonArray?.forEach { applyStep(world, it.jsonObject) }
            assertExpect(world, scenario.getValue("expect").jsonObject, label)
        }
    }

    @Test fun `conformance exact roundtrip`() = runFixture("exact_roundtrip.json")

    @Test fun `conformance one leaf edit delta`() = runFixture("one_leaf_edit_delta.json")

    @Test fun `conformance split merge`() = runFixture("split_merge.json")

    @Test fun `conformance concurrent insert same parent`() = runFixture("concurrent_insert_same_parent.json")

    @Test fun `conformance concurrent reorder and leaf edit`() = runFixture("concurrent_reorder_and_leaf_edit.json")

    @Test fun `conformance non contiguous anti entropy`() = runFixture("non_contiguous_anti_entropy.json")

    @Test fun `conformance token trivia preservation`() = runFixture("token_trivia_preservation.json")

    @Test fun `conformance invalid source roundtrip`() = runFixture("invalid_source_roundtrip.json")

    @Test fun `conformance concurrent conflict preserves text`() = runFixture("concurrent_conflict_preserves_text.json")
}

private fun leafKind(s: String): LeafKind = when (s) {
    "token" -> LeafKind.Token
    "trivia" -> LeafKind.Trivia
    "raw" -> LeafKind.Raw
    "error" -> LeafKind.Error
    else -> error("unknown leaf kind: $s")
}

private fun nodeSeed(spec: JsonObject): NodeSeed {
    val element = spec["element"]?.jsonPrimitive?.content
    if (element != null) return NodeSeed.Element(element)
    val leaf = spec["leaf"]?.jsonObject ?: error("node spec has neither element nor leaf: $spec")
    return NodeSeed.Leaf(leafKind(leaf.getValue("kind").jsonPrimitive.content), leaf.getValue("text").jsonPrimitive.content)
}
