package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

    private fun strings(el: JsonElement): List<String> =
        el.jsonArray.map { it.jsonPrimitive.content }

    /** Parse a `spec.val` object of key -> canonical value. */
    private fun valSpec(fixture: JsonObject): Map<String, Int> =
        fixture.getValue("spec").jsonObject.getValue("val").jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.int }

    /**
     * The one `expected` key with nothing in lazily-kt to compare against.
     *
     * `assertEquals("eager", ...default_mode)` compared the fixture to a literal
     * in the runner, so the binding never entered the comparison and the arm
     * asserted nothing (#lzconsumednotasserted). lazily-kt exposes no
     * default-mode selector to assert instead: both builds are explicit calls
     * (`materializeAll` vs `getOrInsertWith`), so the key names which of them the
     * corpus calls the default rather than a value the binding can report. It is
     * declared as an excuse, and the two builds it names are asserted by
     * `eager_present` / `lazy_present_after_reads` below.
     */
    private fun excuseDefaultMode(a: AssertionKeys) {
        a.excuseKey(
            "default_mode",
            "lazily-kt exposes no default-mode selector — both builds are explicit calls; " +
                "eager_present / lazy_present_after_reads assert the two strategies themselves",
        )
    }

    @Test
    fun observationalTransparency() {
        val fixture = loadFixture("observational_transparency.json")
        val vals = valSpec(fixture)
        val expected = fixture.getValue("expected").jsonObject

        val ctx = Context()
        val lookup: (String) -> Int = { vals.getValue(it) }

        // eager: pre-mint the whole keyset.
        val eager = ComputedMap<String, Int>()
        eager.materializeAll(ctx, vals.keys) { lookup(it) }
        assertEquals(EntryKind.Computed, eager.entryKind)
        assertEquals(vals.size, eager.presentCount)

        // lazy: empty at build.
        val lazy = ComputedMap<String, Int>()
        assertEquals(0, lazy.presentCount)

        // Fresh lazy replay of the read sequence -> present set is exactly the reads.
        val ctx2 = Context()
        val lazy2 = ComputedMap<String, Int>()
        for (k in strArray(fixture, "reads")) lazy2.getOrInsertWith(ctx2, k) { lookup(it) }

        expected.consuming("materialization/observational_transparency.json expected") { a ->
            excuseDefaultMode(a)
            a.assertKeyWith("eager_present") { want ->
                assertEquals(strings(want).toSet(), eager.presentKeys().toSet(), "eager_present")
            }
            // observe_canonical / eager_lazy_observationally_equivalent.
            a.assertKeyWith("observe") { want ->
                for ((k, v) in want.jsonObject) {
                    assertEquals(v.jsonPrimitive.int, eager.get(ctx, k), "eager observe $k")
                    assertEquals(v.jsonPrimitive.int, lazy.getOrInsertWith(ctx, k) { lookup(it) }, "lazy observe $k")
                }
            }
            a.assertKeyWith("lazy_present_after_reads") { want ->
                assertEquals(strings(want).toSet(), lazy2.presentKeys().toSet(), "lazy_present_after_reads")
            }
        }
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
        val gotSizes = mutableListOf<Int>()
        for (k in strArray(fixture, "reads")) {
            lazy.getOrInsertWith(ctx, k) { lookup(it) }
            gotSizes.add(lazy.presentCount)
        }
        val lazyPresent = lazy.presentKeys().toSet()

        // The eager build this fixture's `eager_present` describes, replayed so
        // the key meets an observation instead of only bounding `lazyPresent`.
        val eagerCtx = Context()
        val eager = ComputedMap<String, Int>()
        eager.materializeAll(eagerCtx, vals.keys) { lookup(it) }

        expected.consuming("materialization/deferral_not_deallocation.json expected") { a ->
            excuseDefaultMode(a)
            a.assertKeyWith("present_after_each_read") { want ->
                assertEquals(
                    want.jsonArray.map { it.jsonPrimitive.int },
                    gotSizes,
                    "cumulative present-set sizes",
                )
            }
            a.assertKeyWith("lazy_present_after_reads") { want ->
                assertEquals(strings(want).toSet(), lazyPresent, "lazy_present_after_reads")
            }
            // `eager_present` was only ever used as an upper bound on the lazy
            // set, so the eager build itself never entered the comparison.
            a.assertKeyWith("eager_present") { want ->
                val eagerPresent = strings(want).toSet()
                assertEquals(eagerPresent, eager.presentKeys().toSet(), "eager_present")
                assertTrue(
                    eagerPresent.containsAll(lazyPresent),
                    "lazy present set must be a subset of eager present set",
                )
            }
            // Deferral is not deallocation: every key observes the same value
            // through the lazy map as through the eager one.
            a.assertKeyWith("observe") { want ->
                for ((k, v) in want.jsonObject) {
                    assertEquals(v.jsonPrimitive.int, eager.get(eagerCtx, k), "eager observe $k")
                    assertEquals(v.jsonPrimitive.int, lazy.getOrInsertWith(ctx, k) { lookup(it) }, "lazy observe $k")
                }
            }
        }
    }

    @Test
    fun entryKindOrthogonalToMode() = replayEntryKindFixture(::identityTag)

    /**
     * The same canonical replay, with each entry's `kind` tag rewritten to the v2
     * spelling on its way into the parser — the corpus emits `"cell"` / `"slot"`
     * today and will flip to `"source"` / `"computed"`. Substituting the tag at the
     * parse boundary is the forward-compatibility gate: if the runner only knew the
     * historical spellings, this replay would fail on an unknown entry kind, and
     * the flipped corpus would land red.
     */
    @Test
    fun entryKindOrthogonalToModeAcceptsV2KindTags() = replayEntryKindFixture(::v2Tag)

    private fun identityTag(tag: String): String = tag

    private fun v2Tag(tag: String): String = when (tag) {
        "cell" -> "source"
        "slot" -> "computed"
        else -> tag
    }

    /** [rewriteKindTag] maps the fixture's raw `kind` tag before it is parsed. */
    private fun replayEntryKindFixture(rewriteKindTag: (String) -> String) {
        val fixture = loadFixture("entry_kind_orthogonal_to_mode.json")
        val expected = fixture.getValue("expected").jsonObject

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
            // The corpus emits the historical `"cell"` / `"slot"` tags today and
            // will flip to the v2 `"source"` / `"computed"` spellings; both parse
            // to the same [EntryKind]. Anything else is still a hard error — the
            // replay must never silently default or skip an entry.
            val kind = rewriteKindTag(o.getValue("kind").jsonPrimitive.content)
            when (EntryKind.fromWire(kind)) {
                EntryKind.Source -> cellKeys.add(key)
                EntryKind.Computed -> slotKeys.add(key)
                null -> error("unknown entry kind $kind")
            }
        }
        assertTrue(cellKeys.isNotEmpty() && slotKeys.isNotEmpty(), "fixture must declare both entry kinds")
        val lookup: (String) -> Int = { vals.getValue(it) }

        val ctx = Context()

        // Eager build: every entry present (cells + slots).
        val eagerCells = SourceMap<String, Int>(ctx)
        for (k in cellKeys) eagerCells.insert(k, lookup(k))
        val eagerSlots = ComputedMap<String, Int>()
        eagerSlots.materializeAll(ctx, slotKeys) { lookup(it) }
        assertEquals(EntryKind.Source, eagerCells.entryKind)
        assertEquals(EntryKind.Computed, eagerSlots.entryKind)
        val eagerPresent = (eagerCells.presentKeys() + eagerSlots.presentKeys()).toSet()

        // Lazy build: cells present at build (always materialized), slots deferred.
        val lazyCtx = Context()
        val lazyCells = SourceMap<String, Int>(lazyCtx)
        for (k in cellKeys) lazyCells.insert(k, lookup(k))
        val lazySlots = ComputedMap<String, Int>()
        assertTrue(lazySlots.presentKeys().isEmpty(), "slots deferred at build")
        val lazyAtBuild = lazyCells.presentKeys().toSet()

        // Reads (slot pulls) grow only the slot present set.
        for (k in strArray(fixture, "reads")) {
            if (k in slotKeys) lazySlots.getOrInsertWith(lazyCtx, k) { lookup(it) }
        }
        val lazyAfter = (lazyCells.presentKeys() + lazySlots.presentKeys()).toSet()

        expected.consuming("materialization/entry_kind_orthogonal_to_mode.json expected") { a ->
            excuseDefaultMode(a)
            a.assertKeyWith("eager_present") { assertEquals(strings(it).toSet(), eagerPresent, "eager_present") }
            a.assertKeyWith("lazy_present_at_build") {
                assertEquals(strings(it).toSet(), lazyAtBuild, "lazy_present_at_build")
            }
            a.assertKeyWith("lazy_present_after_reads") {
                assertEquals(strings(it).toSet(), lazyAfter, "lazy_present_after_reads")
            }
            // Observational transparency across kinds.
            a.assertKeyWith("observe") { want ->
                for ((k, v) in want.jsonObject) {
                    val w = v.jsonPrimitive.int
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
    }
}
