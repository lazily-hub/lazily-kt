package io.github.lazily

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
/**
 * Holds the raw JSON projection returned by [LazilyFFI.agent_doc_state_projection].
 *
 * `null` means no state events have been recorded for the document.
 * The JSON contains document/queue/closeout/transport/supervisor/route/proof
 * slices — consumers parse it with their preferred JSON library.
 */
data class StateProjection(val json: String?) {
    val isAvailable: Boolean get() = json != null
}

/**
 * Client for the lazily state-projection FFI channel.
 *
 * Provides a [StateFlow] of [StateProjection] for reactive UI binding
 * (JetBrains `StateFlow` → Compose/screen rendering), plus [recordStateEvent]
 * for feeding facts into the binary's state backbone.
 *
 * `#lazilystatesync3`: [refreshMirror] subscribes to lazily-spec snapshot/delta
 * messages and applies them to a [StateGraphMirror], so editor UI reads tracked
 * cells instead of re-rendering the full projection JSON on every observed event.
 *
 * Usage:
 * ```
 * val client = StateProjectionClient(documentHash)
 * client.refresh()        // pull latest projection from binary
 * client.projection.value // StateFlow<StateProjection>
 * client.refreshMirror()  // advance the reactive mirror via subscribe
 * client.mirror           // StateGraphMirror — read derived cells
 * client.recordStateEvent("""{"type":"BaselineSaved",...}""")
 * ```
 */
class StateProjectionClient(
    private val documentHash: String,
    private val ffi: LazilyFFI = LazilyFFI.load(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _projection = MutableStateFlow(StateProjection(null))
    val projection: StateFlow<StateProjection> = _projection.asStateFlow()

    /** Per-document reactive mirror, advanced by [refreshMirror]. */
    val mirror = StateGraphMirror()

    /**
     * Pull the latest state projection from the binary (cold full snapshot).
     * Updates [projection]. Safe to call on any thread (FFI is thread-safe).
     */
    fun refresh() {
        val ptr = ffi.agent_doc_state_projection(documentHash)
        try {
            val raw = ptr.getString(0, "UTF-8")
            _projection.value = StateProjection(
                if (raw == "null") null else raw
            )
        } finally {
            ffi.agent_doc_free_string(ptr)
        }
    }

    /**
     * Subscribe to lazily-spec snapshot/delta messages and apply them to
     * [mirror] (`#lazilystatesync3`).
     *
     * First call (`mirror.epoch == 0` and mirror uninitialized) requests a cold
     * snapshot; subsequent calls request a delta since `mirror.epoch`. The
     * mirror converges identically to a full re-render because the projection
     * is a pure fold of deduped events.
     *
     * @return the [WireSubscribe] message that was applied, or null on FFI
     *   failure (no state yet).
     */
    fun refreshMirror(): WireSubscribe? {
        val lastEpoch = if (mirror.isInitialized) mirror.epoch else 0L
        val ptr = ffi.agent_doc_state_subscribe(documentHash, lastEpoch)
        val raw = try {
            ptr.getString(0, "UTF-8")
        } finally {
            ffi.agent_doc_free_string(ptr)
        }
        if (raw == "null" || raw.isEmpty()) return null
        val message = decodeSubscribe(json, raw) ?: return null
        mirror.apply(message)
        return message
    }

    /**
     * Record a state event in the binary's backbone.
     *
     * @param factJson JSON object deserializable as a `StateEvent`
     *                 (internally tagged `{"type":"...",...}`).
     * @return `true` if accepted, `false` on parse/ledger failure.
     */
    fun recordStateEvent(factJson: String): Boolean {
        return ffi.agent_doc_record_state_event(documentHash, factJson) == 1
    }
}

/**
 * Decode a `agent_doc_state_subscribe` message by reading the lazily-spec
 * `"type"` discriminator, then decoding the concrete [WireSubscribe.Snapshot]
 * or [WireSubscribe.Delta]. Manual dispatch avoids relying on sealed-class
 * polymorphic serializer generation over the FFI boundary.
 *
 * The nested [WireDeltaOp] discriminator is `"op"` (matches Rust
 * `#[serde(tag = "op", rename_all = "snake_case")]`), so the concrete decode
 * uses a [Json] with `classDiscriminator = "op"`.
 */
internal fun decodeSubscribe(json: Json, raw: String): WireSubscribe? {
    val root = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        return null
    }
    val type = (root["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val opJson = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "op"
    }
    return try {
        when (type) {
            "snapshot" -> opJson.decodeFromString(WireSubscribe.Snapshot.serializer(), raw)
            "delta" -> opJson.decodeFromString(WireSubscribe.Delta.serializer(), raw)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
