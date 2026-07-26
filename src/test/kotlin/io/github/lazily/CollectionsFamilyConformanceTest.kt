package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The keyed-collection ordering contract replayed against **all three** execution
 * flavors.
 *
 * `CollectionsConformanceTest` already replays the ordering fixtures, but only
 * against the single-threaded [SourceMap]. That was the widest blind spot in the
 * family: of this binding's six keyed collections, **five had no ordering surface
 * at all** — including the single-threaded [ComputedMap]. The coverage matrix
 * read OK because *a* flavor passed.
 *
 * Invalidation is measured by **recompute count** inside the reader's own
 * compute body, not by a cache flag: a counter the library has to move is the one
 * probe that cannot be satisfied by runner bookkeeping.
 */
/** Order-sensitive, so an order reader's *value* changes on a reorder. */
private fun orderDigest(keys: List<String>): Int {
    var acc = 17
    for (key in keys) {
        for (ch in key) acc = acc * 31 + ch.code
        acc = acc * 31 + 7
    }
    return acc
}

class CollectionsFamilyConformanceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(name: String): JsonObject {
        ConformanceFixtures.requireRoot()
        return json.parseToJsonElement(ConformanceFixtures.read("collections/$name")).jsonObject
    }

    /** One execution flavor, driving the same fixture ops. */
    private interface Flavor {
        val name: String

        fun setValue(key: String, value: Int)
        fun remove(key: String)
        fun moveTo(key: String, index: Int)
        fun moveBefore(key: String, anchor: String)
        fun moveAfter(key: String, anchor: String)

        fun keysUntracked(): List<String>
        fun valueUntracked(key: String): Int?

        /**
         * The entry's node identity: stable across a reorder, different after a
         * re-mint. This is what separates a move from a remove + insert.
         */
        fun entryIdentity(key: String): Any?

        fun valueReader(key: String): () -> Int
        fun membershipReader(): () -> Int
        fun orderReader(): () -> Int
    }

    private class SyncFlavor : Flavor {
        val ctx = Context()
        val map = SourceMap<String, Int>(ctx)

        override val name = "sync"
        override fun setValue(key: String, value: Int) {
            if (map.containsNow(key)) map.setValue(key, value) else map.insert(key, value)
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun moveTo(key: String, index: Int) {
            if (map.containsNow(key)) map.moveTo(key, index)
        }

        override fun moveBefore(key: String, anchor: String) {
            if (map.containsNow(key) && map.containsNow(anchor)) map.moveBefore(key, anchor)
        }

        override fun moveAfter(key: String, anchor: String) {
            if (map.containsNow(key) && map.containsNow(anchor)) map.moveAfter(key, anchor)
        }

        override fun keysUntracked(): List<String> = map.keysNow()
        override fun valueUntracked(key: String): Int? =
            if (map.containsNow(key)) map.get(key) else null

        override fun entryIdentity(key: String): Any? =
            if (map.containsNow(key)) map.value(key).id else null

        private fun reader(body: ComputeOps.() -> Int): () -> Int {
            var count = 0
            val slot = ctx.slotAny {
                count += 1
                body()
            }
            return {
                ctx.getSlotAny(slot)
                count
            }
        }

        override fun valueReader(key: String): () -> Int =
            reader { if (map.containsNow(key)) map.get(key, this) else -1 }

        // `len()` / `keys()` ALLOCATE a Computed. Allocating inside the reader
        // body would mint a fresh node on every recompute, so the reader would
        // depend on a throwaway slot and never be invalidated again. Hoist once.
        override fun membershipReader(): () -> Int {
            val lenSlot = map.len()
            return reader { getSlotAny(lenSlot.id) as Int }
        }

        @Suppress("UNCHECKED_CAST")
        override fun orderReader(): () -> Int {
            val keysSlot = map.keys()
            return reader { orderDigest(getSlotAny(keysSlot.id) as List<String>) }
        }
    }

    private class ThreadSafeFlavor : Flavor {
        val ctx = ThreadSafeContext()
        val map = ThreadSafeSourceMap<String, Int>()

        override val name = "thread-safe"
        override fun setValue(key: String, value: Int) = map.set(ctx, key, value)
        override fun remove(key: String) {
            map.remove(ctx, key)
        }

        override fun moveTo(key: String, index: Int) {
            map.moveTo(ctx, key, index)
        }

        override fun moveBefore(key: String, anchor: String) {
            map.moveBefore(ctx, key, anchor)
        }

        override fun moveAfter(key: String, anchor: String) {
            map.moveAfter(ctx, key, anchor)
        }

        override fun keysUntracked(): List<String> = map.presentKeys()
        override fun valueUntracked(key: String): Int? = map.get(ctx, key)
        override fun entryIdentity(key: String): Any? = map.handle(key)?.id

        private fun reader(body: () -> Int): () -> Int {
            var count = 0
            val slot = ctx.slotAny(memo = false) {
                count += 1
                body()
            }
            return {
                ctx.getSlotAny(slot)
                count
            }
        }

        override fun valueReader(key: String): () -> Int = reader { map.get(ctx, key) ?: -1 }
        override fun membershipReader(): () -> Int = reader { map.len(ctx) }
        override fun orderReader(): () -> Int = reader { orderDigest(map.keys(ctx)) }
    }

    private class AsyncFlavor : Flavor {
        val ctx = AsyncContext()
        val map = AsyncSourceMap<String, Int>()

        override val name = "async"
        override fun setValue(key: String, value: Int) = map.set(ctx, key, value)
        override fun remove(key: String) {
            map.remove(ctx, key)
        }

        override fun moveTo(key: String, index: Int) {
            map.moveTo(ctx, key, index)
        }

        override fun moveBefore(key: String, anchor: String) {
            map.moveBefore(ctx, key, anchor)
        }

        override fun moveAfter(key: String, anchor: String) {
            map.moveAfter(ctx, key, anchor)
        }

        override fun keysUntracked(): List<String> = map.presentKeys()
        override fun valueUntracked(key: String): Int? = map.get(ctx, key)
        override fun entryIdentity(key: String): Any? = map.handle(key)

        // The async graph only recomputes through `getAsync`; the non-blocking
        // `get` returns the cached value (or null) without driving. Driving is
        // the ONE async-coloured obligation in this contract — ordering itself is
        // not, which is why the same ops drive all three flavors.
        private fun reader(body: AsyncComputeContext.() -> Int): () -> Int {
            var count = 0
            val slot = ctx.computedAsync {
                count += 1
                body()
            }
            return {
                runBlocking { ctx.getAsync(slot) }
                count
            }
        }

        override fun valueReader(key: String): () -> Int =
            reader { map.get(ctx, key, this) ?: -1 }

        override fun membershipReader(): () -> Int = reader { map.len(ctx, this) }
        override fun orderReader(): () -> Int = reader { orderDigest(map.keys(ctx, this)) }
    }

    private fun flavors(): List<() -> Flavor> =
        listOf({ SyncFlavor() }, { ThreadSafeFlavor() }, { AsyncFlavor() })

    private fun strings(element: JsonArray): List<String> =
        element.map { it.jsonPrimitive.content }

    private fun replay(flavor: Flavor, fixtureName: String) {
        val fixture = loadFixture(fixtureName)
        fun where(i: Int) = "${flavor.name} $fixtureName step $i"

        val initial = fixture["initial"]!!.jsonObject
        val seed = strings(initial["order"]!!.jsonArray)
        assertTrue(seed.isNotEmpty(), "${flavor.name}: fixture $fixtureName seeds no keys")
        val values = initial["values"]!!.jsonObject
        for (key in seed) {
            val value = values[key] ?: error("${flavor.name}: no initial value for $key")
            flavor.setValue(key, value.jsonPrimitive.int)
        }

        val steps = fixture["steps"]!!.jsonArray
        // A zero-step replay asserts nothing and still reports green.
        assertTrue(
            steps.isNotEmpty(),
            "${flavor.name}: fixture $fixtureName has no steps - a vacuous replay would report green",
        )

        var matrices = 0

        steps.forEachIndexed { i, rawStep ->
            val step = rawStep.jsonObject
            val op = step["op"]!!.jsonObject
            val expected = step["expected"]!!.jsonObject

            // Rebuild + settle readers from the CURRENT key set so each step's
            // invalidation is measured against a fully settled graph.
            val beforeKeys = flavor.keysUntracked()
            val valueReaders = beforeKeys.associateWith { flavor.valueReader(it) }
            val baseline = valueReaders.mapValues { (_, drive) -> drive() }
            val membership = flavor.membershipReader()
            val order = flavor.orderReader()
            val membershipBase = membership()
            val orderBase = order()

            val idsBefore = beforeKeys.associateWith { flavor.entryIdentity(it) }

            when (val kind = op["type"]!!.jsonPrimitive.content) {
                "set_value", "insert" -> {
                    val key = op["key"]!!.jsonPrimitive.content
                    flavor.setValue(key, op["value"]!!.jsonPrimitive.int)
                    // `at` says where the new key lands; minting appends, so "end"
                    // is already right. An unrecognised form must fail, not
                    // silently append.
                    val at = op["at"]?.jsonPrimitive
                    if (at != null) {
                        val asIndex = at.intOrNull
                        if (asIndex != null) {
                            flavor.moveTo(key, asIndex)
                        } else {
                            assertEquals(
                                "end",
                                at.content,
                                "${where(i)}: unsupported insert placement",
                            )
                        }
                    }
                }
                "remove" -> flavor.remove(op["key"]!!.jsonPrimitive.content)
                "move_to" ->
                    flavor.moveTo(op["key"]!!.jsonPrimitive.content, op["index"]!!.jsonPrimitive.int)
                "move_before" ->
                    flavor.moveBefore(
                        op["key"]!!.jsonPrimitive.content,
                        op["before"]!!.jsonPrimitive.content,
                    )
                "move_after" ->
                    flavor.moveAfter(
                        op["key"]!!.jsonPrimitive.content,
                        op["after"]!!.jsonPrimitive.content,
                    )
                else ->
                    error(
                        "${where(i)}: unsupported op $kind - an unknown op must fail, " +
                            "never silently skip",
                    )
            }

            val gotOrder = flavor.keysUntracked()
            assertEquals(
                strings(expected["order"]!!.jsonArray),
                gotOrder,
                "${where(i)}: order diverged",
            )

            expected["membership"]?.let {
                assertEquals(
                    strings(it.jsonArray).toSet(),
                    gotOrder.toSet(),
                    "${where(i)}: membership set diverged",
                )
            }

            expected["values"]?.jsonObject?.forEach { (key, want) ->
                assertEquals(
                    want.jsonPrimitive.int,
                    flavor.valueUntracked(key),
                    "${where(i)}: value for $key diverged",
                )
            }

            // The invalidation matrix, read from expected.invalidates - where the
            // fixtures actually nest it. lazily-rs read it off the step instead,
            // so its assertion never ran once.
            val invalidates =
                expected["invalidates"]?.jsonObject
                    ?: error("${where(i)}: expected.invalidates is missing - the matrix is the contract")
            matrices += 1

            val dirty = invalidates["value"]?.jsonArray?.let { strings(it) }?.toSet() ?: emptySet()
            val survivors = gotOrder.toSet()
            for ((key, drive) in valueReaders) {
                if (key !in survivors) continue // removed: no entry left to read
                val recomputed = drive() != baseline[key]
                if (key in dirty) {
                    assertTrue(
                        recomputed,
                        "${where(i)}: value reader for $key should have been invalidated",
                    )
                } else {
                    assertTrue(
                        !recomputed,
                        "${where(i)}: value reader for $key should have stayed cached - " +
                            "per-entry independence is the whole point",
                    )
                }
            }

            assertEquals(
                invalidates["membership"]?.jsonPrimitive?.boolean ?: false,
                membership() != membershipBase,
                "${where(i)}: membership reader invalidation mismatch - " +
                    "a pure reorder must NOT invalidate set-identity readers",
            )
            assertEquals(
                invalidates["order"]?.jsonPrimitive?.boolean ?: false,
                order() != orderBase,
                "${where(i)}: order reader invalidation mismatch",
            )

            // Handle stability: the law separating an atomic move from a remove +
            // re-mint. A reorder keeps the entry's node, so dependents and lineage
            // survive.
            expected["handle_stable"]?.jsonObject?.forEach { (key, wantStable) ->
                val after = flavor.entryIdentity(key)
                val before = idsBefore[key]
                if (wantStable.jsonPrimitive.boolean) {
                    assertTrue(
                        before != null && after == before,
                        "${where(i)}: handle for $key must survive the move - " +
                            "a reorder that re-mints is a remove + insert, not a move",
                    )
                } else {
                    assertTrue(after != before, "${where(i)}: handle for $key should have changed")
                }
            }
        }

        assertTrue(
            matrices > 0,
            "${flavor.name}: $fixtureName asserted no invalidation matrix",
        )
    }

    @Test
    fun `atomic move contract binds every flavor`() {
        for (build in flavors()) replay(build(), "cellmap_atomic_move.json")
    }

    @Test
    fun `reader independence contract binds every flavor`() {
        for (build in flavors()) replay(build(), "cellmap_independence.json")
    }

    /**
     * The **derived-slot** families carry the Core surface too.
     *
     * The ordering fixtures mutate values, which a ComputedMap has no `set` for,
     * so they drive the SourceMap flavors above. That left the three derived-slot
     * maps — including the single-threaded [ComputedMap] — outside the gate, which
     * is exactly how five of this binding's six families ended up with no ordering
     * surface while the matrix read green. This drives them directly.
     */
    @Test
    fun `derived-slot families carry the Core surface`() {
        val seed = listOf("a", "b", "c", "d")

        run {
            val ctx = Context()
            val map = ComputedMap<String, Int>()
            seed.forEachIndexed { i, key -> map.getOrInsertWith(ctx, key) { i + 1 } }

            var lenRecomputes = 0
            val lenSlot = ctx.slotAny {
                lenRecomputes += 1
                map.len(this)
            }
            var orderRecomputes = 0
            val orderSlot = ctx.slotAny {
                orderRecomputes += 1
                orderDigest(map.keys(this))
            }
            ctx.getSlotAny(lenSlot)
            ctx.getSlotAny(orderSlot)
            val lenBase = lenRecomputes
            val orderBase = orderRecomputes
            val handleBefore = map.handle("a")

            // A pure reorder bumps ONLY the order signal.
            assertTrue(map.moveBefore(ctx, "a", "d"), "sync ComputedMap: moveBefore applied")
            assertEquals(listOf("b", "c", "a", "d"), map.presentKeys(), "sync ComputedMap: order")
            ctx.getSlotAny(orderSlot)
            ctx.getSlotAny(lenSlot)
            assertTrue(orderRecomputes != orderBase, "sync ComputedMap: order reader invalidated")
            assertEquals(
                lenBase,
                lenRecomputes,
                "sync ComputedMap: a pure reorder must NOT invalidate a membership reader",
            )
            assertEquals(
                handleBefore,
                map.handle("a"),
                "sync ComputedMap: a reorder must keep the entry's node",
            )

            // A removal bumps membership.
            assertTrue(map.remove(ctx, "b"), "sync ComputedMap: remove applied")
            ctx.getSlotAny(lenSlot)
            assertTrue(
                lenRecomputes != lenBase,
                "sync ComputedMap: remove must invalidate a membership reader",
            )
            assertEquals(3, map.presentCount, "sync ComputedMap: present count after remove")
        }

        run {
            val ctx = ThreadSafeContext()
            val map = ThreadSafeComputedMap<String, Int>()
            seed.forEachIndexed { i, key -> map.getOrInsertWith(ctx, key) { i + 1 } }
            assertTrue(map.moveBefore(ctx, "a", "d"), "thread-safe ComputedMap: moveBefore applied")
            assertEquals(
                listOf("b", "c", "a", "d"),
                map.presentKeys(),
                "thread-safe ComputedMap: the target must be computed on the pre-removal list",
            )
            assertEquals(2, map.position("a"), "thread-safe ComputedMap: position")
        }

        run {
            val ctx = AsyncContext()
            val map = AsyncComputedMap<String, Int>()
            seed.forEachIndexed { i, key -> map.getOrInsertWith(ctx, key) { i + 1 } }
            assertTrue(map.moveBefore(ctx, "a", "d"), "async ComputedMap: moveBefore applied")
            assertEquals(
                listOf("b", "c", "a", "d"),
                map.presentKeys(),
                "async ComputedMap: the target must be computed on the pre-removal list",
            )
            assertEquals(2, map.position("a"), "async ComputedMap: position")
        }
    }

    /**
     * Cover a direction the canonical corpus does not.
     *
     * `cellmap_atomic_move.json`'s only `move_before` step moves a key that
     * already *follows* its anchor (from=2, anchor=0), so it exercises only the
     * branch where the insertion point is the anchor index itself. The branch
     * where the key *precedes* its anchor — target `anchor - 1` — is never
     * replayed. That is exactly the direction lazily-zig's `moveBefore` was wrong
     * in: `moveBefore("a","d")` on `[a,b,c,d]` produced `[b,c,d,a]`.
     */
    @Test
    fun `directional moves bind every flavor`() {
        val seed = listOf("a", "b", "c", "d")
        val cases: List<Triple<String, (Flavor) -> Unit, List<String>>> =
            listOf(
                Triple("move_before, key precedes anchor", { f: Flavor -> f.moveBefore("a", "d") },
                    listOf("b", "c", "a", "d")),
                Triple("move_before, key follows anchor", { f: Flavor -> f.moveBefore("d", "b") },
                    listOf("a", "d", "b", "c")),
                Triple("move_after, key precedes anchor", { f: Flavor -> f.moveAfter("a", "c") },
                    listOf("b", "c", "a", "d")),
                Triple("move_after, key follows anchor", { f: Flavor -> f.moveAfter("d", "a") },
                    listOf("a", "d", "b", "c")),
                Triple("move_to past the end clamps", { f: Flavor -> f.moveTo("a", 99) },
                    listOf("b", "c", "d", "a")),
                Triple("move_to to -1 clamps to the front", { f: Flavor -> f.moveTo("d", -1) },
                    listOf("d", "a", "b", "c")),
                Triple("move on an absent key is a no-op", { f: Flavor ->
                    f.moveBefore("zz", "a")
                    f.moveTo("zz", 0)
                }, seed),
            )

        for (build in flavors()) {
            for ((what, run, want) in cases) {
                val flavor = build()
                seed.forEachIndexed { i, key -> flavor.setValue(key, i + 1) }
                val identityBefore = flavor.entryIdentity("a")
                run(flavor)
                assertEquals(
                    want,
                    flavor.keysUntracked(),
                    "${flavor.name}: $what diverged - the target must be computed on the " +
                        "pre-removal list",
                )
                assertEquals(
                    identityBefore,
                    flavor.entryIdentity("a"),
                    "${flavor.name}: $what re-minted entry a - a reorder must keep the node",
                )
            }
        }
    }
}
