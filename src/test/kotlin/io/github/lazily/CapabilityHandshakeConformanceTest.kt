package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class CapabilityHandshakeConformanceTest {
    private val path = "codec/capability_handshake.json"

    @Test
    fun `canonical capability handshake scenarios negotiate production state`() {
        val fixture = Json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject

        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val id = scenario.getValue("id").jsonPrimitive.content
            val localSource = scenario.getValue("local").toString()
            val remoteSource = scenario.getValue("remote").toString()

            // Exercise both production codec directions before negotiation.
            val local =
                CapabilityHandshake.decodeJson(
                    CapabilityHandshake.decodeJson(localSource).encodeJson(),
                )
            val remote =
                CapabilityHandshake.decodeJson(
                    CapabilityHandshake.decodeJson(remoteSource).encodeJson(),
                )
            val check = local.checkCompatible(remote)
            val expected = AssertionKeys("$path $id", scenario.getValue("expected").jsonObject)

            expected.assertBoolean("compatible") { check.isCompatible }
            expected.assertString("field") { check.field }
            if (check.isCompatible) {
                val negotiated = NegotiatedSession(local, remote)
                expected.assertLong("negotiated_max_frame_size") { negotiated.maxFrameSize }
                expected.assertBoolean("negotiated_fragmentation_supported") {
                    negotiated.fragmentationSupported
                }
            } else {
                expected.assertLong("negotiated_max_frame_size") {
                    error("$id: incompatible handshakes cannot have a negotiated frame ceiling")
                }
                expected.assertBoolean("negotiated_fragmentation_supported") {
                    error("$id: incompatible handshakes cannot negotiate fragmentation")
                }
            }
            expected.requireAllSatisfied()
        }
    }
}
