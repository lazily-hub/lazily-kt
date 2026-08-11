package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays the canonical `lazily-spec/conformance/lossless-tree/` compute fixtures
 * against the native [LosslessTreeCrdt] — the same `{scenarios: [{seed, steps,
 * expect}]}` shape and the same `label`→id addressing the Rust reference harness
 * uses. Each scenario builds `seed.tree` on replica `a`, replays the schedule of
 * ops / forks / anti-entropy syncs across named replicas, and asserts exact
 * rendered text, live-node counts, and convergence across delivery orders. The
 * lossless invariant `render(tree) == source_text` is what every assertion checks.
 *
 * The step interpreter lives in [LosslessTreeReplayWorld] so that
 * [LosslessTreeDeliverOrderTest] can pin the parts of it no fixture can see —
 * chiefly that a `deliver.order` sequence reaches `applyUpdate` UNSORTED and in
 * ONE call.
 */
class LosslessTreeCrdtConformanceTest {
    private val json = Json

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("lossless-tree/$name")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun assertExpect(
        world: LosslessTreeReplayWorld,
        expect: JsonObject,
        scenario: String,
    ) {
        expect["render"]?.jsonPrimitive?.content?.let {
            assertEquals(it, world.replicas.getValue("a").render(), "$scenario: render on `a`")
        }
        expect["render_on"]?.jsonObject?.forEach { (name, text) ->
            assertEquals(text.jsonPrimitive.content, world.replicas.getValue(name).render(), "$scenario: render on `$name`")
        }
        expect["live_nodes"]?.jsonPrimitive?.int?.let {
            assertEquals(it, world.replicas.getValue("a").liveNodeCount(), "$scenario: live_nodes on `a`")
        }
        expect["converged"]?.jsonArray?.let { names ->
            val labels = names.map { it.jsonPrimitive.content }
            val first = world.replicas.getValue(labels[0]).render()
            for (name in labels.drop(1)) {
                assertEquals(first, world.replicas.getValue(name).render(), "$scenario: `${labels[0]}`/`$name` should converge")
            }
        }
    }

    private fun runFixture(name: String) {
        val fixture = loadFixture(name)
        for ((i, scenario) in ConformanceScenarios.indexed("lossless-tree/$name", fixture)) {
            val label = "$name[${ConformanceScenarios.idOf(scenario, i).value}]"
            val seed = scenario.getValue("seed").jsonObject
            val peer = seed.getValue("peer").jsonPrimitive.long
            val world = LosslessTreeReplayWorld()
            world.replicas["a"] = LosslessTreeCrdt(peer)
            world.buildChildren(seed.getValue("tree").jsonObject, TreeNodeId.ROOT)
            scenario["steps"]?.jsonArray?.forEach { world.applyStep(it.jsonObject) }
            assertExpect(world, scenario.getValue("expect").jsonObject, label)
        }
    }

    @Test fun `conformance exact roundtrip`() = runFixture("exact_roundtrip.json")

    @Test fun `conformance one leaf edit delta`() = runFixture("one_leaf_edit_delta.json")

    @Test fun `conformance split merge`() = runFixture("split_merge.json")

    @Test fun `conformance concurrent insert same parent`() = runFixture("concurrent_insert_same_parent.json")

    @Test fun `conformance concurrent reorder and leaf edit`() = runFixture("concurrent_reorder_and_leaf_edit.json")

    @Test fun `conformance non contiguous anti entropy`() = runFixture("non_contiguous_anti_entropy.json")

    @Test fun `conformance token trivia preservation`() = runFixture("token_trivia_preservation.json")

    @Test fun `conformance invalid source roundtrip`() = runFixture("invalid_source_roundtrip.json")

    @Test fun `conformance concurrent conflict preserves text`() = runFixture("concurrent_conflict_preserves_text.json")

    /**
     * `apply_update` advances the Lamport counter past every op it OBSERVES,
     * unconditionally and BEFORE the idempotence skip, so a write minted after a
     * sync outranks everything that sync delivered. Every other fixture here is
     * fork → concurrent edits → sync and never mutates a replica AFTER a sync
     * into it, so none of them can see this. Note both replicas still CONVERGE
     * when the advance is missing — `render_on` is the load-bearing assertion,
     * not `converged`.
     */
    @Test fun `conformance apply update advances counter`() = runFixture("apply_update_advances_counter.json")

    /**
     * `apply_update` BUFFERS an op whose dependency has not arrived and retries
     * it as the rest of the same batch lands, rather than dropping it while
     * recording its dot. Dropping is PERMANENT — the following full `sync`
     * returns nothing, because both frontiers already hold every op — which is
     * why the fixture syncs a second time and still asserts convergence.
     */
    @Test fun `conformance out of order delivery buffers`() = runFixture("out_of_order_delivery_buffers.json")
}
