package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replay the shared `lazily-spec/conformance/message-passing` fixtures
 * through the Kotlin [CommandProjection] reducer and RPC facade, and mirror the
 * Rust unit tests so both bindings agree fixture-by-fixture.
 */
class CommandConformanceTest {
    private val fixtureDir = Path.of("../lazily-spec/conformance/message-passing")

    private fun fixturesPresent(): Boolean = Files.isDirectory(fixtureDir)

    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(Files.readString(fixtureDir.resolve(name))).jsonObject

    private fun foldFrame(projection: CommandProjection, frame: JsonElement): CommandApplyStatus {
        val obj = frame.jsonObject
        val schema = obj.getValue("schema").jsonPrimitive.content
        val wire = obj.getValue("wire")
        return when (schema) {
            "message-passing" -> projection.applyMessage(CommandMessage.fromJson(wire))
            "receipts" -> {
                val message = assertIs<ReceiptMessage.CausalReceiptsMessage>(ReceiptMessage.fromJson(wire))
                var last: CommandApplyStatus = CommandApplyStatus.Unknown
                message.batch.receipts.forEach { last = projection.observeReceipt(it) }
                last
            }
            else -> error("unknown frame schema: $schema")
        }
    }

    private fun frames(obj: JsonObject): List<JsonElement> = obj.getValue("frames").jsonArray

    private fun assertProjection(projection: CommandProjection, expect: JsonObject) {
        val want = CommandProjectionImage.fromJson(expect.getValue("projection"))
        assertEquals(want, projection.toImage(), "projection image mismatch")
    }

    // --- unit tests mirroring the Rust reducer ---

    @Test
    fun `command status terminality is explicit`() {
        assertFalse(CommandStatus.Submitted.isTerminal)
        assertFalse(CommandStatus.Accepted.isTerminal)
        assertFalse(CommandStatus.Running.isTerminal)
        assertTrue(CommandStatus.Applied.isTerminal)
        assertTrue(CommandStatus.Cancelled.isTerminal)
        assertTrue(CommandStatus.TimedOut.isTerminal)
    }

    private fun submitFixture(commandId: String, generation: Long): CommandSubmit =
        CommandSubmit(
            commandId = commandId,
            causationId = commandId,
            source = "vscode-plugin",
            target = "project-controller",
            namespace = "agent-doc",
            name = "editor_route",
            authorityGeneration = generation,
            idempotencyKey = "project-root:plan.md:run",
            deadlineMs = 120_000,
            policy = CommandPolicy(DedupePolicy.SameIdempotencyKey, supersede = false, cancelOnPreempt = true),
            payloadType = "agent-doc.editor_route.v1",
            payloadHash = "sha256:deadbeef",
            payload = IpcValue.Inline(byteArrayOf(1, 2, 3)),
            requiredFeatures = listOf("causal-receipts"),
        )

    @Test
    fun `command message round trips through JSON`() {
        val message = CommandMessage.Submit(submitFixture("cmd-1", 42))
        val decoded = CommandMessage.decodeJson(message.encodeJson())
        assertEquals(message, decoded)
    }

    @Test
    fun `accepted progress is not terminal`() {
        val p = CommandProjection()
        p.submit(submitFixture("cmd-1", 42))
        p.event(CommandEvent("ev-1", "cmd-1", CommandEventKind.Accepted, 42, "queued"))
        val entry = p.entry("cmd-1")!!
        assertFalse(entry.terminal)
        assertEquals(CommandStatus.Accepted, entry.status)
        assertNull(p.terminalFor("cmd-1"))
    }

    @Test
    fun `duplicate submit is idempotent`() {
        val p = CommandProjection()
        assertEquals(CommandApplyStatus.Recorded, p.submit(submitFixture("cmd-1", 42)))
        assertEquals(CommandApplyStatus.Duplicate, p.submit(submitFixture("cmd-1", 99)))
        assertEquals(42, p.entry("cmd-1")!!.generation)
    }

    @Test
    fun `conflicting terminal receipts fail closed`() {
        val p = CommandProjection()
        p.submit(submitFixture("cmd-1", 42))
        p.observeReceipt(CausalReceipt.applied("rcpt-applied", "cmd-1", "project-controller", 42))
        val status = p.observeReceipt(
            CausalReceipt.rejected("rcpt-rejected", "cmd-1", "project-controller", 42, reason = "conflict"),
        )
        assertIs<CommandApplyStatus.TerminalConflict>(status)
        assertTrue(p.hasConflict("cmd-1"))
        assertEquals(CommandStatus.Applied, p.entry("cmd-1")!!.status)
    }

    // --- fixture replay ---

    @Test
    fun `editor_route submit is nonterminal`() {
        if (!fixturesPresent()) return
        val fx = load("editor_route_submit.json")
        val p = CommandProjection()
        frames(fx).forEach { foldFrame(p, it) }
        assertProjection(p, fx.getValue("expect").jsonObject)
        assertNull(p.terminalFor("cmd-run-1"))
    }

