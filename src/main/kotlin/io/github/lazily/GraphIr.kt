package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val GRAPH_IR_VERSION = "1"
private const val GRAPH_DRAIN_BUDGET = 100_000
private val ID_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val UNARY = setOf("neg", "not", "len")
private val BINARY =
    setOf(
        "add",
        "sub",
        "mul",
        "div",
        "mod",
        "min",
        "max",
        "eq",
        "ne",
        "lt",
        "le",
        "gt",
        "ge",
        "concat",
        "get",
        "and",
        "or",
    )
private val POLICIES = setOf("keep-latest", "sum", "max", "min", "and", "or")

/** A parse, check, edit, or value fault. */
class GraphIrError(
    message: String,
) : Exception(message)

/** Source merge policies. The wire strings are the constant values. */
object Policy {
    const val KEEP_LATEST = "keep-latest"
    const val SUM = "sum"
    const val MAX = "max"
    const val MIN = "min"
    const val AND = "and"
    const val OR = "or"
}

/** Read sorts before write. */
object EdgeKind {
    const val READ = "read"
    const val WRITE = "write"
}

/** A derived read or write edge. */
data class Edge(
    val from: String,
    val kind: String,
    val to: String,
)

/** One successful effect evaluation. Removal does not erase it. */
data class GraphEffectRun(
    val id: String,
    val result: Any?,
)

class GraphExpr(
    val type: String,
    val lit: Any? = null,
    val ref: String? = null,
    val op: String? = null,
    val args: List<GraphExpr> = emptyList(),
    val fields: List<GraphField> = emptyList(),
)

data class GraphField(
    val key: String,
    val value: GraphExpr,
)

class GraphStmt(
    val type: String,
    val expr: GraphExpr? = null,
    val target: String? = null,
    val steps: List<GraphStmt> = emptyList(),
    val cond: GraphExpr? = null,
    val then: GraphStmt? = null,
    val otherwise: GraphStmt? = null,
)

class GraphIrNode(
    val kind: String,
    val id: String,
    val init: Any? = null,
    val policy: String = Policy.KEEP_LATEST,
    val eager: Boolean = false,
    val expr: GraphExpr? = null,
    val body: GraphStmt? = null,
)

internal class EditOp(
    val op: String,
    val node: GraphIrNode? = null,
    val id: String? = null,
)

/** A parsed graph definition. */
class GraphIr internal constructor(
    val id: String?,
    internal val nodes: MutableList<GraphIrNode>,
    private val index: MutableMap<String, Int>,
) {
    fun node(id: String): GraphIrNode? {
        val slot = index[id] ?: return null
        return nodes[slot]
    }

    fun toJson(): Map<String, Any?> {
        val obj = linkedMapOf<String, Any?>("kind" to "graph", "ir" to GRAPH_IR_VERSION)
        if (id != null) obj["id"] = id
        obj["nodes"] = nodes.map { nodeJson(it) }
        return obj
    }

    fun staticEdges(): List<Edge> {
        val edges = mutableListOf<Edge>()
        for (node in nodes) {
            val (reads, writes) = edgeSets(node)
            for (to in reads) edges.add(Edge(node.id, EdgeKind.READ, to))
            for (to in writes) edges.add(Edge(node.id, EdgeKind.WRITE, to))
        }
        edges.sortWith(compareBy({ it.from }, { it.kind }, { it.to }))
        return edges
    }

    fun emitSource(): String {
        val b = StringBuilder()
        if (id == null) {
            b.append("graph ir ").append(GRAPH_IR_VERSION).append('\n')
        } else {
            b.append("graph ").append(id).append(" ir ").append(GRAPH_IR_VERSION).append('\n')
        }
        for (node in nodes) {
            b.append(emitNode(node)).append('\n')
        }
        return b.toString()
    }

    internal fun clone(): GraphIr = GraphIr(id, nodes.toMutableList(), index.toMutableMap())

    internal fun applyOp(op: EditOp) {
        when (op.op) {
            "add" -> {
                if (index.containsKey(op.node!!.id)) {
                    throw GraphIrError("add requires a new id, `${op.node.id}` already exists")
                }
                nodes.add(op.node)
            }
            "replace" -> {
                val slot = index[op.node!!.id] ?: throw GraphIrError("replace requires an existing node `${op.node.id}`")
                nodes[slot] = op.node
            }
            "remove" -> {
                val slot = index[op.id] ?: throw GraphIrError("remove requires an existing node `${op.id}`")
                nodes.removeAt(slot)
            }
        }
        reindex()
        check()
    }

    internal fun reindex() {
        index.clear()
        for ((slot, node) in nodes.withIndex()) {
            if (index.containsKey(node.id)) throw GraphIrError("duplicate node id `${node.id}`")
            index[node.id] = slot
        }
    }

    internal fun check() {
        checkRefs()
        checkInits()
        checkCycles()
    }

    private fun checkRefs() {
        for (node in nodes) {
            val (reads, writes) = edgeSets(node)
            for (to in reads) {
                val target = this.node(to) ?: throw GraphIrError("node `${node.id}` reads unknown node `$to`")
                if (target.kind == "effect") {
                    throw GraphIrError("node `${node.id}` reads effect `$to`, which has no value")
                }
            }
            for (to in writes) {
                val target = this.node(to) ?: throw GraphIrError("node `${node.id}` writes unknown node `$to`")
                if (target.kind != "source") throw GraphIrError("node `${node.id}` writes non-source `$to`")
            }
        }
    }

    private fun checkInits() {
        for (node in nodes) {
            if (node.kind != "source") continue
            try {
                checkPolicyInit(node.policy, node.init)
            } catch (err: GraphIrError) {
                throw GraphIrError("source `${node.id}`: ${err.message}")
            }
        }
    }

    private fun checkCycles() {
        val color = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        fun visit(id: String) {
            when (color[id] ?: 0) {
                1 -> {
                    val start = stack.indexOf(id)
                    throw GraphIrError("cycle in read edges: ${(stack.subList(start, stack.size) + id).joinToString(" -> ")}")
                }
                2 -> return
            }
            color[id] = 1
            stack.add(id)
            val target = node(id)
            if (target != null) {
                for (dep in edgeSets(target).first.sorted()) {
                    if (node(dep) != null) visit(dep)
                }
            }
            stack.removeAt(stack.lastIndex)
            color[id] = 2
        }
        for (node in nodes) visit(node.id)
    }

    companion object {
        fun fromJsonStr(text: String): GraphIr = graphFrom(parseGraphJson(text))
    }
}

