package io.github.lazily

import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for the [StateGraphMirror] apply/diff state machine (`#lazilystatesync3`).
 *
 * These pin the canonical behavior the JB plugin-local mirror (Kotlin 1.9 /
 * Gson) must reproduce (`#lzpkgwire`). The mirror must converge identically to
 * a snapshot after applying the corresponding delta stream, and a no-op delta
 * must leave it unchanged.
 */
class StateGraphMirrorTest {
    private fun b64(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray())

    private fun routePayload(readiness: String, paneId: String?): String {
        val pane = if (paneId != null) ""","pane_id":"$paneId"""" else ""
        return b64("""{"readiness":"$readiness"$pane}""")
    }

    private fun patchPayload(phase: String) = b64("""{"phase":"$phase","actor_generation":1}""")

    @Test
    fun `cold snapshot populates nodes edges and epoch`() {
        val mirror = StateGraphMirror()
        val route = routePayload("dispatch_proven", "%2")
        val snapshot = WireSnapshot(
            epoch = 3,
            documentHash = "doc-a",
            nodes = listOf(
                WireNodeSnapshot(slotId = 10, typeTag = MirrorProjectionSummary.ROUTE, payload = route),
                WireNodeSnapshot(slotId = 20, typeTag = MirrorProjectionSummary.PROOF_MARKER, payload = b64("{}")),
            ),
            edges = listOf(WireEdgeSnapshot(dependent = 10, dependency = 20)),
            roots = listOf(10),
        )

        mirror.applySnapshot(snapshot)

        assertTrue(mirror.isInitialized)
        assertEquals(3, mirror.epoch)
        assertEquals(2, mirror.nodeCount)
        assertEquals("doc-a", mirror.documentHash)
    }

    @Test
    fun `delta cell_set updates payload and advances epoch`() {
        val mirror = StateGraphMirror()
        mirror.applySnapshot(
            WireSnapshot(
                epoch = 1,
                documentHash = "doc",
                nodes = listOf(WireNodeSnapshot(10, MirrorProjectionSummary.ROUTE, payload = routePayload("idle", null))),
            )
        )

        mirror.applyDelta(
            WireDelta(
                baseEpoch = 1,
                epoch = 2,
                documentHash = "doc",
                ops = listOf(WireDeltaOp.CellSet(10, routePayload("dispatch_proven", "%9"))),
            )
        )

        assertEquals(2, mirror.epoch)
        val route = mirror.payloadObject(MirrorProjectionSummary.ROUTE)!!
        assertEquals("dispatch_proven", route["readiness"]!!.jsonPrimitive.content)
        assertEquals("%9", route["pane_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no-op delta leaves mirror unchanged but is safe`() {
        val mirror = StateGraphMirror()
        mirror.applySnapshot(
            WireSnapshot(
                epoch = 5,
                documentHash = "doc",
                nodes = listOf(WireNodeSnapshot(10, MirrorProjectionSummary.ROUTE, payload = routePayload("idle", null))),
            )
        )
        val before = mirror.nodeCount

        mirror.applyDelta(WireDelta(baseEpoch = 5, epoch = 5, documentHash = "doc", ops = emptyList()))

        assertEquals(5, mirror.epoch)
        assertEquals(before, mirror.nodeCount)
    }

    @Test
    fun `node_remove and edge_remove are honored`() {
        val mirror = StateGraphMirror()
        mirror.applySnapshot(
            WireSnapshot(
                epoch = 2,
                documentHash = "doc",
                nodes = listOf(
                    WireNodeSnapshot(10, MirrorProjectionSummary.ROUTE, payload = routePayload("idle", null)),
                    WireNodeSnapshot(30, MirrorProjectionSummary.TRANSPORT_PATCH, payload = patchPayload("queued")),
                ),
                edges = listOf(WireEdgeSnapshot(30, 10)),
            )
        )

        mirror.applyDelta(
            WireDelta(
                baseEpoch = 2,
                epoch = 3,
                documentHash = "doc",
                ops = listOf(
                    WireDeltaOp.EdgeRemove(30, 10),
                    WireDeltaOp.NodeRemove(30),
                ),
            )
        )

        assertEquals(1, mirror.nodeCount)
        assertNull(mirror.singletonNode(MirrorProjectionSummary.TRANSPORT_PATCH))
    }

    @Test
    fun `MirrorProjectionSummary derives route phase and proof count from tracked cells`() {
        val mirror = StateGraphMirror()
        mirror.applySnapshot(
            WireSnapshot(
                epoch = 4,
                documentHash = "doc",
                nodes = listOf(
                    WireNodeSnapshot(11, MirrorProjectionSummary.ROUTE, payload = routePayload("dispatch_proven", "%2")),
                    WireNodeSnapshot(40, MirrorProjectionSummary.TRANSPORT_PATCH, payload = patchPayload("applied")),
                    WireNodeSnapshot(41, MirrorProjectionSummary.TRANSPORT_PATCH, payload = patchPayload("queued")),
                    WireNodeSnapshot(50, MirrorProjectionSummary.PROOF_MARKER, payload = b64("{}")),
                ),
            )
        )

        val summary = MirrorProjectionSummary.fromMirror(mirror)
        assertEquals("dispatch_proven", summary.routeReadiness)
        assertEquals("%2", summary.routePaneId)
        // Latest transport patch by slot_id wins; phase is readable, patch_id is not on the wire.
        assertEquals("queued", summary.latestTransportPhase)
        assertEquals(1, summary.proofMarkers)
    }

    @Test
    fun `empty mirror yields nullish summary`() {
        val mirror = StateGraphMirror()
        val summary = MirrorProjectionSummary.fromMirror(mirror)
        assertNull(summary.routeReadiness)
        assertNull(summary.latestTransportPhase)
        assertEquals(0, summary.proofMarkers)
    }

    @Test
    fun `subscribe cold then delta converges identically to snapshot`() {
        // The defining property: applying a cold snapshot for the full state, OR
        // a cold snapshot of partial state then a delta to the full state, must
        // produce the same mirror summary.
        val routeFinal = routePayload("dispatch_proven", "%2")
        val fullNodes = listOf(
            WireNodeSnapshot(11, MirrorProjectionSummary.ROUTE, payload = routeFinal),
            WireNodeSnapshot(50, MirrorProjectionSummary.PROOF_MARKER, payload = b64("{}")),
        )

        // Path A: one cold snapshot of the full state.
        val direct = StateGraphMirror().apply {
            applySnapshot(WireSnapshot(epoch = 3, documentHash = "doc", nodes = fullNodes))
        }

        // Path B: cold snapshot (partial, epoch 1) then delta to full (epoch 3).
        val incremental = StateGraphMirror().apply {
            applySnapshot(
                WireSnapshot(
                    epoch = 1,
                    documentHash = "doc",
                    nodes = listOf(WireNodeSnapshot(11, MirrorProjectionSummary.ROUTE, payload = routePayload("idle", null))),
                )
            )
            applyDelta(
                WireDelta(
                    baseEpoch = 1,
                    epoch = 3,
                    documentHash = "doc",
                    ops = listOf(
                        WireDeltaOp.CellSet(11, routeFinal),
                        WireDeltaOp.NodeAdd(50, MirrorProjectionSummary.PROOF_MARKER, b64("{}")),
                    ),
                )
            )
        }

        assertEquals(direct.epoch, incremental.epoch)
        assertEquals(
            MirrorProjectionSummary.fromMirror(direct).compact(),
            MirrorProjectionSummary.fromMirror(incremental).compact(),
        )
        assertNotNull(MirrorProjectionSummary.fromMirror(incremental).routeReadiness)
    }
}
