package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/**
 * Command / RPC message plane (`command-plane-v1`).
 *
 * An evented command message family that is an additive sibling to Snapshot /
 * Delta / CrdtSync. The one hard rule: terminal authority is the causal receipt,
 * not the event or the transport. `observed` / `accepted` / `started` events are
 * non-terminal progress; a command becomes terminal only when a terminal
 * [CausalReceipt] folds in. [CommandRpcClient] is derived behavior over the
 * [CommandProjection] reducer — a unary `call` resolves only on a terminal
 * projection.
 *
 * Hand-rolled JSON parity with [Receipt] and [Ipc], keyed off the same
 * externally-tagged wire form the Rust reference serializes.
 */

private val commandJson = Json { prettyPrint = false }

private fun JsonElement.commandObject(name: String): JsonObject =
    this as? JsonObject ?: error("$name must be a JSON object")

private fun JsonObject.commandRequired(name: String): JsonElement =
    this[name] ?: error("missing required field: $name")

private fun JsonObject.commandString(name: String): String =
    commandRequired(name).jsonPrimitive.content

private fun JsonObject.commandLong(name: String): Long =
    commandRequired(name).jsonPrimitive.long

private fun JsonObject.commandBool(name: String): Boolean =
    commandRequired(name).jsonPrimitive.boolean

private fun JsonObject.commandOptionalString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

enum class DedupePolicy(val wireName: String) {
    None("none"),
    SameIdempotencyKey("same_idempotency_key"),
    SameCommandId("same_command_id"),
    ;

    companion object {
        fun fromWire(value: String): DedupePolicy =
            entries.firstOrNull { it.wireName == value } ?: error("unknown dedupe policy: $value")
    }
}

data class CommandPolicy(
    val dedupe: DedupePolicy,
    val supersede: Boolean,
    val cancelOnPreempt: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("dedupe", dedupe.wireName)
        put("supersede", supersede)
        put("cancel_on_preempt", cancelOnPreempt)
    }

    companion object {
        fun fromJson(element: JsonElement): CommandPolicy {
            val obj = element.commandObject("CommandPolicy")
            return CommandPolicy(
                dedupe = DedupePolicy.fromWire(obj.commandString("dedupe")),
                supersede = obj.commandBool("supersede"),
                cancelOnPreempt = obj.commandBool("cancel_on_preempt"),
            )
        }
    }
}