/** An ordered list of add, replace, and remove ops. */
class GraphEdit internal constructor(
    internal val ops: List<EditOp>,
) {
    companion object {
        fun fromJsonStr(text: String): GraphEdit = editFrom(parseGraphJson(text))
    }
}

private class SourceState(
    var value: Any?,
    val policy: String,
)

private class ComputedState(
    val expr: GraphExpr,
    val eager: Boolean,
    var cache: Any? = null,
    var hasValue: Boolean = false,
    var dirty: Boolean = true,
)

private class EffectState(
    val body: GraphStmt,
)

private class Snap(
    val kind: String,
    val source: SourceState? = null,
    val computed: ComputedState? = null,
    val effect: EffectState? = null,
    val deps: Set<String> = emptySet(),
)

/** A document plus the cells it was lowered onto. */
class GraphRuntime private constructor(
    private var doc: GraphIr,
    val drainBudget: Int,
) {
    private val sources = mutableMapOf<String, SourceState>()
    private val computeds = mutableMapOf<String, ComputedState>()
    private val effects = mutableMapOf<String, EffectState>()
    private val deps = mutableMapOf<String, MutableSet<String>>()
    private val subs = mutableMapOf<String, MutableSet<String>>()
    private val observed = mutableMapOf<String, MutableSet<String>>()
    private val trace = mutableListOf<GraphEffectRun>()
    private val frames = mutableListOf<MutableSet<String>>()
    private val stack = mutableListOf<String?>()
    private var current: String? = null
    private val computing = mutableSetOf<String>()
    private val scheduled = ArrayDeque<String>()
    private val scheduledSet = mutableSetOf<String>()
    private var draining = false
    private var fault: String? = null
    var lastDrainExhaustion: Int? = null
        private set

    fun document(): GraphIr = doc

    fun contains(id: String): Boolean = doc.node(id) != null

    fun isEager(id: String): Boolean {
        val node = doc.node(id) ?: throw GraphIrError("unknown node `$id`")
        if (node.kind != "computed") throw GraphIrError("`$id` is not a computed")
        return node.eager
    }

    fun sourcePolicy(id: String): String {
        val node = doc.node(id) ?: throw GraphIrError("unknown node `$id`")
        if (node.kind != "source") throw GraphIrError("`$id` is not a source")
        return node.policy
    }

    fun staticEdges(): List<Edge> = doc.staticEdges()

    fun emitSource(): String = doc.emitSource()

    fun observedReads(id: String): Set<String> = observed[id]?.toSet() ?: emptySet()

    fun effectTrace(): List<GraphEffectRun> = trace.toList()

    fun get(id: String): Any? {
        if (effects.containsKey(id) && !sources.containsKey(id) && !computeds.containsKey(id)) {
            throw GraphIrError("`$id` is an effect and has no value")
        }
        sources[id]?.let { return freezeGraph(it.value) }
        val comp = computeds[id] ?: throw GraphIrError("unknown node `$id`")
        if (comp.dirty || !comp.hasValue) recompute(id)
        return freezeGraph(computeds[id]!!.cache)
    }

    fun writeSource(
        id: String,
        value: Any?,
    ) {
        setSource(id, freezeGraph(value))
        drain()
        raiseFault()
    }

    fun apply(edit: GraphEdit) {
        for ((index, op) in edit.ops.withIndex()) {
            val next = doc.clone()
            try {
                next.applyOp(op)
            } catch (err: GraphIrError) {
                throw GraphIrError("edit op $index: ${err.message}")
            }
            try {
                installOp(op)
            } catch (err: GraphIrError) {
                throw GraphIrError("edit op $index: ${err.message}")
            }
            doc = next
        }
    }

    private fun installDocument() {
        for (node in doc.nodes) if (node.kind == "source") insertSource(node)
        for (node in doc.nodes) if (node.kind == "computed") insertComputed(node)
        for (node in doc.nodes) if (node.kind == "computed" && node.eager) recompute(node.id)
        for (node in doc.nodes) if (node.kind == "effect") insertEffect(node)
        raiseFault()
    }

    private fun installOp(op: EditOp) {
        when (op.op) {
            "add" -> addNode(op.node!!)
            "replace" -> replaceNode(op.node!!)
            else -> removeNode(op.id!!)
        }
        drain()
        raiseFault()
    }

    private fun addNode(node: GraphIrNode) {
        when (node.kind) {
            "source" -> insertSource(node)
            "computed" -> {
                insertComputed(node)
                if (node.eager) {
                    try {
                        recompute(node.id)
                    } catch (err: GraphIrError) {
                        dropBinding(node.id)
                        throw err
                    }
                }
            }
            else -> insertEffect(node)
        }
    }

    private fun replaceNode(node: GraphIrNode) {
        val snap = snapshot(node.id)
        dropBinding(node.id)
        try {
            addNode(node)
            notify(node.id)
        } catch (err: GraphIrError) {
            dropBinding(node.id)
            restore(node.id, snap)
            throw err
        }
    }

    private fun removeNode(id: String) {
        if (!sources.containsKey(id) && !computeds.containsKey(id) && !effects.containsKey(id)) {
            throw GraphIrError("unknown node `$id`")
        }
        dropBinding(id)
        subs.remove(id)
    }

    private fun insertSource(node: GraphIrNode) {
        checkPolicyInit(node.policy, node.init)
        sources[node.id] = SourceState(freezeGraph(node.init), node.policy)
    }

    private fun insertComputed(node: GraphIrNode) {
        computeds[node.id] = ComputedState(node.expr!!, node.eager)
        deps[node.id] = mutableSetOf()
    }

    private fun insertEffect(node: GraphIrNode) {
        effects[node.id] = EffectState(node.body!!)
        deps[node.id] = mutableSetOf()
        try {
            runEffect(node.id)
        } catch (err: GraphIrError) {
            dropBinding(node.id)
            throw err
        }
    }

    private fun snapshot(id: String): Snap? {
        sources[id]?.let { return Snap("source", source = SourceState(freezeGraph(it.value), it.policy)) }
        computeds[id]?.let { comp ->
            return Snap(
                "computed",
                computed =
                ComputedState(comp.expr, comp.eager).also {
                    it.cache = if (comp.hasValue) freezeGraph(comp.cache) else null
                    it.hasValue = comp.hasValue
                    it.dirty = comp.dirty
                },
                deps = deps[id]?.toSet() ?: emptySet(),
            )
        }
        effects[id]?.let { return Snap("effect", effect = it, deps = deps[id]?.toSet() ?: emptySet()) }
        return null
    }

    private fun restore(
        id: String,
        snap: Snap?,
    ) {
        if (snap == null) return
        when (snap.kind) {
            "source" -> sources[id] = snap.source!!
            "computed" -> {
                computeds[id] = snap.computed!!
                deps[id] = mutableSetOf()
                for (dep in snap.deps) {
                    subAdd(dep, id)
                    deps[id]!!.add(dep)
                }
            }
            "effect" -> {
                effects[id] = snap.effect!!
                deps[id] = mutableSetOf()
                for (dep in snap.deps) {
                    subAdd(dep, id)
                    deps[id]!!.add(dep)
                }
            }
        }
    }

    private fun dropBinding(id: String) {
        sources.remove(id)
        computeds.remove(id)
        effects.remove(id)
        for (dep in deps[id].orEmpty()) subs[dep]?.remove(id)
        deps.remove(id)
        observed.remove(id)
        scheduledSet.remove(id)
    }

    private fun setSource(
        id: String,
        value: Any?,
    ): Any? {
        val src =
            sources[id] ?: run {
                if (computeds.containsKey(id) || effects.containsKey(id)) throw GraphIrError("`$id` is not a source")
                throw GraphIrError("unknown node `$id`")
            }
        val changed = !jsonEq(src.value, value)
        src.value = freezeGraph(value)
        if (changed) notify(id)
        return freezeGraph(src.value)
    }

    private fun mergeSource(
        id: String,
        value: Any?,
    ): Any? {
        val src =
            sources[id] ?: run {
                if (computeds.containsKey(id) || effects.containsKey(id)) throw GraphIrError("`$id` is not a source")
                throw GraphIrError("unknown node `$id`")
            }
        return setSource(id, foldPolicy(src.policy, src.value, value))
    }

    private fun notify(id: String) {
        for (dep in subs[id].orEmpty().sorted()) {
            val comp = computeds[dep]
            if (comp != null) {
                if (comp.eager || subs[dep].orEmpty().isNotEmpty()) {
                    if (computing.contains(dep)) {
                        comp.dirty = true
                        continue
                    }
                    recompute(dep)
                } else {
                    comp.dirty = true
                }
            } else if (effects.containsKey(dep)) {
                schedule(dep)
            }
        }
    }

    private fun schedule(id: String) {
        if (effects.containsKey(id) && scheduledSet.add(id)) scheduled.add(id)
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            var iterations = 0
            while (scheduled.isNotEmpty()) {
                val id = scheduled.removeFirst()
                scheduledSet.remove(id)
                iterations++
                if (iterations >= drainBudget) {
                    lastDrainExhaustion = iterations
                    return
                }
                if (!effects.containsKey(id)) continue
                try {
                    runEffect(id)
                } catch (err: GraphIrError) {
                    fault = err.message
                    return
                }
            }
        } finally {
            draining = false
        }
    }

    private fun raiseFault() {
        val msg = fault ?: return
        fault = null
        throw GraphIrError(msg)
    }

    private fun runEffect(id: String) {
        val state = effects[id]!!
        begin(id)
        val result =
            try {
                evalStmt(state.body)
            } finally {
                end(id)
            }
        trace.add(GraphEffectRun(id, freezeGraph(result)))
        drain()
    }

    private fun recompute(id: String): Any? {
        val state = computeds[id]!!
        if (!computing.add(id)) throw GraphIrError("cycle in read edges: $id")
        try {
            begin(id)
            val value =
                try {
                    evalExpr(state.expr)
                } finally {
                    end(id)
                }
            val changed = !state.hasValue || !jsonEq(state.cache, value)
            state.cache = freezeGraph(value)
            state.hasValue = true
            state.dirty = false
            if (changed) notify(id)
            return state.cache
        } catch (err: GraphIrError) {
            state.dirty = true
            state.hasValue = false
            state.cache = null
            throw err
        } finally {
            computing.remove(id)
        }
    }

    private fun begin(id: String) {
        for (dep in deps[id].orEmpty()) subs[dep]?.remove(id)
        deps[id] = mutableSetOf()
        frames.add(mutableSetOf())
        stack.add(current)
        current = id
    }

    private fun end(id: String) {
        observed[id] = frames.removeAt(frames.lastIndex)
        current = stack.removeAt(stack.lastIndex)
    }

    private fun note(id: String) {
        if (frames.isEmpty() || current == null) throw GraphIrError("read outside a node evaluation")
        frames.last().add(id)
        subAdd(id, current!!)
        deps[current!!]!!.add(id)
    }

    private fun subAdd(
        src: String,
        reader: String,
    ) {
        subs.getOrPut(src) { mutableSetOf() }.add(reader)
    }

    private fun readRef(id: String): Any? {
        note(id)
        sources[id]?.let { return freezeGraph(it.value) }
        computeds[id]?.let { comp ->
            if (comp.dirty || !comp.hasValue) return freezeGraph(recompute(id))
            return freezeGraph(comp.cache)
        }
        if (effects.containsKey(id)) throw GraphIrError("`$id` is an effect and has no value")
        throw GraphIrError("unknown node `$id`")
    }

    private fun evalExpr(expr: GraphExpr): Any? =
        when (expr.type) {
            "lit" -> freezeGraph(expr.lit)
            "ref" -> readRef(expr.ref!!)
            "object" -> {
                val obj = linkedMapOf<String, Any?>()
                for (field in expr.fields) {
                    if (obj.containsKey(field.key)) throw GraphIrError("duplicate object key `${field.key}`")
                    obj[field.key] = evalExpr(field.value)
                }
                obj
            }
            "call" ->
                when (expr.op) {
                    "and" -> evalAnd(expr.args)
                    "or" -> evalOr(expr.args)
                    "if" -> evalIf(expr.args)
                    "array" -> expr.args.map { evalExpr(it) }
                    else -> evalValues(expr.op!!, expr.args.map { evalExpr(it) })
                }
            else -> throw GraphIrError("unknown expression `${expr.type}`")
        }

    private fun evalAnd(args: List<GraphExpr>): Boolean {
        if (args.size != 2) throw GraphIrError("op `and` has the wrong number of arguments")
        if (!expectBool(evalExpr(args[0]))) return false
        return expectBool(evalExpr(args[1]))
    }

    private fun evalOr(args: List<GraphExpr>): Boolean {
        if (args.size != 2) throw GraphIrError("op `or` has the wrong number of arguments")
        if (expectBool(evalExpr(args[0]))) return true
        return expectBool(evalExpr(args[1]))
    }

    private fun evalIf(args: List<GraphExpr>): Any? {
        if (args.size != 3) throw GraphIrError("op `if` has the wrong number of arguments")
        return evalExpr(if (expectBool(evalExpr(args[0]))) args[1] else args[2])
    }

    private fun evalStmt(stmt: GraphStmt): Any? =
        when (stmt.type) {
            "eval" -> evalExpr(stmt.expr!!)
            "set" -> setSource(stmt.target!!, evalExpr(stmt.expr!!))
            "merge" -> mergeSource(stmt.target!!, evalExpr(stmt.expr!!))
            "seq" -> stmt.steps.map { evalStmt(it) }
            "when" -> {
                if (expectBool(evalExpr(stmt.cond!!))) {
                    evalStmt(stmt.then!!)
                } else if (stmt.otherwise != null) {
                    evalStmt(stmt.otherwise)
                } else {
                    null
                }
            }
            else -> throw GraphIrError("unknown statement `${stmt.type}`")
        }

    companion object {
        fun loadJson(
            text: String,
            drainBudget: Int = GRAPH_DRAIN_BUDGET,
        ): GraphRuntime = lower(GraphIr.fromJsonStr(text), drainBudget)

        fun lower(
            doc: GraphIr,
            drainBudget: Int = GRAPH_DRAIN_BUDGET,
        ): GraphRuntime {
            doc.check()
            val rt = GraphRuntime(doc.clone(), drainBudget)
            rt.installDocument()
            return rt
        }
    }
}

