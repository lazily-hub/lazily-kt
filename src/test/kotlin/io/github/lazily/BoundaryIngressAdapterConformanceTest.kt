package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertTrue

class BoundaryIngressAdapterConformanceTest {
    private companion object {
        const val FIXTURE = "ingress/boundary_ingress_adapter.json"
    }

    private data class Delivery(
        val id: String,
        val targets: MutableSet<String>,
        val acked: MutableSet<String> = sortedSetOf(),
    )

    private class Model(
        private val maxBuffered: Int,
        private val freshnessHorizon: Long,
    ) {
        private var phase = "detached"
        private var generation = 0L
        private var cursor: Long? = null
        private val buffered = TreeMap<Long, JsonObject>()
        private var sourceKeys: MutableSet<String> = sortedSetOf()
        private var members: MutableSet<String> = sortedSetOf()
        private var validation = "valid"
        private var replayFrom: Long? = null
        private var staleEvents = 0L
        private var delivery: Delivery? = null
        private var lastStampedAt: Long? = null
        private var now = 0L
        private var revision = 0L

        private fun changed() {
            revision++
        }

        private fun applyPayload(op: JsonObject) {
            when (op.string("action")) {
                "upsert" -> sourceKeys += op.string("key")
                "remove" -> sourceKeys -= op.string("key")
                "validate" -> validation = op.string("validation")
                else -> error("unknown boundary event action")
            }
            cursor = op.long("cursor")
            lastStampedAt = op.long("stamped_at")
            phase = if (validation == "valid") "live" else "invalid"
            replayFrom = null
        }

        private fun drain() {
            while (cursor != null) {
                val next = buffered.remove(cursor!! + 1) ?: break
                applyPayload(next)
            }
            if (buffered.isNotEmpty()) {
                phase = "replay_required"
                replayFrom = cursor!! + 1
            }
        }

        fun apply(op: JsonObject) {
            when (op.string("type")) {
                "subscribe" -> {
                    val next = op.long("generation")
                    if (next < generation) return
                    generation = next
                    cursor = null
                    buffered.clear()
                    sourceKeys.clear()
                    members.clear()
                    validation = "valid"
                    replayFrom = null
                    phase = "bootstrapping"
                    changed()
                }
                "snapshot" -> {
                    val next = op.long("generation")
                    if (next < generation) {
                        staleEvents++
                        changed()
                        return
                    }
                    if (next > generation) {
                        generation = next
                        buffered.clear()
                    }
                    cursor = op.long("cursor")
                    lastStampedAt = op.long("stamped_at")
                    sourceKeys = op.strings("source_keys").toSortedSet()
                    members = op.strings("members").toSortedSet()
                    validation = op.string("validation")
                    phase = if (validation == "valid") "live" else "invalid"
                    replayFrom = null
                    buffered.keys.removeIf { it <= cursor!! }
                    drain()
                    changed()
                }
                "event" -> {
                    val next = op.long("generation")
                    val eventCursor = op.long("cursor")
                    if (next < generation) {
                        staleEvents++
                        changed()
                        return
                    }
                    if (next > generation) {
                        generation = next
                        cursor = null
                        buffered.clear()
                        sourceKeys.clear()
                        members.clear()
                        phase = "bootstrapping"
                        replayFrom = null
                    }
                    if (cursor == null) {
                        if (buffered.size >= maxBuffered && eventCursor !in buffered) {
                            phase = "backpressured"
                            replayFrom = 0
                            changed()
                            return
                        }
                        if (buffered.putIfAbsent(eventCursor, op) == null) changed()
                        return
                    }
                    if (eventCursor <= cursor!! || eventCursor in buffered) return
                    if (eventCursor == cursor!! + 1) {
                        applyPayload(op)
                        drain()
                        changed()
                        return
                    }
                    if (buffered.size >= maxBuffered) {
                        phase = "backpressured"
                        replayFrom = cursor!! + 1
                        changed()
                        return
                    }
                    buffered[eventCursor] = op
                    phase = "replay_required"
                    replayFrom = cursor!! + 1
                    changed()
                }
                "member_join" -> {
                    val member = op.string("member")
                    if (!members.add(member)) return
                    delivery?.takeIf { it.targets.isEmpty() }?.targets?.add(member)
                    changed()
                }
                "member_leave" -> {
                    if (members.remove(op.string("member"))) changed()
                }
                "open_receipt" -> {
                    delivery = Delivery(op.string("receipt_id"), members.toSortedSet())
                    changed()
                }
                "ack" -> {
                    val open = delivery ?: return
                    if (open.id != op.string("receipt_id")) return
                    val member = op.string("member")
                    if (member in open.targets && open.acked.add(member)) changed()
                }
                "tick" -> {
                    val before = fresh
                    now = op.long("now")
                    if (fresh != before) changed()
                }
                else -> error("unknown boundary ingress op")
            }
        }

        private val fresh: Boolean
            get() = lastStampedAt?.let { now - it <= freshnessHorizon } ?: false

        fun projection(): JsonObject =
            buildJsonObject {
                put("phase", phase)
                put("generation", generation)
                putNullableLong("cursor", cursor)
                put("buffered_cursors", longs(buffered.keys))
                put("source_keys", strings(sourceKeys))
                put("members", strings(members))
                put("validation", validation)
                putNullableLong("replay_from", replayFrom)
                put("stale_events", staleEvents)
                put(
                    "delivery",
                    delivery?.let { open ->
                        buildJsonObject {
                            put("receipt_id", open.id)
                            put("targets", strings(open.targets))
                            put("acked", strings(open.acked))
                            put(
                                "converged",
                                open.targets.isNotEmpty() && open.targets.all { it in open.acked },
                            )
                        }
                    } ?: JsonNull,
                )
                put("ready", phase == "live" && validation == "valid")
                put("fresh", fresh)
                put("observation_revision", revision)
                put("revision", revision)
            }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(
            key: String,
            value: Long?,
        ) {
            put(key, value?.let(::JsonPrimitive) ?: JsonNull)
        }

        private fun strings(values: Iterable<String>): JsonElement =
            buildJsonArray { values.sorted().forEach { add(JsonPrimitive(it)) } }

        private fun longs(values: Iterable<Long>): JsonElement =
            buildJsonArray { values.sorted().forEach { add(JsonPrimitive(it)) } }
    }

    @Test
    fun `replays canonical boundary ingress adapter contract`() {
        val fixture = Json.parseToJsonElement(ConformanceFixtures.read(FIXTURE)).jsonObject
        var replayed = 0
        for (scenario in ConformanceScenarios.of(FIXTURE, fixture)) {
            val policy =
                fixture["policy"]!!.jsonObject.toMutableMap().apply {
                    scenario["policy"]?.jsonObject?.let(::putAll)
                }
            val model =
                Model(
                    policy.getValue("max_buffered").jsonPrimitive.int,
                    policy.getValue("freshness_horizon").jsonPrimitive.long,
                )
            for ((index, stepElement) in scenario["steps"]!!.jsonArray.withIndex()) {
                val step = stepElement.jsonObject
                model.apply(step["op"]!!.jsonObject)
                val actual = model.projection()
                step["expected"]!!.jsonObject.consuming("$FIXTURE ${scenario.string("id")} step $index") { expected ->
                    for (key in expected.keys) {
                        expected.assertKeyValue(key) { actual.getValue(key) }
                    }
                }
                replayed++
            }
        }
        assertTrue(replayed > 0)
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.strings(key: String): List<String> =
    getValue(key).jsonArray.map { it.jsonPrimitive.content }