data class CommandSubmit(
    val commandId: String,
    val causationId: String,
    val source: String,
    val target: String,
    val namespace: String,
    val name: String,
    val authorityGeneration: Long,
    val idempotencyKey: String,
    val deadlineMs: Long,
    val policy: CommandPolicy,
    val payloadType: String,
    val payloadHash: String,
    val payload: IpcValue,
    val requiredFeatures: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("command_id", commandId)
        put("causation_id", causationId)
        put("source", source)
        put("target", target)
        put("namespace", namespace)
        put("name", name)
        put("authority_generation", authorityGeneration)
        put("idempotency_key", idempotencyKey)
        put("deadline_ms", deadlineMs)
        put("policy", policy.toJson())
        put("payload_type", payloadType)
        put("payload_hash", payloadHash)
        put("payload", payload.toJson())
        put("required_features", buildJsonArray { requiredFeatures.forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        fun fromJson(element: JsonElement): CommandSubmit {
            val obj = element.commandObject("CommandSubmit")
            return CommandSubmit(
                commandId = obj.commandString("command_id"),
                causationId = obj.commandString("causation_id"),
                source = obj.commandString("source"),
                target = obj.commandString("target"),
                namespace = obj.commandString("namespace"),
                name = obj.commandString("name"),
                authorityGeneration = obj.commandLong("authority_generation"),
                idempotencyKey = obj.commandString("idempotency_key"),
                deadlineMs = obj.commandLong("deadline_ms"),
                policy = CommandPolicy.fromJson(obj.commandRequired("policy")),
                payloadType = obj.commandString("payload_type"),
                payloadHash = obj.commandString("payload_hash"),
                payload = IpcValue.fromJson(obj.commandRequired("payload")),
                requiredFeatures = obj.commandRequired("required_features").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }
}

data class CommandCancel(
    val commandId: String,
    val causationId: String,
    val source: String,
    val authorityGeneration: Long,
    val reason: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("command_id", commandId)
        put("causation_id", causationId)
        put("source", source)
        put("authority_generation", authorityGeneration)
        put("reason", reason?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        fun fromJson(element: JsonElement): CommandCancel {
            val obj = element.commandObject("CommandCancel")
            return CommandCancel(
                commandId = obj.commandString("command_id"),
                causationId = obj.commandString("causation_id"),
                source = obj.commandString("source"),
                authorityGeneration = obj.commandLong("authority_generation"),
                reason = obj.commandOptionalString("reason"),
            )
        }
    }
}

enum class CommandEventKind(val wireName: String) {
    Observed("observed"),
    Accepted("accepted"),
    Started("started"),
    Progress("progress"),
    Cancelled("cancelled"),
    Superseded("superseded"),
    TimedOut("timed_out"),
    ;

    companion object {
        fun fromWire(value: String): CommandEventKind =
            entries.firstOrNull { it.wireName == value } ?: error("unknown command event kind: $value")
    }
}

data class CommandEvent(
    val eventId: String,
    val commandId: String,
    val kind: CommandEventKind,
    val generation: Long,
    val detail: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("event_id", eventId)
        put("command_id", commandId)
        put("kind", kind.wireName)
        put("generation", generation)
        put("detail", detail?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        fun fromJson(element: JsonElement): CommandEvent {
            val obj = element.commandObject("CommandEvent")
            return CommandEvent(
                eventId = obj.commandString("event_id"),
                commandId = obj.commandString("command_id"),
                kind = CommandEventKind.fromWire(obj.commandString("kind")),
                generation = obj.commandLong("generation"),
                detail = obj.commandOptionalString("detail"),
            )
        }
    }
}

data class CommandEvents(val events: List<CommandEvent>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("events", buildJsonArray { events.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(element: JsonElement): CommandEvents {
            val obj = element.commandObject("CommandEvents")
            return CommandEvents(obj.commandRequired("events").jsonArray.map { CommandEvent.fromJson(it) })
        }
    }
}

enum class CommandStatus(val wireName: String) {
    Submitted("submitted"),
    Accepted("accepted"),
    Running("running"),
    Applied("applied"),
    Rejected("rejected"),
    Cancelled("cancelled"),
    Superseded("superseded"),
    TimedOut("timed_out"),
    ;

    val isTerminal: Boolean
        get() = this == Applied || this == Rejected || this == Cancelled ||
            this == Superseded || this == TimedOut

    companion object {
        fun fromWire(value: String): CommandStatus =
            entries.firstOrNull { it.wireName == value } ?: error("unknown command status: $value")
    }
}

data class CommandProjectionEntry(
    val commandId: String,
    val status: CommandStatus,
    val terminal: Boolean,
    val generation: Long,
    val reason: String? = null,
    val terminalReceiptId: String? = null,
    val lastEventId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("command_id", commandId)
        put("status", status.wireName)
        put("terminal", terminal)
        put("generation", generation)
        put("reason", reason?.let { JsonPrimitive(it) } ?: JsonNull)
        put("terminal_receipt_id", terminalReceiptId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("last_event_id", lastEventId?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        fun fromJson(element: JsonElement): CommandProjectionEntry {
            val obj = element.commandObject("CommandProjectionEntry")
            return CommandProjectionEntry(
                commandId = obj.commandString("command_id"),
                status = CommandStatus.fromWire(obj.commandString("status")),
                terminal = obj.commandBool("terminal"),
                generation = obj.commandLong("generation"),
                reason = obj.commandOptionalString("reason"),
                terminalReceiptId = obj.commandOptionalString("terminal_receipt_id"),
                lastEventId = obj.commandOptionalString("last_event_id"),
            )
        }
    }
}

data class CommandProjectionImage(
    val generation: Long,
    val commands: List<CommandProjectionEntry>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("generation", generation)
        put("commands", buildJsonArray { commands.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(element: JsonElement): CommandProjectionImage {
            val obj = element.commandObject("CommandProjectionImage")
            return CommandProjectionImage(
                generation = obj.commandLong("generation"),
                commands = obj.commandRequired("commands").jsonArray.map { CommandProjectionEntry.fromJson(it) },
            )
        }
    }
}

sealed interface CommandMessage {
    fun toJson(): JsonObject

    data class Submit(val submit: CommandSubmit) : CommandMessage {
        override fun toJson(): JsonObject = buildJsonObject { put("CommandSubmit", submit.toJson()) }
    }

    data class Cancel(val cancel: CommandCancel) : CommandMessage {
        override fun toJson(): JsonObject = buildJsonObject { put("CommandCancel", cancel.toJson()) }
    }

    data class Events(val events: CommandEvents) : CommandMessage {
        override fun toJson(): JsonObject = buildJsonObject { put("CommandEvents", events.toJson()) }
    }

    data class Projection(val image: CommandProjectionImage) : CommandMessage {
        override fun toJson(): JsonObject = buildJsonObject { put("CommandProjection", image.toJson()) }
    }

    fun encodeJson(): ByteArray =
        commandJson.encodeToString(JsonElement.serializer(), toJson()).encodeToByteArray()

    companion object {
        fun decodeJson(data: ByteArray): CommandMessage =
            fromJson(commandJson.parseToJsonElement(data.decodeToString()))

        fun decodeJson(data: String): CommandMessage =
            fromJson(commandJson.parseToJsonElement(data))

        fun fromJson(element: JsonElement): CommandMessage {
            val obj = element.commandObject("CommandMessage")
            require(obj.size == 1) { "CommandMessage must be externally tagged" }
            val (tag, body) = obj.entries.single()
            return when (tag) {
                "CommandSubmit" -> Submit(CommandSubmit.fromJson(body))
                "CommandCancel" -> Cancel(CommandCancel.fromJson(body))
                "CommandEvents" -> Events(CommandEvents.fromJson(body))
                "CommandProjection" -> Projection(CommandProjectionImage.fromJson(body))
                else -> error("unknown CommandMessage variant: $tag")
            }
        }
    }
}

sealed interface CommandApplyStatus {
    data object Recorded : CommandApplyStatus
    data object Duplicate : CommandApplyStatus
    data object Unknown : CommandApplyStatus
    data class StaleGeneration(val expected: Long, val actual: Long) : CommandApplyStatus
    data class TerminalConflict(
        val commandId: String,
        val existing: CommandStatus,
        val incoming: CommandStatus,
    ) : CommandApplyStatus
}

/** Map a terminal receipt outcome + reason to a folded [CommandStatus]. */
private fun terminalStatusOf(outcome: ReceiptOutcome, reason: String?): CommandStatus =
    when (outcome) {
        ReceiptOutcome.Applied -> CommandStatus.Applied
        ReceiptOutcome.Rejected -> when (reason) {
            "cancelled" -> CommandStatus.Cancelled
            "superseded" -> CommandStatus.Superseded
            "timed_out" -> CommandStatus.TimedOut
            else -> CommandStatus.Rejected
        }
        // Non-terminal outcomes never reach here (guarded by isTerminal).
        ReceiptOutcome.Observed, ReceiptOutcome.Accepted -> CommandStatus.Accepted
    }

private fun progressStatusOf(kind: CommandEventKind): CommandStatus? =
    when (kind) {
        CommandEventKind.Observed, CommandEventKind.Accepted -> CommandStatus.Accepted
        CommandEventKind.Started, CommandEventKind.Progress -> CommandStatus.Running
        CommandEventKind.Cancelled, CommandEventKind.Superseded, CommandEventKind.TimedOut -> null
    }

private fun phaseRank(status: CommandStatus): Int =
    when (status) {
        CommandStatus.Submitted -> 0
        CommandStatus.Accepted -> 1
        CommandStatus.Running -> 2
        else -> 3
    }

/** The folded command projection reducer. Mirrors the Rust `CommandProjection`. */
class CommandProjection {
    var generation: Long = 0
        private set

    private val entries: MutableMap<String, CommandProjectionEntry> = linkedMapOf()
    private val seenEventIds: MutableSet<String> = linkedSetOf()
    private val seenReceiptIds: MutableSet<String> = linkedSetOf()
    private val seenCancelIds: MutableSet<String> = linkedSetOf()
    private val conflicts: MutableSet<String> = linkedSetOf()

    fun applyMessage(message: CommandMessage): CommandApplyStatus =
        when (message) {
            is CommandMessage.Submit -> submit(message.submit)
            is CommandMessage.Cancel -> cancel(message.cancel)
            is CommandMessage.Events -> {
                var last: CommandApplyStatus = CommandApplyStatus.Unknown
                for (event in message.events.events) last = event(event)
                last
            }
            is CommandMessage.Projection -> applyProjection(message.image)
        }

    fun submit(submit: CommandSubmit): CommandApplyStatus {
        if (entries.containsKey(submit.commandId)) return CommandApplyStatus.Duplicate
        generation = maxOf(generation, submit.authorityGeneration)
        entries[submit.commandId] = CommandProjectionEntry(
            commandId = submit.commandId,
            status = CommandStatus.Submitted,
            terminal = false,
            generation = submit.authorityGeneration,
        )
        return CommandApplyStatus.Recorded
    }

    fun event(event: CommandEvent): CommandApplyStatus {
        if (event.eventId in seenEventIds) return CommandApplyStatus.Duplicate
        val entry = entries[event.commandId] ?: return CommandApplyStatus.Unknown
        if (event.generation != entry.generation) {
            return CommandApplyStatus.StaleGeneration(entry.generation, event.generation)
        }
        seenEventIds.add(event.eventId)
        var updated = entry.copy(lastEventId = event.eventId)
        val next = progressStatusOf(event.kind)
        if (!updated.terminal && next != null && phaseRank(next) >= phaseRank(updated.status)) {
            updated = updated.copy(status = next)
        }
        entries[event.commandId] = updated
        return CommandApplyStatus.Recorded
    }

    fun cancel(cancel: CommandCancel): CommandApplyStatus {
        if (cancel.causationId in seenCancelIds) return CommandApplyStatus.Duplicate
        val entry = entries[cancel.commandId] ?: return CommandApplyStatus.Unknown
        if (cancel.authorityGeneration != entry.generation) {
            return CommandApplyStatus.StaleGeneration(entry.generation, cancel.authorityGeneration)
        }
        seenCancelIds.add(cancel.causationId)
        // A cancel is non-terminal by itself; the rejected receipt makes it terminal.
        return CommandApplyStatus.Recorded
    }

    fun observeReceipt(receipt: CausalReceipt): CommandApplyStatus {
        if (receipt.receiptId in seenReceiptIds) return CommandApplyStatus.Duplicate
        val entry = entries[receipt.causationId] ?: return CommandApplyStatus.Unknown
        if (receipt.generation != entry.generation) {
            return CommandApplyStatus.StaleGeneration(entry.generation, receipt.generation)
        }
        if (!receipt.outcome.isTerminal) {
            seenReceiptIds.add(receipt.receiptId)
            if (!entry.terminal && phaseRank(CommandStatus.Accepted) >= phaseRank(entry.status)) {
                entries[receipt.causationId] = entry.copy(status = CommandStatus.Accepted)
            }
            return CommandApplyStatus.Recorded
        }
        val incoming = terminalStatusOf(receipt.outcome, receipt.reason)
        if (entry.terminal) {
            if (entry.status == incoming) {
                seenReceiptIds.add(receipt.receiptId)
                return CommandApplyStatus.Recorded
            }
            conflicts.add(receipt.causationId)
            return CommandApplyStatus.TerminalConflict(receipt.causationId, entry.status, incoming)
        }
        seenReceiptIds.add(receipt.receiptId)
        entries[receipt.causationId] = entry.copy(
            terminal = true,
            status = incoming,
            reason = receipt.reason,
            terminalReceiptId = receipt.receiptId,
        )
        return CommandApplyStatus.Recorded
    }

    fun applyProjection(image: CommandProjectionImage): CommandApplyStatus {
        generation = maxOf(generation, image.generation)
        for (entry in image.commands) {
            entries[entry.commandId] = entry
            entry.lastEventId?.let { seenEventIds.add(it) }
            entry.terminalReceiptId?.let { seenReceiptIds.add(it) }
        }
        return CommandApplyStatus.Recorded
    }

    fun entry(commandId: String): CommandProjectionEntry? = entries[commandId]

    fun terminalFor(commandId: String): CommandProjectionEntry? =
        entries[commandId]?.takeIf { it.terminal }

    fun hasConflict(commandId: String): Boolean = commandId in conflicts

    fun toImage(): CommandProjectionImage =
        CommandProjectionImage(
            generation = generation,
            commands = entries.values.sortedBy { it.commandId }.toList(),
        )
}

/** Transport used by [CommandRpcClient] to emit command-plane frames. */
fun interface CommandTransport {
    fun send(message: CommandMessage)
}

/** Resolution state of an RPC `call`. */
sealed interface CallState {
    data object Pending : CallState
    data class Resolved(val entry: CommandProjectionEntry) : CallState
    data object Conflict : CallState
}

/**
 * RPC facade over the command plane. `submit` builds and sends CommandSubmit;
 * incoming frames and receipts are folded via `ingest*`; a unary `call` resolves
 * only when the projection reaches a terminal outcome — never on an ACK or an
 * `accepted` event.
 */
class CommandRpcClient(private val transport: CommandTransport) {
    val projection: CommandProjection = CommandProjection()

    fun submit(submit: CommandSubmit): String {
        val message = CommandMessage.Submit(submit)
        transport.send(message)
        projection.applyMessage(message)
        return submit.commandId
    }

    fun cancel(cancel: CommandCancel) {
        val message = CommandMessage.Cancel(cancel)
        transport.send(message)
        projection.applyMessage(message)
    }

    fun ingestCommand(message: CommandMessage): CommandApplyStatus =
        projection.applyMessage(message)

    fun ingestReceipt(receipt: CausalReceipt): CommandApplyStatus =
        projection.observeReceipt(receipt)

    fun pollCall(commandId: String): CallState =
        when {
            projection.hasConflict(commandId) -> CallState.Conflict
            else -> projection.terminalFor(commandId)?.let { CallState.Resolved(it) } ?: CallState.Pending
        }
}