private fun parseGraphJson(text: String): Any? =
    try {
        jsonElementToAny(Json.parseToJsonElement(text))
    } catch (err: GraphIrError) {
        throw err
    } catch (err: Exception) {
        throw GraphIrError("graph document is not JSON: ${err.message}")
    }

private fun jsonElementToAny(el: JsonElement): Any? =
    when (el) {
        is JsonNull -> null
        is JsonArray -> el.map { jsonElementToAny(it) }
        is JsonObject -> el.entries.associate { it.key to jsonElementToAny(it.value) }
        is JsonPrimitive -> primitiveToAny(el)
        else -> throw GraphIrError("value is not JSON")
    }

private fun primitiveToAny(el: JsonPrimitive): Any {
    if (el.isString) return el.content
    val text = el.content
    if (text == "true") return true
    if (text == "false") return false
    if (text.any { it == '.' || it == 'e' || it == 'E' }) {
        val number = text.toDouble()
        if (!number.isFinite()) throw GraphIrError("number $text is outside the graph IR numeric range")
        return number
    }
    text.toLongOrNull()?.let { return it }
    val number = text.toDouble()
    if (!number.isFinite()) throw GraphIrError("number $text is outside the graph IR numeric range")
    return number
}

private fun graphFrom(value: Any?): GraphIr {
    val obj = asMap(value, "graph document")
    allow(obj, "graph document", listOf("kind", "ir", "id", "nodes"))
    val kind = requireString(obj, "kind", "graph document")
    if (kind != "graph") throw GraphIrError("graph document kind must be \"graph\", got `$kind`")
    requireVersion(obj, "graph document")
    val id = optionalId(obj, "id", "graph id")
    val raw = require(obj, "nodes", "graph document")
    if (raw !is List<*>) throw GraphIrError("graph nodes must be an array")
    val nodes = mutableListOf<GraphIrNode>()
    val seen = mutableSetOf<String>()
    for (item in raw) {
        val node = parseNode(item)
        if (!seen.add(node.id)) throw GraphIrError("duplicate node id `${node.id}`")
        nodes.add(node)
    }
    val doc = GraphIr(id, nodes, mutableMapOf())
    doc.reindex()
    doc.check()
    return doc
}

