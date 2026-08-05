package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DependencyAvailabilityConformanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun stateJson(state: DependencyAvailability<Int>): JsonElement =
        when (state) {
            DependencyAvailability.Unavailable -> JsonPrimitive("Unavailable")
            is DependencyAvailability.Available ->
                buildJsonObject { put("Available", state.value) }
        }

    @Test
    fun `exact-key dependency availability fixture`() {
        val path = "collections/dependency_reactive_availability.json"
        val fixture = json.parseToJsonElement(ConformanceFixtures.read(path)).jsonObject
        val wanted = fixture["key"]!!.jsonPrimitive.content
        val ctx = Context()
        val map = DependencyMap<String, Int>(ctx)
        var recomputes = 0
        val reader =
            ctx.slotAny {
                recomputes += 1
                map.observeDependency(wanted, this)
            }
        var identity: Any? = null

        fixture["steps"]!!.jsonArray.forEachIndexed { index, element ->
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            when (op["type"]!!.jsonPrimitive.content) {
                "observe_dependency" -> ctx.getSlotAny(reader)
                "publish" ->
                    map.publish(
                        op["key"]!!.jsonPrimitive.content,
                        op["value"]!!.jsonPrimitive.int,
                    )
                "unpublish" -> map.unpublish(op["key"]!!.jsonPrimitive.content)
                else -> error("unknown dependency operation: ${op["type"]}")
            }

            val state = ctx.getSlotAny(reader) as DependencyAvailability<Int>
            if (identity == null) identity = map.handle(wanted)
            val expected = AssertionKeys("$path step $index", step["expected"]!!.jsonObject)
            expected.assertKeyValue("state") { stateJson(state) }
            expected.assertInt("recomputes") { recomputes }
            expected.assertInt("present_count") { map.presentCount }
            expected.assertString("identity") {
                assertEquals(identity, map.handle(wanted))
                "wanted-1"
            }
            expected.requireAllSatisfied()
        }
    }

    @Test
    fun `thread-safe and async flavors preserve source identity`() {
        val threadCtx = ThreadSafeContext()
        val thread = ThreadSafeDependencyMap<String, Int>()
        assertEquals(DependencyAvailability.Unavailable, thread.observeDependency(threadCtx, "wanted"))
        val threadHandle = thread.handle("wanted")
        thread.publish(threadCtx, "wanted", 7)
        assertSame(threadHandle, thread.handle("wanted"))
        assertEquals(DependencyAvailability.Available(7), thread.observeDependency(threadCtx, "wanted"))

        val asyncCtx = AsyncContext()
        val async = AsyncDependencyMap<String, Int>()
        assertEquals(DependencyAvailability.Unavailable, async.observeDependency(asyncCtx, "wanted"))
        val asyncHandle = async.handle("wanted")
        async.publish(asyncCtx, "wanted", 8)
        assertSame(asyncHandle, async.handle("wanted"))
        assertEquals(DependencyAvailability.Available(8), async.observeDependency(asyncCtx, "wanted"))
    }
}
