package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.PrintWriter

/**
 * NDJSON adapter used by the cross-binding interoperability suite.
 *
 * This is test infrastructure, not a production daemon. Wire values and frames
 * are parsed by [IpcMessage], and all merge/dedup decisions are delegated to
 * [CrdtPlaneRuntime].
 */
private class InteropPeer {
    private var peerId: PeerId? = null
    private var logical: Long = 0L
    private var runtime: CrdtPlaneRuntime? = null
    private val addresses = linkedSetOf<Pair<NodeId, String?>>()
    private val stdlib = mutableMapOf<String, StdlibFeature>()

    fun handle(request: JsonObject): JsonObject =
        when (request.string("cmd")) {
            "hello" -> hello(request)
            "local_set" -> localSet(request)
            "deliver" -> deliver(request)
            "snapshot" -> snapshot()
            "feature_reset" -> featureReset(request)
            "feature_step" -> featureStep(request)
            "feature_observe" -> featureObserve(request)
            "bye" -> ok()
            "link_open", "link_send", "link_recv", "link_close", "link_stats" ->
                buildJsonObject {
                    put("ok", false)
                    put("error", "unsupported channel")
                    put("unsupported", true)
                }
            else -> failure("unknown command")
        }

    private fun hello(request: JsonObject): JsonObject {
        if (request.long("protocol_version") != PROTOCOL_VERSION) {
            return failure("unsupported protocol_version")
        }
        val assigned = request.long("peer")
        peerId = assigned
        logical = 0L
        runtime = CrdtPlaneRuntime(assigned)
        addresses.clear()
        stdlib.clear()
        return buildJsonObject {
            put("ok", true)
            put("binding", "lazily-kt")
            put("version", "0.38.1")
            put("protocol_version", PROTOCOL_VERSION)
            put(
                "features",
                strings(
                    "distributed_crdt",
                    "stdlib_timer_v1",
                    "stdlib_timeout_v1",
                    "stdlib_revision_barrier_v1",
                ),
            )
            put("codecs", strings("json"))
            put("channels", JsonArray(emptyList()))
            put("channel_variants", JsonObject(emptyMap()))
            put("platform_profile", "portable")
            put("carve_outs", strings("msgpack", "transport_links"))
        }
    }

    private fun featureReset(request: JsonObject): JsonObject {
        val feature = request.string("feature") ?: return failure("missing field: feature")
        if (feature !in STDLIB_FEATURES) {
            return buildJsonObject {
                put("ok", false)
                put("error", "unsupported feature $feature")
                put("unsupported", true)
            }
        }
        stdlib[feature] = StdlibFeature(feature)
        return buildJsonObject {
            put("ok", true)
            put("feature", feature)
        }
    }

    private fun featureStep(request: JsonObject): JsonObject {
        val featureName = request.string("feature") ?: error("missing field: feature")
        val feature =
            stdlib[featureName]
                ?: error("feature $featureName must be reset before stepping")
        val observation = feature.step(request.required("step").jsonObject)
        return featureResponse(featureName, observation)
    }

    private fun featureObserve(request: JsonObject): JsonObject {
        val featureName = request.string("feature") ?: error("missing field: feature")
        val feature =
            stdlib[featureName]
                ?: error("feature $featureName must be reset before observation")
        val observation =
            feature.last
                ?: error("feature $featureName has no observation")
        return featureResponse(featureName, observation)
    }

    private fun localSet(request: JsonObject): JsonObject {
        val (plane, assigned) = ready()
        val node = request.long("node")
        val at = request.long("at")
        val key =
            request["key"]?.let {
                if (it is JsonNull) null else it.jsonPrimitive.content
            }
        logical += 1L
        val op =
            CrdtOp(
                node = node,
                key = key?.let(NodeKey::from),
                stamp = WireStamp(at, logical, assigned),
                state = IpcValue.fromJson(request.required("state")),
            )
        check(plane.ingest(CrdtSync(emptyList(), listOf(op)), at) == 1) {
            "production runtime rejected fresh local op"
        }
        addresses += node to key
        val frame =
            IpcMessage
                .ofCrdtSync(
                    CrdtSync(plane.wireFrontier(), listOf(op)),
                ).toJson()
        return buildJsonObject {
            put("ok", true)
            put("frame", frame)
        }
    }

