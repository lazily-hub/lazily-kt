package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private val capabilityJson = Json { prettyPrint = false }

/** The structured result of a fail-closed capability check. */
data class CapabilityCheck(
    val isCompatible: Boolean,
    val field: String? = null,
    val reason: String? = null,
) {
    companion object {
        val Compatible = CapabilityCheck(isCompatible = true)

        fun fail(
            field: String,
            reason: String,
        ) = CapabilityCheck(isCompatible = false, field = field, reason = reason)
    }
}

/**
 * The standalone compatibility frame exchanged before non-local graph or command traffic.
 *
 * [maxFrameSize] and [fragmentationSupported] are endpoint advertisements. A successful
 * [NegotiatedSession] retains their common effective values; callers must not continue using
 * either endpoint's advertisement directly.
 */
data class CapabilityHandshake(
    val protocolId: String,
    val protocolMajorVersion: Long,
    val codec: String,
    val maxFrameSize: Long,
    val fragmentationSupported: Boolean,
    val orderedReliable: Boolean,
    val peerId: Long,
    val sessionId: String,
    val features: List<String>,
) {
    fun hasFeature(feature: String): Boolean = feature in features

    fun checkCompatible(
        other: CapabilityHandshake,
        vararg requiredFeatures: String,
    ): CapabilityCheck {
        if (protocolId != REQUIRED_PROTOCOL_ID) {
            return CapabilityCheck.fail("protocol_id", "local protocol_id is not lazily-ipc")
        }
        if (other.protocolId != REQUIRED_PROTOCOL_ID) {
            return CapabilityCheck.fail("protocol_id", "remote protocol_id is not lazily-ipc")
        }
        if (
            protocolMajorVersion != REQUIRED_PROTOCOL_MAJOR_VERSION ||
            other.protocolMajorVersion != REQUIRED_PROTOCOL_MAJOR_VERSION ||
            protocolMajorVersion != other.protocolMajorVersion
        ) {
            return CapabilityCheck.fail(
                "protocol_major_version",
                "protocol major versions are incompatible",
            )
        }
        if (codec != other.codec) {
            return CapabilityCheck.fail("codec", "codec mismatch ($codec vs ${other.codec})")
        }
        if (!orderedReliable || !other.orderedReliable) {
            return CapabilityCheck.fail(
                "ordered_reliable",
                "both peers must require ordered-reliable delivery",
            )
        }
        if (maxFrameSize <= 0 || other.maxFrameSize <= 0) {
            return CapabilityCheck.fail(
                "max_frame_size",
                "both peers must advertise a positive receive ceiling",
            )
        }
        if (sessionId.isEmpty() || other.sessionId.isEmpty() || sessionId != other.sessionId) {
            return CapabilityCheck.fail(
                "session_id",
                "both peers must name the same non-empty session",
            )
        }
        for (feature in requiredFeatures.distinct()) {
            if (!hasFeature(feature) || !other.hasFeature(feature)) {
                return CapabilityCheck.fail(
                    "features",
                    "required feature '$feature' must be advertised by both peers",
                )
            }
        }
        return CapabilityCheck.Compatible
    }

    /** Serializes the standalone frame in canonical field order. */
    fun encodeJson(): String =
        capabilityJson.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("protocol_id", protocolId)
                put("protocol_major_version", protocolMajorVersion)
                put("codec", codec)
                put("max_frame_size", maxFrameSize)
                put("fragmentation_supported", fragmentationSupported)
                put("ordered_reliable", orderedReliable)
                put("peer_id", peerId)
                put("session_id", sessionId)
                put("features", buildJsonArray { features.forEach { add(JsonPrimitive(it)) } })
            },
        )

    companion object {
        const val REQUIRED_PROTOCOL_ID = "lazily-ipc"
        const val REQUIRED_PROTOCOL_MAJOR_VERSION = 1L
        const val COMMAND_PLANE_V1 = "command-plane-v1"

        private val fieldNames =
            setOf(
                "protocol_id",
                "protocol_major_version",
                "codec",
                "max_frame_size",
                "fragmentation_supported",
                "ordered_reliable",
                "peer_id",
                "session_id",
                "features",
            )

        fun create(
            peerId: Long,
            sessionId: String,
            features: List<String> = emptyList(),
        ) = CapabilityHandshake(
            protocolId = REQUIRED_PROTOCOL_ID,
            protocolMajorVersion = REQUIRED_PROTOCOL_MAJOR_VERSION,
            codec = "json",
            maxFrameSize = 1_048_576,
            fragmentationSupported = false,
            orderedReliable = true,
            peerId = peerId,
            sessionId = sessionId,
            features = features,
        )

        /** Decodes and structurally validates the standalone frame. */
        fun decodeJson(encoded: String): CapabilityHandshake {
            val root =
                capabilityJson.parseToJsonElement(encoded) as? JsonObject
                    ?: error("CapabilityHandshake must be a JSON object")
            require(root.keys == fieldNames) {
                "CapabilityHandshake fields differ: expected ${fieldNames.sorted()}, got ${root.keys.sorted()}"
            }
            return CapabilityHandshake(
                protocolId = root.requiredString("protocol_id"),
                protocolMajorVersion = root.requiredNonNegativeLong("protocol_major_version"),
                codec = root.requiredString("codec"),
                maxFrameSize = root.requiredNonNegativeLong("max_frame_size"),
                fragmentationSupported = root.requiredBoolean("fragmentation_supported"),
                orderedReliable = root.requiredBoolean("ordered_reliable"),
                peerId = root.requiredNonNegativeLong("peer_id"),
                // Empty is structurally valid and is rejected by negotiation with field=session_id.
                sessionId = root.requiredString("session_id", allowEmpty = true),
                features = root.requiredStringArray("features"),
            )
        }
    }
}

