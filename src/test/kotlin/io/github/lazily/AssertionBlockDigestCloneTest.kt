package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `#lzrunnerownjsonclone` obligation for this binding.
 *
 * [ConformanceFixtures.read] hands a runner the fixture's **text**, so every
 * conformance runner in this repo parses the corpus a SECOND time and hands
 * [ConformanceFixtures.noteBound] an object out of its OWN parse rather than the
 * loader's. That is structurally the shape that cost lazily-cpp 71 of its 96
 * unbound sites: its hand-rolled reader normalised numbers, so a clone digested
 * `"value": 5` as `#5.000000` where the loader digested `#5`, every bind silently
 * missed, and the clone's own comment falsely asserted content-identity.
 *
 * kotlinx.serialization keeps a number's SOURCE token in
 * [JsonPrimitive.content], so kt's clones should be byte-identical to the
 * loader's — but "should" is the word that hid the cpp bug for a cycle. This
 * asserts it instead, across every site of a set of fixtures chosen to cover the
 * three shapes that can diverge, and against both `Json` spellings kt's runners
 * actually use.
 */
class AssertionBlockDigestCloneTest {
    /** The `Json` instance most runners in this repo hold. */
    private val runnerJson = Json { ignoreUnknownKeys = true }

    /**
     * Fixtures whose blocks between them carry a NUMBER-bearing, a
     * STRING-bearing and a NESTED-object-bearing site. The test asserts that
     * coverage below rather than trusting this list to stay true.
     */
    private val fixtures =
        listOf(
            "reactive-graph/transitive_invalidation_reaches_depth.json",
            "collections/queuecell_spsc_push_pop.json",
            "stdlib/timer.json",
            "signaling/frames.json",
            "codec/nodeid_exact_range.json",
        )

    @Test
    fun everyRunnerParseDigestsIdenticallyToTheLoader() {
        ConformanceFixtures.requireRoot()
        var sitesChecked = 0
        var sawNumber = false
        var sawString = false
        var sawNested = false

        for (rel in fixtures) {
            val text = ConformanceFixtures.read(rel)
            val loaderSites = ConformanceFixtures.blockSitesOf(rel, text)
            assertTrue(loaderSites.isNotEmpty(), "$rel carries no assertion-block site to compare")

            // The two parses kt's runners actually perform. Both must reproduce the
            // loader's bytes exactly, or `noteBound`'s content match cannot fire.
            for (
            (label, root) in
            listOf(
                "Json.parseToJsonElement" to Json.parseToJsonElement(text),
                "Json { ignoreUnknownKeys = true }" to runnerJson.parseToJsonElement(text),
            )
            ) {
                val cloneSites = ConformanceFixtures.blockSitesOf(rel, root)
                assertEquals(
                    loaderSites.keys,
                    cloneSites.keys,
                    "$rel: the $label clone walks to a different SITE SET than the loader",
                )
                for ((siteId, loaderBlock) in loaderSites) {
                    val cloneBlock = cloneSites.getValue(siteId)
                    assertEquals(
                        ConformanceFixtures.blockDigest(loaderBlock),
                        ConformanceFixtures.blockDigest(cloneBlock),
                        "$siteId: the $label clone digests differently from the loader. A runner " +
                            "binding this object would match NOTHING (#lzrunnerownjsonclone)",
                    )
                    // The bind itself is `JsonObject` equality, so assert the thing
                    // `noteBound` actually relies on, not only its hash.
                    assertEquals(
                        loaderBlock,
                        cloneBlock,
                        "$siteId: the $label clone is not EQUAL to the loader's object",
                    )
                    sitesChecked++
                }
            }

            for (block in loaderSites.values) {
                if (!sawNumber) sawNumber = block.values.any { it is JsonPrimitive && !it.isString && it.content.any { c -> c.isDigit() } }
                if (!sawString) sawString = block.values.any { it is JsonPrimitive && it.isString }
                if (!sawNested) sawNested = block.values.any { it is JsonObject }
            }
        }

        assertTrue(sitesChecked > 0, "compared nothing — the proof is vacuous (#lzvacuousrun)")
        assertTrue(sawNumber, "no NUMBER-bearing block in the chosen set; number spelling is the seam cpp lost")
        assertTrue(sawString, "no STRING-bearing block in the chosen set; escaping is the other seam")
        assertTrue(sawNested, "no NESTED-object block in the chosen set; the digest recurses and so must the proof")
    }

    /**
     * The digest must DISTINGUISH number spellings, or the proof above is
     * satisfied by a digest that folds them together and the guard would be blind
     * to exactly cpp's defect.
     *
     * `5`, `5.0` and `5e0` are the same VALUE and three different claims on the
     * wire. A digest that folds them cannot tell a corpus that tightened a
     * spelling from one that did not.
     */
    @Test
    fun theDigestSeparatesNumberSpellings() {
        val spellings = listOf("5", "5.0", "5e0")
        val digests =
            spellings.map { literal ->
                val text = """{"assertions":{"value":$literal}}"""
                val site = ConformanceFixtures.blockSitesOf("probe.json", text)
                ConformanceFixtures.blockDigest(site.getValue("probe.json|assertions"))
            }
        assertEquals(
            spellings.size,
            digests.toSet().size,
            "the block digest folds number spellings together: $spellings all hash to $digests. " +
                "That is the lazily-cpp defect in the digest rather than in a runner — a clone " +
                "that renormalises a number would then bind, and the corpus could tighten a " +
                "spelling with nothing noticing (#lzrunnerownjsonclone)",
        )
    }
}
