package io.github.lazily

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Host-neutral view compute document. A signal is a [Source], each standing
 * text/hidden/disabled expression is a guarded [Computed], and an optional
 * chart is one [StateChart] cell. Internal: conformance replay, not a widget
 * toolkit and not part of the published core surface.
 */
internal class View private constructor(
    private val ctx: Context,
    private val root: String,
    private val nodes: Map<String, Node>,
    private val signals: Map<String, Source<ViewValue>>,
    private val exprs: Map<String, Computed<ViewValue>>,
    private val effects: Map<String, Map<String, JsonElement>>,
    private val chart: StateChart?,
    val subscription: Effect,
) {
    private var payload: ViewValue? = null
    private var inEffect = false

    private class Node(
        val role: String,
        val children: List<String>,
        val flow: String?,
    )

    companion object {
        private val roles = setOf("text", "button", "field", "list", "region")
        private val flows = setOf("row", "column", "stack")
        private val aligns = setOf("start", "center", "end", "stretch")
        private val events = setOf("activate", "change", "submit")
        private val exprOps = setOf("lit", "ref", "call", "if", "eq", "concat", "trim")
        private val effectOps = setOf("set", "batch", "send")
        private val calls = setOf("event", "not", "add", "len")

        fun install(ctx: Context, doc: JsonElement): View {
            val obj = obj(doc, "view")
            allow(obj, "view", "id", "root", "signals", "nodes", "chart")
            val id = str(obj["id"], "view id")
            if (id.isEmpty()) error("view id must be a non-empty string")
            val root = str(obj["root"], "view root")
            if (root.isEmpty()) error("view root must be a non-empty string")
            val signalsDoc = obj(obj["signals"], "view signals")
            val signalIds = LinkedHashMap<String, ViewValue>()
            for ((sid, raw) in signalsDoc) {
                if (sid.isEmpty()) error("signal id must be a non-empty string")
                val sig = obj(raw, "signal $sid")
                allow(sig, "signal $sid", "initial")
                signalIds[sid] = literal(sig["initial"] ?: error("signal $sid initial is required"))
            }
            val hasChart = obj.containsKey("chart")
            val chartEl = if (hasChart) obj["chart"] ?: JsonNull else null
            if (hasChart && chartEl !is JsonObject) error("chart must be a statechart document")
            if (hasChart) ChartDef.fromJson(chartEl!!)
            val nodesDoc = obj(obj["nodes"], "view nodes")
            if (nodesDoc.isEmpty()) error("view nodes must contain at least one node")
            val nodes = LinkedHashMap<String, Node>()
            val effects = LinkedHashMap<String, Map<String, JsonElement>>()
            val exprDocs = ArrayList<Pair<String, JsonElement>>()
            for ((nid, raw) in nodesDoc) {
                if (nid.isEmpty()) error("node id must be a non-empty string")
                val node = obj(raw, "node $nid")
                allow(node, "node $nid", "role", "children", "layout", "text", "bind", "hidden", "disabled", "effects")
                val role = str(node["role"], "node $nid role")
                if (role !in roles) error("unknown role `$role`")
                val children = if (node.containsKey("children")) children(nid, node.getValue("children")) else emptyList()
                val flow = if (node.containsKey("layout")) layout(nid, node.getValue("layout")) else null
                if (node.containsKey("bind")) {
                    val bind = str(node["bind"], "bind")
                    if (bind.isEmpty() || bind !in signalIds) error("bind names unknown signal `$bind`")
                }
                node["text"]?.let { exprDocs += "$nid.text" to standingText(it, signalIds.keys) }
                node["hidden"]?.let { exprDocs += "$nid.hidden" to standingFlag("hidden", it, signalIds.keys) }
                node["disabled"]?.let { exprDocs += "$nid.disabled" to standingFlag("disabled", it, signalIds.keys) }
                if (node.containsKey("effects")) {
                    val effectMap = obj(node.getValue("effects"), "node $nid effects")
                    allow(effectMap, "node $nid effects", "activate", "change", "submit")
                    val stored = LinkedHashMap<String, JsonElement>()
                    for ((event, effect) in effectMap) {
                        validateEffect(effect, signalIds.keys, hasChart)
                        stored[event] = effect
                    }
                    if (stored.isNotEmpty()) effects[nid] = stored
                }
                nodes[nid] = Node(role, children, flow)
            }
            if (root !in nodes) error("root `$root` is not a node")
            checkTree(root, nodes)
            val sources = LinkedHashMap<String, Source<ViewValue>>()
            for ((sid, initial) in signalIds) sources[sid] = ctx.source(initial)
            fun readBare(sid: String): ViewValue {
                val src = sources[sid] ?: error("unknown signal `$sid`")
                return ctx.get(src)
            }
            for ((key, expr) in exprDocs) {
                try {
                    evalExpr(expr, ::readBare, null, false)
                } catch (err: IllegalStateException) {
                    error("$key: ${err.message}")
                }
            }
            val exprs = LinkedHashMap<String, Computed<ViewValue>>()
            for ((key, expr) in exprDocs) {
                exprs[key] = ctx.computed {
                    val cx = this
                    evalExpr(expr, { sid -> cx.get(sources[sid] ?: error("unknown signal `$sid`")) }, null, false)
                }
            }
            val chart = if (hasChart) StateChart(ctx, ChartDef.fromJson(chartEl!!)) else null
            val subscription = ctx.effect {
                if (exprs.isNotEmpty()) exprs.values.forEach { get(it) } else sources.values.forEach { get(it) }
                null
            }
            return View(ctx, root, nodes, sources, exprs, effects, chart, subscription)
        }

        private fun obj(value: JsonElement?, what: String): JsonObject {
            if (value !is JsonObject) error("$what must be an object")
            return value
        }

        private fun allow(value: JsonObject, what: String, vararg keys: String) {
            val allowed = keys.toSet()
            for (key in value.keys) if (key !in allowed) error("$what has unknown field `$key`")
        }

        private fun str(value: JsonElement?, what: String): String {
            val prim = value as? JsonPrimitive ?: error("$what must be a string")
            if (!prim.isString) error("$what must be a string")
            return prim.content
        }

        private fun literal(value: JsonElement): ViewValue = when (value) {
            is JsonNull -> ViewValue.Null
            is JsonPrimitive -> when {
                value.isString -> ViewValue.Str(value.content)
                value.content == "true" || value.content == "false" ->
                    ViewValue.Bool(value.booleanOrNull ?: error("value must be null, boolean, integer, or string"))
                value.longOrNull != null && '.' !in value.content && 'e' !in value.content && 'E' !in value.content ->
                    ViewValue.Int(value.longOrNull!!)
                else -> error("value must be null, boolean, integer, or string")
            }
            else -> error("value must be null, boolean, integer, or string")
        }

        private fun children(nid: String, value: JsonElement): List<String> {
            val arr = value as? JsonArray ?: error("node `$nid` children must be an array")
            val seen = HashSet<String>()
            return arr.map { child ->
                val text = (child as? JsonPrimitive)?.contentOrNull?.takeIf { child.isString && it.isNotEmpty() }
                    ?: error("node `$nid` children must be non-empty strings")
                if (!seen.add(text)) error("node `$nid` repeats child `$text`")
                text
            }
        }

        private fun layout(nid: String, value: JsonElement): String? {
            val obj = obj(value, "node $nid layout")
            allow(obj, "node $nid layout", "flow", "align", "grow")
            val flow = obj["flow"]?.let { text ->
                val name = str(text, "flow")
                if (name !in flows) error("unknown flow `$name`")
                name
            }
            obj["align"]?.let { text ->
                val name = str(text, "align")
                if (name !in aligns) error("unknown align `$name`")
            }
            obj["grow"]?.let { raw ->
                val grow = literal(raw)
                if (grow !is ViewValue.Int || grow.n < 0) error("node `$nid` layout.grow must be an integer >= 0")
            }
            return flow
        }

        private fun standingText(value: JsonElement, signals: Set<String>): JsonElement {
            if (value is JsonPrimitive && value.isString) {
                return JsonObject(mapOf("op" to JsonPrimitive("lit"), "args" to JsonArray(listOf(value))))
            }
            if (value is JsonObject) {
                validateExpr(value, signals, false)
                return value
            }
            error("text must be a string or an expression")
        }

        private fun standingFlag(field: String, value: JsonElement, signals: Set<String>): JsonElement {
            if (value is JsonPrimitive && !value.isString && value.booleanOrNull != null) {
                return JsonObject(mapOf("op" to JsonPrimitive("lit"), "args" to JsonArray(listOf(value))))
            }
            if (value is JsonObject) {
                validateExpr(value, signals, false)
                return value
            }
            error("$field must be a boolean or an expression")
        }

        private fun validateEffect(effect: JsonElement, signals: Set<String>, hasChart: Boolean) {
            val obj = obj(effect, "effect")
            allow(obj, "effect", "op", "args")
            val op = str(obj["op"], "effect op")
            if (op !in effectOps) error("unknown effect op `$op`")
            val args = obj["args"] as? JsonArray ?: error("effect args must be an array")
            when (op) {
                "set" -> {
                    if (args.size != 2) error("set takes a signal id and a value")
                    val sid = str(args[0], "set signal")
                    if (sid !in signals) error("unknown signal `$sid`")
                    validateArg(args[1], signals, true)
                }
                "send" -> {
                    if (!hasChart) error("send requires a chart")
                    if (args.size != 1 && args.size != 2) error("send takes an event name and an optional payload")
                    str(args[0], "send event")
                    if (args.size == 2) validateArg(args[1], signals, true)
                }
                "batch" -> {
                    if (args.isEmpty()) error("batch takes one or more effects")
                    args.forEach { validateEffect(it, signals, hasChart) }
                }
            }
        }

        private fun validateArg(value: JsonElement, signals: Set<String>, inEffect: Boolean) {
            if (value is JsonObject) validateExpr(value, signals, inEffect) else literal(value)
        }

        private fun validateExpr(expr: JsonElement, signals: Set<String>, inEffect: Boolean) {
            val obj = obj(expr, "expression")
            allow(obj, "expression", "op", "args")
            val op = str(obj["op"], "expression op")
            if (op !in exprOps) error("unknown expression op `$op`")
            val args = obj["args"] as? JsonArray ?: error("expression args must be an array")
            when (op) {
                "lit" -> {
                    if (args.size != 1) error("lit takes one literal")
                    literal(args[0])
                }
                "ref" -> {
                    if (args.size != 1) error("ref takes one signal id")
                    val sid = str(args[0], "ref")
                    if (sid !in signals) error("unknown signal `$sid`")
                }
                "call" -> validateCall(args, signals, inEffect)
                "if" -> {
                    if (args.size != 3) error("if takes three arguments")
                    args.forEach { validateArg(it, signals, inEffect) }
                }
                "eq" -> {
                    if (args.size != 2) error("eq takes two arguments")
                    args.forEach { validateArg(it, signals, inEffect) }
                }
                "concat" -> args.forEach { validateArg(it, signals, inEffect) }
                "trim" -> {
                    if (args.size != 1) error("trim takes one value")
                    validateArg(args[0], signals, inEffect)
                }
            }
        }

        private fun validateCall(args: JsonArray, signals: Set<String>, inEffect: Boolean) {
            if (args.isEmpty()) error("call takes a name")
            val name = str(args[0], "call")
            if (name !in calls) error("unknown call `$name`")
            val rest = args.drop(1)
            when (name) {
                "event" -> {
                    if (rest.isNotEmpty()) error("event takes no arguments")
                    if (!inEffect) error("event is illegal outside an effect")
                }
                "not" -> if (rest.size != 1) error("not takes one boolean")
                "add" -> if (rest.size < 2) error("add takes two or more integers")
                "len" -> if (rest.size != 1) error("len takes one string")
            }
            if (name != "event") rest.forEach { validateArg(it, signals, inEffect) }
        }

        private fun checkTree(root: String, nodes: Map<String, Node>) {
            val stack = HashSet<String>()
            val done = HashSet<String>()
            fun walk(id: String) {
                if (id in stack) error("children cycle at `$id`")
                if (id in done) error("node `$id` has more than one parent")
                stack += id
                for (child in nodes.getValue(id).children) {
                    if (child !in nodes) error("children name unknown node `$child`")
                    walk(child)
                }
                stack -= id
                done += id
            }
            walk(root)
            if (done.size != nodes.size) error("a node is not reachable from root")
        }

        private fun evalExpr(
            expr: JsonElement,
            read: (String) -> ViewValue,
            payload: ViewValue?,
            inEffect: Boolean,
        ): ViewValue {
            val obj = expr.jsonObject
            val op = (obj["op"] as JsonPrimitive).content
            val args = obj["args"] as JsonArray
            return when (op) {
                "lit" -> literal(args[0])
                "ref" -> read((args[0] as JsonPrimitive).content)
                "call" -> evalCall(args, read, payload, inEffect)
                "if" -> {
                    val cond = evalArg(args[0], read, payload, inEffect)
                    if (cond !is ViewValue.Bool) error("if condition must be a boolean")
                    evalArg(if (cond.v) args[1] else args[2], read, payload, inEffect)
                }
                "eq" -> {
                    val left = evalArg(args[0], read, payload, inEffect)
                    val right = evalArg(args[1], read, payload, inEffect)
                    ViewValue.Bool(left == right)
                }
                "concat" -> ViewValue.Str(args.joinToString("") { stringify(evalArg(it, read, payload, inEffect)) })
                "trim" -> ViewValue.Str(stringify(evalArg(args[0], read, payload, inEffect)).trim())
                else -> error("unknown expression op `$op`")
            }
        }

        private fun evalCall(
            args: JsonArray,
            read: (String) -> ViewValue,
            payload: ViewValue?,
            inEffect: Boolean,
        ): ViewValue {
            val name = (args[0] as JsonPrimitive).content
            val rest = args.drop(1)
            return when (name) {
                "event" -> payload ?: error("event is illegal outside an effect")
                "not" -> {
                    val value = evalArg(rest[0], read, payload, inEffect)
                    if (value !is ViewValue.Bool) error("not expects a boolean")
                    ViewValue.Bool(!value.v)
                }
                "add" -> {
                    var sum = 0L
                    for (arg in rest) {
                        val value = evalArg(arg, read, payload, inEffect)
                        if (value !is ViewValue.Int) error("add expects integers")
                        val next = sum + value.n
                        if ((value.n > 0 && next < sum) || (value.n < 0 && next > sum)) error("add overflow")
                        sum = next
                    }
                    ViewValue.Int(sum)
                }
                "len" -> {
                    val value = evalArg(rest[0], read, payload, inEffect)
                    if (value !is ViewValue.Str) error("len expects a string")
                    ViewValue.Int(value.v.codePointCount(0, value.v.length).toLong())
                }
                else -> error("unknown call `$name`")
            }
        }

        private fun evalArg(
            value: JsonElement,
            read: (String) -> ViewValue,
            payload: ViewValue?,
            inEffect: Boolean,
        ): ViewValue = if (value is JsonObject) evalExpr(value, read, payload, inEffect) else literal(value)

        private fun stringify(value: ViewValue): String = when (value) {
            ViewValue.Null -> ""
            is ViewValue.Bool -> if (value.v) "true" else "false"
            is ViewValue.Int -> value.n.toString()
            is ViewValue.Str -> value.v
        }
    }

    fun signalsJson(): Map<String, Any?> = signals.mapValues { (_, src) -> ctx.get(src).any() }

    fun expressionsJson(): Map<String, Any?> = exprs.mapValues { (_, expr) -> ctx.get(expr).any() }

    fun layoutJson(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        fun walk(id: String) {
            val node = nodes.getValue(id)
            out += mapOf(
                "id" to id,
                "role" to node.role,
                "children" to node.children,
                "flow" to node.flow,
            )
            node.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    fun chartJson(): List<String>? = chart?.activeLeaves(ctx)

    fun dispatchJson(node: String, event: String, value: JsonElement): List<Map<String, Any?>> {
        if (event !in events) error("unknown event `$event`")
        if (node !in nodes) error("unknown node `$node`")
        val effect = effects[node]?.get(event) ?: return emptyList()
        payload = literal(value)
        inEffect = true
        try {
            val trace = ArrayList<Map<String, Any?>>()
            exec(effect, trace)
            return trace
        } finally {
            payload = null
            inEffect = false
        }
    }

    private fun exec(effect: JsonElement, trace: MutableList<Map<String, Any?>>) {
        val obj = effect.jsonObject
        val op = (obj["op"] as JsonPrimitive).content
        val args = obj["args"] as JsonArray
        when (op) {
            "set" -> {
                val sid = (args[0] as JsonPrimitive).content
                val src = signals[sid] ?: error("unknown signal `$sid`")
                val next = evalArg(args[1], { id -> ctx.get(signals[id] ?: error("unknown signal `$id`")) }, payload, true)
                src.set(ctx, next)
                trace += mapOf("op" to "set", "signal" to sid, "value" to next.any())
            }
            "send" -> {
                val chart = chart ?: error("send requires a chart")
                val name = (args[0] as JsonPrimitive).content
                val record = LinkedHashMap<String, Any?>()
                record["op"] = "send"
                record["event"] = name
                if (args.size == 2) {
                    val sent = evalArg(args[1], { id -> ctx.get(signals[id] ?: error("unknown signal `$id`")) }, payload, true)
                    record["payload"] = sent.any()
                }
                chart.send(ctx, name, emptyMap())
                trace += record
            }
            "batch" -> args.forEach { exec(it, trace) }
            else -> error("unknown effect op `$op`")
        }
    }
}

internal sealed class ViewValue {
    data object Null : ViewValue()
    data class Bool(val v: Boolean) : ViewValue()
    data class Int(val n: Long) : ViewValue()
    data class Str(val v: String) : ViewValue()

    fun any(): Any? = when (this) {
        Null -> null
        is Bool -> v
        is Int -> n
        is Str -> v
    }
}