private fun editFrom(value: Any?): GraphEdit {
    val obj = asMap(value, "graph edit")
    allow(obj, "graph edit", listOf("kind", "ir", "ops"))
    val kind = requireString(obj, "kind", "graph edit")
    if (kind != "edit") throw GraphIrError("graph edit kind must be \"edit\", got `$kind`")
    requireVersion(obj, "graph edit")
    val raw = require(obj, "ops", "graph edit")
    if (raw !is List<*>) throw GraphIrError("graph edit ops must be an array")
    if (raw.isEmpty()) throw GraphIrError("graph edit ops must not be empty")
    return GraphEdit(raw.map { parseEdit(it) })
}

private fun asMap(
    value: Any?,
    what: String,
): Map<String, Any?> {
    if (value is Map<*, *>) {
        return value.entries.associate { it.key.toString() to it.value }
    }
    throw GraphIrError("$what must be an object")
}

private fun allow(
    obj: Map<String, Any?>,
    what: String,
    keys: List<String>,
) {
    val allowed = keys.toSet()
    for (key in obj.keys) if (key !in allowed) throw GraphIrError("$what: unknown field `$key`")
}

private fun require(
    obj: Map<String, Any?>,
    field: String,
    what: String,
): Any? {
    if (!obj.containsKey(field)) throw GraphIrError("$what requires `$field`")
    return obj[field]
}

