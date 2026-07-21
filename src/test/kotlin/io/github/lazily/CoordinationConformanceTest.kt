package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language conformance for distributed coordination (`#lzcoord`) — see
 * `lazily-spec/docs/coordination.md` and the JSON fixtures under
 * `lazily-spec/conformance/coordination/`.
 */
class CoordinationConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("coordination/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun steps(fx: JsonObject) = fx["steps"]!!.jsonArray
    private fun inval(step: JsonObject, reader: String) =
        step["expected"]!!.jsonObject["invalidates"]!!.jsonObject[reader]!!.jsonPrimitive.boolean

    private inline fun <reified T : Any> observe(ctx: Context, cell: Source<T>): Computed<Any> {
        val obs = ctx.computed { getCell(cell) as Any }
        ctx.get(obs)
        return obs
    }

    private fun checkInval(ctx: Context, obs: Computed<Any>, step: JsonObject, reader: String) {
        val wasCached = ctx.isSet(obs)
        ctx.get(obs)
        assertEquals(inval(step, reader), !wasCached, "$reader invalidation")
    }

    @Test
    fun lease() {
        val fx = loadFixture("lease.json")
        val ctx = Context()
        val lease = LeaseCell<Long>(ctx)
        val obs = observe(ctx, lease.holderCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            when (op["type"]!!.jsonPrimitive.content) {
                "acquire" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.longOrNull,
                    lease.acquire(op["peer"]!!.jsonPrimitive.long, now, op["ttl"]!!.jsonPrimitive.long),
                )
                "renew" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.boolean,
                    lease.renew(op["peer"]!!.jsonPrimitive.long, now, op["ttl"]!!.jsonPrimitive.long),
                )
                "tick" -> assertEquals(step["returns"]!!.jsonPrimitive.boolean, lease.tick(now))
            }
            val exp = step["expected"]!!.jsonObject
            assertEquals(exp["holder"]!!.jsonPrimitive.longOrNull, lease.holder(now))
            assertEquals(exp["held"]!!.jsonPrimitive.boolean, lease.isHeld(now))
            assertEquals(exp["fence"]!!.jsonPrimitive.long, lease.fence())
            checkInval(ctx, obs, step, "holder")
        }
    }

    @Test
    fun leader() {
        val fx = loadFixture("leader.json")
        val ctx = Context()
        val me = fx["config"]!!.jsonObject["me"]!!.jsonPrimitive.long
        val leader = LeaderCell<Long>(ctx, me)
        val obs = observe(ctx, leader.currentLeaderCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]!!.jsonPrimitive.long
            val role = when (op["type"]!!.jsonPrimitive.content) {
                "campaign" -> leader.campaign(now, op["ttl"]!!.jsonPrimitive.long)
                "contend" -> leader.contend(op["peer"]!!.jsonPrimitive.long, now, op["ttl"]!!.jsonPrimitive.long)
                "tick" -> leader.tick(now)
                else -> error("bad op")
            }
            val exp = step["expected"]!!.jsonObject
            assertEquals(exp["role"]!!.jsonPrimitive.content, role.name)
            assertEquals(exp["current_leader"]!!.jsonPrimitive.longOrNull, leader.currentLeader(now))
            checkInval(ctx, obs, step, "current_leader")
        }
    }

    @Test
    fun lock() {
        val fx = loadFixture("lock.json")
        val ctx = Context()
        val lock = LockCell<Long>(ctx)
        val obs = observe(ctx, lock.isLockedCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val op = step["op"]!!.jsonObject
            val now = op["now"]?.jsonPrimitive?.longOrNull ?: 0
            when (op["type"]!!.jsonPrimitive.content) {
                "acquire" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.longOrNull,
                    lock.acquire(op["peer"]!!.jsonPrimitive.long, now, op["ttl"]!!.jsonPrimitive.long),
                )
                "validate" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.boolean,
                    lock.validate(op["fence"]!!.jsonPrimitive.long),
                )
                "tick" -> assertEquals(step["returns"]!!.jsonPrimitive.boolean, lock.tick(now))
            }
            val exp = step["expected"]!!.jsonObject
            assertEquals(exp["is_locked"]!!.jsonPrimitive.boolean, lock.isLocked(now))
            assertEquals(exp["fence"]!!.jsonPrimitive.long, lock.fence())
            checkInval(ctx, obs, step, "is_locked")
        }
    }

    @Test
    fun semaphore() {
        val fx = loadFixture("semaphore.json")
        val ctx = Context()
        val cap = fx["config"]!!.jsonObject["capacity"]!!.jsonPrimitive.long
        val sem = SemaphoreCell(ctx, cap)
        val obs = observe(ctx, sem.permitsAvailableCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            when (step["op"]!!.jsonObject["type"]!!.jsonPrimitive.content) {
                "acquire" -> assertEquals(
                    step["returns"]!!.jsonPrimitive.booleanOrNull,
                    sem.acquire(),
                )
                "release" -> sem.release()
            }
            val exp = step["expected"]!!.jsonObject
            assertEquals(exp["permits_available"]!!.jsonPrimitive.long, sem.permitsAvailable())
            checkInval(ctx, obs, step, "permits_available")
        }
    }

    @Test
    fun quorum() {
        val fx = loadFixture("quorum.json")
        val ctx = Context()
        val total = fx["config"]!!.jsonObject["total"]!!.jsonPrimitive.long
        val q = BarrierCell.quorum<Long>(ctx, total)
        val obs = observe(ctx, q.isOpenCell)
        for (element in steps(fx)) {
            val step = element.jsonObject
            val got = q.arrive(step["op"]!!.jsonObject["peer"]!!.jsonPrimitive.long)
            assertEquals(step["returns"]!!.jsonPrimitive.boolean, got)
            val exp = step["expected"]!!.jsonObject
            assertEquals(exp["votes"]!!.jsonPrimitive.long, q.count())
            assertEquals(exp["is_open"]!!.jsonPrimitive.boolean, q.isOpen())
            checkInval(ctx, obs, step, "is_open")
        }
    }
}
