package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SHAPE table for [ConformanceFixtures.blockSitesOf] — the walk rung 0 is
 * derived from (`#lzktblockwalk`, `#lzarrayelementsites`).
 *
 * The canonical corpus exercises exactly ONE array-valued tracked key
 * (`signaling/anti_spoof_session.json`, eight `expect` arrays carrying 12
 * plain-object elements), so the corpus cannot catch an OVER-widening: a walk
 * that emitted a site for a scalar element, for a nested element, for an
 * untracked key's array, or that renumbered a mixed array's indexes would derive
 * the same 12 sites there and the magnitude equality would stay green. Every
 * such shape is probed here on SYNTHETIC bytes instead, which is the only place
 * they exist.
 *
 * These probes go through [ConformanceFixtures.blockSitesOf], which is the walk
 * itself and does NOT touch the inventory — a synthetic fixture that declared
 * sites would be a site no runner can bind, and rung 0 would fail on bytes that
 * are not in the corpus.
 */
class AssertionBlockWalkShapeTest {
    private fun sitesOf(text: String): List<String> =
        ConformanceFixtures
            .blockSitesOf("probe.json", text)
            .keys
            .map { it.removePrefix("probe.json|") }
            .sorted()

    @Test
    fun anObjectValuedTrackedKeyIsOneSiteAndIsNotDescendedInto() {
        assertEquals(
            listOf("expect"),
            sitesOf("""{"expect":{"a":1,"expected":{"b":2}}}"""),
            "an object-valued tracked key is the site, emitted and NOT descended into — a nested " +
                "`expected` inside it must not become a second, separately bindable site",
        )
    }

    @Test
    fun anArrayOfObjectsIsOneSitePerElement() {
        assertEquals(
            listOf("expect[0]", "expect[1]"),
            sitesOf("""{"expect":[{"a":1},{"a":2}]}"""),
            "each plain-object element of an array held at a tracked key is its own site " +
                "(#lzarrayelementsites)",
        )
    }

    @Test
    fun anArrayOfScalarsIsNoSiteAtAll() {
        assertEquals(
            emptyList(),
            sitesOf("""{"expect":[1,"two",true,null]}"""),
            "PLAIN OBJECTS only — a scalar or null element carries no assertion keys, so a site " +
                "there would be unbindable by construction",
        )
    }

    @Test
    fun aMixedArrayKeepsTrueIndexes() {
        assertEquals(
            listOf("expect[0]", "expect[2]"),
            sitesOf("""{"expect":[{"a":1},3,{"a":2}]}"""),
            "the label carries the element's REAL position: renumbering the survivors to [0],[1] " +
                "would name a site the corpus does not have, and the two sides of the magnitude " +
                "equality would enumerate different labels",
        )
    }

    @Test
    fun aNestedArrayElementIsNotASite() {
        assertEquals(
            emptyList(),
            sitesOf("""{"expect":[[{"a":1}]]}"""),
            "ONE level only — the element pass is entered from the tracked-key branch alone, so " +
                "`expect[0][0]` is not directly under a tracked key",
        )
    }

    @Test
    fun anUntrackedKeyIsNotABlockArrayValuedOrNot() {
        assertEquals(
            emptyList(),
            sitesOf("""{"scenarios":[{"a":1},{"a":2}],"steps":[{"input":{"a":1}}]}"""),
            "an untracked key is not a block whatever its value — widening to array elements must " +
                "not widen the NAME set with it",
        )
        assertEquals(
            listOf("steps[0].expect[0]"),
            sitesOf("""{"steps":[{"expect":[{"a":1}]}]}"""),
            "a tracked key reached THROUGH an untracked array still emits its elements",
        )
    }

    /**
     * Label disambiguation, on the array the corpus actually carries.
     *
     * A label per ARRAY would collapse a step's frames into one site, and two
     * frames that are individually falsifiable would stop being individually
     * nameable. `steps[2]` is the three-peer join, the corpus's widest `expect`
     * array, and `[1]`/`[2]` differ only in their routing target — so this also
     * proves the DIGEST dimension separates them, which a per-array label or a
     * label that dropped the index could not.
     */
    @Test
    fun everyElementOfOneArrayIsNamedAndDigestedSeparately() {
        ConformanceFixtures.requireRoot()
        val rel = "signaling/anti_spoof_session.json"
        val sites = ConformanceFixtures.blockSitesOf(rel, ConformanceFixtures.read(rel))

        val elementSites = sites.keys.filter { it.contains(".expect[") }.sorted()
        assertEquals(
            listOf(
                "$rel|steps[0].expect[0]",
                "$rel|steps[1].expect[0]",
                "$rel|steps[1].expect[1]",
                "$rel|steps[2].expect[0]",
                "$rel|steps[2].expect[1]",
                "$rel|steps[2].expect[2]",
                "$rel|steps[3].expect[0]",
                "$rel|steps[4].expect[0]",
                "$rel|steps[5].expect[0]",
                "$rel|steps[6].expect[0]",
                "$rel|steps[7].expect[0]",
                "$rel|steps[7].expect[1]",
            ),
            elementSites,
            "the 12 expected-frame elements must be named one per element, per step " +
                "(#lzarrayelementsites)",
        )

        val stepTwo = elementSites.filter { it.startsWith("$rel|steps[2].expect[") }
        assertEquals(3, stepTwo.size, "steps[2] carries the three-element array this proof needs")
        assertEquals(
            stepTwo.size,
            stepTwo.toSet().size,
            "two elements of the SAME array collapsed onto one label",
        )
        val stepTwoDigests = stepTwo.map { ConformanceFixtures.blockDigest(sites.getValue(it)) }
        assertEquals(
            stepTwo.size,
            stepTwoDigests.toSet().size,
            "the three frames of steps[2] must be content-DISTINCT, or the digest dimension is " +
                "blind to losing one of them",
        )

        val allDigests = elementSites.map { ConformanceFixtures.blockDigest(sites.getValue(it)) }
        assertEquals(
            12,
            allDigests.toSet().size,
            "all 12 element blocks must be content-distinct, which is what makes the +12 sites / " +
                "+12 digests delta the two dimensions each measure independently",
        )
        assertTrue(
            sites.keys.contains("$rel|assertions"),
            "the fixture's own top-level `assertions` block must still be a site — the widening " +
                "adds element sites, it does not move the object rule",
        )
    }
}