/** Compatibility-checked session state retained for subsequent framing and feature gates. */
class NegotiatedSession(
    val local: CapabilityHandshake,
    val remote: CapabilityHandshake,
    vararg requiredFeatures: String,
) {
    private val featureIntersection: Set<String>

    val maxFrameSize: Long
    val fragmentationSupported: Boolean
    val sessionId: String

    init {
        val check = local.checkCompatible(remote, *requiredFeatures)
        require(check.isCompatible) {
            "session negotiation failed at ${check.field}: ${check.reason}"
        }
        maxFrameSize = minOf(local.maxFrameSize, remote.maxFrameSize)
        fragmentationSupported =
            local.fragmentationSupported &&
            remote.fragmentationSupported
        sessionId = local.sessionId
        featureIntersection = local.features.intersect(remote.features.toSet())
    }

    fun supports(feature: String): Boolean = feature in featureIntersection

    fun requireCommandPlane() {
        check(supports(CapabilityHandshake.COMMAND_PLANE_V1)) {
            "command-plane-v1 was not advertised by both peers"
        }
    }
}

/** Compatibility spelling used by bindings whose public surface calls the frame a session handshake. */
typealias SessionHandshake = CapabilityHandshake

private fun JsonObject.required(name: String): JsonElement =
    this[name] ?: error("missing required CapabilityHandshake field: $name")

private fun JsonObject.requiredString(
    name: String,
    allowEmpty: Boolean = false,
): String {
    val value = required(name) as? JsonPrimitive ?: error("$name must be a string")
    require(value.isString) { "$name must be a string" }
    val content = value.content
    require(allowEmpty || content.isNotEmpty()) { "$name must be a non-empty string" }
    return content
}

private fun JsonObject.requiredNonNegativeLong(name: String): Long {
    val value = required(name).jsonPrimitive.long
    require(value >= 0) { "$name must be a non-negative integer" }
    return value
}

private fun JsonObject.requiredBoolean(name: String): Boolean = required(name).jsonPrimitive.boolean

private fun JsonObject.requiredStringArray(name: String): List<String> {
    val value = required(name)
    require(value is JsonArray) { "$name must be an array" }
    return value.jsonArray.mapIndexed { index, element ->
        val item = element as? JsonPrimitive ?: error("$name[$index] must be a string")
        require(item.isString) { "$name[$index] must be a string" }
        item.content
    }
}
