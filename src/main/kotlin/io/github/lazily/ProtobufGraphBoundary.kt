package io.github.lazily

import io.github.lazily.protobuf.v1.GraphInput
import io.github.lazily.protobuf.v1.ProtocolEnvelope
import io.github.lazily.protobuf.v1.SnapshotPurpose
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.SortedMap
import java.util.TreeMap

/** Capability token peers must both advertise before using this encoding. */
const val PROTOBUF_GRAPH_BOUNDARY_FEATURE = "protobuf-graph-boundary-v1"

/** Semantic result of admitting one generated graph-boundary envelope. */
enum class BoundaryDecision {
    Apply,
    Bootstrap,
    Project,
    Observe,
    Duplicate,
    RejectStale,
    RejectGap,
}

/** Stable logical cell projected from any negotiated graph-boundary codec. */
data class ProjectedCell(
    var revision: Long,
    var text: String,
)

/**
 * Native semantic reducer behind the generated Protobuf representation.
 *
 * Protobuf owns field shape only. This class owns generation/epoch fencing,
 * sequence admission, bounded splice behavior, and logical projection.
 */
class ProtobufGraphBoundaryProjection {
    private var sourceGeneration = 0L
    private var causalEpoch = 0L
    private var lastSequence = 0L
    val cells: SortedMap<String, ProjectedCell> = TreeMap()

    fun logicalProjection(): String =
        cells.entries.joinToString("|") { (id, cell) -> "$id@${cell.revision}=${cell.text}" }

    fun admit(envelope: ProtocolEnvelope): BoundaryDecision {
        val incoming = envelope.sourceGeneration to envelope.causalEpoch
        val current = sourceGeneration to causalEpoch
        if (incoming < current) return BoundaryDecision.RejectStale
        if (incoming > current) {
            sourceGeneration = incoming.first
            causalEpoch = incoming.second
            lastSequence = 0
        }
        if (envelope.sequence <= lastSequence) return BoundaryDecision.Duplicate
        if (envelope.sequence != lastSequence + 1) return BoundaryDecision.RejectGap

        val decision =
            when (envelope.bodyCase) {
                ProtocolEnvelope.BodyCase.GRAPH_INPUT -> applyInput(envelope.graphInput)
                ProtocolEnvelope.BodyCase.DERIVED_PROJECTION -> {
                    cells.clear()
                    envelope.derivedProjection.cellsList.forEach {
                        cells[it.cellId] = ProjectedCell(it.revision, it.text)
                    }
                    BoundaryDecision.Project
                }
                else -> error("unsupported graph-boundary body ${envelope.bodyCase}")
            }
        lastSequence = envelope.sequence
        return decision
    }

    fun installSnapshotCells(snapshot: Map<String, String>) {
        cells.clear()
        snapshot.forEach { (id, text) -> cells[id] = ProjectedCell(1, text) }
    }

    private fun applyInput(input: GraphInput): BoundaryDecision =
        when (input.inputCase) {
            GraphInput.InputCase.CELL_TEXT_SPLICE -> {
                val splice = input.cellTextSplice
                val cell = cells.getOrPut(splice.cellId) { ProjectedCell(0, "") }
                require(cell.revision == splice.expectedCellRevision) {
                    "cell revision mismatch"
                }
                cell.text =
                    spliceUtf8(
                        cell.text,
                        splice.localOffsetUtf8,
                        splice.deleteLengthUtf8,
                        splice.insertText,
                    )
                cell.revision += 1
                BoundaryDecision.Apply
            }
            GraphInput.InputCase.BOOTSTRAP_SNAPSHOT -> {
                require(
                    input.bootstrapSnapshot.purpose != SnapshotPurpose.SNAPSHOT_PURPOSE_UNSPECIFIED,
                ) {
                    "snapshot purpose must be explicit"
                }
                BoundaryDecision.Bootstrap
            }
            GraphInput.InputCase.SURFACE_OBSERVATION -> BoundaryDecision.Observe
            else -> error("unsupported graph input ${input.inputCase}")
        }

    private fun spliceUtf8(
        text: String,
        offset: Int,
        deleteLength: Int,
        insertText: String,
    ): String {
        val original = text.toByteArray(Charsets.UTF_8)
        require(offset >= 0 && deleteLength >= 0 && offset + deleteLength <= original.size) {
            "splice outside UTF-8 bytes"
        }
        val replacement = insertText.toByteArray(Charsets.UTF_8)
        val result =
            original.copyOfRange(0, offset) +
                replacement +
                original.copyOfRange(offset + deleteLength, original.size)
        return Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(result))
            .toString()
    }
}

private operator fun Pair<Long, Long>.compareTo(other: Pair<Long, Long>): Int =
    when {
        first != other.first -> first.compareTo(other.first)
        else -> second.compareTo(other.second)
    }
