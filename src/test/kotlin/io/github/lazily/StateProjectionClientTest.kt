package io.github.lazily

import com.sun.jna.Pointer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [StateProjectionClient] using a mock FFI.
 *
 * The real FFI requires the `agent_doc` native library to be loaded;
 * these tests verify the client logic (StateFlow updates, null handling,
 * free lifecycle) without a native dependency.
 */
class StateProjectionClientTest {

    private class MockFFI : LazilyFFI {
        var projectionJson = "null"
        var recordedEvent: String? = null
        var recordResult = 1
        var freeCallCount = 0

        override fun agent_doc_state_projection(documentHash: String): Pointer {
            val bytes = projectionJson.toByteArray(Charsets.UTF_8)
            val mem = com.sun.jna.Memory(bytes.size + 1L)
            mem.write(0, bytes, 0, bytes.size)
            mem.setByte(bytes.size.toLong(), 0)
            return mem
        }

        override fun agent_doc_record_state_event(documentHash: String, factJson: String): Int {
            recordedEvent = factJson
            return recordResult
        }

        override fun agent_doc_free_string(ptr: Pointer) {
            freeCallCount++
        }
    }

    @Test
    fun `refresh updates StateFlow with projection JSON`() = runTest {
        val mock = MockFFI()
        mock.projectionJson = """{"document":{"hash":"abc123"}}"""
        val client = StateProjectionClient("abc123", mock)

        assertFalse(client.projection.value.isAvailable)

        client.refresh()

        assertTrue(client.projection.value.isAvailable)
        assertEquals("""{"document":{"hash":"abc123"}}""", client.projection.value.json)
        assertEquals(1, mock.freeCallCount)
    }

    @Test
    fun `refresh with null projection keeps isAvailable false`() = runTest {
        val mock = MockFFI()
        mock.projectionJson = "null"
        val client = StateProjectionClient("noevents", mock)

        client.refresh()

        assertFalse(client.projection.value.isAvailable)
        assertEquals(null, client.projection.value.json)
        assertEquals(1, mock.freeCallCount)
    }

    @Test
    fun `recordStateEvent passes fact JSON and returns true on success`() = runTest {
        val mock = MockFFI()
        mock.recordResult = 1
        val client = StateProjectionClient("doc1", mock)

        val accepted = client.recordStateEvent("""{"type":"BaselineSaved"}""")

        assertTrue(accepted)
        assertEquals("""{"type":"BaselineSaved"}""", mock.recordedEvent)
    }

    @Test
    fun `recordStateEvent returns false on failure`() = runTest {
        val mock = MockFFI()
        mock.recordResult = 0
        val client = StateProjectionClient("doc1", mock)

        val accepted = client.recordStateEvent("""{"type":"Invalid"}""")

        assertFalse(accepted)
    }

    @Test
    fun `document hash uses canonical path sha256`() {
        val file = File.createTempFile("agent-doc-state", ".md")
        try {
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(file.canonicalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, StateProjectionBridgeSupport.documentHash(file.path))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `state event json matches Rust state backbone serde shape`() {
        val json = StateProjectionBridgeSupport.stateEventJson(
            documentHash = "doc-a",
            type = "editor_patch_queued",
            fields = mapOf("patch_id" to "patch-1", "actor_generation" to 7),
            eventSuffix = "editor-patch-queued-patch-1-7",
        )

        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("doc-a:editor-patch-queued-patch-1-7", root["event_id"]!!.jsonPrimitive.content)
        val fact = root["fact"]!!.jsonObject
        assertEquals("editor_patch_queued", fact["type"]!!.jsonPrimitive.content)
        assertEquals("doc-a", fact["document_hash"]!!.jsonPrimitive.content)
        assertEquals("patch-1", fact["patch_id"]!!.jsonPrimitive.content)
        assertEquals(7, fact["actor_generation"]!!.jsonPrimitive.long)
    }

    @Test
    fun `projection summary renders route transport and proof slices`() {
        val projection = """
            {
              "document_hash":"doc-a",
              "route":{"generation":3,"pane_id":"%2","readiness":"dispatch_proven","dispatch_proofs":["p1"]},
              "transport":{"patches":{"patch-1":{"phase":"queued"},"patch-2":{"phase":"acked"}}},
              "proof":{"markers":{"dispatch_start":{"phase":"observed","sources":["route"]}}},
              "document":{},
              "queue":{},
              "closeout":{},
              "supervisor":{}
            }
        """.trimIndent()

        val summary = StateProjectionBridgeSupport.projectionSummary(projection)!!
        assertEquals("dispatch_proven", summary.routeReadiness)
        assertEquals("%2", summary.routePaneId)
        assertEquals("patch-2", summary.latestTransportPatchId)
        assertEquals("acked", summary.latestTransportPhase)
        assertEquals(1, summary.proofMarkers)
        assertEquals(
            "route=dispatch_proven pane=%2 transport=patch-2:acked proof_markers=1",
            summary.compact(),
        )
    }
}