    private fun deliver(request: JsonObject): JsonObject {
        val (plane) = ready()
        val message = IpcMessage.fromJson(request.required("frame"))
        val sync =
            (message as? IpcMessage.CrdtSyncMessage)?.sync
                ?: error("deliver requires CrdtSync")
        sync.ops.forEach { addresses += it.node to it.key?.path }
        return buildJsonObject {
            put("ok", true)
            put("applied", plane.ingest(sync, request.long("at")))
        }
    }

    private fun snapshot(): JsonObject {
        val (plane) = ready()
        val cells =
            addresses
                .sortedWith(compareBy<Pair<NodeId, String?>>({ it.first }, { it.second ?: "" }))
                .mapNotNull { (node, key) ->
                    plane.value(node)?.let { state ->
                        buildJsonObject {
                            put("node", node)
                            put("key", key?.let(::JsonPrimitive) ?: JsonNull)
                            put("state", state.toJson())
                        }
                    }
                }
        return buildJsonObject {
            put("ok", true)
            put("cells", JsonArray(cells))
        }
    }

    private fun ready(): Pair<CrdtPlaneRuntime, PeerId> =
        (runtime ?: error("hello must run first")) to
            (peerId ?: error("hello must run first"))
}

private class StdlibFeature(
    private val name: String,
) {
    private var timer: Timer? = null
    private var timeout: Timeout<String>? = null
    private var barrier: RevisionBarrier? = null

    var last: JsonObject? = null
        private set

    fun step(step: JsonObject): JsonObject {
        val observation =
            when (name) {
                "stdlib_timer_v1" -> timerStep(step)
                "stdlib_timeout_v1" -> timeoutStep(step)
                "stdlib_revision_barrier_v1" -> barrierStep(step)
                else -> error("unsupported feature $name")
            }
        last = observation
        return observation
    }

    private fun timerStep(step: JsonObject): JsonObject =
        when (step.string("op")) {
            "start" -> {
                try {
                    Timer(step.ulong("now"), step.ulong("duration")).also { timer = it }
                    buildJsonObject {
                        put("outcome", "pending")
                        putULong("deadline", timer!!.deadline)
                    }
                } catch (failure: StdlibUnavailableException) {
                    timer = null
                    buildJsonObject {
                        put("outcome", "unavailable")
                        put("reason", failure.reason.wireName)
                    }
                }
            }

            "observe" ->
                timerObservation(
                    checkNotNull(timer) { "timer feature is not started" }.observe(step.ulong("now")),
                )

            else -> error("unsupported timer feature step ${step.string("op")}")
        }

    private fun timeoutStep(step: JsonObject): JsonObject =
        when (step.string("op")) {
            "start" -> {
                try {
                    Timeout<String>(step.ulong("now"), step.ulong("duration")).also { timeout = it }
                    buildJsonObject {
                        put("outcome", "pending")
                        putULong("deadline", timeout!!.deadline)
                    }
                } catch (failure: StdlibUnavailableException) {
                    timeout = null
                    buildJsonObject {
                        put("outcome", "unavailable")
                        put("reason", failure.reason.wireName)
                    }
                }
            }

            "poll" -> {
                var operationCalls = 0
                var cancellationCalls = 0
                val observation =
                    checkNotNull(timeout) { "timeout feature is not started" }.poll(
                        now = step.ulong("now"),
                        operation = {
                            operationCalls += 1
                            val result: TimeoutOperation<String> =
                                when (step.string("operation")) {
                                    "pending" -> TimeoutOperation.Pending
                                    "completed" ->
                                        TimeoutOperation.Completed(
                                            step.string("value") ?: error("missing field: value"),
                                        )
                                    "unavailable" -> TimeoutOperation.Unavailable
                                    else -> error("unsupported timeout operation ${step.string("operation")}")
                                }
                            result
                        },
                        cancellation = {
                            cancellationCalls += 1
                            step.cancellation()
                        },
                    )
                timeoutObservation(observation, operationCalls, cancellationCalls)
            }

            else -> error("unsupported timeout feature step ${step.string("op")}")
        }

    private fun barrierStep(step: JsonObject): JsonObject {
        var cancellationCalls = 0
        val observation =
            when (step.string("op")) {
                "start" ->
                    RevisionBarrier(
                        revision = step.ulong("revision"),
                        requiredRevision = step.ulong("required_revision"),
                        deadline = step.nullableULong("deadline"),
                    ).also { barrier = it }.receipt("")

                "observe" ->
                    checkNotNull(barrier) { "barrier feature is not started" }.observe(
                        now = step.ulong("now"),
                        predicate = step.required("predicate").jsonPrimitive.boolean,
                        cancellation = {
                            cancellationCalls += 1
                            step.cancellation()
                        },
                    )

                "register_recheck" ->
                    checkNotNull(barrier) { "barrier feature is not started" }.registerRecheck(
                        now = step.ulong("now"),
                        observedRevision = step.ulong("observed_revision"),
                        predicate = step.required("predicate").jsonPrimitive.boolean,
                    )

                "advance" ->
                    checkNotNull(barrier) { "barrier feature is not started" }.advance(
                        revision = step.ulong("revision"),
                        predicate = step.required("predicate").jsonPrimitive.boolean,
                    )

                "dispose" -> checkNotNull(barrier) { "barrier feature is not started" }.dispose()
                "receipt" ->
                    checkNotNull(barrier) { "barrier feature is not started" }
                        .receipt(step.string("key") ?: error("missing field: key"))

                else -> error("unsupported revision barrier feature step ${step.string("op")}")
            }
        return barrierObservation(
            observation,
            cancellationCalls.takeIf { step.string("op") == "observe" },
        )
    }
}