    @Test
    fun `sync tmux layout submit shared blob`() {
        if (!fixturesPresent()) return
        val fx = load("sync_tmux_layout_submit.json")
        val p = CommandProjection()
        frames(fx).forEach { foldFrame(p, it) }
        assertProjection(p, fx.getValue("expect").jsonObject)
    }

    @Test
    fun `accepted then applied receipt is terminal only at receipt`() {
        if (!fixturesPresent()) return
        val fx = load("accepted_then_applied_receipt.json")
        val expect = fx.getValue("expect").jsonObject
        val terminalAt = expect.getValue("terminal_after_frame_index").jsonPrimitive.content.toInt()
        val p = CommandProjection()
        frames(fx).forEachIndexed { i, frame ->
            foldFrame(p, frame)
            val isTerminal = p.terminalFor("cmd-run-1") != null
            if (i < terminalAt) assertFalse(isTerminal, "frame $i must be non-terminal")
            else assertTrue(isTerminal, "frame $i must be terminal")
        }
        assertProjection(p, expect)
    }

    @Test
    fun `stale generation events and receipts are ignored`() {
        if (!fixturesPresent()) return
        val fx = load("stale_generation_ignored.json")
        val expect = fx.getValue("expect").jsonObject
        val ignored = expect.getValue("ignored_frame_indices").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val p = CommandProjection()
        frames(fx).forEachIndexed { i, frame ->
            val status = foldFrame(p, frame)
            if (i in ignored) assertIs<CommandApplyStatus.StaleGeneration>(status)
        }
        assertProjection(p, expect)
    }

    @Test
    fun `terminal conflict fails closed fixture`() {
        if (!fixturesPresent()) return
        val fx = load("terminal_conflict_fail_closed.json")
        val expect = fx.getValue("expect").jsonObject
        val conflictAt = expect.getValue("conflict_after_frame_index").jsonPrimitive.content.toInt()
        val commandId = expect.getValue("conflict_command_id").jsonPrimitive.content
        val p = CommandProjection()
        frames(fx).forEachIndexed { i, frame ->
            val status = foldFrame(p, frame)
            if (i == conflictAt) assertIs<CommandApplyStatus.TerminalConflict>(status)
        }
        assertTrue(p.hasConflict(commandId))
        val before = CommandProjectionImage.fromJson(expect.getValue("projection_before_conflict"))
        assertEquals(before, p.toImage())
    }

    @Test
    fun `cancel preempts nonterminal scenarios`() {
        if (!fixturesPresent()) return
        val fx = load("cancel_preempts_nonterminal.json")
        fx.getValue("scenarios").jsonArray.forEach { scenarioEl ->
            val scenario = scenarioEl.jsonObject
            val p = CommandProjection()
            scenario.getValue("frames").jsonArray.forEach { foldFrame(p, it) }
            assertProjection(p, scenario.getValue("expect").jsonObject)
        }
    }

    @Test
    fun `reconnect command projection resyncs`() {
        if (!fixturesPresent()) return
        val fx = load("reconnect_command_projection.json")
        val p = CommandProjection()
        frames(fx).forEach { foldFrame(p, it) }
        assertProjection(p, fx.getValue("expect").jsonObject)
    }

    @Test
    fun `rpc call waits for terminal`() {
        if (!fixturesPresent()) return
        val fx = load("rpc_call_waits_for_terminal.json")
        val expect = fx.getValue("expect").jsonObject
        val rpc = expect.getValue("rpc").jsonObject
        val commandId = rpc.getValue("command_id").jsonPrimitive.content
        val resolvesAt = rpc.getValue("resolves_after_frame_index").jsonPrimitive.content.toInt()
        val unresolved = rpc.getValue("unresolved_after_frame_indices").jsonArray.map { it.jsonPrimitive.content.toInt() }
        val p = CommandProjection()
        frames(fx).forEachIndexed { i, frame ->
            foldFrame(p, frame)
            val resolved = p.terminalFor(commandId) != null
            if (i in unresolved) assertFalse(resolved, "frame $i must not resolve")
            if (i == resolvesAt) assertTrue(resolved, "frame $i must resolve")
        }
        assertProjection(p, expect)
    }

    @Test
    fun `rpc facade resolves only on terminal receipt`() {
        val sent = mutableListOf<CommandMessage>()
        val client = CommandRpcClient { sent.add(it) }
        val id = client.submit(submitFixture("cmd-1", 42))
        client.ingestCommand(
            CommandMessage.Events(
                CommandEvents(
                    listOf(
                        CommandEvent("ev-1", id, CommandEventKind.Accepted, 42, "queued"),
                        CommandEvent("ev-2", id, CommandEventKind.Started, 42),
                    ),
                ),
            ),
        )
        assertEquals(CallState.Pending, client.pollCall(id))
        client.ingestReceipt(CausalReceipt.applied("rcpt-1", id, "project-controller", 42))
        val state = client.pollCall(id)
        assertIs<CallState.Resolved>(state)
        assertEquals(CommandStatus.Applied, state.entry.status)
        assertEquals(1, sent.size)
    }
}
