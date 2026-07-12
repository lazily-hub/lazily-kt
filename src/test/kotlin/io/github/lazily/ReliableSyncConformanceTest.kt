package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val specPath = Path.of("../lazily-spec/conformance/reliable-sync/$name")
        val text = if (Files.exists(specPath)) {
            Files.readString(specPath)
        } else {
            val resource = javaClass.getResource("/conformance/reliable-sync/$name")
                ?: error("missing conformance fixture: $name")
            resource.readText()
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun scenario(fx: JsonObject, name: String): JsonObject =
        fx["scenarios"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == name }

    private fun JsonObject.long(key: String): Long = this[key]!!.jsonPrimitive.long
    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
    private fun msg(el: JsonElement): IpcMessage = IpcMessage.fromJson(el)

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

        val sc = scenario(fx, "span_3_applies_equal_to_unit_fold")
        val d = sc["delta"]!!.jsonObject
        val base = d.long("base_epoch")
        val epoch = d.long("epoch")
        assertTrue(epoch > base + 1, "fixture pins a multi-epoch span")
        val delta = Delta(base, epoch)
        assertEquals(epoch - base, delta.span())
        val coord = ResyncCoordinator(sc.long("receiver_last_epoch"))
        assertEquals(ResyncAction.Apply, coord.ingestDelta(delta))
        assertEquals(sc["expect"]!!.jsonObject.long("receiver_last_epoch_after"), coord.lastEpoch)

        val gap = scenario(fx, "gap_rule_unchanged_under_span")
        val gd = gap["delta"]!!.jsonObject
        val gc = ResyncCoordinator(gap.long("receiver_last_epoch"))
        assertEquals(
            ResyncAction.RequestSnapshot(gap["expect"]!!.jsonObject.long("request_from")),
            gc.ingestDelta(Delta(gd.long("base_epoch"), gd.long("epoch"))),
        )
        assertEquals(gap.long("receiver_last_epoch"), gc.lastEpoch)
    }

    // -- resync_gap_converge.json -------------------------------------------

    @Test
    fun resyncGapConverge() {
        val fx = loadFixture("resync_gap_converge.json")

        val sc = scenario(fx, "drop_suffix_then_resync_converges")
        val coord = ResyncCoordinator(sc.long("start_last_epoch"))
        var requests = 0
        for (frameEl in sc["inbound"]!!.jsonArray) {
            val frame = frameEl.jsonObject
            if (frame["dropped"]?.jsonPrimitive?.boolean == true) continue
            val action = coord.ingest(msg(frame["frame"]!!))
            when (frame["expect_action"]!!.jsonPrimitive.content) {
                "Apply" -> assertEquals(ResyncAction.Apply, action)
                "RequestSnapshot" -> {
                    requests++
                    assertEquals(ResyncAction.RequestSnapshot(frame.long("request_from")), action)
                }
                "Ignore" -> assertEquals(ResyncAction.Ignore, action)
                else -> error("unknown expect_action")
            }
            assertEquals(frame.long("last_epoch_after"), coord.lastEpoch)
        }
        val expect = sc["expect"]!!.jsonObject
        assertEquals(expect.long("final_last_epoch"), coord.lastEpoch)
        assertEquals(expect.long("resync_requests_emitted"), requests.toLong())

        val single = scenario(fx, "single_request_per_gap")
        val sc2 = ResyncCoordinator(single.long("start_last_epoch"))
        var req2 = 0
        for (frameEl in single["inbound"]!!.jsonArray) {
            if (sc2.ingest(msg(frameEl.jsonObject["frame"]!!)) is ResyncAction.RequestSnapshot) req2++
        }
        assertEquals(single["expect"]!!.jsonObject.long("resync_requests_emitted"), req2.toLong())
    }

    // -- idempotent_redelivery.json -----------------------------------------

    @Test
    fun idempotentRedelivery() {
        val fx = loadFixture("idempotent_redelivery.json")
        for (name in listOf("replayed_delta_is_ignored", "duplicate_current_head_is_ignored")) {
            val sc = scenario(fx, name)
            val coord = ResyncCoordinator(sc.long("start_last_epoch"))
            for (frameEl in sc["inbound"]!!.jsonArray) {
                val frame = frameEl.jsonObject
                assertEquals(ResyncAction.Ignore, coord.ingest(msg(frame["frame"]!!)), name)
                assertEquals(frame.long("last_epoch_after"), coord.lastEpoch)
            }
            assertEquals(sc["expect"]!!.jsonObject.long("final_last_epoch"), coord.lastEpoch)
        }
    }

    // -- a reference file-backed DurableOutbox (crash-replay test helper) ----

    private class FileOutbox(private val path: Path) : DurableOutbox {
        private var ackedThrough = 0L

        init {
            if (!Files.exists(path)) Files.writeString(path, "")
        }

        private fun readAll(): List<Pair<Long, IpcMessage>> =
            Files.readString(path).lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val arr = Json.parseToJsonElement(line).jsonArray
                    arr[0].jsonPrimitive.long to IpcMessage.fromJson(arr[1])
                }
                .toList()

        override fun append(epoch: Long, msg: IpcMessage) {
            val line = "[$epoch,${msg.toJson()}]\n"
            Files.writeString(path, line, java.nio.file.StandardOpenOption.APPEND)
        }

        override fun ackThrough(epoch: Long) {
            if (epoch > ackedThrough) ackedThrough = epoch
            val retained = readAll().filter { it.first > ackedThrough }
            Files.writeString(path, retained.joinToString("") { "[${it.first},${it.second.toJson()}]\n" })
        }

        override fun replayFrom(cursor: Long): List<Pair<Long, IpcMessage>> =
            readAll().filter { it.first > cursor }.sortedBy { it.first }

        override fun retainedEpochs(): List<Long> = readAll().map { it.first }.sorted()
    }

    private fun frames(sc: JsonObject, key: String): List<Pair<Long, IpcMessage>> =
        sc[key]!!.jsonArray.map {
            val e = it.jsonObject
            e.long("epoch") to IpcMessage.fromJson(e["frame"]!!)
        }

    private fun longs(el: JsonElement): List<Long> = el.jsonArray.map { it.jsonPrimitive.long }

    // -- outbox_replay_after_crash.json -------------------------------------

    @Test
    fun outboxReplayAfterCrash() {
        val fx = loadFixture("outbox_replay_after_crash.json")
        val sc = scenario(fx, "crash_between_append_and_ack_replays_on_reconnect")
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

        val expect = sc["expect"]!!.jsonObject
        assertEquals(longs(expect["retained_after_ack"]!!), mem.retainedEpochs())
        assertEquals(longs(expect["retained_after_ack"]!!), file.retainedEpochs())

        // "crash": reopen the durable file outbox from disk.
        file = FileOutbox(path)
        val replay = file.replayFrom(cursor)
        assertEquals(longs(expect["replayed_from_cursor"]!!), replay.map { it.first })

        // feed replay to a receiver at the reconnect cursor: applies each once.
        val coord = ResyncCoordinator(cursor)
        val applied = mutableListOf<Long>()
        for ((_, m) in replay) {
            if (coord.ingest(m) is ResyncAction.Apply) applied.add(coord.lastEpoch)
        }
        assertEquals(longs(expect["receiver_applies"]!!), applied)
        assertEquals(expect.long("receiver_last_epoch_after"), coord.lastEpoch)

        // send_failure_retains_frame_for_next_tick
        val sc2 = scenario(fx, "send_failure_retains_frame_for_next_tick")
        val mem2 = InMemoryOutbox()
        for ((e, m) in frames(sc2, "appended")) mem2.append(e, m)
        val retained = longs(sc2["expect"]!!.jsonObject["retained"]!!)
        assertEquals(retained, mem2.retainedEpochs())
        assertEquals(retained, mem2.replayFrom(retained.first() - 1).map { it.first })

        Files.deleteIfExists(path)
        Files.deleteIfExists(dir)
    }

    // -- liveness_orset_lww.json --------------------------------------------

    private fun stamp(o: JsonObject): WireStamp =
        WireStamp(o.long("wall_time"), o.long("logical"), o.long("peer"))

    @Test
    fun livenessOrSetLww() {
        val fx = loadFixture("liveness_orset_lww.json")

        val add = scenario(fx, "open_set_add_wins_over_stale_remove")
        val set = OrSet()
        for (opEl in add["ops"]!!.jsonArray) {
            val op = opEl.jsonObject
            when (op["op"]!!.jsonPrimitive.content) {
                "add" -> set.add(op["tag"]!!.jsonPrimitive.content)
                "remove" -> set.removeObserved(op["observed_tags"]!!.jsonArray.map { it.jsonPrimitive.content })
            }
        }
        assertEquals(add["expect"]!!.jsonObject.bool("present"), set.present())

        val lww = scenario(fx, "lww_alive_highest_stamp_wins")
        val ops = lww["ops"]!!.jsonArray.map { it.jsonObject }
        val reg = WireLwwRegister(stamp(ops[0]["stamp"]!!.jsonObject), ops[0].bool("value"))
        for (op in ops.drop(1)) reg.set(stamp(op["stamp"]!!.jsonObject), op.bool("value"))
        assertEquals(lww["expect"]!!.jsonObject.bool("value"), reg.value)

        val death = scenario(fx, "whole_editor_death_cascades")
        val open = death["open_set"]!!.jsonArray.map { it.jsonObject }
            .filter { it.bool("present") }
            .map {
                val (doc, pid) = it["key"]!!.jsonPrimitive.content.split("/")
                doc to pid.removePrefix("pid").toLong()
            }
        val alive = mutableMapOf<Long, WireLwwRegister<Boolean>>()
        for ((pid, v) in death["alive_before"]!!.jsonObject) {
            alive[pid.toLong()] = WireLwwRegister(WireStamp(1, 0, 1), v.jsonPrimitive.boolean)
        }
        val op = death["op"]!!.jsonObject
        val pid = op["key"]!!.jsonPrimitive.content.removePrefix("alive/pid").toLong()
        alive[pid]!!.set(stamp(op["stamp"]!!.jsonObject), op.bool("value"))
        val live = open.filter { (_, p) -> alive[p]?.value == true }.map { it.first }.distinct().sorted()
        val expectLive = death["expect"]!!.jsonObject["live_docs_after"]!!.jsonArray
            .map { it.jsonPrimitive.content }.sorted()
        assertEquals(expectLive, live)
    }
}
