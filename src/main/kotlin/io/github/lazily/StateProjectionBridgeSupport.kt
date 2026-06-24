package io.github.lazily

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ProjectionSummary(
    val routeReadiness: String?,
    val routePaneId: String?,
    val latestTransportPatchId: String?,
    val latestTransportPhase: String?,
    val proofMarkers: Int,
) {
    fun compact(): String =
        "route=${routeReadiness ?: "unknown"} pane=${routePaneId ?: "-"} " +
            "transport=${latestTransportPatchId ?: "-"}:${latestTransportPhase ?: "-"} " +
            "proof_markers=$proofMarkers"
}

object StateProjectionBridgeSupport {
    fun documentHash(filePath: String): String {
        val canonical = try {
            File(filePath).canonicalPath
        } catch (_: Exception) {
            File(filePath).absolutePath
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
    }

    fun stateEventJson(
        documentHash: String,
        type: String,
        fields: Map<String, Any?>,
        eventSuffix: String,
    ): String {
        val fact = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("document_hash", JsonPrimitive(documentHash))
            fields.forEach { (key, value) -> put(key, value.toJsonElement()) }
        }
        val event = buildJsonObject {
            put("event_id", JsonPrimitive("$documentHash:$eventSuffix"))
            put("fact", fact)
        }
        return Json.encodeToString(event)
    }

    fun projectionSummary(json: String): ProjectionSummary? = try {
        val root = Json.parseToJsonElement(json).jsonObject
        val route = root["route"]?.jsonObject
        val transport = root["transport"]?.jsonObject
        val proof = root["proof"]?.jsonObject
        val patches = transport?.get("patches")?.jsonObject
        val latestPatch = patches?.entries?.maxByOrNull { it.key }

        ProjectionSummary(
            routeReadiness = route?.get("readiness")?.asStringOrNull(),
            routePaneId = route?.get("pane_id")?.asStringOrNull(),
            latestTransportPatchId = latestPatch?.key,
            latestTransportPhase = latestPatch?.value?.jsonObject?.get("phase")?.asStringOrNull(),
            proofMarkers = proof?.get("markers")?.jsonObject?.entries?.size ?: 0,
        )
    } catch (_: Exception) {
        null
    }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

private fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
