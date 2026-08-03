package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Replays the canonical `lazily-spec/conformance/reliable-sync/` fixtures against
 * the native [ResyncCoordinator] / [DurableOutbox] / [OrSet] / [WireLwwRegister]
 * implementation, and round-trips the two control frames ([ResyncRequest] /
 * [OutboxAck]) through JSON. Cross-language pin with lazily-rs / lazily-js;
 * correctness backstop `lazily-formal` `ReliableSync.lean`.
 */
class ReliableSyncConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("reliable-sync/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    /**
     * The one scenario of [fx] named [name], recorded in the runtime scenario
     * ledger (`#lzscenariocoverage`).
     *
     * This runner reaches for scenarios by name rather than iterating, which is
     * the shape where a skipped scenario is hardest to notice: nothing enumerates
     * the ones you did not name. [ConformanceScenarios.pick] records what was
     * actually taken, and the guard compares that against the ids the fixture
     * carries on disk. [file] is threaded through because the ledger is keyed by
     * spec-relative fixture path, exactly as the fixture manifest is.
     */
    private fun scenario(
        file: String,
        fx: JsonObject,
        name: String,
    ): JsonObject = ConformanceScenarios.pick("reliable-sync/$file", fx, name)

    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.long

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    private fun msg(el: JsonElement): IpcMessage = IpcMessage.fromJson(el)

    /**
     * The receiver's materialized node state, folded from applied frames.
     *
     * Needed because the fixtures assert *state*, not only `last_epoch`:
     * `state_after`, `converged_nodes`, `net_effect_unchanged`,
     * `equals_no_drop_receiver`, `fold_equivalent`, `atomic_advance` and
     * `exactly_once_effect` are all claims about what the ops did. Without a
     * receiver those keys are unreadable, which is exactly why they went
     * unconsumed (#lzassertunknownkeys).
     */
    private class Receiver(
        startEpoch: Long,
    ) {
        val coord = ResyncCoordinator(startEpoch)
        val nodes = sortedMapOf<Long, List<Int>>()

        /** Ingest through the coordinator, folding ops only when it says Apply. */
        fun ingest(m: IpcMessage): ResyncAction {
            val action = coord.ingest(m)
            if (action is ResyncAction.Apply) fold(m)
            return action
        }

        fun fold(m: IpcMessage) {
            when (m) {
                is IpcMessage.SnapshotMessage -> for (n in m.snapshot.nodes) {
                    (n.state as? NodeState.Payload)?.let { nodes[n.node] = it.bytes.map { b -> b.toInt() and 0xff } }
                }
                is IpcMessage.DeltaMessage -> apply(m.delta)
                else -> {}
            }
        }

        fun apply(delta: Delta) {
            for (op in delta.ops) {
                val (node, payload) =
                    when (op) {
                        is DeltaOp.CellSet -> op.node to op.payload
                        is DeltaOp.SlotValue -> op.node to op.payload
                        else -> continue
                    }
                val inline = payload as? IpcValue.Inline ?: continue
                nodes[node] = inline.bytes.map { it.toInt() and 0xff }
            }
        }

        fun state(): Map<Long, List<Int>> = nodes.toSortedMap()
    }

    /** `{"1": [10]}` -> `{1L: [10]}`, the fixtures' node-state spelling. */
    private fun nodeState(el: JsonElement): Map<Long, List<Int>> =
        el.jsonObject.entries
            .associate { (k, v) ->
                k.toLong() to v.jsonArray.map { it.jsonPrimitive.int }
            }.toSortedMap()

    // -- control-frame serde round-trip -------------------------------------

    @Test
    fun resyncRequestRoundTripsJson() {
        val m = IpcMessage.ofResyncRequest(ResyncRequest(2))
        val text = m.toJson().toString()
        assertEquals("""{"ResyncRequest":{"from_epoch":2}}""", text)
        assertEquals(m, IpcMessage.decodeJson(text))
    }

    @Test
    fun outboxAckRoundTripsJson() {
        val m = IpcMessage.ofOutboxAck(OutboxAck(41))
        val text = m.toJson().toString()
        assertEquals("""{"OutboxAck":{"through_epoch":41}}""", text)
        assertEquals(m, IpcMessage.decodeJson(text))
    }

    // -- multi_epoch_delta.json ---------------------------------------------

    @Test
    fun multiEpochDelta() {
        val fx = loadFixture("multi_epoch_delta.json")
        assertEquals("ReliableSync", fx["kind"]!!.jsonPrimitive.content)

        // The fixture-level `assertions` block was carried and read by nothing.
        val wire = assertIs<IpcMessage.DeltaMessage>(msg(fx["wire"]!!)).delta
        fx["assertions"]!!.jsonObject.consuming("multi_epoch_delta.json assertions") { a ->
            a.assertLong("base_epoch") { wire.baseEpoch }
            a.assertLong("epoch") { wire.epoch }
            a.assertLong("span") { wire.span() }
            a.assertBoolean("is_multi_epoch") { wire.span() > 1 }
            a.assertInt("op_count") { wire.ops.size }
        }

        val sc = scenario("multi_epoch_delta.json", fx, "span_3_applies_equal_to_unit_fold")
        val d = sc["delta"]!!.jsonObject
        val delta = assertIs<IpcMessage.DeltaMessage>(msg(buildDeltaWire(d))).delta
        assertTrue(delta.epoch > delta.baseEpoch + 1, "fixture pins a multi-epoch span")
        assertEquals(delta.epoch - delta.baseEpoch, delta.span())

        val batch = Receiver(sc.long("receiver_last_epoch"))
        val action = batch.ingest(IpcMessage.ofDelta(delta))
        sc["expect"]!!.jsonObject.consuming("multi_epoch_delta.json[span_3] expect") { e ->
            e.assertString("action") { actionName(action) }
            e.assertBoolean("applied") { action is ResyncAction.Apply }
            e.assertLong("receiver_last_epoch_after") { batch.coord.lastEpoch }
            // last_epoch advances to `epoch` in one step, never through the
            // intermediate epochs the equivalent unit fold passes through.
            e.assertBoolean("atomic_advance") { batch.coord.lastEpoch == delta.epoch }
            // batch == fold: replaying the fixture's `equivalent_unit_fold` must
            // land on the same node state AND the same last_epoch.
            e.assertBoolean("fold_equivalent") {
                val unit = Receiver(sc.long("receiver_last_epoch"))
                for (uEl in sc["equivalent_unit_fold"]!!.jsonArray) {
                    val u = assertIs<IpcMessage.DeltaMessage>(msg(buildDeltaWire(uEl.jsonObject))).delta
                    assertEquals(ResyncAction.Apply, unit.ingest(IpcMessage.ofDelta(u)), "unit fold step")
                }
                unit.state() == batch.state() && unit.coord.lastEpoch == batch.coord.lastEpoch
            }
        }

        val gap = scenario("multi_epoch_delta.json", fx, "gap_rule_unchanged_under_span")
        val gd = gap["delta"]!!.jsonObject
        val gc = Receiver(gap.long("receiver_last_epoch"))
        val gapAction = gc.ingest(IpcMessage.ofDelta(Delta(gd.long("base_epoch"), gd.long("epoch"))))
        gap["expect"]!!.jsonObject.consuming("multi_epoch_delta.json[gap_rule] expect") { e ->
            e.assertString("action") { actionName(gapAction) }
            e.assertKeyWith("request_from") { want ->
                assertEquals(ResyncAction.RequestSnapshot(want.jsonPrimitive.long), gapAction, "request_from")
            }
            e.assertBoolean("applied") { gapAction is ResyncAction.Apply }
            e.assertLong("receiver_last_epoch_after") { gc.coord.lastEpoch }
        }
    }

    /** The externally-tagged `Delta` envelope the fixtures store bare. */
    private fun buildDeltaWire(delta: JsonObject): JsonElement = buildJsonObject { put("Delta", delta) }

    private fun actionName(action: ResyncAction): String =
        when (action) {
            is ResyncAction.Apply -> "Apply"
            is ResyncAction.Ignore -> "Ignore"
            is ResyncAction.RequestSnapshot -> "RequestSnapshot"
        }

    // -- resync_gap_converge.json -------------------------------------------

    @Test
    fun resyncGapConverge() {
        val fx = loadFixture("resync_gap_converge.json")

        val sc = scenario("resync_gap_converge.json", fx, "drop_suffix_then_resync_converges")
        val recv = Receiver(sc.long("start_last_epoch"))
        var requests = 0
        for (frameEl in sc["inbound"]!!.jsonArray) {
            val frame = frameEl.jsonObject
            if (frame["dropped"]?.jsonPrimitive?.boolean == true) continue
            val action = recv.ingest(msg(frame["frame"]!!))
            when (frame["expect_action"]!!.jsonPrimitive.content) {
                "Apply" -> assertEquals(ResyncAction.Apply, action)
                "RequestSnapshot" -> {
                    requests++
                    assertEquals(ResyncAction.RequestSnapshot(frame.long("request_from")), action)
                }
                "Ignore" -> assertEquals(ResyncAction.Ignore, action)
                else -> error("unknown expect_action")
            }
            assertEquals(frame.long("last_epoch_after"), recv.coord.lastEpoch)
        }
        sc["expect"]!!.jsonObject.consuming("resync_gap_converge.json[drop_suffix] expect") { e ->
            e.assertLong("final_last_epoch") { recv.coord.lastEpoch }
            e.assertLong("resync_requests_emitted") { requests.toLong() }
            // Convergence is the point of the scenario, and it is state, not an
            // epoch counter: a receiver that requested the snapshot and then
            // failed to fold it still lands on final_last_epoch=4.
            // The node-id KEY SET first, in both directions, then the values
            // (#lzsubblockkeyset). Map equality below already subsumes it, but
            // the tracker cannot see inside an opaque check — so the key-set
            // claim is made through the entry point that records it, and a later
            // edit that narrows the comparison to a few named ids fails instead
            // of quietly comparing the rest against nothing.
            e.assertKeySet("converged_nodes") { recv.state().keys.map { it.toString() } }
            e.assertKeyWith("converged_nodes") { assertEquals(nodeState(it), recv.state(), "converged_nodes") }
            // ... and it must equal what a receiver that never lost a frame
            // sees. The fixture does not record the dropped frame's contents, so
            // the no-drop receiver is reconstructed from the resync Snapshot,
            // which is by definition the full authoritative state at its epoch.
            // The real content of the claim is that nothing stale survived the
            // resync and nothing the drop cost is still missing.
            e.assertBoolean("equals_no_drop_receiver") {
                val snapshot =
                    sc["inbound"]!!
                        .jsonArray
                        .map { it.jsonObject }
                        .mapNotNull { it["frame"] }
                        .map { msg(it) }
                        .filterIsInstance<IpcMessage.SnapshotMessage>()
                        .lastOrNull()
                        ?: error("equals_no_drop_receiver needs a resync Snapshot in `inbound`")
                val noDrop = Receiver(sc.long("start_last_epoch"))
                noDrop.fold(snapshot)
                noDrop.state() == recv.state()
            }
        }

        val single = scenario("resync_gap_converge.json", fx, "single_request_per_gap")
        val sc2 = ResyncCoordinator(single.long("start_last_epoch"))
        var req2 = 0
        for (frameEl in single["inbound"]!!.jsonArray) {
            if (sc2.ingest(msg(frameEl.jsonObject["frame"]!!)) is ResyncAction.RequestSnapshot) req2++
        }
        single["expect"]!!.jsonObject.consuming("resync_gap_converge.json[single_request] expect") { e ->
            e.assertLong("resync_requests_emitted") { req2.toLong() }
            e.assertLong("final_last_epoch") { sc2.lastEpoch }
        }
    }

    // -- idempotent_redelivery.json -----------------------------------------

    @Test
    fun idempotentRedelivery() {
        val fx = loadFixture("idempotent_redelivery.json")
        for (name in listOf("replayed_delta_is_ignored", "duplicate_current_head_is_ignored")) {
            val sc = scenario("idempotent_redelivery.json", fx, name)
            val recv = Receiver(sc.long("start_last_epoch"))
            // `state_before` seeds the receiver so "unchanged" is a claim about
            // real state rather than about an empty map staying empty.
            recv.nodes.putAll(nodeState(sc["state_before"]!!))
            val before = recv.state()
            for (frameEl in sc["inbound"]!!.jsonArray) {
                val frame = frameEl.jsonObject
                assertEquals(ResyncAction.Ignore, recv.ingest(msg(frame["frame"]!!)), name)
                assertEquals(frame.long("last_epoch_after"), recv.coord.lastEpoch)
            }
            sc["expect"]!!.jsonObject.consuming("idempotent_redelivery.json[$name] expect") { e ->
                e.assertLong("final_last_epoch") { recv.coord.lastEpoch }
                // At-least-once delivery, exactly-once effect: the re-sent frame
                // carries a DIFFERENT payload for node 1 in the first scenario,
                // so a receiver that folded it would land on 99, not 10.
                // Key set in both directions, then the values (#lzsubblockkeyset).
                e.assertKeySet("state_after") { recv.state().keys.map { it.toString() } }
                e.assertKeyWith("state_after") { assertEquals(nodeState(it), recv.state(), "state_after") }
                e.assertBoolean("net_effect_unchanged") { before == recv.state() }
            }
        }
    }

    // -- a reference file-backed DurableOutbox (crash-replay test helper) ----

    private class FileOutbox(
        private val path: Path,
    ) : DurableOutbox {
        private var ackedThrough = 0L

        init {
            if (!Files.exists(path)) Files.writeString(path, "")
        }

        private fun readAll(): List<Pair<Long, IpcMessage>> =
            Files
                .readString(path)
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val arr = Json.parseToJsonElement(line).jsonArray
                    arr[0].jsonPrimitive.long to IpcMessage.fromJson(arr[1])
                }.toList()

        override fun append(
            epoch: Long,
            msg: IpcMessage,
        ) {
            val line = "[$epoch,${msg.toJson()}]\n"
            Files.writeString(path, line, java.nio.file.StandardOpenOption.APPEND)
        }

        override fun ackThrough(epoch: Long) {
            if (epoch > ackedThrough) ackedThrough = epoch
            val retained = readAll().filter { it.first > ackedThrough }
            Files.writeString(path, retained.joinToString("") { "[${it.first},${it.second.toJson()}]\n" })
        }

        override fun replayFrom(cursor: Long): List<Pair<Long, IpcMessage>> = readAll().filter { it.first > cursor }.sortedBy { it.first }

        override fun retainedEpochs(): List<Long> = readAll().map { it.first }.sorted()
    }

    private fun frames(
        sc: JsonObject,
        key: String,
    ): List<Pair<Long, IpcMessage>> =
        sc[key]!!.jsonArray.map {
            val e = it.jsonObject
            e.long("epoch") to IpcMessage.fromJson(e["frame"]!!)
        }

    private fun longs(el: JsonElement): List<Long> = el.jsonArray.map { it.jsonPrimitive.long }

    // -- outbox_replay_after_crash.json -------------------------------------

    @Test
    fun outboxReplayAfterCrash() {
        val fx = loadFixture("outbox_replay_after_crash.json")
        val sc = scenario("outbox_replay_after_crash.json", fx, "crash_between_append_and_ack_replays_on_reconnect")
        val appended = frames(sc, "appended")
        val ack = sc.long("ack_through")
        val cursor = sc.long("reconnect_cursor")

        val dir = Files.createTempDirectory("lz_outbox_kt")
        val path = dir.resolve("outbox.jsonl")

        val mem = InMemoryOutbox()
        var file = FileOutbox(path)
        for ((e, m) in appended) {
            mem.append(e, m)
            file.append(e, m)
        }
        mem.ackThrough(ack)
        file.ackThrough(ack)

        // "crash": reopen the durable file outbox from disk.
        file = FileOutbox(path)
        val replay = file.replayFrom(cursor)

        // Feed replay to a receiver at the reconnect cursor: applies each once.
        val recv = Receiver(cursor)
        val applied = mutableListOf<Long>()
        for ((_, m) in replay) {
            if (recv.ingest(m) is ResyncAction.Apply) applied.add(recv.coord.lastEpoch)
        }

        sc["expect"]!!.jsonObject.consuming("outbox_replay_after_crash.json[crash] expect") { e ->
            e.assertKeyWith("retained_after_ack") {
                assertEquals(longs(it), mem.retainedEpochs(), "retained_after_ack (memory)")
                assertEquals(longs(it), file.retainedEpochs(), "retained_after_ack (file)")
            }
            e.assertKeyWith("replayed_from_cursor") {
                assertEquals(longs(it), replay.map { r -> r.first }, "replayed_from_cursor")
            }
            // Replay must be ordered, not merely complete: a store that returned
            // the retained suffix as a set would satisfy replayed_from_cursor.
            e.assertKeyWith("replay_order") {
                assertEquals(longs(it), replay.map { r -> r.first }, "replay_order")
            }
            e.assertKeyWith("receiver_applies") { assertEquals(longs(it), applied, "receiver_applies") }
            e.assertLong("receiver_last_epoch_after") { recv.coord.lastEpoch }
            // The exactly-once ledger. `ops_lost`/`ops_doubled` are counted
            // against the appended frames above the reconnect cursor, so an
            // outbox that dropped or duplicated on crash is caught here rather
            // than only via the epoch list.
            val expectedEpochs = appended.map { it.first }.filter { it > cursor }
            val lost = expectedEpochs.count { it !in applied }
            val doubled = applied.size - applied.distinct().size
            e.assertInt("ops_lost") { lost }
            e.assertInt("ops_doubled") { doubled }
            e.assertBoolean("exactly_once_effect") { lost == 0 && doubled == 0 }
        }

        // send_failure_retains_frame_for_next_tick
        val sc2 = scenario("outbox_replay_after_crash.json", fx, "send_failure_retains_frame_for_next_tick")
        val mem2 = InMemoryOutbox()
        for ((e, m) in frames(sc2, "appended")) mem2.append(e, m)
        sc2["expect"]!!.jsonObject.consuming("outbox_replay_after_crash.json[send_failure] expect") { e ->
            val retained = longs(e["retained"] ?: error("retained is required"))
            e.assertKeyWith("retained") { assertEquals(longs(it), mem2.retainedEpochs(), "retained") }
            val resend = mem2.replayFrom(retained.first() - 1).map { it.first }
            // A failed send must not ack: the frame is still there for the next tick.
            e.assertBoolean("frame_retained_after_failed_send") { mem2.retainedEpochs().isNotEmpty() }
            e.assertKeyWith("resent_on_next_tick") { assertEquals(longs(it), resend, "resent_on_next_tick") }
            // The failure is transient precisely because nothing was ack'd away.
            e.assertBoolean("permanent_gap") { resend != retained }
        }

        Files.deleteIfExists(path)
        Files.deleteIfExists(dir)
    }

    // -- liveness_orset_lww.json --------------------------------------------

    private fun stamp(o: JsonObject): WireStamp = WireStamp(o.long("wall_time"), o.long("logical"), o.long("peer"))

    @Test
    fun livenessOrSetLww() {
        val fx = loadFixture("liveness_orset_lww.json")

        val add = scenario("liveness_orset_lww.json", fx, "open_set_add_wins_over_stale_remove")
        val addOps = add["ops"]!!.jsonArray.map { it.jsonObject }

        fun replayOrSet(ops: List<JsonObject>): OrSet =
            OrSet().also { s ->
                for (op in ops) {
                    when (op["op"]!!.jsonPrimitive.content) {
                        "add" -> s.add(op["tag"]!!.jsonPrimitive.content)
                        "remove" -> s.removeObserved(op["observed_tags"]!!.jsonArray.map { it.jsonPrimitive.content })
                        // Fail closed on an unrecognised op (`#lzscenariobodyskip`).
                        // An unknown `op` used to fall through, leaving the replica
                        // untouched — the `present`/`order_independent` claims then
                        // held vacuously and the scenario still booked as replayed.
                        else -> error("liveness_orset_lww.json: unknown op '${op["op"]!!.jsonPrimitive.content}'")
                    }
                }
            }
        val set = replayOrSet(addOps)
        add["expect"]!!.jsonObject.consuming("liveness_orset_lww.json[open_set_add_wins] expect") { e ->
            e.assertBoolean("present") { set.present() }
            // Add-wins is a *semilattice* claim: applying the same ops in the
            // reverse order must agree. Reading only `present` cannot tell a
            // conforming OR-set from a last-write-wins set on this op stream.
            e.assertBoolean("order_independent") {
                replayOrSet(addOps.reversed()).present() == set.present()
            }
            // Idempotence, COUNTED rather than gated. `assertEquals(0, want)`
            // compared the fixture against a literal in the runner, so the count
            // itself never met an observation (#lzconsumednotasserted). An op
            // counts as applied when re-delivering it moves the replica's only
            // observable — `present()`.
            e.assertInt("redeliver_applied_count") {
                var reapplied = 0
                for (op in addOps) {
                    val before = set.present()
                    when (op["op"]!!.jsonPrimitive.content) {
                        "add" -> set.add(op["tag"]!!.jsonPrimitive.content)
                        "remove" -> set.removeObserved(op["observed_tags"]!!.jsonArray.map { it.jsonPrimitive.content })
                        // Fail closed on an unrecognised op (`#lzscenariobodyskip`) —
                        // a fallthrough here re-delivers nothing, so the idempotence
                        // count is 0 for a reason the fixture never named.
                        else -> error("liveness_orset_lww.json: unknown redeliver op '${op["op"]!!.jsonPrimitive.content}'")
                    }
                    if (set.present() != before) reapplied++
                }
                reapplied
            }
            // Why the remove loses: it never observed tag `t3`. Asserted against
            // the op stream so the prose and the fixture cannot drift apart.
            e.assertKeyWith("reason") { el ->
                val reason = el.jsonPrimitive.content
                val tag =
                    Regex("add_tag_(\\w+?)_not_observed_by_remove")
                        .find(reason)
                        ?.groupValues
                        ?.get(1)
                        ?: error("unsupported reason spelling '$reason'")
                val observed =
                    addOps
                        .filter { it["op"]!!.jsonPrimitive.content == "remove" }
                        .flatMap { it["observed_tags"]!!.jsonArray.map { t -> t.jsonPrimitive.content } }
                assertTrue(
                    addOps.any { it["tag"]?.jsonPrimitive?.content == tag },
                    "reason names tag '$tag' which no add op carries",
                )
                assertTrue(tag !in observed, "reason claims '$tag' was not observed by the remove")
            }
        }

        val lww = scenario("liveness_orset_lww.json", fx, "lww_alive_highest_stamp_wins")
        val ops = lww["ops"]!!.jsonArray.map { it.jsonObject }

        fun replayLww(seq: List<JsonObject>): WireLwwRegister<Boolean> {
            val r = WireLwwRegister(stamp(seq[0]["stamp"]!!.jsonObject), seq[0].bool("value"))
            for (op in seq.drop(1)) r.set(stamp(op["stamp"]!!.jsonObject), op.bool("value"))
            return r
        }
        val reg = replayLww(ops)
        lww["expect"]!!.jsonObject.consuming("liveness_orset_lww.json[lww_alive] expect") { e ->
            e.assertBoolean("value") { reg.value }
            e.assertBoolean("order_independent") { replayLww(ops.reversed()).value == reg.value }
            // The rule, not just its outcome: the winner must be the op with the
            // greatest stamp, which a register resolving by arrival order fails.
            e.assertKeyWith("resolution") { el ->
                val rule = el.jsonPrimitive.content
                assertEquals("max_stamp", rule, "unsupported resolution rule '$rule'")
                val winner =
                    ops.maxWithOrNull(
                        compareBy<JsonObject>(
                            { it["stamp"]!!.jsonObject.long("wall_time") },
                            { it["stamp"]!!.jsonObject.long("logical") },
                            { it["stamp"]!!.jsonObject.long("peer") },
                        ),
                    )!!
                assertEquals(winner.bool("value"), reg.value, "resolution=max_stamp")
            }
        }

        val death = scenario("liveness_orset_lww.json", fx, "whole_editor_death_cascades")
        val open =
            death["open_set"]!!
                .jsonArray
                .map { it.jsonObject }
                .filter { it.bool("present") }
                .map {
                    val (doc, pid) = it["key"]!!.jsonPrimitive.content.split("/")
                    doc to pid.removePrefix("pid").toLong()
                }
        val alive = mutableMapOf<Long, WireLwwRegister<Boolean>>()
        for ((pid, v) in death["alive_before"]!!.jsonObject) {
            alive[pid.toLong()] = WireLwwRegister(WireStamp(1, 0, 1), v.jsonPrimitive.boolean)
        }

        fun liveDocs(): List<String> =
            open
                .filter { (_, p) -> alive[p]?.value == true }
                .map { it.first }
                .distinct()
                .sorted()
        val liveBefore = liveDocs()
        val op = death["op"]!!.jsonObject
        val pid =
            op["key"]!!
                .jsonPrimitive.content
                .removePrefix("alive/pid")
                .toLong()
        alive[pid]!!.set(stamp(op["stamp"]!!.jsonObject), op.bool("value"))
        val liveAfter = liveDocs()
        death["expect"]!!.jsonObject.consuming("liveness_orset_lww.json[death_cascades] expect") { e ->
            e.assertKeyWith("live_docs_after") { want ->
                assertEquals(want.jsonArray.map { it.jsonPrimitive.content }.sorted(), liveAfter, "live_docs_after")
            }
            // The "before" half. Without it the fixture never establishes that
            // the docs it claims to lose were live in the first place, so a
            // binding deriving an empty aggregate satisfies `live_docs_after`
            // for the wrong reason.
            e.assertKeyWith("live_docs_before") { want ->
                assertEquals(want.jsonArray.map { it.jsonPrimitive.content }.sorted(), liveBefore, "live_docs_before")
            }
            e.assertBoolean("cascade") { liveAfter.size < liveBefore.size }
        }

        // derived_live_doc_aggregate_converges_under_retry — replayed here for
        // the first time: it was the one scenario in this fixture the runner
        // skipped entirely, so all four of its keys were unevaluated.
        val agg = scenario("liveness_orset_lww.json", fx, "derived_live_doc_aggregate_converges_under_retry")
        val aggOps = agg["ops"]!!.jsonArray.map { it.jsonObject }

        fun aggregate(seq: List<JsonObject>): List<String> {
            val docs = sortedMapOf<String, OrSet>()
            val liveness = mutableMapOf<Long, WireLwwRegister<Boolean>>()
            val owner = mutableMapOf<String, MutableSet<Long>>()
            for (o in seq) {
                val key = o["key"]!!.jsonPrimitive.content
                when (o["register_kind"]!!.jsonPrimitive.content) {
                    "orset" -> {
                        val (doc, pidText) = key.split("/")
                        docs.getOrPut(doc) { OrSet() }.add(o["tag"]!!.jsonPrimitive.content)
                        owner.getOrPut(doc) { mutableSetOf() }.add(pidText.removePrefix("pid").toLong())
                    }
                    "lww" -> {
                        val p = key.removePrefix("alive/pid").toLong()
                        val s = stamp(o["stamp"]!!.jsonObject)
                        liveness[p]?.set(s, o.bool("value"))
                            ?: run { liveness[p] = WireLwwRegister(s, o.bool("value")) }
                    }
                    else -> error("unknown register_kind")
                }
            }
            return docs
                .filter { (doc, set) ->
                    set.present() && owner[doc].orEmpty().any { liveness[it]?.value == true }
                }.keys
                .sorted()
        }
        val forward = aggregate(aggOps)
        agg["expect"]!!.jsonObject.consuming("liveness_orset_lww.json[aggregate] expect") { e ->
            e.assertKeyWith("converged_live_docs") { want ->
                assertEquals(
                    want.jsonArray.map { it.jsonPrimitive.content }.sorted(),
                    forward,
                    "converged_live_docs",
                )
            }
            e.assertBoolean("order_independent") { aggregate(aggOps.reversed()) == forward }
            // Counted, not gated against a literal: an op counts as applied when
            // re-delivering it moves the derived aggregate (#lzconsumednotasserted).
            e.assertInt("redeliver_applied_count") {
                var reapplied = 0
                for (k in 1..aggOps.size) {
                    if (aggregate(aggOps + aggOps.take(k)) != aggregate(aggOps + aggOps.take(k - 1))) {
                        reapplied++
                    }
                }
                reapplied
            }
            // Each doc's liveness derives only from its own OR-set and its own
            // owner's register: dropping docB's op must not move docA.
            e.assertBoolean("per_doc_isolation") {
                val withoutB = aggregate(aggOps.filterNot { it["key"]!!.jsonPrimitive.content.startsWith("docB/") })
                forward.filter { it != "docB" } == withoutB
            }
        }
    }
}
