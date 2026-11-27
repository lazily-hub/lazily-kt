package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ViewConformanceTest {
    private val json = Json { }

    @Test
    fun view_conformance() {
        val dir = ConformanceFixtures.path("view")
        if (!Files.isDirectory(dir)) return
        val names = Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }.filter { it.endsWith(".json") }.sorted().toList()
        }
        assertEquals(listOf("batch_send.json", "counter.json", "field_change.json", "malformed_rejected.json"), names)
        names.forEach { replay(it) }
    }

    private fun load(name: String): JsonObject =
        json.parseToJsonElement(ConformanceFixtures.read("view/$name")).jsonObject

    private fun replay(name: String) {
        val doc = load(name)
        if (doc.getValue("kind").jsonPrimitiveContent() == "ViewMalformed") {
            for (case in doc.getValue("cases").jsonArray) {
                val obj = case.jsonObject
                val label = obj.getValue("name").jsonPrimitiveContent()
                assertFails { View.install(Context(), obj.getValue("view")) }
                assertTrue(label.isNotEmpty())
            }
            return
        }
        val ctx = Context()
        val view = View.install(ctx, doc.getValue("view"))
        view.subscription
        assertEquals(emptyList<Any?>(), anyOf(doc.getValue("trace")))
        assertSame(doc.getValue("signals"), view.signalsJson())
        assertSame(doc.getValue("expressions"), view.expressionsJson())
        assertSame(doc.getValue("layout"), view.layoutJson())
        assertSame(doc.getValue("chart"), view.chartJson())
        for (step in doc.getValue("steps").jsonArray) {
            val row = step.jsonObject
            val trace = view.dispatchJson(
                row.getValue("node").jsonPrimitiveContent(),
                row.getValue("event").jsonPrimitiveContent(),
                row["value"] ?: JsonNull,
            )
            assertSame(row.getValue("signals"), view.signalsJson())
            assertSame(row.getValue("expressions"), view.expressionsJson())
            assertSame(row.getValue("trace"), trace)
            assertSame(row.getValue("chart"), view.chartJson())
        }
    }

    private fun assertSame(want: JsonElement, got: Any?) {
        assertEquals(anyOf(want), got)
    }

    private fun anyOf(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" || value.content == "false" -> value.content == "true"
            value.content.toLongOrNull() != null && '.' !in value.content && 'e' !in value.content && 'E' !in value.content ->
                value.content.toLong()
            else -> error("unsupported json value ${value.content}")
        }
        is JsonArray -> value.map { anyOf(it) }
        is JsonObject -> value.mapValues { anyOf(it.value) }
    }

    private fun JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content
}
