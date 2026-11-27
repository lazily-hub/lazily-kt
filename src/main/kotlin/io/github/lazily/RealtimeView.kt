package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val SCALAR_TAG = "lazily.view.scalar.v1"

enum class ViewEventType {
    ACTIVATE,
    CHANGE,
    SUBMIT,
}

data class ViewEvent(
    val instance: String,
    val displayedRevision: Long,
    val nodeId: String,
    val eventId: String,
    val event: ViewEventType,
    val payload: JsonPrimitive,
) {
    init {
        require(instance.isNotEmpty() && nodeId.isNotEmpty() && eventId.isNotEmpty())
        require(displayedRevision in 1..MAX_SAFE_VIEW_INTEGER)
        normalizeScalar(payload)
        require(event != ViewEventType.ACTIVATE || payload == JsonNull) { "activate requires a null payload" }
    }

    companion object {
        fun fromJson(raw: JsonElement): ViewEvent {
            val frame = raw.fields(
                "version",
                "view_instance",
                "displayed_revision",
                "node_id",
                "event_id",
                "event",
                "payload",
            )
            require(frame.getValue("version").number() == 1L) { "Unsupported realtime View version" }
            return ViewEvent(
                frame.getValue("view_instance").id(),
                frame.getValue("displayed_revision").number(1),
                frame.getValue("node_id").id(),
                frame.getValue("event_id").id(),
                eventType(frame.getValue("event").id()),
                normalizeScalar(frame.getValue("payload")),
            )
        }
    }
}

data class RealtimeViewNode(
    val id: String,
    val role: String,
    val flow: String?,
    val children: List<String>,
    val binding: String?,
    val identity: Any,
    val text: Any?,
    val hidden: Boolean,
    val disabled: Boolean,
)

enum class ViewUpdate {
    APPLIED,
    IGNORED,
    SNAPSHOT_REQUIRED,
}

sealed interface ViewEventResult {
    data class Accepted(val action: String) : ViewEventResult
    data object WrongInstance : ViewEventResult
    data object Duplicate : ViewEventResult
    data object Conflict : ViewEventResult
    data object Stale : ViewEventResult
    data object Unavailable : ViewEventResult
    data object Denied : ViewEventResult
}

private fun JsonElement.fields(vararg keys: String): JsonObject {
    require(this is JsonObject && this.keys == keys.toSet()) { "Invalid realtime View fields" }
    return this
}

private fun JsonElement.id(): String {
    require(this is JsonPrimitive && isString && content.isNotEmpty()) { "Invalid realtime View ID" }
    return content
}

private fun JsonElement.number(minimum: Long = 0): Long {
    val primitive = normalizeScalar(this)
    require(!primitive.isString) { "Invalid realtime View counter" }
    val value = primitive.content.toLongOrNull()
    require(value != null && value in minimum..MAX_SAFE_VIEW_INTEGER) { "Invalid realtime View counter" }
    return value
}

private fun normalizeScalar(raw: JsonElement): JsonPrimitive {
    require(raw is JsonPrimitive) { "Invalid realtime View scalar" }
    if (raw is JsonNull || raw.isString || raw.content == "true" || raw.content == "false") return raw
    val value = raw.content.toBigDecimalOrNull()?.longValueExact()
    require(value != null && value in -MAX_SAFE_VIEW_INTEGER..MAX_SAFE_VIEW_INTEGER) { "Invalid realtime View integer" }
    return JsonPrimitive(value)
}

private fun normalizeJson(raw: JsonElement): JsonElement = when (raw) {
    is JsonArray -> JsonArray(raw.map(::normalizeJson))
    is JsonObject -> JsonObject(raw.mapValues { normalizeJson(it.value) })
    is JsonPrimitive -> normalizeScalar(raw)
}

private fun eventType(name: String): ViewEventType =
    when (name) {
        "activate" -> ViewEventType.ACTIVATE
        "change" -> ViewEventType.CHANGE
        "submit" -> ViewEventType.SUBMIT
        else -> throw IllegalArgumentException("Unknown View event $name")
    }

private data class Revision(
    val instance: String,
    val number: Long,
    val definition: JsonObject,
    val bindings: Map<String, NodeId>,
    val actions: Map<String, Map<ViewEventType, String>>,
) {
    val id: String get() = definition.getValue("id").id()

    companion object {
        fun parse(raw: JsonElement): Revision {
            val frame = raw.fields("version", "view_instance", "revision", "definition", "bindings", "actions")
            require(frame.getValue("version").number() == 1L) { "Unsupported realtime View version" }
            val definition = normalizeJson(frame.getValue("definition")) as? JsonObject ?: error("Invalid View definition")
            val signals = definition["signals"] as? JsonObject ?: error("Invalid View signals")
            val nodes = definition["nodes"] as? JsonObject ?: error("Invalid View nodes")
            val bindings = frame["bindings"] as? JsonObject ?: error("Invalid View bindings")
            val actions = frame["actions"] as? JsonObject ?: error("Invalid View actions")
            return Revision(
                frame.getValue("view_instance").id(),
                frame.getValue("revision").number(1),
                definition,
                bindings.mapValues { (signal, node) ->
                    require(signal in signals) { "Unknown View signal $signal" }
                    node.number()
                },
                actions.mapValues { (node, rawActions) ->
                    require(node in nodes && rawActions is JsonObject) { "Invalid View actions for $node" }
                    rawActions.entries.associate { (event, action) -> eventType(event) to action.id() }
                },
            )
        }
    }
}

