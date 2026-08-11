package io.github.lazily

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The replay machinery behind [LosslessTreeCrdtConformanceTest] — the named
 * replicas, the `label`→id table, and the step interpreter for the canonical
 * `lazily-spec/conformance/lossless-tree/` schedules.
 *
 * It lives in its own file rather than inside the test class because a FIXTURE
 * cannot assert on how its own step was interpreted. `deliver.order` is the case
 * that forces the split: a runner that re-sorts the listed sequence still
 * selects the same op SET, so every fixture using it stays green — measured
 * against a library with no dependency buffer at all. Only a direct test on this
 * seam can see the difference, so the seam has to be reachable from one.
 */
internal class LosslessTreeReplayWorld {
    val replicas = HashMap<String, LosslessTreeCrdt>()
    val ids = HashMap<String, TreeNodeId>()

    /**
     * When non-null, every batch handed to [LosslessTreeCrdt.applyUpdate] is
     * appended here as `replica to batch`, in call order, BEFORE it is applied.
     *
     * Recording never suppresses the apply: a spy that changes what the replica
     * sees would be pinning a different code path than the one the fixtures run.
     * This is what lets a test assert both the SEQUENCE inside one batch and the
     * number of batches — neither of which survives into observable CRDT state.
     */
    var deliveries: MutableList<Pair<String, TreeUpdate>>? = null

    fun id(label: String): TreeNodeId = ids[label] ?: error("unknown node label `$label`")

    fun afterOf(op: JsonObject): TreeNodeId? =
        when (val after = op["after"]) {
            null, JsonNull -> null
            is JsonPrimitive -> id(after.content)
            else -> error("bad `after`: $after")
        }

