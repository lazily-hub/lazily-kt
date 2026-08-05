package io.github.lazily

import com.google.protobuf.ByteString
import io.github.lazily.protobuf.v1.CapabilityHandshake
import io.github.lazily.protobuf.v1.CellProjection
import io.github.lazily.protobuf.v1.CellTextSplice
import io.github.lazily.protobuf.v1.DerivedProjection
import io.github.lazily.protobuf.v1.GraphInput
import io.github.lazily.protobuf.v1.GraphSnapshot
import io.github.lazily.protobuf.v1.ProtocolEnvelope
import io.github.lazily.protobuf.v1.SnapshotPurpose
import io.github.lazily.protobuf.v1.SurfaceObservation
import io.github.lazily.protobuf.v1.SurfaceObservationKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtobufGraphBoundaryConformanceTest {
    private val path = "protobuf/graph_boundary_traces.json"

    @Test
    fun `generated handshake negotiates the optional feature`() {
        val encoded =
            CapabilityHandshake
                .newBuilder()
                .setMinimumProtocolVersion(1)
                .setMaximumProtocolVersion(1)
                .addCodecs("protobuf")
                .addFeatures(PROTOBUF_GRAPH_BOUNDARY_FEATURE)
                .build()
                .toByteArray()
        val decoded = CapabilityHandshake.parseFrom(encoded)
        assertEquals(listOf("protobuf"), decoded.codecsList)
        assertEquals(listOf(PROTOBUF_GRAPH_BOUNDARY_FEATURE), decoded.featuresList)
    }

    @Test
    fun `generated protobuf round trips canonical logical traces`() {
        val fixture = Json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject
        for (scenario in ConformanceScenarios.of(path, fixture)) {
            val id = scenario.getValue("id").jsonPrimitive.content
            val projection = ProtobufGraphBoundaryProjection()
            val decisions = mutableListOf<String>()

            for (stepElement in scenario.getValue("steps").jsonArray) {
                val step = stepElement.jsonObject
                val encoded = envelope(step).toByteArray()
                val decoded = ProtocolEnvelope.parseFrom(encoded)
                val decision = projection.admit(decoded)
                if (decision == BoundaryDecision.Bootstrap) {
                    projection.installSnapshotCells(strings(step.getValue("cells").jsonObject))
                }
                decisions += decision.wireName()
            }

            val expected = scenario.getValue("expect").jsonObject
            assertEquals(
                strings(expected.getValue("cells").jsonObject),
                projection.cells.mapValues { it.value.text },
                id,
            )
            assertEquals(
                expected.getValue("decisions").jsonArray.map { it.jsonPrimitive.content },
                decisions,
                id,
            )
            assertEquals(
                expected.getValue("logical_projection").jsonPrimitive.content,
                projection.logicalProjection(),
                id,
            )
            assertEquals(0, expected.getValue("ordinary_snapshot_count").jsonPrimitive.content.toInt(), id)
        }
    }

    private fun envelope(step: JsonObject): ProtocolEnvelope {
        val builder =
            ProtocolEnvelope
                .newBuilder()
                .setProtocolVersion(1)
                .setSchemaVersion("1.0.0-experimental")
                .setGraphId("fixture-graph")
                .setSourceId("fixture-source")
                .setSourceGeneration(step.long("source_generation"))
                .setCausalEpoch(step.long("causal_epoch"))
                .setSequence(step.long("sequence"))
                .setCorrelationId("fixture-${step.long("sequence")}")

        when (step.string("kind")) {
            "cell_text_splice" ->
                builder.graphInput =
                    GraphInput
                        .newBuilder()
                        .setCellTextSplice(
                            CellTextSplice
                                .newBuilder()
                                .setDocumentId(step.string("document_id"))
                                .setCellId(step.string("cell_id"))
                                .setExpectedCellRevision(step.long("expected_revision"))
                                .setLocalOffsetUtf8(step.int("offset"))
                                .setDeleteLengthUtf8(step.int("delete_length"))
                                .setInsertText(step.string("insert_text")),
                        ).build()
            "bootstrap_snapshot" ->
                builder.graphInput =
                    GraphInput
                        .newBuilder()
                        .setBootstrapSnapshot(
                            GraphSnapshot
                                .newBuilder()
                                .setPurpose(SnapshotPurpose.SNAPSHOT_PURPOSE_BOOTSTRAP)
                                .setCanonicalJson(ByteString.copyFromUtf8(step.getValue("cells").toString())),
                        ).build()
            "derived_projection" ->
                builder.derivedProjection =
                    DerivedProjection
                        .newBuilder()
                        .setProjectionVersion(step.long("sequence"))
                        .addAllCells(
                            strings(step.getValue("cells").jsonObject).map { (cellId, text) ->
                                CellProjection
                                    .newBuilder()
                                    .setDocumentId("doc")
                                    .setCellId(cellId)
                                    .setRevision(1)
                                    .setText(text)
                                    .build()
                            },
                        ).build()
            "surface_observation" ->
                builder.graphInput =
                    GraphInput
                        .newBuilder()
                        .setSurfaceObservation(
                            SurfaceObservation
                                .newBuilder()
                                .setSurfaceId("fixture")
                                .setKind(SurfaceObservationKind.SURFACE_OBSERVATION_KIND_NATIVE_RELOAD)
                                .setCellId(step.string("cell_id")),
                        ).build()
            else -> error("unknown fixture kind ${step.string("kind")}")
        }
        return builder.build()
    }

    private fun strings(value: JsonObject): Map<String, String> =
        value.mapValues { (_, item) -> item.jsonPrimitive.content }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.long(key: String): Long = string(key).toLong()

    private fun JsonObject.int(key: String): Int = string(key).toInt()

    private fun BoundaryDecision.wireName(): String =
        when (this) {
            BoundaryDecision.Apply -> "apply"
            BoundaryDecision.Bootstrap -> "bootstrap"
            BoundaryDecision.Project -> "project"
            BoundaryDecision.Observe -> "observe"
            BoundaryDecision.Duplicate -> "duplicate"
            BoundaryDecision.RejectStale -> "reject_stale"
            BoundaryDecision.RejectGap -> "reject_gap"
        }
}