private const val PROTOCOL_VERSION = 1L
private val controlJson = Json
private val STDLIB_FEATURES =
    setOf(
        "stdlib_timer_v1",
        "stdlib_timeout_v1",
        "stdlib_revision_barrier_v1",
    )

private fun JsonObject.required(name: String): JsonElement = this[name] ?: error("missing field: $name")

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(name: String): Long = required(name).jsonPrimitive.long

private fun JsonObject.ulong(name: String): ULong = required(name).jsonPrimitive.content.toULong()

private fun JsonObject.nullableULong(name: String): ULong? =
    required(name)
        .takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content
        ?.toULong()

private fun JsonObject.cancellation(): TimeoutCancellation =
    when (string("cancellation")) {
        "pending" -> TimeoutCancellation.Pending
        "cancelled" -> TimeoutCancellation.Cancelled
        "unavailable" -> TimeoutCancellation.Unavailable
        else -> error("unsupported cancellation ${string("cancellation")}")
    }

private fun strings(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

private fun featureResponse(
    feature: String,
    observation: JsonObject,
): JsonObject =
    buildJsonObject {
        put("ok", true)
        put("feature", feature)
        put("observation", observation)
    }

private fun timerObservation(observation: TimerObservation): JsonObject =
    buildJsonObject {
        put("outcome", observation.outcome.wireName)
        observation.deadline?.let { putULong("deadline", it) }
        observation.firedAt?.let { putULong("fired_at", it) }
        observation.reason?.let { put("reason", it.wireName) }
    }

private fun timeoutObservation(
    observation: TimeoutObservation<String>,
    operationCalls: Int,
    cancellationCalls: Int,
): JsonObject =
    buildJsonObject {
        put("outcome", observation.outcome.wireName)
        observation.deadline?.let { putULong("deadline", it) }
        if (observation.outcome == TimeoutOutcome.Completed) {
            put("value", observation.value)
        }
        observation.reason?.let { put("reason", it.wireName) }
        put("operation_calls", operationCalls)
        put("cancellation_calls", cancellationCalls)
    }

private fun barrierObservation(
    observation: RevisionBarrierObservation,
    cancellationCalls: Int?,
): JsonObject =
    buildJsonObject {
        put("outcome", observation.outcome.wireName)
        observation.reason?.let { put("reason", it.wireName) }
        putULong("revision", observation.revision)
        putULong("generation", observation.generation)
        cancellationCalls?.let { put("cancellation_calls", it) }
    }

private fun JsonObjectBuilder.putULong(
    name: String,
    value: ULong,
) {
    put(name, controlJson.parseToJsonElement(value.toString()))
}

private fun ok(): JsonObject = buildJsonObject { put("ok", true) }

private fun failure(message: String): JsonObject =
    buildJsonObject {
        put("ok", false)
        put("error", message)
    }

private fun selfCheck() {
    val peer = InteropPeer()
    val hello =
        peer.handle(
            buildJsonObject {
                put("cmd", "hello")
                put("peer", 1)
                put("protocol_version", PROTOCOL_VERSION)
            },
        )
    check(hello["ok"]?.jsonPrimitive?.content == "true")
    val advertised =
        hello
            .required("features")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
    check(STDLIB_FEATURES.all { it in advertised })
    val local =
        peer.handle(
            controlJson
                .parseToJsonElement(
                    """{"cmd":"local_set","node":7,"key":null,"state":{"Inline":[65]},"at":10}""",
                ).jsonObject,
        )
    val frame = local.required("frame")
    val delivered =
        peer.handle(
            buildJsonObject {
                put("cmd", "deliver")
                put("frame", frame)
                put("at", 11)
            },
        )
    check(delivered.long("applied") == 0L)
    check(
        peer
            .handle(buildJsonObject { put("cmd", "snapshot") })
            .required("cells")
            .toString()
            .contains("\"Inline\":[65]"),
    )

    val featureSteps =
        mapOf(
            "stdlib_timer_v1" to
                listOf(
                    """{"op":"start","now":0,"duration":1}""",
                    """{"op":"observe","now":1}""",
                ),
            "stdlib_timeout_v1" to
                listOf(
                    """{"op":"start","now":0,"duration":1}""",
                    """{"op":"poll","now":1,"operation":"completed","value":"late","cancellation":"cancelled"}""",
                ),
            "stdlib_revision_barrier_v1" to
                listOf(
                    """{"op":"start","revision":0,"required_revision":1,"deadline":null}""",
                    """{"op":"advance","revision":1,"predicate":true}""",
                ),
        )
    featureSteps.forEach { (feature, steps) ->
        check(
            peer
                .handle(
                    buildJsonObject {
                        put("cmd", "feature_reset")
                        put("feature", feature)
                    },
                )["ok"]
                ?.jsonPrimitive
                ?.content == "true",
        )
        steps.forEach { step ->
            val response =
                peer.handle(
                    buildJsonObject {
                        put("cmd", "feature_step")
                        put("feature", feature)
                        put("step", controlJson.parseToJsonElement(step))
                    },
                )
            check(response["ok"]?.jsonPrimitive?.content == "true")
        }
        check(
            peer
                .handle(
                    buildJsonObject {
                        put("cmd", "feature_observe")
                        put("feature", feature)
                    },
                )["ok"]
                ?.jsonPrimitive
                ?.content == "true",
        )
    }
}

fun main(args: Array<String>) {
    if (args.contains("--self-check")) {
        selfCheck()
        System.err.println("lazily-kt interop peer self-check: ok")
        return
    }

    val peer = InteropPeer()
    val output = PrintWriter(System.out, true)
    for (line in System.`in`.bufferedReader().lineSequence()) {
        var request: JsonObject? = null
        val response =
            try {
                request = controlJson.parseToJsonElement(line).jsonObject
                peer.handle(request)
            } catch (error: Exception) {
                failure(error.message ?: error::class.simpleName ?: "peer error")
            }
        output.println(response)
        if (request?.string("cmd") == "bye") break
    }
}