    fun buildChildren(
        spec: JsonObject,
        parent: TreeNodeId,
    ) {
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

    /** Hand [update] to replica [to] as ONE `applyUpdate` call, recording it first. */
    fun deliver(
        to: String,
        update: TreeUpdate,
    ) {
        deliveries?.add(to to update)
        replicas.getValue(to).applyUpdate(update)
    }

    /**
     * The canonical diff a `sync` or `deliver` step selects from: the ops [from]
     * holds that [to]'s frontier lacks, in `(counter, peer)` order.
     *
     * `deliver.only` / `deliver.order` index into THIS list, so both selectors
     * and a plain `sync` have to compute it the same way — a `deliver` that
     * diffed against anything else would be indexing a different sequence than
     * the corpus wrote its indexes against.
     */
    fun canonicalDiff(
        from: String,
        to: String,
    ): TreeUpdate = replicas.getValue(from).diff(replicas.getValue(to).frontier())

    fun applyStep(step: JsonObject) {
        val fork = step["fork"]?.jsonPrimitive?.content
        val clone = step["clone"]?.jsonPrimitive?.content
        val sync = step["sync"]?.jsonObject
        val deliverStep = step["deliver"]?.jsonObject
        val on = step["on"]?.jsonPrimitive?.content
        when {
            fork != null -> {
                val peer = step.getValue("peer").jsonPrimitive.long
                replicas[fork] = replicas.getValue("a").fork(peer)
            }
            clone != null -> {
                val from = step.getValue("from").jsonPrimitive.content
                // No public clone(): a same-peer fork reproduces the state deep-copy.
                error("clone step unused by fixtures; from=$from")
            }
            sync != null -> {
                val from = sync.getValue("from").jsonPrimitive.content
                val to = sync.getValue("to").jsonPrimitive.content
                deliver(to, canonicalDiff(from, to))
            }
            deliverStep != null -> {
                val from = deliverStep.getValue("from").jsonPrimitive.content
                val to = deliverStep.getValue("to").jsonPrimitive.content
                deliver(to, deliverSelection(canonicalDiff(from, to), deliverStep))
            }
            on != null -> applyOp(on, step)
            else -> error("unrecognized step: $step")
        }
    }

    private fun applyOp(
        on: String,
        op: JsonObject,
    ) {
        val replica = replicas.getValue(on)
        when (val kind = op.getValue("op").jsonPrimitive.content) {
            "create" -> {
                val parent = id(op.getValue("parent").jsonPrimitive.content)
                val after = afterOf(op)
                val label = op.getValue("label").jsonPrimitive.content
                ids[label] = replica.createNode(parent, after, nodeSeed(op))
            }
            "edit_leaf" -> {
                val node = id(op.getValue("node").jsonPrimitive.content)
                val at = op.getValue("at_byte").jsonPrimitive.int
                val del = op["delete_bytes"]?.jsonPrimitive?.int ?: 0
                val insert = op["insert"]?.jsonPrimitive?.content ?: ""
                replica.editLeaf(node, at, del, insert)
            }
            "split" -> {
                val node = id(op.getValue("node").jsonPrimitive.content)
                val at = op.getValue("at_byte").jsonPrimitive.int
                val label = op.getValue("new_label").jsonPrimitive.content
                ids[label] = replica.splitLeaf(node, at)
            }
            "merge_leaves" -> {
                val left = id(op.getValue("left").jsonPrimitive.content)
                val right = id(op.getValue("right").jsonPrimitive.content)
                replica.mergeAdjacentLeaves(left, right)
            }
            "reorder" -> {
                val node = id(op.getValue("node").jsonPrimitive.content)
                replica.reorderChild(node, afterOf(op))
            }
            "tombstone" -> {
                val node = id(op.getValue("node").jsonPrimitive.content)
                replica.tombstoneNode(node)
            }
            else -> error("unknown op: $kind")
        }
    }
}

/**
 * Resolve a `deliver` step's selector against the canonical diff [full].
 *
 * Exactly one of `only` / `order` is present, and both are 0-based indexes into
 * [full] — the same `diff(to.frontier())` a plain `sync` computes, in
 * `(counter, peer)` order (`#lzdifforderallbindings`).
 *
 * - **`only`** — that SUBSET, delivered in canonical order. Which is what it has
 *   always meant, so the indexes are sorted here rather than trusted: an `only`
 *   whose listing order happens to be canonical must stay indistinguishable from
 *   one that is not, or the two selectors collapse into each other.
 * - **`order`** — exactly this SEQUENCE, in the listed order, unsorted, in ONE
 *   `applyUpdate` call. It need not be a permutation and it need not be
 *   injective. Re-sorting it is the failure mode this whole selector exists to
 *   expose: `out_of_order_delivery_buffers.json` was MEASURED green, against a
 *   library with no dependency buffer, in a binding that re-sorted — because two
 *   orders select the same op SET and only the arrival order discriminates.
 *
 * An out-of-range index is a hard error rather than a clamp or a skip. The
 * corpus addresses `diff` output POSITIONALLY, so an index the diff cannot
 * satisfy means this binding computed a DIFFERENT diff than the fixture was
 * written against — silently delivering a shorter batch would turn that finding
 * into a pass.
 */
internal fun deliverSelection(
    full: TreeUpdate,
    deliver: JsonObject,
): TreeUpdate {
    val only = deliver["only"] as? JsonArray
    val order = deliver["order"] as? JsonArray
    require((only == null) != (order == null)) {
        "deliver step needs exactly one of `only` / `order`, got " +
            if (only != null) "both" else "neither"
    }
    val listed = (only ?: order!!).map { it.jsonPrimitive.int }
    for (i in listed) {
        require(i in full.ops.indices) {
            "deliver index $i is out of range for a canonical diff of ${full.ops.size} op(s). " +
                "The corpus addresses diff output positionally, so this binding computed a " +
                "different diff than the fixture was written against — clamping would hide it."
        }
    }
    val picked = if (only != null) listed.sorted() else listed
    return TreeUpdate(picked.map { full.ops[it] })
}

internal fun leafKind(s: String): LeafKind =
    when (s) {
        "token" -> LeafKind.Token
        "trivia" -> LeafKind.Trivia
        "raw" -> LeafKind.Raw
        "error" -> LeafKind.Error
        else -> error("unknown leaf kind: $s")
    }

internal fun nodeSeed(spec: JsonObject): NodeSeed {
    val element = spec["element"]?.jsonPrimitive?.content
    if (element != null) return NodeSeed.Element(element)
    val leaf = spec["leaf"]?.jsonObject ?: error("node spec has neither element nor leaf: $spec")
    return NodeSeed.Leaf(leafKind(leaf.getValue("kind").jsonPrimitive.content), leaf.getValue("text").jsonPrimitive.content)
}