/** Receiver state for one realtime View instance. The host owns outward actions. */
class RealtimeView(val instance: String) {
    init {
        require(instance.isNotEmpty()) { "Invalid View instance ID" }
    }

    private var frame: Revision? = null
    private var graph = GraphView()
    private val sync = ResyncCoordinator()
    private var projectedNodes: Map<String, RealtimeViewNode> = emptyMap()
    private var projectedSignals: Map<String, Any?> = emptyMap()
    private val accepted = mutableMapOf<String, ViewEvent>()

    val revision: Long? get() = frame?.number
    val epoch: Long? get() = graph.epoch.takeIf { graph.isInitialized }
    val nodes: List<RealtimeViewNode> get() = projectedNodes.values.toList()
    val signals: Map<String, Any?> get() = projectedSignals.toMap()

    private fun checkRevision(raw: JsonElement): Revision? {
        val next = Revision.parse(raw)
        require(next.instance == instance) { "Wrong View instance" }
        val previous = frame ?: return next
        if (next.number < previous.number) return null
        require(next.id == previous.id) { "View document ID changed" }
        require(next.number != previous.number || next == previous) { "Conflicting View revision" }
        return next
    }

    private fun publish(next: Revision, candidate: GraphView) {
        val values = next.bindings.mapValues { (signal, id) ->
            val node = candidate.node(id)
            val bytes = node?.payload
            require(node?.typeTag == SCALAR_TAG && bytes != null) { "Missing scalar payload for View signal $signal" }
            normalizeScalar(Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)))
        }
        val view = View.install(Context(), next.definition, values)
        val expressions = view.expressionsJson()
        val rawNodes = next.definition.getValue("nodes") as JsonObject
        val nodes = view.layoutJson().associate { row ->
            val id = row.getValue("id") as String
            val role = row.getValue("role") as String
            val binding = (rawNodes.getValue(id) as JsonObject)["bind"]?.id()
            val previous = projectedNodes[id]
            val identity = if (previous?.role == role && previous.binding == binding) previous.identity else Any()

            @Suppress("UNCHECKED_CAST")
            val children = row.getValue("children") as List<String>
            id to RealtimeViewNode(
                id, role, row.getValue("flow") as String?, children.toList(), binding, identity,
                expressions["$id.text"].also { value ->
                    require(value !is Long || value in -MAX_SAFE_VIEW_INTEGER..MAX_SAFE_VIEW_INTEGER) { "View text exceeds safe integer range" }
                },
                expressions.flag("$id.hidden"),
                expressions.flag("$id.disabled"),
            )
        }
        frame = next
        graph = candidate
        projectedNodes = nodes
        projectedSignals = view.signalsJson()
    }

    fun attach(raw: JsonElement, snapshot: Snapshot): ViewUpdate {
        val next = checkRevision(raw)
        require(snapshot.epoch in 0..MAX_SAFE_VIEW_INTEGER) { "Invalid graph Snapshot epoch" }
        if (next == null || (epoch?.let { snapshot.epoch < it } == true)) return ViewUpdate.IGNORED
        val candidate = GraphView()
        candidate.applySnapshot(snapshot)
        publish(next, candidate)
        sync.ingestSnapshot(snapshot.epoch)
        return ViewUpdate.APPLIED
    }

    fun replace(raw: JsonElement): ViewUpdate {
        check(frame != null) { "Attach a View Snapshot first" }
        val next = checkRevision(raw)
        if (next == null || next.number == revision) return ViewUpdate.IGNORED
        publish(next, graph)
        return ViewUpdate.APPLIED
    }

    fun applyDelta(delta: Delta): ViewUpdate {
        val current = checkNotNull(frame) { "Attach a View Snapshot first" }
        require(delta.baseEpoch in 0..MAX_SAFE_VIEW_INTEGER && delta.epoch in 0..MAX_SAFE_VIEW_INTEGER && delta.epoch > delta.baseEpoch) {
            "Invalid graph Delta span"
        }
        if (delta.baseEpoch != graph.epoch) {
            return when (sync.ingestDelta(delta)) {
                is ResyncAction.RequestSnapshot -> ViewUpdate.SNAPSHOT_REQUIRED
                else -> ViewUpdate.IGNORED
            }
        }
        val candidate = graph.fork()
        candidate.applyDelta(delta)
        publish(current, candidate)
        sync.ingestDelta(delta)
        return ViewUpdate.APPLIED
    }

    fun routeEvent(event: ViewEvent, authorize: (String, ViewEvent) -> Boolean): ViewEventResult {
        if (event.instance != instance) return ViewEventResult.WrongInstance
        val normalized = event.copy(payload = normalizeScalar(event.payload))
        val previous = accepted[event.eventId]
        if (previous != null) return if (previous == normalized) ViewEventResult.Duplicate else ViewEventResult.Conflict
        val current = frame
        if (current?.number != event.displayedRevision) return ViewEventResult.Stale
        val node = projectedNodes[event.nodeId]
        val action = current.actions[event.nodeId]?.get(event.event)
        if (node == null || node.hidden || node.disabled || action == null) return ViewEventResult.Unavailable
        if (!authorize(action, normalized)) return ViewEventResult.Denied
        accepted[event.eventId] = normalized
        return ViewEventResult.Accepted(action)
    }
}

private fun Map<String, Any?>.flag(key: String): Boolean {
    if (!containsKey(key)) return false
    return get(key) as? Boolean ?: error("View node has a non-boolean flag: $key")
}