private fun requireString(
    obj: Map<String, Any?>,
    field: String,
    what: String,
): String {
    val value = require(obj, field, what)
    if (value !is String) throw GraphIrError("$what `$field` must be a string, got ${canonicalJson(value)}")
    return value
}

private fun parseId(
    text: String,
    what: String,
): String {
    if (!ID_PATTERN.matches(text) || text.length > 128) throw GraphIrError("$what `$text` is not a graph IR id")
    return text
}

private fun requireVersion(
    obj: Map<String, Any?>,
    what: String,
) {
    val version = requireString(obj, "ir", what)
    if (version != GRAPH_IR_VERSION) throw GraphIrError("unsupported graph IR version `$version`")
}

private fun optionalId(
    obj: Map<String, Any?>,
    field: String,
    what: String,
): String? {
    if (!obj.containsKey(field)) return null
    val value = obj[field]
    if (value !is String) throw GraphIrError("$what must be a string, got ${canonicalJson(value)}")
    return parseId(value, what)
}

private fun parseNode(value: Any?): GraphIrNode {
    val obj = asMap(value, "node")
    val kind = requireString(obj, "kind", "node")
    val id = parseId(requireString(obj, "id", "node"), "node id")
    return when (kind) {
        "source" -> {
            allow(obj, "source", listOf("id", "kind", "init", "policy"))
            if (!obj.containsKey("init")) throw GraphIrError("source `$id` requires `init`")
            val policy = parsePolicy(obj, id)
            checkPolicyInit(policy, obj["init"])
            GraphIrNode(kind = "source", id = id, init = freezeGraph(obj["init"]), policy = policy)
        }
        "computed" -> {
            allow(obj, "computed", listOf("id", "kind", "expr", "eager"))
            GraphIrNode(kind = "computed", id = id, eager = parseEager(obj, id), expr = parseExpr(require(obj, "expr", "computed")))
        }
        "effect" -> {
            allow(obj, "effect", listOf("id", "kind", "body"))
            GraphIrNode(kind = "effect", id = id, body = parseStmt(require(obj, "body", "effect")))
        }
        else -> throw GraphIrError("unknown node kind `$kind`")
    }
}

private fun parsePolicy(
    obj: Map<String, Any?>,
    id: String,
): String {
    if (!obj.containsKey("policy")) return Policy.KEEP_LATEST
    val value = obj["policy"]
    if (value !is String) throw GraphIrError("source `$id` policy must be a string")
    if (value !in POLICIES) throw GraphIrError("unknown source policy `$value`")
    return value
}

private fun parseEager(
    obj: Map<String, Any?>,
    id: String,
): Boolean {
    if (!obj.containsKey("eager")) return false
    val value = obj["eager"]
    if (value !is Boolean) throw GraphIrError("computed `$id` eager must be a boolean")
    return value
}

private fun parseExpr(value: Any?): GraphExpr {
    val obj = asMap(value, "expression")
    allow(obj, "expression", listOf("lit", "ref", "op", "args", "object"))
    val forms = listOf("lit", "ref", "op", "object").count { obj.containsKey(it) }
    if (forms != 1) throw GraphIrError("expression must be exactly one of lit, ref, op, or object")
    if (obj.containsKey("args") && !obj.containsKey("op")) throw GraphIrError("args is only valid on an op expression")
    if (obj.containsKey("lit")) return GraphExpr("lit", lit = freezeGraph(obj["lit"]))
    if (obj.containsKey("ref")) return GraphExpr("ref", ref = parseId(requireString(obj, "ref", "expression"), "ref"))
    if (obj.containsKey("object")) return parseObject(obj["object"])
    val op = requireString(obj, "op", "expression")
    if (op !in UNARY && op !in BINARY && op != "if" && op != "array") throw GraphIrError("unknown op `$op`")
    val raw = require(obj, "args", "op expression")
    if (raw !is List<*>) throw GraphIrError("op args must be an array")
    val ok =
        when {
            op in UNARY -> raw.size == 1
            op == "if" -> raw.size == 3
            op == "array" -> true
            else -> raw.size == 2
        }
    if (!ok) throw GraphIrError("op `$op` has the wrong number of arguments")
    return GraphExpr("call", op = op, args = raw.map { parseExpr(it) })
}

