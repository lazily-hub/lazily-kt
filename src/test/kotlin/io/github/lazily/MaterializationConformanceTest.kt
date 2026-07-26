package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-language conformance tests for `ComputedMap` materialization (`#reactivemap`),
 * driven by the canonical fixtures in `lazily-spec/conformance/materialization/`
 * (read only from the sibling checkout — there is no bundled fallback, because a
 * fallback is what makes spec drift invisible). These exercise the laws proved in `lazily-formal`'s
 * `Materialization` module against the [ComputedMap] derived-slot specialization (and,
 * for the mixed-kind fixture, the [SourceMap] input-cell specialization):
 *
 * - `observational_transparency.json` — eager (pre-mint loop) and lazy
 *   (`getOrInsertWith` mint-on-access) return identical values for every key;
 *   eager materializes all up front, lazy only the read keys; default is eager.
 * - `deferral_not_deallocation.json` — the present set only *grows* and is
 *   unchanged by a re-read; the lazy present set is a subset of the eager one.
 * - `entry_kind_orthogonal_to_mode.json` — input **cell** entries are materialized
 *   in every strategy; derived **slot** entries defer under lazy.
 *
 * Every fixture carries a materialization `"model"`; the harness dispatches on it.
 */
class MaterializationConformanceTest {
    private val json = Json

    /**
     * Model tags this harness replays. The canonical corpus emits the v2 spelling
     * `"ComputedMap"`, but the pre-v2 `"SlotMap"` is *deprecated, not removed*, so the
     * dispatch accepts both spellings of the same model. Accepting both keeps this
     * replay green against an older pinned `lazily-spec` checkout as well as the
     * renamed one; it stays a real gate because any *other* model tag still fails.
     */
    private val materializationModels = setOf("ComputedMap", "SlotMap")

    private fun loadFixture(name: String): JsonObject {
        val text = ConformanceFixtures.read("materialization/$name")
        val obj = json.parseToJsonElement(text).jsonObject
        val model = obj.getValue("model").jsonPrimitive.content
        assertTrue(
            model in materializationModels,
            "[$name] this harness replays the ComputedMap materialization model " +
                "(accepted tags: $materializationModels), got \"$model\"",
        )
        return obj
    }

