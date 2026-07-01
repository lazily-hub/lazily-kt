package io.github.lazily

import java.util.TreeSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Full Harel/SCXML state charts — native Kotlin, conforming to
 * `lazily-spec/docs/state-charts.md` and the formal model in
 * `lazily-formal/LazilyFormal/StateChart.lean`.
 *
 * A chart is **compute, not protocol**: it is never serialized as a distinct
 * wire kind. The active configuration lives in a [CellHandle], so any
 * slot/signal/effect reading [StateChart.configuration], [StateChart.activeLeaves],
 * or [StateChart.matches] is invalidated on a real transition; a no-op
 * self-transition is suppressed by the cell's `==` (PartialEq) guard (see the
 * spec's "Self-transitions" section).
 *
 * Implemented subset (per the spec's implementation-status note): compound
 * states, orthogonal (parallel) regions, shallow + deep history, entry/exit/
 * transition actions, named guards, external + internal transitions. Extended
 * state `{"expr": …}` guards and `run` actions are rejected explicitly; `final`
 * states are accepted as leaves without raising completion (`done`) events.
 */

// -- Definitions --------------------------------------------------------------

/** Kind of a state node — mirrors `LazilyFormal.StateChart.Kind`. */
internal sealed interface Kind {
    data object Atomic : Kind
    data object Compound : Kind
    data object Parallel : Kind
    data object Final : Kind
    /** `deep = true` → deep history; `deep = false` → shallow history. */
    data class History(val deep: Boolean) : Kind
}

internal data class Transition(
    val target: String,
    val guard: String? = null,
    val action: List<String> = emptyList(),
    val internal: Boolean = false,
)

internal data class StateDef(
    val parent: String?,
    val kind: Kind,
    val initial: String?,
    val default: String?,
    val transitions: Map<String, Transition>,
    val entry: List<String>,
    val exit: List<String>,
)

/** A history recording for a region exited at least once. */
internal sealed interface Recording {
    /** Direct child of the region that was active (shallow). */
    class Shallow(val child: String) : Recording
    /** Full active sub-configuration below the region (deep). */
    class Deep(val set: TreeSet<String>) : Recording
}

private fun Kind.isLeaf(): Boolean = this is Kind.Atomic || this is Kind.Final

// -- Chart definition ---------------------------------------------------------

/**
 * A parsed, immutable chart definition. The node-labeled functions of the
 * declarative form, materialized as maps for deterministic descent.
 */
class ChartDef internal constructor(
    internal val states: Map<String, StateDef>,
    internal val children: Map<String, MutableList<String>>,
    internal val order: Map<String, Int>,
    internal val depths: Map<String, Int>,
    internal val root: String,
) {
    /** Parse a chart definition from the declarative JSON form. */
    companion object {
        fun fromJson(value: JsonElement): ChartDef {
            val obj = value as? JsonObject
                ?: error("chart must be a JSON object")
            // Validates chart.initial is present; descent uses each compound's
            // own `initial` from the root, so the value itself is not stored.
            obj["initial"]?.jsonPrimitive?.contentOrNull
                ?: error("chart.initial is required")

            val statesObj = obj["states"] as? JsonObject
                ?: error("chart.states is required")

            val states = LinkedHashMap<String, StateDef>()
            val order = HashMap<String, Int>()
            for ((idx, entry) in statesObj.entries.withIndex()) {
                order[entry.key] = idx
                states[entry.key] = parseState(entry.key, entry.value)
            }

            val children = LinkedHashMap<String, MutableList<String>>()
            var root: String? = null
            for ((id, def) in states) {
                val p = def.parent
                if (p != null) {
                    children.getOrPut(p) { mutableListOf() }.add(id)
                } else {
                    check(root == null) { "chart has more than one root (parent-less state)" }
                    root = id
                }
            }
            // Sort children by document order for deterministic parallel descent.
            for (kids in children.values) kids.sortBy { order[it] ?: Int.MAX_VALUE }
            val rootId = root ?: error("chart has no root (parent-less state)")

            val depths = HashMap<String, Int>()
            computeDepth(states, rootId, 0, depths)

            return ChartDef(states, children, order, depths, rootId)
        }
    }

    internal fun kind(id: String): Kind = states[id]?.kind ?: Kind.Atomic

    /** Ancestors of [id] inclusive, `[id, …, root]`. */
    internal fun ancestorsInclusive(id: String): List<String> {
        val out = ArrayList<String>()
        var cur: String? = id
        while (cur != null) {
            out.add(cur)
            cur = states[cur]?.parent
        }
        return out
    }

    /** Lowest common ancestor (inclusive) of [a] and [b]; falls back to root. */
    internal fun lca(a: String, b: String): String {
        val ancA = ancestorsInclusive(a).toHashSet()
        for (cid in ancestorsInclusive(b)) if (cid in ancA) return cid
        return root
    }

    /** `true` iff [desc] is a proper descendant of [anc]. */
    internal fun isProperDescendant(desc: String, anc: String): Boolean =
        desc != anc && ancestorsInclusive(desc).any { it == anc }

    internal fun depth(id: String): Int = depths[id] ?: 0
}

private fun parseState(id: String, raw: JsonElement): StateDef {
    val obj = raw as? JsonObject ?: error("state $id must be an object")
    val parent = obj["parent"]?.jsonPrimitive?.contentOrNull
    val initial = obj["initial"]?.jsonPrimitive?.contentOrNull
    val default = obj["default"]?.jsonPrimitive?.contentOrNull

    if (obj["run"] != null) {
        error("state $id uses `run` actions, which are not supported (rejecting explicitly per spec)")
    }

    val kind: Kind = when {
        obj["history"]?.jsonPrimitive?.contentOrNull != null -> when (obj.getValue("history").jsonPrimitive.content) {
            "shallow" -> Kind.History(deep = false)
            "deep" -> Kind.History(deep = true)
            else -> error("state $id: unknown history kind")
        }
        obj["parallel"]?.jsonPrimitive?.booleanOrNull == true -> Kind.Parallel
        obj["kind"]?.jsonPrimitive?.contentOrNull == "final" -> Kind.Final
        obj.contains("initial") -> Kind.Compound
        else -> Kind.Atomic
    }

    val entry = parseActionList(obj["entry"])
    val exit = parseActionList(obj["exit"])

    val transitions = HashMap<String, Transition>()
    (obj["on"] as? JsonObject)?.forEach { (event, rawT) ->
        transitions[event] = parseTransition(rawT)
    }

    return StateDef(parent, kind, initial, default, transitions, entry, exit)
}

private fun parseActionList(raw: JsonElement?): List<String> = when (raw) {
    null -> emptyList()
    else -> raw.jsonArray.map { v ->
        v.jsonPrimitive.contentOrNull
            ?: error("action must be a string (object-form actions are rejected explicitly per spec)")
    }
}

private fun parseTransition(raw: JsonElement): Transition = when (raw) {
    is JsonPrimitive -> Transition(target = raw.content)
    is JsonObject -> {
        val target = raw["target"]?.jsonPrimitive?.contentOrNull
            ?: error("transition requires `target`")
        val guard = when (raw["guard"]) {
            null -> null
            is JsonPrimitive -> raw.getValue("guard").jsonPrimitive.content
            is JsonObject -> error(
                "context-expression `{expr: …}` guards are not supported (rejecting explicitly per spec)"
            )
            else -> error("guard must be a string")
        }
        Transition(
            target = target,
            guard = guard,
            action = parseActionList(raw["action"]),
            internal = raw["internal"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
    else -> error("transition must be a string or object")
}

private fun computeDepth(
    states: Map<String, StateDef>,
    id: String,
    current: Int,
    out: MutableMap<String, Int>,
) {
    out[id] = current
    val next = states.entries.filter { it.value.parent == id }.map { it.key }
    for (child in next) computeDepth(states, child, current + 1, out)
}

// -- Reactive chart -----------------------------------------------------------

/**
 * A reactive full-Harel state chart backed by a configuration cell. Mirrors
 * `StateChart` in lazily-rs and `LazilyFormal.StateChart.send` — deterministic
 * by construction (`send` is a total function of `(chart, configuration,
 * history, guards, event)`).
 *
 * Construct via [new] (descending the root's initial configuration, recording
 * initial entry actions). Then drive with [send]; query with [configuration],
 * [activeLeaves], [matches]; inspect the last step's action trace with
 * [lastActions].
 */
class StateChart internal constructor(
    private val def: ChartDef,
    private val configId: Int,
    private val history: MutableMap<String, Recording>,
    private var lastActions: List<String>,
) {
    /** Enter the initial configuration by descending from the root. */
    constructor(ctx: Context, def: ChartDef) : this(
        def = def,
        configId = ctx.cellAny(TreeSet<String>()),
        history = mutableMapOf(),
        lastActions = emptyList(),
    ) {
        val enter = TreeSet<String>()
        val actions = ArrayList<String>()
        enterSubtree(def, def.root, enter, actions)
        ctx.setCellAny(configId, enter)
        lastActions = actions
    }

    /** Ordered action names fired by the initial entry or the most recent [send]
     *  (exit innermost-first → transition → entry outermost-first). */
    fun lastActions(): List<String> = lastActions

    /** The full active configuration (active leaves plus all active ancestors). */
    fun configuration(ctx: Context): TreeSet<String> {
        @Suppress("UNCHECKED_CAST")
        val stored = ctx.getCellAny(configId) as TreeSet<String>
        return TreeSet(stored) // defensive copy: callers / setCellAny must not alias the live cell
    }

    /** Active atomic leaves, sorted (one per parallel region; one for single-region). */
    fun activeLeaves(ctx: Context): List<String> =
        configuration(ctx).filter { def.kind(it).isLeaf() }.sorted()

    /** Hierarchical "state-in" predicate: `true` iff [id] is in the active configuration. */
    fun matches(ctx: Context, id: String): Boolean = configuration(ctx).contains(id)

    /**
     * Send an event (run-to-completion). Returns `true` if any transition was
     * taken, `false` if rejected (configuration unchanged, no actions fired).
     * [guards] resolves named guards for this send (absent/unknown name →
     * fail-closed `false`).
     */
    fun send(ctx: Context, event: String, guards: Map<String, Boolean> = emptyMap()): Boolean {
        val config = configuration(ctx)

        // 1. Enabled transitions: per active leaf, innermost passing match.
        data class Cand(val source: String, val transition: Transition, val leaf: String)
        val candidates = ArrayList<Cand>()
        for (leaf in config.filter { def.kind(it).isLeaf() }) {
            for (anc in def.ancestorsInclusive(leaf)) {
                val t = def.states[anc]?.transitions?.get(event)
                if (t != null && guardPasses(t, guards)) {
                    candidates.add(Cand(anc, t, leaf))
                    break // innermost wins for this leaf's chain
                }
            }
        }

        if (candidates.isEmpty()) {
            lastActions = emptyList()
            return false
        }

        // 2. Conflict resolution: order by source depth desc, then document order;
        //    take greedily, skipping any whose exit set intersects the taken union.
        val sorted = candidates.sortedWith(
            compareByDescending<Cand> { def.depth(it.source) }
                .thenBy { def.order[it.source] ?: Int.MAX_VALUE }
        )

        val exitUnion = TreeSet<String>()
        val enterUnion = TreeSet<String>()
        val takenTransitions = ArrayList<Transition>()
        for (cand in sorted) {
            val (exitSet, enterSet) = computeExitEnter(cand.source, cand.transition, cand.leaf, config)
            if (exitSet.any { it in exitUnion }) continue // conflicts with an already-taken transition
            exitUnion.addAll(exitSet)
            enterUnion.addAll(enterSet)
            takenTransitions.add(cand.transition)
        }

        if (takenTransitions.isEmpty()) {
            lastActions = emptyList()
            return false
        }

        // 3. Record history for regions being exited that own a history child.
        for (s in exitUnion) {
            historyChildOf(def, s)?.let { hChild -> recordRegion(def, s, hChild, config, history) }
        }

        // 4. Action trace: exit (innermost-first) → transition → entry (outermost-first).
        val actions = ArrayList<String>()
        for (s in exitUnion.sortedByDescending { def.depth(it) }) actions.addAll(def.states.getValue(s).exit)
        for (t in takenTransitions) actions.addAll(t.action)
        for (s in enterUnion.sortedBy { def.depth(it) }) actions.addAll(def.states.getValue(s).entry)

        // 5. Apply new configuration (PartialEq guard suppresses no-op writes).
        val newConfig = TreeSet(config)
        for (s in exitUnion) newConfig.remove(s)
        for (s in enterUnion) newConfig.add(s)

        lastActions = actions
        if (newConfig != config) ctx.setCellAny(configId, newConfig)
        return true
    }

    private fun computeExitEnter(
        source: String,
        transition: Transition,
        leaf: String,
        config: Set<String>,
    ): Pair<TreeSet<String>, TreeSet<String>> {
        val target = transition.target
        val internal = transition.internal &&
            (target == source || def.isProperDescendant(target, source))
        val lca = if (internal) source else def.lca(leaf, target)

        // Exit set: active proper-descendants of the lca.
        val exitSet = TreeSet<String>()
        for (s in config) if (def.isProperDescendant(s, lca)) exitSet.add(s)

        // Enter set.
        val enter = TreeSet<String>()
        val kind = def.kind(target)
        if (kind is Kind.History) {
            val region = def.states.getValue(target).parent ?: def.root
            enter.addAll(pathBelow(def, lca, region))
            restoreViaHistory(target, region, enter)
        } else {
            enter.addAll(pathBelow(def, lca, target))
            val tmp = ArrayList<String>()
            enterSubtree(def, target, enter, tmp)
        }
        return exitSet to enter
    }

    private fun restoreViaHistory(hist: String, region: String, enter: TreeSet<String>) {
        // The recording carries its own shape (Shallow/Deep); the history kind's
        // shallow/deep flag is consulted at record time (`recordRegion`).
        when (val rec = history[hist]) {
            is Recording.Shallow -> {
                enter.add(rec.child)
                val tmp = ArrayList<String>()
                enterSubtree(def, rec.child, enter, tmp)
            }
            is Recording.Deep -> enter.addAll(rec.set)
            null -> {
                // First entry: descend via `default`, else the region's `initial`.
                val start = def.states.getValue(hist).default
                    ?: def.states.getValue(region).initial
                if (start != null) {
                    enter.addAll(pathBelow(def, region, start))
                    val tmp = ArrayList<String>()
                    enterSubtree(def, start, enter, tmp)
                }
            }
        }
    }
}

private fun guardPasses(t: Transition, guards: Map<String, Boolean>): Boolean = when (t.guard) {
    null -> true
    else -> guards[t.guard] ?: false // fail-closed
}

/** Enter [state] and its default descendants, recording entry actions top-down. */
private fun enterSubtree(
    def: ChartDef,
    state: String,
    enter: TreeSet<String>,
    actions: MutableList<String>,
) {
    enter.add(state)
    actions.addAll(def.states.getValue(state).entry)
    when (def.kind(state)) {
        is Kind.Atomic, is Kind.Final, is Kind.History -> Unit
        is Kind.Compound -> def.states.getValue(state).initial?.let { enterSubtree(def, it, enter, actions) }
        is Kind.Parallel -> for (region in def.children[state] ?: emptyList()) enterSubtree(def, region, enter, actions)
    }
}

/** Path from just-below [lca] down to [target] (exclusive lca, inclusive target). */
private fun pathBelow(def: ChartDef, lca: String, target: String): List<String> {
    val chain = def.ancestorsInclusive(target) // [target, ..., root]
    val idx = chain.indexOfFirst { it == lca }.let { if (it < 0) chain.size else it }
    return chain.take(idx).reversed() // [child-of-lca, ..., target]
}

private fun historyChildOf(def: ChartDef, region: String): String? =
    def.children[region]?.firstOrNull { def.kind(it) is Kind.History }

private fun recordRegion(
    def: ChartDef,
    region: String,
    histChild: String,
    config: Set<String>,
    history: MutableMap<String, Recording>,
) {
    val kind = def.kind(histChild)
    if (kind !is Kind.History) return
    if (!kind.deep) {
        // Shallow: record the direct child of `region` that was active.
        val child = (def.children[region] ?: emptyList())
            .firstOrNull { it in config && def.kind(it) !is Kind.History }
        if (child != null) history[histChild] = Recording.Shallow(child)
    } else {
        // Deep: record every active state strictly below `region`.
        val set = TreeSet<String>()
        for (s in config) if (def.isProperDescendant(s, region)) set.add(s)
        history[histChild] = Recording.Deep(set)
    }
}