private fun parseObject(value: Any?): GraphExpr {
    if (value !is List<*>) throw GraphIrError("object expression must be an array")
    val fields = mutableListOf<GraphField>()
    val seen = mutableSetOf<String>()
    for (item in value) {
        val obj = asMap(item, "object field")
        allow(obj, "object field", listOf("key", "value"))
        val key = requireString(obj, "key", "object field")
        if (!seen.add(key)) throw GraphIrError("duplicate object key `$key`")
        fields.add(GraphField(key, parseExpr(require(obj, "value", "object field"))))
    }
    return GraphExpr("object", fields = fields)
}

private fun parseStmt(value: Any?): GraphStmt {
    val obj = asMap(value, "statement")
    val verb = requireString(obj, "do", "statement")
    return when (verb) {
        "eval" -> {
            allow(obj, "eval", listOf("do", "expr"))
            GraphStmt("eval", expr = parseExpr(require(obj, "expr", "eval")))
        }
        "set", "merge" -> {
            allow(obj, verb, listOf("do", "target", "expr"))
            GraphStmt(
                verb,
                target = parseId(requireString(obj, "target", verb), "write target"),
                expr = parseExpr(require(obj, "expr", verb)),
            )
        }
        "seq" -> {
            allow(obj, "seq", listOf("do", "steps"))
            val raw = require(obj, "steps", "seq")
            if (raw !is List<*>) throw GraphIrError("seq steps must be an array")
            if (raw.isEmpty()) throw GraphIrError("seq steps must not be empty")
            GraphStmt("seq", steps = raw.map { parseStmt(it) })
        }
        "when" -> {
            allow(obj, "when", listOf("do", "cond", "then", "else"))
            GraphStmt(
                "when",
                cond = parseExpr(require(obj, "cond", "when")),
                then = parseStmt(require(obj, "then", "when")),
                otherwise = if (obj.containsKey("else")) parseStmt(obj["else"]) else null,
            )
        }
        else -> throw GraphIrError("unknown statement `$verb`")
    }
}

private fun parseEdit(value: Any?): EditOp {
    val obj = asMap(value, "edit op")
    val op = requireString(obj, "op", "edit op")
    return when (op) {
        "add", "replace" -> {
            allow(obj, op, listOf("op", "node"))
            EditOp(op, node = parseNode(require(obj, "node", op)))
        }
        "remove" -> {
            allow(obj, "remove", listOf("op", "id"))
            EditOp(op, id = parseId(requireString(obj, "id", "remove"), "remove id"))
        }
        else -> throw GraphIrError("unknown edit op `$op`")
    }
}

private fun edgeSets(node: GraphIrNode): Pair<List<String>, List<String>> {
    val reads = mutableSetOf<String>()
    val writes = mutableSetOf<String>()
    if (node.kind == "computed") collectExpr(node.expr!!, reads)
    if (node.kind == "effect") collectStmt(node.body!!, reads, writes)
    return reads.sorted() to writes.sorted()
}

private fun collectExpr(
    expr: GraphExpr,
    reads: MutableSet<String>,
) {
    when (expr.type) {
        "ref" -> reads.add(expr.ref!!)
        "call" -> expr.args.forEach { collectExpr(it, reads) }
        "object" -> expr.fields.forEach { collectExpr(it.value, reads) }
    }
}

private fun collectStmt(
    stmt: GraphStmt,
    reads: MutableSet<String>,
    writes: MutableSet<String>,
) {
    when (stmt.type) {
        "eval" -> collectExpr(stmt.expr!!, reads)
        "set", "merge" -> {
            writes.add(stmt.target!!)
            collectExpr(stmt.expr!!, reads)
        }
        "seq" -> stmt.steps.forEach { collectStmt(it, reads, writes) }
        "when" -> {
            collectExpr(stmt.cond!!, reads)
            collectStmt(stmt.then!!, reads, writes)
            stmt.otherwise?.let { collectStmt(it, reads, writes) }
        }
    }
}

private fun emitNode(node: GraphIrNode): String =
    when (node.kind) {
        "source" -> {
            val line = "source ${node.id} = ${canonicalJson(node.init)}"
            if (node.policy == Policy.KEEP_LATEST) line else "$line policy ${node.policy}"
        }
        "computed" -> {
            val flag = if (node.eager) " eager" else ""
            "computed ${node.id}$flag = ${emitExpr(node.expr!!)}"
        }
        else -> "effect ${node.id} = ${emitStmt(node.body!!)}"
    }

private fun emitExpr(expr: GraphExpr): String =
    when (expr.type) {
        "lit" -> "lit(${canonicalJson(expr.lit)})"
        "ref" -> "ref(${expr.ref})"
        "call" -> "${expr.op}(${expr.args.joinToString(", ") { emitExpr(it) }})"
        else -> "object(${expr.fields.joinToString(", ") { "${canonicalJson(it.key)}: ${emitExpr(it.value)}" }})"
    }

private fun emitStmt(stmt: GraphStmt): String =
    when (stmt.type) {
        "eval" -> "eval(${emitExpr(stmt.expr!!)})"
        "set" -> "set(${stmt.target}, ${emitExpr(stmt.expr!!)})"
        "merge" -> "merge(${stmt.target}, ${emitExpr(stmt.expr!!)})"
        "seq" -> "seq(${stmt.steps.joinToString(", ") { emitStmt(it) }})"
        else ->
            if (stmt.otherwise == null) {
                "when(${emitExpr(stmt.cond!!)}, ${emitStmt(stmt.then!!)})"
            } else {
                "when(${emitExpr(stmt.cond!!)}, ${emitStmt(stmt.then!!)}, ${emitStmt(stmt.otherwise)})"
            }
    }