    private fun strArray(obj: JsonObject, key: String): List<String> =
        obj.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    /** Parse a `spec.val` object of key -> canonical value. */
    private fun valSpec(fixture: JsonObject): Map<String, Int> =
        fixture.getValue("spec").jsonObject.getValue("val").jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.int }

    @Test
    fun observationalTransparency() {
        val fixture = loadFixture("observational_transparency.json")
        val vals = valSpec(fixture)
        val expected = fixture.getValue("expected").jsonObject
        assertEquals("eager", expected.getValue("default_mode").jsonPrimitive.content, "default is eager")

        val ctx = Context()
        val lookup: (String) -> Int = { vals.getValue(it) }

        // eager: pre-mint the whole keyset.
        val eager = ComputedMap<String, Int>()
        eager.materializeAll(ctx, vals.keys) { lookup(it) }
        assertEquals(EntryKind.Slot, eager.entryKind)
        assertEquals(vals.size, eager.presentCount)
        assertEquals(strArray(expected, "eager_present").toSet(), eager.presentKeys().toSet())

        // lazy: empty at build.
        val lazy = ComputedMap<String, Int>()
        assertEquals(0, lazy.presentCount)

        // observe_canonical / eager_lazy_observationally_equivalent.
        for ((k, want) in expected.getValue("observe").jsonObject) {
            assertEquals(want.jsonPrimitive.int, eager.get(ctx, k), "eager observe $k")
            assertEquals(want.jsonPrimitive.int, lazy.getOrInsertWith(ctx, k) { lookup(it) }, "lazy observe $k")
        }

        // Fresh lazy replay of the read sequence -> present set is exactly the reads.
        val ctx2 = Context()
        val lazy2 = ComputedMap<String, Int>()
        for (k in strArray(fixture, "reads")) lazy2.getOrInsertWith(ctx2, k) { lookup(it) }
        assertEquals(strArray(expected, "lazy_present_after_reads").toSet(), lazy2.presentKeys().toSet())
    }

    @Test
    fun deferralNotDeallocation() {
        val fixture = loadFixture("deferral_not_deallocation.json")
        val vals = valSpec(fixture)
        val expected = fixture.getValue("expected").jsonObject
        val lookup: (String) -> Int = { vals.getValue(it) }

        val ctx = Context()
        val lazy = ComputedMap<String, Int>()

        // present_after_each_read: monotone, unchanged by a re-read.
        val wantSizes = expected.getValue("present_after_each_read").jsonArray.map { it.jsonPrimitive.int }
        val gotSizes = mutableListOf<Int>()
        for (k in strArray(fixture, "reads")) {
            lazy.getOrInsertWith(ctx, k) { lookup(it) }
            gotSizes.add(lazy.presentCount)
        }
        assertEquals(wantSizes, gotSizes, "cumulative present-set sizes")

        val lazyPresent = lazy.presentKeys().toSet()
        assertEquals(strArray(expected, "lazy_present_after_reads").toSet(), lazyPresent)
        val eagerPresent = strArray(expected, "eager_present").toSet()
        assertTrue(eagerPresent.containsAll(lazyPresent), "lazy present set must be a subset of eager present set")
    }

    @Test
    fun entryKindOrthogonalToMode() {
        val fixture = loadFixture("entry_kind_orthogonal_to_mode.json")
        val expected = fixture.getValue("expected").jsonObject
        assertEquals("eager", expected.getValue("default_mode").jsonPrimitive.content)

        // Split declared entries by kind. A single ReactiveMap fixes one handle
        // kind, so a mixed-kind fixture is modelled by a SourceMap over the cell
        // entries and a ComputedMap over the slot entries, sharing one key space.
        val entries = fixture.getValue("spec").jsonObject.getValue("entries").jsonObject
        val cellKeys = mutableListOf<String>()
        val slotKeys = mutableListOf<String>()
        val vals = HashMap<String, Int>()
        for ((key, entry) in entries) {
            val o = entry.jsonObject
            vals[key] = o.getValue("val").jsonPrimitive.int
            when (val kind = o.getValue("kind").jsonPrimitive.content) {
                "cell" -> cellKeys.add(key)
                "slot" -> slotKeys.add(key)
                else -> error("unknown entry kind $kind")
            }
        }
        val lookup: (String) -> Int = { vals.getValue(it) }

        val ctx = Context()

        // Eager build: every entry present (cells + slots).
        val eagerCells = SourceMap<String, Int>(ctx)
        for (k in cellKeys) eagerCells.insert(k, lookup(k))
        val eagerSlots = ComputedMap<String, Int>()
        eagerSlots.materializeAll(ctx, slotKeys) { lookup(it) }
        assertEquals(EntryKind.Cell, eagerCells.entryKind)
        assertEquals(EntryKind.Slot, eagerSlots.entryKind)
        val eagerPresent = (eagerCells.presentKeys() + eagerSlots.presentKeys()).toSet()
        assertEquals(strArray(expected, "eager_present").toSet(), eagerPresent)

        // Lazy build: cells present at build (always materialized), slots deferred.
        val lazyCtx = Context()
        val lazyCells = SourceMap<String, Int>(lazyCtx)
        for (k in cellKeys) lazyCells.insert(k, lookup(k))
        val lazySlots = ComputedMap<String, Int>()
        assertTrue(lazySlots.presentKeys().isEmpty(), "slots deferred at build")
        assertEquals(strArray(expected, "lazy_present_at_build").toSet(), lazyCells.presentKeys().toSet())

        // Reads (slot pulls) grow only the slot present set.
        for (k in strArray(fixture, "reads")) {
            if (k in slotKeys) lazySlots.getOrInsertWith(lazyCtx, k) { lookup(it) }
        }
        val lazyAfter = (lazyCells.presentKeys() + lazySlots.presentKeys()).toSet()
        assertEquals(strArray(expected, "lazy_present_after_reads").toSet(), lazyAfter)

        // Observational transparency across kinds.
        for ((k, want) in expected.getValue("observe").jsonObject) {
            val w = want.jsonPrimitive.int
            if (k in cellKeys) {
                assertEquals(w, eagerCells.get(k), "eager cell observe $k")
                assertEquals(w, lazyCells.get(k), "lazy cell observe $k")
            } else {
                assertEquals(w, eagerSlots.get(ctx, k), "eager slot observe $k")
                assertEquals(w, lazySlots.getOrInsertWith(lazyCtx, k) { lookup(it) }, "lazy slot observe $k")
            }
        }
    }
}
