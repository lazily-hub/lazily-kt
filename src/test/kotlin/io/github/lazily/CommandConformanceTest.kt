package io.github.lazily

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
    private fun load(name: String): JsonObject = Json.parseToJsonElement(ConformanceFixtures.read("message-passing/$name")).jsonObject

    private fun foldFrame(
        projection: CommandProjection,
        frame: JsonElement,
    ): CommandApplyStatus {
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

    /**
     * The frame indices a fresh projection IGNORED — folded without changing the
     * projection image.
     *
     * Defined from the observable rather than from a status discriminator on
     * purpose. The corpus spells `ignored_frame_indices` in two fixtures for two
     * different reasons — a stale generation in one, a cancel arriving after the
     * command already went terminal in the other — and a runner that pattern-matched
     * one status would silently answer "none ignored" for the other, which is how
     * this key read `[]` against an expected `[2]` the first time it was asserted
     * both ways.
     */
    private fun ignoredFrames(frames: List<JsonElement>): List<Int> {
        val projection = CommandProjection()
        val ignored = mutableListOf<Int>()
        frames.forEachIndexed { i, frame ->
            val before = projection.toImage()
            foldFrame(projection, frame)
            if (projection.toImage() == before) ignored += i
        }
        return ignored
    }

    /**
     * Bind [expect] as a rung-0 block and assert its `projection` key
     * (#lzktbindpending).
     *
     * For the five fixtures whose `expect` carries nothing but `projection` this
     * IS the whole block, so [AssertionKeys.requireAllSatisfied] runs here. The
     * fixtures with extra keys pass their own tracker through [keys] instead, so
     * one tracker owns the whole block rather than two trackers each owning part
     * of it and neither seeing an unread key.
     */
    private fun assertProjection(
        projection: CommandProjection,
        expect: JsonObject,
        where: String,
        keys: AssertionKeys? = null,
    ) {
        val tracker = keys ?: AssertionKeys(where, expect)
        // assertKeyValue, not assertKeyWith: `projection` is OBJECT-valued, so it
        // carries the block's own obligation one level down. Whole-element
        // equality subsumes the key-set check, and it is what `assertImage`
        // already does in its second comparison — the tracker just needs to be
        // told, or the opaque path leaves a field added upstream compared by
        // nothing (#lzsubblockkeyset).
        assertImage(expect.getValue("projection"), projection.toImage(), "projection")
        tracker.assertKeyValue("projection") { projection.toImage().toJson() }
        if (keys == null) tracker.requireAllSatisfied()
    }

    /**
     * Compare [got] against the fixture's projection image BOTH ways round.
     *
     * The typed comparison is the readable one, but `fromJson` silently drops
     * any field it does not know, so on its own it is blind to a key added to
     * the fixture's object upstream — the null form one level down inside an
     * assertion key (`#lzsubblockkeyset`). The second comparison re-encodes the
     * produced image and compares whole ELEMENTS, which subsumes key-set
     * equality: a planted or renamed field changes the object and reddens here.
     */
    private fun assertImage(
        wantElement: JsonElement,
        got: CommandProjectionImage,
        where: String,
    ) {
        assertEquals(CommandProjectionImage.fromJson(wantElement), got, "$where image mismatch")
        assertEquals(wantElement.jsonObject, got.toJson(), "$where wire image mismatch")
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

    private fun submitFixture(
        commandId: String,
        generation: Long,
    ): CommandSubmit =
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
        val status =
            p.observeReceipt(
                CausalReceipt.rejected("rcpt-rejected", "cmd-1", "project-controller", 42, reason = "conflict"),
            )
        assertIs<CommandApplyStatus.TerminalConflict>(status)
        assertTrue(p.hasConflict("cmd-1"))
        assertEquals(CommandStatus.Applied, p.entry("cmd-1")!!.status)
    }

    // --- fixture replay ---

    @Test
    fun `editor_route submit is nonterminal`() {
        val fx = load("editor_route_submit.json")
        val p = CommandProjection()
        frames(fx).forEach { foldFrame(p, it) }
        assertProjection(p, fx.getValue("expect").jsonObject, "message-passing/editor_route_submit.json expect")
        assertNull(p.terminalFor("cmd-run-1"))
    }

    @Test
    fun `sync tmux layout submit shared blob`() {
        val fx = load("sync_tmux_layout_submit.json")
        val p = CommandProjection()
        frames(fx).forEach { foldFrame(p, it) }
        assertProjection(
            p,
            fx.getValue("expect").jsonObject,
            "message-passing/sync_tmux_layout_submit.json expect",
        )
    }

    @Test
    fun `accepted then applied receipt is terminal only at receipt`() {
        val fx = load("accepted_then_applied_receipt.json")
        val expect = fx.getValue("expect").jsonObject
        expect.consuming("message-passing/accepted_then_applied_receipt.json expect") { e ->
            val p = CommandProjection()
            var firstTerminal = -1
            frames(fx).forEachIndexed { i, frame ->
                foldFrame(p, frame)
                if (firstTerminal < 0 && p.terminalFor("cmd-run-1") != null) firstTerminal = i
            }
            // Asserted as the INDEX the run first went terminal at, rather than by
            // replaying the fixture's index back as a branch condition. The old
            // shape read the key and then used it to decide which of two
            // assertions to make, which is a read the fixture's own value steers
            // rather than one it is compared against (#lzconsumednotasserted).
            e.assertInt("terminal_after_frame_index") { firstTerminal }
            assertProjection(p, expect, "", e)
        }
    }

    @Test
    fun `stale generation events and receipts are ignored`() {
        val fx = load("stale_generation_ignored.json")
        val expect = fx.getValue("expect").jsonObject
        expect.consuming("message-passing/stale_generation_ignored.json expect") { e ->
            val p = CommandProjection()
            // Asserted BOTH ways against the indices the run really ignored. The
            // old shape only checked that the fixture's listed frames came back
            // stale, so a frame the binding wrongly dropped was invisible — and
            // the stale status itself is still asserted below, so widening the
            // observable does not weaken the claim.
            frames(fx).forEachIndexed { i, frame ->
                val status = foldFrame(p, frame)
                if (status is CommandApplyStatus.StaleGeneration) {
                    assertTrue(
                        i in ignoredFrames(frames(fx)),
                        "frame $i folded stale but changed the projection",
                    )
                }
            }
            e.assertKeyWith("ignored_frame_indices") { want ->
                assertEquals(
                    want.jsonArray.map { it.jsonPrimitive.content.toInt() },
                    ignoredFrames(frames(fx)),
                    "ignored_frame_indices",
                )
            }
            assertProjection(p, expect, "", e)
        }
    }

    @Test
    fun `terminal conflict fails closed fixture`() {
        val fx = load("terminal_conflict_fail_closed.json")
        val expect = fx.getValue("expect").jsonObject
        expect.consuming("message-passing/terminal_conflict_fail_closed.json expect") { e ->
            val commandId = e.string("conflict_command_id") ?: error("conflict_command_id")
            val p = CommandProjection()
            var conflictAt = -1
            frames(fx).forEachIndexed { i, frame ->
                if (foldFrame(p, frame) is CommandApplyStatus.TerminalConflict && conflictAt < 0) {
                    conflictAt = i
                }
            }
            // `conflict` was NEVER READ. The block declares the trace must fail
            // closed and no runner looked, so the one key carrying the headline
            // claim of the fixture was silent — which is exactly what rung 0 is
            // for: no grep finds an assertion nobody makes (#lzassertunknownkeys).
            e.assertBoolean("conflict") { p.hasConflict(commandId) }
            // The index the run first conflicted at, not a branch steered by the
            // fixture's own value.
            e.assertInt("conflict_after_frame_index") { conflictAt }
            e.assertString("conflict_command_id") { commandId }
            assertImage(
                expect.getValue("projection_before_conflict"),
                p.toImage(),
                "projection_before_conflict",
            )
            e.assertKeyValue("projection_before_conflict") { p.toImage().toJson() }
        }
    }

    @Test
    fun `cancel preempts nonterminal scenarios`() {
        val fx = load("cancel_preempts_nonterminal.json")
        ConformanceScenarios.of("message-passing/cancel_preempts_nonterminal.json", fx).forEach { scenario ->
            val p = CommandProjection()
            scenario.getValue("frames").jsonArray.forEach { foldFrame(p, it) }
            val expect = scenario.getValue("expect").jsonObject
            val id = scenario["id"]?.jsonPrimitive?.content ?: "?"
            expect.consuming("message-passing/cancel_preempts_nonterminal.json [$id] expect") { e ->
                // Only the `cancel_after_applied_ignored` scenario carries
                // `ignored_frame_indices`; the tracker sees the key only where the
                // fixture puts it, so no excuse is needed for the other one.
                e.assertKeyWith("ignored_frame_indices") { want ->
                    assertEquals(
                        want.jsonArray.map { it.jsonPrimitive.content.toInt() },
                        ignoredFrames(scenario.getValue("frames").jsonArray),
                        "ignored_frame_indices",
                    )
                }
                assertProjection(p, expect, "", e)
            }
        }
    }

    @Test
    fun `reconnect command projection resyncs`() {
        val fx = load("reconnect_command_projection.json")
        val p = CommandProjection()
        frames(fx).forEach { foldFrame(p, it) }
        assertProjection(
            p,
            fx.getValue("expect").jsonObject,
            "message-passing/reconnect_command_projection.json expect",
        )
    }

    @Test
    fun `rpc call waits for terminal`() {
        val fx = load("rpc_call_waits_for_terminal.json")
        val expect = fx.getValue("expect").jsonObject
        expect.consuming("message-passing/rpc_call_waits_for_terminal.json expect") { e ->
            val p = CommandProjection()
            e.sub("rpc") { rpc ->
                val commandId = rpc.string("command_id") ?: error("command_id")
                val resolvedAt = mutableListOf<Int>()
                val unresolvedAt = mutableListOf<Int>()
                frames(fx).forEachIndexed { i, frame ->
                    foldFrame(p, frame)
                    if (p.terminalFor(commandId) != null) resolvedAt += i else unresolvedAt += i
                }
                rpc.assertString("command_id") { commandId }
                // Both derived from the run and compared both ways. The old shape
                // used the fixture's indices to pick which frames to check, so a
                // frame that resolved early outside the listed set passed.
                rpc.assertInt("resolves_after_frame_index") { resolvedAt.firstOrNull() ?: -1 }
                rpc.assertKeyWith("unresolved_after_frame_indices") { want ->
                    assertEquals(
                        want.jsonArray.map { it.jsonPrimitive.content.toInt() },
                        unresolvedAt,
                        "unresolved_after_frame_indices",
                    )
                }
                // `terminal_status` was PRESENT IN THE FIXTURE AND NEVER READ.
                // The block pins which terminal state the RPC resolves to — the
                // difference between a call that applied and one that timed out —
                // and no runner looked, so the fixture could have said anything
                // (#lzassertunknownkeys). Nothing but rung 0 finds a key nobody
                // reads: the evidence is the absence of a call.
                rpc.assertString("terminal_status") {
                    p.terminalFor(commandId)?.status?.wireName
                }
            }
            assertProjection(p, expect, "", e)
        }
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