private fun nodeJson(node: GraphIrNode): Map<String, Any?> =
    when (node.kind) {
        "source" ->
            linkedMapOf<String, Any?>("id" to node.id, "kind" to "source", "init" to freezeGraph(node.init)).also {
                if (node.policy != Policy.KEEP_LATEST) it["policy"] = node.policy
            }
        "computed" ->
            linkedMapOf<String, Any?>("id" to node.id, "kind" to "computed", "expr" to exprJson(node.expr!!)).also {
                if (node.eager) it["eager"] = true
            }
        else -> linkedMapOf("id" to node.id, "kind" to "effect", "body" to stmtJson(node.body!!))
    }

private fun exprJson(expr: GraphExpr): Map<String, Any?> =
    when (expr.type) {
        "lit" -> mapOf("lit" to freezeGraph(expr.lit))
        "ref" -> mapOf("ref" to expr.ref)
        "call" -> mapOf("op" to expr.op, "args" to expr.args.map { exprJson(it) })
        else -> mapOf("object" to expr.fields.map { mapOf("key" to it.key, "value" to exprJson(it.value)) })
    }

private fun stmtJson(stmt: GraphStmt): Map<String, Any?> =
    when (stmt.type) {
        "eval" -> mapOf("do" to "eval", "expr" to exprJson(stmt.expr!!))
        "set", "merge" -> mapOf("do" to stmt.type, "target" to stmt.target, "expr" to exprJson(stmt.expr!!))
        "seq" -> mapOf("do" to "seq", "steps" to stmt.steps.map { stmtJson(it) })
        else ->
            linkedMapOf<String, Any?>(
                "do" to "when",
                "cond" to exprJson(stmt.cond!!),
                "then" to stmtJson(stmt.then!!),
            ).also { if (stmt.otherwise != null) it["else"] = stmtJson(stmt.otherwise) }
    }

/** Compact JSON with object keys sorted. Integers stay integers. */
fun canonicalJson(value: Any?): String =
    when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Long -> value.toString()
        is Int -> value.toString()
        is Double -> {
            if (!value.isFinite()) throw GraphIrError("non-finite number")
            JsonPrimitive(value).toString()
        }
        is String -> JsonPrimitive(value).toString()
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        is Map<*, *> -> {
            val keys = value.keys.map { it.toString() }.sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                "${JsonPrimitive(key)}:${canonicalJson(value[key])}"
            }
        }
        else -> throw GraphIrError("value is not JSON")
    }

fun freezeGraph(value: Any?): Any? =
    when (value) {
        null, is Boolean, is String, is Long, is Double -> value
        is Int -> value.toLong()
        is List<*> -> value.map { freezeGraph(it) }
        is Map<*, *> -> value.entries.associate { it.key.toString() to freezeGraph(it.value) }
        else -> throw GraphIrError("value is not JSON")
    }

fun jsonEq(
    left: Any?,
    right: Any?,
): Boolean {
    if (left is Boolean || right is Boolean) return left is Boolean && right is Boolean && left == right
    if (left is Long || right is Long || left is Int || right is Int) {
        val a = if (left is Int) left.toLong() else left
        val b = if (right is Int) right.toLong() else right
        return a is Long && b is Long && a == b
    }
    if (left is Double || right is Double) return left is Double && right is Double && left == right
    if (left is String || right is String) return left is String && right is String && left == right
    if (left == null || right == null) return left == null && right == null
    if (left is List<*> && right is List<*>) {
        if (left.size != right.size) return false
        return left.indices.all { jsonEq(left[it], right[it]) }
    }
    if (left is Map<*, *> && right is Map<*, *>) {
        if (left.size != right.size) return false
        return left.keys.all { key -> right.containsKey(key) && jsonEq(left[key], right[key]) }
    }
    return false
}

private fun expectBool(value: Any?): Boolean {
    if (value is Boolean) return value
    throw GraphIrError("expected a boolean, got ${canonicalJson(value)}")
}

private data class Num(
    val kind: String,
    val integer: Long,
    val real: Double,
)

private fun asNum(value: Any?): Num =
    when (value) {
        is Long -> Num("int", value, 0.0)
        is Int -> Num("int", value.toLong(), 0.0)
        is Double -> {
            if (!value.isFinite()) throw GraphIrError("non-finite number")
            Num("float", 0, value)
        }
        else -> throw GraphIrError("expected a number, got ${canonicalJson(value)}")
    }

private fun checkPolicyInit(
    policy: String,
    init: Any?,
) {
    when (policy) {
        Policy.SUM, Policy.MAX, Policy.MIN -> asNum(init)
        Policy.AND, Policy.OR -> expectBool(init)
    }
}

private fun canonicalF64(number: Double): Any {
    if (!number.isFinite()) throw GraphIrError("non-finite number")
    if (number == kotlin.math.truncate(number) && number >= Long.MIN_VALUE.toDouble() && number < 9223372036854775808.0) {
        val integer = number.toLong()
        if (integer.toDouble() == number) return integer
    }
    return number
}

private fun addInt(
    a: Long,
    b: Long,
): Long {
    val result = a + b
    val overflow = ((a xor result) and (b xor result)) < 0
    if (overflow) throw GraphIrError("integer overflow")
    return result
}

private fun subInt(
    a: Long,
    b: Long,
): Long {
    val result = a - b
    val overflow = ((a xor b) and (a xor result)) < 0
    if (overflow) throw GraphIrError("integer overflow")
    return result
}

