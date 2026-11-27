package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class RealtimeViewConformanceTest {
    @Test
    fun shared_realtime_view() {
        val path = ConformanceFixtures.specRoot.resolve("conformance-extensions/realtime-view/explorer.json")
        if (!Files.isRegularFile(path)) {
            check(System.getenv("LAZILY_REQUIRE_REALTIME_VIEW_FIXTURE") != "1") {
                "Pinned lazily-spec must contain conformance-extensions/realtime-view/explorer.json"
            }
            return
        }
        val fixture = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val receiver = RealtimeView(fixture.string("instance"))
        val revisions = fixture.getValue("revisions").jsonObject
        val snapshots = fixture.getValue("snapshots").jsonObject
        val deltas = fixture.getValue("deltas").jsonObject
        val identities = mutableMapOf<String, Pair<Any, Int>>()

        for ((index, raw) in fixture.getValue("steps").jsonArray.withIndex()) {
            val step = raw.jsonObject
            val label = "step ${index + 1} ${step.string("operation")}"
            val expected = step.string("status")
            fun replay(): String = when (step.string("operation")) {
                "attach" -> receiver.attach(
                    revisions.getValue(step.string("revision")),
                    Snapshot.fromJson(snapshots.getValue(step.string("snapshot")).jsonObject.getValue("Snapshot")),
                ).wire()

                "replace" -> receiver.replace(revisions.getValue(step.string("revision"))).wire()

                "delta" -> receiver.applyDelta(
                    Delta.fromJson(deltas.getValue(step.string("delta")).jsonObject.getValue("Delta")),
                ).wire()

                "event" -> when (
                    val result = receiver.routeEvent(ViewEvent.fromJson(step.getValue("frame"))) { _, _ ->
                        step["deny"]?.jsonPrimitive?.boolean != true
                    }
                ) {
                    is ViewEventResult.Accepted -> {
                        assertEquals(step.string("action"), result.action, label)
                        "accepted"
                    }
                    ViewEventResult.Denied -> "denied"
                    ViewEventResult.Conflict -> "conflict"
                    ViewEventResult.Duplicate -> "duplicate"
                    ViewEventResult.Stale -> "stale"
                    ViewEventResult.Unavailable -> "unavailable"
                    ViewEventResult.WrongInstance -> "wrong-instance"
                }

                else -> error("Unknown fixture operation")
            }

            if (expected == "invalid") assertFails(label) { replay() } else assertEquals(expected, replay(), label)
            step["current_revision"]?.let { assertEquals(if (it == JsonNull) null else it.jsonPrimitive.long, receiver.revision, label) }
            step["epoch"]?.let { assertEquals(if (it == JsonNull) null else it.jsonPrimitive.long, receiver.epoch, label) }
            step["rows"]?.jsonArray?.map { it.jsonPrimitive.content }?.let {
                assertEquals(it, receiver.nodes.first { node -> node.id == "results" }.children, label)
            }
            step["message"]?.jsonPrimitive?.content?.let {
                assertEquals(it, receiver.signals["message"], label)
                assertEquals(it, receiver.nodes.first { node -> node.id == "message" }.text, label)
            }
            for (node in receiver.nodes) {
                val before = identities[node.id]
                val generation = (before?.second ?: 0) + if (before?.first === node.identity) 0 else 1
                identities[node.id] = node.identity to generation
            }
            for ((id, generation) in step["identities"]?.jsonObject.orEmpty()) {
                assertEquals(generation.jsonPrimitive.int, identities[id]?.second, "$label $id")
            }
        }

        val badSum = revisions.getValue("new-identity").jsonObject.toMutableMap()
        val definition = badSum.getValue("definition").jsonObject.toMutableMap()
        val nodes = definition.getValue("nodes").jsonObject.toMutableMap()
        nodes["message"] = Json.parseToJsonElement(
            """{"role":"text", "text":{"op":"call","args":["add",9007199254740991,2,-9007199254740991]}}""",
        )
        definition["nodes"] = JsonObject(nodes)
        badSum["definition"] = JsonObject(definition)
        val before = requireNotNull(receiver.revision)
        badSum["revision"] = JsonPrimitive(before + 1)
        assertFails { receiver.replace(JsonObject(badSum)) }
        assertEquals(before, receiver.revision)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun ViewUpdate.wire(): String = name.lowercase().replace('_', '-')
}
