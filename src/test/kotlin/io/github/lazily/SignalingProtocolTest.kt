package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
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

            val decoded: Any = when (direction) {
                "client" -> ClientMessage.fromJson(wire)
                "server" -> ServerMessage.fromJson(wire)
                else -> error("unknown direction: $direction ($label)")
            }
            val reencoded = when (decoded) {
                is ClientMessage -> decoded.toJson()
                is ServerMessage -> decoded.toJson()
                else -> error("unreachable")
            }
            assertEquals(wire, reencoded, "wire round-trip mismatch for $label")

            // Each frame carries an `assertions` block naming the fields it
            // exists to pin. Round-tripping the wire does not evaluate any of
            // them: a frame whose `from` the server is supposed to stamp
            // round-trips identically whether or not the binding stamps it.
            frame["assertions"]?.jsonObject?.consuming("signaling/frames.json[$label]") { a ->
                assertFrameAssertions(label, decoded, a)
            }
        }
    }

    /** Evaluate one signaling frame's `assertions` block against the decoded message. */
    private fun assertFrameAssertions(label: String, decoded: Any, a: AssertionKeys) {
        a.long("peer")?.let { want ->
            val got = when (decoded) {
                is ClientMessage.Join -> decoded.peer
                is ServerMessage.Welcome -> decoded.peer
                is ServerMessage.PeerJoined -> decoded.peer
                is ServerMessage.PeerLeft -> decoded.peer
                else -> error("$label: `peer` asserted on a frame that carries none")
            }
            assertEquals(want, got, "$label: peer")
        }
        a.long("to")?.let { want ->
            val got = when (decoded) {
                is ClientMessage.Offer -> decoded.to
                is ClientMessage.Answer -> decoded.to
                is ClientMessage.Ice -> decoded.to
                is ClientMessage.Relay -> decoded.to
                else -> error("$label: `to` asserted on a frame that carries none")
            }
            assertEquals(want, got, "$label: to")
        }
        a.long("from")?.let { want ->
            assertEquals(want, serverFrom(label, decoded), "$label: from")
        }
        // The anti-spoof invariant, stated per frame: a server->client forward
        // identifies its sender with a `from` the server stamped, and carries no
        // client-supplied `to` that could be used to impersonate a target.
        a.boolean("server_stamped_from")?.let { want ->
            val stamped = runCatching { serverFrom(label, decoded) }.isSuccess
            assertEquals(want, stamped, "$label: server_stamped_from")
        }
        a.boolean("has_capabilities")?.let { want ->
            val join = decoded as? ClientMessage.Join
                ?: error("$label: `has_capabilities` asserted on a non-join frame")
            assertEquals(want, join.capabilities != null, "$label: has_capabilities")
        }
        a.strings("capabilities")?.let { want ->
            val join = decoded as? ClientMessage.Join
                ?: error("$label: `capabilities` asserted on a non-join frame")
            assertEquals(want, join.capabilities, "$label: capabilities")
        }
        a.array("peers")?.let { want ->
            val welcome = decoded as? ServerMessage.Welcome
                ?: error("$label: `peers` asserted on a non-welcome frame")
            assertEquals(want.map { it.jsonPrimitive.long }, welcome.peers, "$label: peers")
        }
        a.boolean("roster_excludes_self")?.let { want ->
            val welcome = decoded as? ServerMessage.Welcome
                ?: error("$label: `roster_excludes_self` asserted on a non-welcome frame")
            assertEquals(want, welcome.peer !in welcome.peers, "$label: roster_excludes_self")
        }
        a.string("code")?.let { want ->
            val err = decoded as? ServerMessage.Error
                ?: error("$label: `code` asserted on a non-error frame")
            assertEquals(want, err.code, "$label: code")
        }
    }

    /** The server-stamped sender of a forwarded frame, or an error when the frame has none. */
    private fun serverFrom(label: String, decoded: Any): Long = when (decoded) {
        is ServerMessage.Offer -> decoded.from
        is ServerMessage.Answer -> decoded.from
        is ServerMessage.Ice -> decoded.from
        is ServerMessage.Relay -> decoded.from
        else -> error("$label: frame carries no server-stamped `from`")
    }

    @Test
    fun `signaling negative fixtures are rejected`() {
        val frames = loadFixture("signaling/frames.json")
        for (rejectEl in frames.getValue("rejects").jsonArray) {
            val reject = rejectEl.jsonObject
            val direction = reject.getValue("direction").jsonPrimitive.content
            val wire = reject.getValue("wire")
            assertFails(reject.getValue("label").jsonPrimitive.content) {
                when (direction) {
                    "client" -> ClientMessage.fromJson(wire)
                    "server" -> ServerMessage.fromJson(wire)
                    else -> error("unknown direction: $direction")
                }
            }
        }

        val session = loadFixture("signaling/anti_spoof_session.json")
        for (rejectEl in session.getValue("rejects").jsonArray) {
            val reject = rejectEl.jsonObject
            val wire = reject.getValue("input").jsonObject.getValue("recv")
            assertFails(reject.getValue("label").jsonPrimitive.content) {
                ClientMessage.fromJson(wire)
            }
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
        val registered = mutableSetOf<Long>()
        val welcomes = mutableListOf<ServerMessage.Welcome>()
        val forwardedFrom = mutableListOf<Long>()

        for ((i, stepEl) in steps.withIndex()) {
            val step = stepEl.jsonObject
            val input = step.getValue("input").jsonObject
            val conn = input.getValue("conn").jsonPrimitive.content
            val recv = ClientMessage.fromJson(input.getValue("recv"))
            if (recv is ClientMessage.Join) registered.add(recv.peer)

            val emits = room.handle(conn, recv)
            for (e in emits) {
                when (val f = e.frame) {
                    is ServerMessage.Welcome -> welcomes.add(f)
                    is ServerMessage.Offer -> forwardedFrom.add(f.from)
                    is ServerMessage.Answer -> forwardedFrom.add(f.from)
                    is ServerMessage.Ice -> forwardedFrom.add(f.from)
                    is ServerMessage.Relay -> forwardedFrom.add(f.from)
                    else -> {}
                }
            }

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

        // The session-level claims. Byte-for-byte frame comparison makes these
        // *true*, but it never makes them *asserted*: it only says lazily-kt
        // emits what the fixture recorded. Stating them against the observed
        // emissions is what turns the fixture's contract into a check.
        fixture.getValue("assertions").jsonObject
            .consuming("signaling/anti_spoof_session.json assertions") { a ->
                a.boolean("roster_excludes_self")?.let { want ->
                    assertTrue(welcomes.isNotEmpty(), "roster_excludes_self: no welcome emitted")
                    assertEquals(
                        want,
                        welcomes.all { it.peer !in it.peers },
                        "roster_excludes_self",
                    )
                }
                a.boolean("roster_sorted_ascending")?.let { want ->
                    assertTrue(welcomes.isNotEmpty(), "roster_sorted_ascending: no welcome emitted")
                    assertEquals(
                        want,
                        welcomes.all { it.peers == it.peers.sorted() },
                        "roster_sorted_ascending",
                    )
                }
                a.boolean("forwarded_from_is_server_registered")?.let { want ->
                    assertTrue(
                        forwardedFrom.isNotEmpty(),
                        "forwarded_from_is_server_registered: nothing was forwarded",
                    )
                    assertEquals(
                        want,
                        forwardedFrom.all { it in registered },
                        "forwarded_from_is_server_registered",
                    )
                }
            }
    }
}