private fun mulInt(
    a: Long,
    b: Long,
): Long {
    if (a == 0L || b == 0L) return 0L
    val result = a * b
    if (b == -1L && a == Long.MIN_VALUE) throw GraphIrError("integer overflow")
    if (result / b != a) throw GraphIrError("integer overflow")
    return result
}

private fun ordering(
    left: Any?,
    right: Any?,
): Int {
    val a = asNum(left)
    val b = asNum(right)
    if (a.kind == "int" && b.kind == "int") return a.integer.compareTo(b.integer)
    val lf = if (a.kind == "int") a.integer.toDouble() else a.real
    val rf = if (b.kind == "int") b.integer.toDouble() else b.real
    return lf.compareTo(rf)
}

private fun numBin(
    op: String,
    left: Any?,
    right: Any?,
): Any {
    val a = asNum(left)
    val b = asNum(right)
    if (op != "div" && a.kind == "int" && b.kind == "int") {
        return when (op) {
            "add" -> addInt(a.integer, b.integer)
            "sub" -> subInt(a.integer, b.integer)
            "mul" -> mulInt(a.integer, b.integer)
            else -> throw GraphIrError("op `$op` is not an integer operator")
        }
    }
    val lf = if (a.kind == "int") a.integer.toDouble() else a.real
    val rf = if (b.kind == "int") b.integer.toDouble() else b.real
    if (op == "div" && rf == 0.0) throw GraphIrError("division by zero")
    val value =
        when (op) {
            "add" -> lf + rf
            "sub" -> lf - rf
            "mul" -> lf * rf
            "div" -> lf / rf
            else -> throw GraphIrError("op `$op` is not a numeric operator")
        }
    return canonicalF64(value)
}

private fun evalValues(
    op: String,
    values: List<Any?>,
): Any? {
    fun one(): Any? {
        if (values.size != 1) throw GraphIrError("op `$op` has the wrong number of arguments")
        return values[0]
    }
    fun pair(): Pair<Any?, Any?> {
        if (values.size != 2) throw GraphIrError("op `$op` has the wrong number of arguments")
        return values[0] to values[1]
    }
    return when (op) {
        "neg" -> {
            val num = asNum(one())
            if (num.kind == "int") {
                if (num.integer == Long.MIN_VALUE) throw GraphIrError("integer overflow")
                -num.integer
            } else {
                canonicalF64(-num.real)
            }
        }
        "not" -> !expectBool(one())
        "len" -> lenValue(one())
        "add", "sub", "mul", "div" -> {
            val (left, right) = pair()
            numBin(op, left, right)
        }
        "mod" -> {
            val (left, right) = pair()
            val a = asNum(left)
            val b = asNum(right)
            if (a.kind != "int" || b.kind != "int") throw GraphIrError("mod requires integers")
            if (b.integer == 0L) throw GraphIrError("division by zero")
            a.integer % b.integer
        }
        "min", "max" -> {
            val (left, right) = pair()
            val order = ordering(left, right)
            if (op == "max") if (order >= 0) left else right else if (order <= 0) {
                left
            } else {
                right
            }
        }
        "eq", "ne" -> {
            val (left, right) = pair()
            val equal = jsonEq(left, right)
            if (op == "ne") !equal else equal
        }
        "lt", "le", "gt", "ge" -> {
            val (left, right) = pair()
            val order = ordering(left, right)
            when (op) {
                "lt" -> order < 0
                "le" -> order <= 0
                "gt" -> order > 0
                else -> order >= 0
            }
        }
        "concat" -> {
            val (left, right) = pair()
            if (left !is String || right !is String) {
                throw GraphIrError("concat requires strings, got ${canonicalJson(left)} and ${canonicalJson(right)}")
            }
            left + right
        }
        "get" -> {
            val (target, key) = pair()
            evalGet(target, key)
        }
        else -> throw GraphIrError("op `$op` is not an eager operator")
    }
}

private fun lenValue(value: Any?): Long =
    when (value) {
        is String -> value.codePointCount(0, value.length).toLong()
        is List<*> -> value.size.toLong()
        is Map<*, *> -> value.size.toLong()
        else -> throw GraphIrError("len requires a string, array, or object, got ${canonicalJson(value)}")
    }

private fun evalGet(
    target: Any?,
    key: Any?,
): Any? {
    if (target is List<*>) {
        val index = asNum(key)
        if (index.kind != "int") throw GraphIrError("array index must be an integer")
        if (index.integer < 0) throw GraphIrError("array index must be >= 0")
        if (index.integer >= target.size) throw GraphIrError("index ${index.integer} out of range")
        return freezeGraph(target[index.integer.toInt()])
    }
    if (target is Map<*, *>) {
        if (key !is String) throw GraphIrError("object key must be a string, got ${canonicalJson(key)}")
        if (!target.containsKey(key)) throw GraphIrError("missing key `$key`")
        return freezeGraph(target[key])
    }
    throw GraphIrError("get requires an array or object, got ${canonicalJson(target)}")
}

private fun foldPolicy(
    policy: String,
    old: Any?,
    op: Any?,
): Any? =
    when (policy) {
        Policy.KEEP_LATEST -> freezeGraph(op)
        Policy.SUM -> numBin("add", old, op)
        Policy.MAX, Policy.MIN -> {
            val order = ordering(old, op)
            val chosen = if (policy == Policy.MAX) order >= 0 else order <= 0
            freezeGraph(if (chosen) old else op)
        }
        Policy.AND -> expectBool(old) && expectBool(op)
        Policy.OR -> expectBool(old) || expectBool(op)
        else -> throw GraphIrError("unknown source policy `$policy`")
    }
