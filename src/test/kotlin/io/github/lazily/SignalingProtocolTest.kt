package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalingProtocolTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("$name")
        val fixture = json.parseToJsonElement(text).jsonObject
        assertEquals("1", fixture.getValue("protocol_version").jsonPrimitive.content)
        return fixture
    }

    @Test
    fun `signaling frames round-trip byte-for-byte`() {
        val fixture = loadFixture("signaling/frames.json")
        val frames = fixture.getValue("frames").jsonArray
        assertTrue(frames.isNotEmpty())
        for (frameEl in frames) {
            val frame = frameEl.jsonObject
            val label = frame.getValue("label").jsonPrimitive.content
            val wire = frame.getValue("wire").jsonObject
            val direction = frame.getValue("direction").jsonPrimitive.content
            val tag = wire.getValue("type").jsonPrimitive.content
            // Kebab-case tags: never snake_case.
            assertFalse(tag.contains('_'), "tag must be kebab-case, got '$tag' ($label)")

            val reencoded = when (direction) {
                "client" -> ClientMessage.fromJson(wire).toJson()
                "server" -> ServerMessage.fromJson(wire).toJson()
                else -> error("unknown direction: $direction ($label)")
            }
            assertEquals(wire, reencoded, "wire round-trip mismatch for $label")
        }
    }

    @Test
    fun `kebab-case tags for peer-joined and peer-left`() {
        assertEquals("peer-joined", (ServerMessage.PeerJoined(5).toJson().getValue("type").jsonPrimitive.content))
        assertEquals("peer-left", (ServerMessage.PeerLeft(5).toJson().getValue("type").jsonPrimitive.content))
    }

    @Test
    fun `join omits capabilities when null`() {
        val obj = ClientMessage.Join(peer = 1, capabilities = null).toJson()
        assertFalse(obj.containsKey("capabilities"), "capabilities must be omitted when null")
        val withCaps = ClientMessage.Join(peer = 7, capabilities = listOf("crdt")).toJson()
        assertTrue(withCaps.containsKey("capabilities"))
    }

    @Test
    fun `anti-spoof session replays through RoomCore`() {
        val fixture = loadFixture("signaling/anti_spoof_session.json")
        val steps = fixture.getValue("steps").jsonArray
        val room = RoomCore<String>()

        for ((i, stepEl) in steps.withIndex()) {
            val step = stepEl.jsonObject
            val input = step.getValue("input").jsonObject
            val conn = input.getValue("conn").jsonPrimitive.content
            val recv = ClientMessage.fromJson(input.getValue("recv"))

            val emits = room.handle(conn, recv)

            val expected = step.getValue("expect").jsonArray
            assertEquals(expected.size, emits.size, "emit count mismatch at step $i")
            for ((j, expEl) in expected.withIndex()) {
                val exp = expEl.jsonObject
                val expConn = exp.getValue("to").jsonPrimitive.content
                val expFrame = ServerMessage.fromJson(exp.getValue("frame"))
                assertEquals(expConn, emits[j].to, "target conn mismatch at step $i emit $j")
                // Compare on wire JSON so the anti-spoof `to`->`from` rewrite,
                // roster-excludes-self, sorted roster, and unknown_target are all
                // asserted byte-for-byte.
                assertEquals(expFrame.toJson(), emits[j].frame.toJson(), "frame mismatch at step $i emit $j")
            }
        }
    }
}
