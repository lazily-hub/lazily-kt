package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays the canonical `lazily-spec/conformance/collections/` CRDT and semantic
 * fixtures against the native runtime — the language-agnostic conformance every
 * binding MUST validate (`lazily-spec/cell-model.md` § Move-aware sequence order,
 * § Free-text CRDT, § Memoized semantic tree, § Manufactured identity).
 *
 * These are **compute** fixtures: each loads the model, replays the `steps`, and
 * asserts the `expect` observable effects identically to every other binding.
 */
class CollectionsCrdtConformanceTest {
    private val json = Json

    private var fixtureOverride: ((String) -> JsonObject)? = null
    private var expectationTransform: (String, JsonObject) -> JsonObject = { _, expected -> expected }
    private var bindCanonicalBlocks = true

    private fun loadFixture(name: String): JsonObject {
        fixtureOverride?.let { return it(name) }
        val text = ConformanceFixtures.read("collections/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun assertionKeys(
        fixturePath: String,
        sitePath: String,
        expected: JsonObject,
        visitedSites: MutableSet<String>,
    ): AssertionKeys {
        val siteId = "$fixturePath|$sitePath"
        check(visitedSites.add(siteId)) { "$siteId: assertion block visited more than once" }
        return AssertionKeys(
            siteId,
            expectationTransform(siteId, expected),
            fixturePath,
            rungZeroBind = bindCanonicalBlocks,
        )
    }

    private fun assertExactSites(
        fixturePath: String,
        fixture: JsonObject,
        expectedCount: Int,
        visitedSites: Set<String>,
    ) {
        val declared = ConformanceFixtures.blockSitesOf(fixturePath, fixture).keys
        assertEquals(expectedCount, declared.size, "$fixturePath: assertion-block site pin")
        assertEquals(declared, visitedSites, "$fixturePath: exact assertion-block sites")
    }

    private fun requireExactlyOneAction(
        step: JsonObject,
        standalone: Set<String>,
        where: String,
    ) {
        val actions = standalone.filter(step::containsKey) + listOfNotNull("op".takeIf(step::containsKey))
        check(actions.size == 1) { "$where: expected exactly one recognized action, got $actions in $step" }
        check(!step.containsKey("on") || step.containsKey("op")) {
            "$where: `on` is only a target modifier for an `op`, never an action by itself"
        }
    }

    // -- StableId -----------------------------------------------------------

    /**
     * A scenario's block list under [key]: absence is an empty list, a PRESENT
     * non-array is a named failure (`#lzsiblingrunnermasking`).
     *
     * `s[key]?.jsonArray?.map { ... } ?: emptyList()` spelled the same thing, but
     * the `?:` also swallows a shape the corpus never allowed, and the spelling
     * is the one this binding's flag/presence guard now refuses outright. Same
     * shape as StateChartConformanceTest's `actionsOf`.
     */
    private fun blocksOf(
        s: JsonObject,
        key: String,
    ): List<Block> =
        when (val raw = s[key]) {
            null -> emptyList()
            is JsonArray -> raw.map { block(it.jsonObject) }
            else -> error("`$key` must be a JSON array of blocks, got $raw (#lzflagcoercion)")
        }

    private fun block(obj: JsonObject): Block {
        val text = obj.getValue("text").jsonPrimitive.content
        val anchor = obj["anchor"]?.jsonPrimitive?.contentOrNull
        return if (anchor != null) Block.anchored(anchor, text) else Block.text(text)
    }

    @Test
    fun `conformance stableid alignment`() {
        val fixturePath = "collections/stableid_alignment.json"
        val fixture = loadFixture("stableid_alignment.json")
        val visitedSites = linkedSetOf<String>()
        for ((scenarioIndex, s) in ConformanceScenarios.indexed(fixturePath, fixture)) {
            val name = s.getValue("name").jsonPrimitive.content
            val expect = s.getValue("expect").jsonObject
            val keys = assertionKeys(fixturePath, "scenarios[$scenarioIndex].expect", expect, visitedSites)

            // Scenario 1 & 2: key equality over a single `blocks` list.
            val blocksEl = s["blocks"]
            if (blocksEl != null) {
                val blocks = blocksEl.jsonArray.map { block(it.jsonObject) }
                val blockKeys = blocks.map { blockKey(it) }
                keys.assertKeyWith("key_equal") { rawPairs ->
                    rawPairs.jsonArray.forEach { pair ->
                        val (i, j) = pair.jsonArray.map { it.jsonPrimitive.int }
                        assertEquals(blockKeys[i], blockKeys[j], "$name: key_equal[$i,$j]")
                    }
                }
                keys.assertKeyWith("key_not_equal") { rawPairs ->
                    rawPairs.jsonArray.forEach { pair ->
                        val (i, j) = pair.jsonArray.map { it.jsonPrimitive.int }
                        assertFalse(blockKeys[i] == blockKeys[j], "$name: key_not_equal[$i,$j]")
                    }
                }
                keys.requireAllSatisfied()
                continue
            }

            val oldBlocks = blocksOf(s, "old")
            val newBlocks = blocksOf(s, "new")

            // Scenario 6: assign_stable_keys flows identity through edit.
            if (keys.has("new_key_equals_old_key")) {
                val oldKeys = oldBlocks.map { blockKey(it).asString() }
                val newKeys = assignStableKeys(oldBlocks, newBlocks)
                keys.assertKeyWith("new_key_equals_old_key") { keyFlow ->
                    keyFlow.jsonArray.forEach { pair ->
                        val (ni, oi) = pair.jsonArray.map { it.jsonPrimitive.int }
                        assertEquals(oldKeys[oi], newKeys[ni], "$name: new_key_equals_old_key[$ni,$oi]")
                    }
                }
                keys.requireAllSatisfied()
                continue
            }

            // Scenarios 3/4/5: align(old, new) → matches + removed.
            val alignment = align(oldBlocks, newBlocks)
            val editedSimilarities = mutableListOf<Float>()
            keys.assertKeyWith("matches") { rawMatches ->
                rawMatches.jsonArray.forEachIndexed { ni, mEl ->
                    val want = mEl.jsonPrimitive.content
                    val got =
                        when (val m = alignment.newMatches[ni]) {
                            is Match.Same -> "Same:${m.old}"
                            is Match.Edited -> "Edited:${m.old}"
                            is Match.Inserted -> "Inserted"
                        }
                    assertEquals(want, got, "$name: matches[$ni]")
                    if (want.startsWith("Edited")) {
                        editedSimilarities += (alignment.newMatches[ni] as Match.Edited).similarity
                    }
                }
            }
            keys.assertKeyWith("similarity_min") { rawMin ->
                val primitive = rawMin as? JsonPrimitive
                check(primitive != null && !primitive.isString) { "$name: similarity_min must be a JSON number" }
                val min = requireNotNull(primitive.floatOrNull) { "$name: similarity_min must be numeric" }
                check(min.isFinite()) { "$name: similarity_min must be finite" }
                assertTrue(editedSimilarities.isNotEmpty(), "$name: similarity_min has no edited match to constrain")
                for (similarity in editedSimilarities) {
                    assertTrue(similarity >= min, "$name: similarity_min requires $similarity >= $min")
                }
            }
            keys.assertKeyWith("removed") { wantRemoved ->
                assertEquals(wantRemoved.jsonArray.map { it.jsonPrimitive.int }, alignment.removed, "$name: removed")
            }
            keys.requireAllSatisfied()
        }
        assertExactSites(fixturePath, fixture, 6, visitedSites)
    }

    private val JsonPrimitive.floatOrNull: Float?
        get() = contentOrNull?.trim()?.let { it.toFloatOrNull() }

    // -- TextCrdt -----------------------------------------------------------

    private class TextRepl(
        val crdt: TextCrdt,
    )

    private fun seedTextCrdt(scenario: JsonObject): Pair<String, TextRepl> {
        val replicaPeer =
            scenario["replica"]
                ?.jsonObject
                ?.get("peer")
                ?.jsonPrimitive
                ?.long ?: 1L
        val seedEl = scenario["seed"]
        val crdt =
            when {
                seedEl == null -> TextCrdt(replicaPeer)
                seedEl is JsonPrimitive && seedEl.isString -> TextCrdt(replicaPeer, seedEl.content)
                seedEl is JsonObject ->
                    TextCrdt(
                        seedEl["peer"]?.jsonPrimitive?.long ?: replicaPeer,
                        seedEl.getValue("text").jsonPrimitive.content,
                    )
                else -> error("unexpected text-crdt seed: $seedEl")
            }
        return "a" to TextRepl(crdt)
    }

    @Test
    fun `conformance textcrdt convergence`() {
        val fixturePath = "collections/textcrdt_convergence.json"
        val fixture = loadFixture("textcrdt_convergence.json")
        val visitedSites = linkedSetOf<String>()
        for ((scenarioIndex, s) in ConformanceScenarios.indexed(fixturePath, fixture)) {
            val name = s.getValue("name").jsonPrimitive.content
            val replicas = LinkedHashMap<String, TextRepl>()
            val (defaultName, defaultRepl) = seedTextCrdt(s)
            replicas[defaultName] = defaultRepl

            for (stepEl in s.getValue("steps").jsonArray) {
                val step = stepEl.jsonObject
                requireExactlyOneAction(step, setOf("fork", "clone", "merge"), "$name text step")
                when {
                    step["fork"] != null -> {
                        val newName = step.getValue("fork").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        replicas[newName] = TextRepl(replicas.getValue(defaultName).crdt.fork(newPeer))
                    }
                    step["clone"] != null -> {
                        val newName = step.getValue("clone").jsonPrimitive.content
                        val from = step["from"]?.jsonPrimitive?.content ?: defaultName
                        replicas[newName] = TextRepl(replicas.getValue(from).crdt.clone())
                    }
                    step["merge"] != null -> {
                        val m = step.getValue("merge").jsonObject
                        val into = m.getValue("into").jsonPrimitive.content
                        val from = m.getValue("from").jsonPrimitive.content
                        replicas.getValue(into).crdt.merge(replicas.getValue(from).crdt)
                    }
                    step["on"] != null -> {
                        val target = step.getValue("on").jsonPrimitive.content
                        applyTextOp(replicas.getValue(target).crdt, step)
                    }
                    step["op"] != null -> applyTextOp(replicas.getValue(defaultName).crdt, step)
                    else -> error("$name: unrecognized text action $step")
                }
            }

            val expect = s.getValue("expect").jsonObject
            val keys = assertionKeys(fixturePath, "scenarios[$scenarioIndex].expect", expect, visitedSites)
            val primary = replicas.getValue(defaultName).crdt
            keys.assertString("text") { primary.text() }
            keys.assertInt("len") { primary.len() }
            keys.assertInt("tombstone_count") { primary.tombstoneCount() }
            keys.assertKeyWith("a_starts_with") { want ->
                assertTrue(primary.text().startsWith(want.jsonPrimitive.content), "$name: a_starts_with")
            }
            keys.assertKeyWith("a_ends_with") { want ->
                assertTrue(primary.text().endsWith(want.jsonPrimitive.content), "$name: a_ends_with")
            }
            keys.assertKeyWith("texts_equal") { rawPairs ->
                rawPairs.jsonArray.forEach { pair ->
                    val (x, y) = pair.jsonArray.map { it.jsonPrimitive.content }
                    assertEquals(
                        replicas.getValue(x).crdt.text(),
                        replicas.getValue(y).crdt.text(),
                        "$name: texts_equal[$x,$y]",
                    )
                }
            }
            keys.requireAllSatisfied()
        }
        assertExactSites(fixturePath, fixture, 7, visitedSites)
    }

    // -- TextCrdt delta sync (#lztextsync) ----------------------------------

    @Test
    fun `conformance textcrdt delta sync`() {
        val fixturePath = "collections/textcrdt_delta_sync.json"
        val fixture = loadFixture("textcrdt_delta_sync.json")
        val visitedSites = linkedSetOf<String>()
        for ((scenarioIndex, s) in ConformanceScenarios.indexed(fixturePath, fixture)) {
            val name = s.getValue("name").jsonPrimitive.content
            val replicas = LinkedHashMap<String, TextRepl>()
            val (defaultName, defaultRepl) = seedTextCrdt(s)
            replicas[defaultName] = defaultRepl

            for (stepEl in s.getValue("steps").jsonArray) {
                val step = stepEl.jsonObject
                requireExactlyOneAction(
                    step,
                    setOf("fork", "new", "snapshot", "delta", "exchange"),
                    "$name delta step",
                )
                when {
                    step["fork"] != null -> {
                        val newName = step.getValue("fork").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        val forked = replicas.getValue(defaultName).crdt.fork(newPeer)
                        replicas[newName] = TextRepl(forked)
                    }
                    step["new"] != null -> {
                        val newName = step.getValue("new").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        replicas[newName] = TextRepl(TextCrdt(newPeer))
                    }
                    step["snapshot"] != null -> {
                        val snap = step.getValue("snapshot").jsonObject
                        val from = snap.getValue("from").jsonPrimitive.content
                        val into = snap.getValue("into").jsonPrimitive.content
                        val peer = snap.getValue("peer").jsonPrimitive.long
                        val delta = replicas.getValue(from).crdt.deltaSince(emptyMap())
                        val rebuilt = TextCrdt(peer)
                        val changed = rebuilt.applyDelta(delta)
                        assertEquals(
                            step.getValue("expect_changed").jsonPrimitive.boolean,
                            changed,
                            "$name: snapshot expect_changed",
                        )
                        replicas[into] = TextRepl(rebuilt)
                    }
                    step["delta"] != null -> {
                        val d = step.getValue("delta").jsonObject
                        val into = d.getValue("into").jsonPrimitive.content
                        val from = d.getValue("from").jsonPrimitive.content
                        val theirVv = replicas.getValue(into).crdt.versionVector()
                        val delta = replicas.getValue(from).crdt.deltaSince(theirVv)
                        val changed = replicas.getValue(into).crdt.applyDelta(delta)
                        step["expect_changed"]?.let {
                            assertEquals(
                                it.jsonPrimitive.boolean,
                                changed,
                                "$name: delta expect_changed",
                            )
                        }
                    }
                    step["exchange"] != null -> {
                        val (x, y) = step.getValue("exchange").jsonArray.map { it.jsonPrimitive.content }
                        val rx = replicas.getValue(x).crdt
                        val ry = replicas.getValue(y).crdt
                        val xToY = rx.deltaSince(ry.versionVector())
                        val yToX = ry.deltaSince(rx.versionVector())
                        ry.applyDelta(xToY)
                        rx.applyDelta(yToX)
                    }
                    step["on"] != null -> {
                        val target = step.getValue("on").jsonPrimitive.content
                        applyTextOp(replicas.getValue(target).crdt, step)
                    }
                    step["op"] != null -> applyTextOp(replicas.getValue(defaultName).crdt, step)
                    else -> error("$name: unrecognized delta action $step")
                }
            }

            val expect = s.getValue("expect").jsonObject
            val keys = assertionKeys(fixturePath, "scenarios[$scenarioIndex].expect", expect, visitedSites)
            keys.sub("text_on") { textOn ->
                for (replica in textOn.keys.sorted()) {
                    textOn.assertString(replica) { replicas.getValue(replica).crdt.text() }
                }
            }
            keys.assertKeyWith("texts_equal") { rawPairs ->
                rawPairs.jsonArray.forEach { pair ->
                    val (x, y) = pair.jsonArray.map { it.jsonPrimitive.content }
                    assertEquals(
                        replicas.getValue(x).crdt.text(),
                        replicas.getValue(y).crdt.text(),
                        "$name: texts_equal[$x,$y]",
                    )
                }
            }
            keys.sub("version_vector_on") { vectors ->
                for (replica in vectors.keys.sorted()) {
                    val actual = replicas.getValue(replica).crdt.versionVector()
                    vectors.sub(replica) { vector ->
                        assertEquals(vector.keys, actual.keys.map { it.toString() }.toSet(), "$name: version vector peers")
                        for (peer in vector.keys.sorted()) {
                            vector.assertLong(peer) { actual.getValue(peer.toLong()) }
                        }
                    }
                }
            }
            keys.requireAllSatisfied()
        }
        assertExactSites(fixturePath, fixture, 4, visitedSites)
    }

    private fun applyTextOp(
        crdt: TextCrdt,
        step: JsonObject,
    ) {
        when (step.getValue("op").jsonPrimitive.content) {
            "insert" ->
                crdt.insert(
                    step.getValue("index").jsonPrimitive.int,
                    step
                        .getValue("ch")
                        .jsonPrimitive.content
                        .first(),
                )
            "insert_str" -> crdt.insertString(step.getValue("index").jsonPrimitive.int, step.getValue("str").jsonPrimitive.content)
            "delete" -> crdt.delete(step.getValue("index").jsonPrimitive.int)
            "gc" -> {
                val stable = step.getValue("stable").jsonPrimitive.boolean
                val expectCollected = step.getValue("expect_collected").jsonPrimitive.int
                assertEquals(expectCollected, crdt.gcWith { stable }, "gc expect_collected")
            }
            else -> error("unknown text-crdt op: ${step.getValue("op")}")
        }
    }

    // -- SeqCrdt ------------------------------------------------------------

    private class SeqRepl(
        val crdt: SeqCrdt<String, Any>,
    )

    private fun seqValue(el: kotlinx.serialization.json.JsonElement): Any =
        if (el.jsonPrimitive.isString) el.jsonPrimitive.content else el.jsonPrimitive.int

    @Test
    fun `conformance seqcrdt convergence`() {
        val fixturePath = "collections/seqcrdt_convergence.json"
        val fixture = loadFixture("seqcrdt_convergence.json")
        val visitedSites = linkedSetOf<String>()
        for ((scenarioIndex, s) in ConformanceScenarios.indexed(fixturePath, fixture)) {
            val name = s.getValue("name").jsonPrimitive.content
            val replicas = LinkedHashMap<String, SeqRepl>()
            val defaultPeer =
                s["replica"]
                    ?.jsonObject
                    ?.get("peer")
                    ?.jsonPrimitive
                    ?.long
            val seedEl = s["seed"]
            val basePeer =
                when {
                    defaultPeer != null -> defaultPeer
                    seedEl is JsonObject -> seedEl["peer"]?.jsonPrimitive?.long ?: 1L
                    else -> 1L
                }
            val base = SeqCrdt<String, Any>(basePeer)
            if (seedEl is JsonObject) {
                for (ins in seedEl.getValue("inserts").jsonArray) {
                    val o = ins.jsonObject
                    base.insertBack(
                        o.getValue("id").jsonPrimitive.content,
                        seqValue(o.getValue("value")),
                        o.getValue("now").jsonPrimitive.long,
                    )
                }
            }
            replicas["a"] = SeqRepl(base)

            for (stepEl in s.getValue("steps").jsonArray) {
                val step = stepEl.jsonObject
                requireExactlyOneAction(step, setOf("fork", "clone", "merge"), "$name sequence step")
                when {
                    step["fork"] != null -> {
                        val newName = step.getValue("fork").jsonPrimitive.content
                        val newPeer = step.getValue("peer").jsonPrimitive.long
                        replicas[newName] = SeqRepl(replicas.getValue("a").crdt.cloneStateAs(newPeer))
                    }
                    step["clone"] != null -> {
                        val newName = step.getValue("clone").jsonPrimitive.content
                        val from = step["from"]?.jsonPrimitive?.content ?: "a"
                        replicas[newName] = SeqRepl(replicas.getValue(from).crdt.cloneState())
                    }
                    step["merge"] != null -> {
                        val m = step.getValue("merge").jsonObject
                        val into = m.getValue("into").jsonPrimitive.content
                        val from = m.getValue("from").jsonPrimitive.content
                        val now = step["now"]?.jsonPrimitive?.long ?: 0L
                        replicas.getValue(into).crdt.merge(replicas.getValue(from).crdt, now)
                    }
                    step["on"] != null -> {
                        val target = step.getValue("on").jsonPrimitive.content
                        applySeqOp(replicas.getValue(target).crdt, step)
                    }
                    step["op"] != null -> applySeqOp(replicas.getValue("a").crdt, step)
                    else -> error("$name: unrecognized sequence action $step")
                }
            }

            val expect = s.getValue("expect").jsonObject
            val keys = assertionKeys(fixturePath, "scenarios[$scenarioIndex].expect", expect, visitedSites)
            // Default target: an explicit `on`, else the first orders_equal
            // replica (the merged result), else the main replica "a".
            val defaultTarget =
                when {
                    expect["on"] != null -> expect.getValue("on").jsonPrimitive.content
                    expect["orders_equal"] != null ->
                        expect
                            .getValue("orders_equal")
                            .jsonArray
                            .first()
                            .jsonArray
                            .first()
                            .jsonPrimitive.content
                    else -> "a"
                }
            val primary = replicas.getValue(defaultTarget).crdt
            if (keys.has("on")) {
                keys.excuseKey("on", "selects the replica whose observables the sibling keys assert")
            }
            keys.assertStrings("order") { primary.order() }
            keys.sub("get") { gets ->
                for (id in gets.keys.sorted()) {
                    gets.assertKeyWith(id) { want -> assertEquals(seqValue(want), primary.get(id), "$name: get[$id]") }
                }
            }
            keys.assertInt("len") { primary.order().size }
            keys.assertKeyWith("contains_all") { rawIds ->
                rawIds.jsonArray.forEach { id ->
                    assertTrue(primary.contains(id.jsonPrimitive.content), "$name: contains_all ${id.jsonPrimitive.content}")
                }
            }
            keys.sub("order_on") { orders ->
                for (replica in orders.keys.sorted()) {
                    orders.assertStrings(replica) { replicas.getValue(replica).crdt.order() }
                }
            }
            keys.sub("get_on") { getsOn ->
                for (replica in getsOn.keys.sorted()) {
                    getsOn.sub(replica) { gets ->
                        for (id in gets.keys.sorted()) {
                            gets.assertKeyWith(id) { want ->
                                assertEquals(seqValue(want), replicas.getValue(replica).crdt.get(id), "$name: get_on[$replica][$id]")
                            }
                        }
                    }
                }
            }
            keys.assertKeyWith("orders_equal") { rawPairs ->
                rawPairs.jsonArray.forEach { pair ->
                    val (x, y) = pair.jsonArray.map { it.jsonPrimitive.content }
                    assertEquals(
                        replicas.getValue(x).crdt.order(),
                        replicas.getValue(y).crdt.order(),
                        "$name: orders_equal[$x,$y]",
                    )
                }
            }
            keys.sub("not_contains_on") { absentOn ->
                for (replica in absentOn.keys.sorted()) {
                    absentOn.assertKeyWith(replica) { rawIds ->
                        rawIds.jsonArray.forEach { id ->
                            assertFalse(
                                replicas.getValue(replica).crdt.contains(id.jsonPrimitive.content),
                                "$name: not_contains_on[$replica][${id.jsonPrimitive.content}]",
                            )
                        }
                    }
                }
            }
            keys.requireAllSatisfied()
        }
        assertExactSites(fixturePath, fixture, 8, visitedSites)
    }

    private fun applySeqOp(
        crdt: SeqCrdt<String, Any>,
        step: JsonObject,
    ) {
        val now = step["now"]?.jsonPrimitive?.long ?: 0L
        when (step.getValue("op").jsonPrimitive.content) {
            "insert_back" -> crdt.insertBack(step.getValue("id").jsonPrimitive.content, seqValue(step.getValue("value")), now)
            "insert_front" -> crdt.insertFront(step.getValue("id").jsonPrimitive.content, seqValue(step.getValue("value")), now)
            "move_after" -> crdt.moveAfter(step.getValue("id").jsonPrimitive.content, step.getValue("anchor").jsonPrimitive.content, now)
            "set_value" -> crdt.setValue(step.getValue("id").jsonPrimitive.content, seqValue(step.getValue("value")), now)
            "remove" -> crdt.remove(step.getValue("id").jsonPrimitive.content, now)
            else -> error("unknown seq-crdt op: ${step.getValue("op")}")
        }
    }

    // -- SemTree ------------------------------------------------------------

    @Test
    fun `conformance semtree incremental`() {
        val fixturePath = "collections/semtree_incremental.json"
        val fixture = loadFixture("semtree_incremental.json")
        val visitedSites = linkedSetOf<String>()
        for ((scenarioIndex, s) in ConformanceScenarios.indexed(fixturePath, fixture)) {
            val name = s.getValue("name").jsonPrimitive.content
            val foldName = s.getValue("fold").jsonPrimitive.content
            val fold = semFold(foldName)

            val ctx = Context()
            val tree = SourceTree<String, Int>(ctx)
            buildTree(ctx, tree, "root", s.getValue("tree").jsonObject, null)
            val sums = SemTree.build(ctx, tree, "root", fold)

            // expect_initial
            val expectInitial = s.getValue("expect_initial").jsonObject
            val initialKeys =
                assertionKeys(
                    fixturePath,
                    "scenarios[$scenarioIndex].expect_initial",
                    expectInitial,
                    visitedSites,
                )
            for (node in initialKeys.keys.sorted()) {
                initialKeys.assertInt(node) { checkNotNull(sums.nodeValue(ctx, node)) }
            }
            initialKeys.requireAllSatisfied()

            val edit = s["edit"]?.jsonObject
            val expectAfter = s["expect_after"]?.jsonObject
            val afterKeys =
                expectAfter?.let {
                    assertionKeys(
                        fixturePath,
                        "scenarios[$scenarioIndex].expect_after",
                        it,
                        visitedSites,
                    )
                }
            val memoGuard = expectAfter?.get("downstream_consumer_reran") != null

            if (edit != null && memoGuard) {
                // An edit that does not change the folded result must NOT re-run a
                // downstream consumer (memo equality guard). Wire an instrumented
                // observer BEFORE the edit, then assert its call count is unchanged.
                var calls = 0
                val observer =
                    ctx.computed {
                        calls++
                        sums.value(ctx)
                    }
                assertEquals(
                    expectInitial.getValue("root").jsonPrimitive.int,
                    ctx.get(observer),
                    "$name: observer primed",
                )
                assertEquals(1, calls, "$name: observer primed once")

                tree.setValue(edit.getValue("id").jsonPrimitive.content, edit.getValue("value").jsonPrimitive.int)
                ctx.get(observer)
                afterKeys!!.assertKeyWith("downstream_consumer_reran") { rawReran ->
                    val reran = rawReran.jsonPrimitive.boolean
                    assertEquals(if (reran) 2 else 1, calls, "$name: downstream_consumer_reran=$reran")
                }
                for (node in afterKeys.keys.sorted() - "downstream_consumer_reran") {
                    afterKeys.assertInt(node) { checkNotNull(sums.nodeValue(ctx, node)) }
                }
            } else if (edit != null) {
                val siblingA = sums.node("a")
                tree.setValue(edit.getValue("id").jsonPrimitive.content, edit.getValue("value").jsonPrimitive.int)
                for (node in afterKeys!!.keys.sorted()) {
                    when (node) {
                        // BOTH directions, and the node's EXISTENCE first
                        // (`#lzflagcoercion`). This arm used to run only when the
                        // flag was true, so `sibling_a_cached: false` was compared
                        // by nothing at all; and `sums.node("a")` is null for a
                        // tree that carries no 'a', so a `false` expectation would
                        // have been satisfiable by the sibling's NON-EXISTENCE
                        // rather than by its cache state — which is precisely the
                        // second defect lazily-go found beside the flag coercion.
                        "sibling_a_cached" -> {
                            assertNotNull(
                                siblingA,
                                "$name: the fixture asserts sibling 'a' cache state, but this tree " +
                                    "carries no derived slot for 'a' — absence must not answer for it",
                            )
                            afterKeys.assertBoolean(node) { ctx.isSet(siblingA) }
                        }
                        else -> afterKeys.assertInt(node) { checkNotNull(sums.nodeValue(ctx, node)) }
                    }
                }
            }

            s["remove_child"]?.jsonObject?.let { rc ->
                tree.remove(rc.getValue("child").jsonPrimitive.content)
                for (node in afterKeys!!.keys.sorted()) {
                    afterKeys.assertInt(node) { checkNotNull(sums.nodeValue(ctx, node)) }
                }
            }
            afterKeys?.requireAllSatisfied()
        }
        assertExactSites(fixturePath, fixture, 6, visitedSites)
    }

    @Test
    fun `collections crdt expectation families reject fixture value mutations`() {
        data class Mutation(
            val id: String,
            val key: String,
            val fixture: String,
            val siteId: String,
            val replacement: String,
            val run: CollectionsCrdtConformanceTest.() -> Unit,
        )

        val mutations =
            listOf(
                Mutation("stable.key_equal", "key_equal", "stableid_alignment.json", "collections/stableid_alignment.json|scenarios[0].expect", "[[0,2]]") { `conformance stableid alignment`() },
                Mutation("stable.key_not_equal", "key_not_equal", "stableid_alignment.json", "collections/stableid_alignment.json|scenarios[0].expect", "[[0,1]]") { `conformance stableid alignment`() },
                Mutation("stable.new_key_equals_old_key", "new_key_equals_old_key", "stableid_alignment.json", "collections/stableid_alignment.json|scenarios[5].expect", "[[0,0]]") { `conformance stableid alignment`() },
                Mutation("stable.matches", "matches", "stableid_alignment.json", "collections/stableid_alignment.json|scenarios[2].expect", """["Inserted","Same:0","Same:1"]""") { `conformance stableid alignment`() },
                Mutation("stable.similarity_min", "similarity_min", "stableid_alignment.json", "collections/stableid_alignment.json|scenarios[3].expect", "0.99") { `conformance stableid alignment`() },
                Mutation("stable.removed", "removed", "stableid_alignment.json", "collections/stableid_alignment.json|scenarios[4].expect", "[0]") { `conformance stableid alignment`() },
                Mutation("text.text", "text", "textcrdt_convergence.json", "collections/textcrdt_convergence.json|scenarios[0].expect", "\"wrong\"") { `conformance textcrdt convergence`() },
                Mutation("text.len", "len", "textcrdt_convergence.json", "collections/textcrdt_convergence.json|scenarios[0].expect", "99") { `conformance textcrdt convergence`() },
                Mutation("text.tombstone_count", "tombstone_count", "textcrdt_convergence.json", "collections/textcrdt_convergence.json|scenarios[4].expect", "99") { `conformance textcrdt convergence`() },
                Mutation("text.a_starts_with", "a_starts_with", "textcrdt_convergence.json", "collections/textcrdt_convergence.json|scenarios[1].expect", "\"wrong\"") { `conformance textcrdt convergence`() },
                Mutation("text.a_ends_with", "a_ends_with", "textcrdt_convergence.json", "collections/textcrdt_convergence.json|scenarios[1].expect", "\"wrong\"") { `conformance textcrdt convergence`() },
                Mutation("text.texts_equal", "texts_equal", "textcrdt_convergence.json", "collections/textcrdt_convergence.json|scenarios[3].expect", "[[\"ab\",\"a\"]]") { `conformance textcrdt convergence`() },
                Mutation("delta.text_on", "text_on", "textcrdt_delta_sync.json", "collections/textcrdt_delta_sync.json|scenarios[1].expect", "{\"a1\":\"wrong\"}") { `conformance textcrdt delta sync`() },
                Mutation("delta.texts_equal", "texts_equal", "textcrdt_delta_sync.json", "collections/textcrdt_delta_sync.json|scenarios[1].expect", "[[\"a1\",\"a\"]]") { `conformance textcrdt delta sync`() },
                Mutation("delta.version_vector_on", "version_vector_on", "textcrdt_delta_sync.json", "collections/textcrdt_delta_sync.json|scenarios[0].expect", "{\"a\":{\"1\":999},\"b\":{\"1\":4,\"2\":5}}") { `conformance textcrdt delta sync`() },
                Mutation("seq.order", "order", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[0].expect", "[\"wrong\"]") { `conformance seqcrdt convergence`() },
                Mutation("seq.get", "get", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[0].expect", "{\"b\":999}") { `conformance seqcrdt convergence`() },
                Mutation("seq.len", "len", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[1].expect", "99") { `conformance seqcrdt convergence`() },
                Mutation("seq.contains_all", "contains_all", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[2].expect", "[\"missing\"]") { `conformance seqcrdt convergence`() },
                Mutation("seq.order_on", "order_on", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[3].expect", "{\"merged\":[\"wrong\"]}") { `conformance seqcrdt convergence`() },
                Mutation("seq.get_on", "get_on", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[4].expect", "{\"merged\":{\"a\":999}}") { `conformance seqcrdt convergence`() },
                Mutation("seq.orders_equal", "orders_equal", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[2].expect", "[[\"a2\",\"a\"]]") { `conformance seqcrdt convergence`() },
                Mutation("seq.not_contains_on", "not_contains_on", "seqcrdt_convergence.json", "collections/seqcrdt_convergence.json|scenarios[5].expect", "{\"ab\":[\"a\"]}") { `conformance seqcrdt convergence`() },
                Mutation("semtree.node_value", "root", "semtree_incremental.json", "collections/semtree_incremental.json|scenarios[0].expect_initial", "999") { `conformance semtree incremental`() },
                Mutation("semtree.downstream_consumer_reran", "downstream_consumer_reran", "semtree_incremental.json", "collections/semtree_incremental.json|scenarios[1].expect_after", "true") { `conformance semtree incremental`() },
                Mutation("semtree.sibling_a_cached", "sibling_a_cached", "semtree_incremental.json", "collections/semtree_incremental.json|scenarios[0].expect_after", "false") { `conformance semtree incremental`() },
            )
        assertEquals(
            setOf(
                "stable.key_equal",
                "stable.key_not_equal",
                "stable.new_key_equals_old_key",
                "stable.matches",
                "stable.similarity_min",
                "stable.removed",
                "text.text",
                "text.len",
                "text.tombstone_count",
                "text.a_starts_with",
                "text.a_ends_with",
                "text.texts_equal",
                "delta.text_on",
                "delta.texts_equal",
                "delta.version_vector_on",
                "seq.order",
                "seq.get",
                "seq.len",
                "seq.contains_all",
                "seq.order_on",
                "seq.get_on",
                "seq.orders_equal",
                "seq.not_contains_on",
                "semtree.node_value",
                "semtree.downstream_consumer_reran",
                "semtree.sibling_a_cached",
            ),
            mutations.map { it.id }.toSet(),
            "CRDT mutation matrix families",
        )

        try {
            fixtureOverride = { name -> rawFixture(name) }
            bindCanonicalBlocks = false
            for (mutation in mutations) {
                val replacement: JsonElement = json.parseToJsonElement(mutation.replacement)
                val raw = Files.readString(ConformanceFixtures.path("collections/${mutation.fixture}"))
                val canonical =
                    ConformanceFixtures
                        .blockSitesOf("collections/${mutation.fixture}", raw)
                        .getValue(mutation.siteId)
                assertTrue(canonical.getValue(mutation.key) != replacement, "${mutation.id}: mutation changes value")
                var hits = 0
                expectationTransform = { siteId, expected ->
                    if (siteId == mutation.siteId) {
                        hits++
                        JsonObject(expected + (mutation.key to replacement))
                    } else {
                        expected
                    }
                }
                val failure = assertFails("${mutation.id}: changed fixture value survived production replay") {
                    mutation.run(this)
                }
                assertEquals(1, hits, "${mutation.id}: target site visited exactly once")
                assertTrue(
                    failure.message.orEmpty().contains(mutation.key),
                    "${mutation.id}: production failure must name family '${mutation.key}', got ${failure.message}",
                )
            }
        } finally {
            resetMutationMode()
        }
    }

    @Test
    fun `text delta and sequence action discriminators reject unknown keys`() {
        data class ActionMutation(
            val id: String,
            val fixture: String,
            val scenario: Int,
            val step: Int,
            val run: CollectionsCrdtConformanceTest.() -> Unit,
        )

        val mutations =
            listOf(
                ActionMutation("text", "textcrdt_convergence.json", 0, 0) { `conformance textcrdt convergence`() },
                ActionMutation("delta", "textcrdt_delta_sync.json", 0, 0) { `conformance textcrdt delta sync`() },
                ActionMutation("sequence", "seqcrdt_convergence.json", 0, 0) { `conformance seqcrdt convergence`() },
            )
        try {
            bindCanonicalBlocks = false
            expectationTransform = { _, expected -> expected }
            for (mutation in mutations) {
                fixtureOverride = { name ->
                    val raw = rawFixture(name)
                    if (name == mutation.fixture) {
                        rewriteScenarioStepKey(raw, mutation.scenario, mutation.step, "op", "unknown_${mutation.id}_action")
                    } else {
                        raw
                    }
                }
                val failure = assertFails("${mutation.id}: unknown action discriminator survived production replay") {
                    mutation.run(this)
                }
                assertTrue(
                    failure.message.orEmpty().contains("exactly one recognized action"),
                    "${mutation.id}: expected fail-closed dispatcher, got ${failure.message}",
                )
            }
        } finally {
            resetMutationMode()
        }
    }

    private fun rawFixture(name: String): JsonObject =
        json.parseToJsonElement(Files.readString(ConformanceFixtures.path("collections/$name"))).jsonObject

    private fun rewriteScenarioStepKey(
        fixture: JsonObject,
        scenarioIndex: Int,
        stepIndex: Int,
        oldKey: String,
        newKey: String,
    ): JsonObject {
        val scenarios = fixture.getValue("scenarios").jsonArray.toMutableList()
        val scenario = scenarios[scenarioIndex].jsonObject
        val steps = scenario.getValue("steps").jsonArray.toMutableList()
        val step = LinkedHashMap(steps[stepIndex].jsonObject)
        val value = checkNotNull(step.remove(oldKey)) { "missing action key '$oldKey'" }
        step[newKey] = value
        steps[stepIndex] = JsonObject(step)
        scenarios[scenarioIndex] = JsonObject(scenario + ("steps" to JsonArray(steps)))
        return JsonObject(fixture + ("scenarios" to JsonArray(scenarios)))
    }

    private fun resetMutationMode() {
        fixtureOverride = null
        expectationTransform = { _, expected -> expected }
        bindCanonicalBlocks = true
    }

    private fun semFold(name: String): SemFold<Int, Int> =
        when (name) {
            "sum" -> SemFold { v, kids -> v + kids.sum() }
            "count_positive" -> SemFold { v, kids -> (if (v > 0) 1 else 0) + kids.sum() }
            else -> error("unknown semtree fold: $name")
        }

    /** Build a [SourceTree] node from a fixture tree object, attaching it under [parent] (or as a root). */
    private fun buildTree(
        ctx: Context,
        tree: SourceTree<String, Int>,
        id: String,
        obj: JsonObject,
        parent: String?,
    ) {
        val value = obj.getValue("value").jsonPrimitive.int
        if (parent == null) tree.addRoot(id, value) else tree.insertChild(parent, id, value)
        val children = obj["children"]?.jsonObject ?: return
        val order = children.getValue("order").jsonArray.map { it.jsonPrimitive.content }
        val values = children.getValue("values").jsonObject
        for (cid in order) buildTree(ctx, tree, cid, values.getValue(cid).jsonObject, id)
    }
}
