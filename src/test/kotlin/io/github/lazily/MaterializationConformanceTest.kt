package io.github.lazily

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

    private fun strArray(
        obj: JsonObject,
        key: String,
    ): List<String> = obj.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun strings(el: JsonElement): List<String> = el.jsonArray.map { it.jsonPrimitive.content }

    /** Parse a `spec.val` object of key -> canonical value. */
    private fun valSpec(fixture: JsonObject): Map<String, Int> =
        fixture
            .getValue("spec")
            .jsonObject
            .getValue("val")
            .jsonObject
            .mapValues { (_, v) -> v.jsonPrimitive.int }

    /**
     * `default_mode_eager` — asserted, not excused (`#lzdefaultmodeuniform`).
     *
     * lazily-kt has no mode flag to read back: eager vs lazy is which call the
     * caller makes (`materializeAll` pre-mint vs `getOrInsertWith` mint-on-access).
     * That is not a reason to skip the key. The fixture's value **selects the
     * build**, and the behavioural fact asserted is the clause itself — *a map
     * built the way the fixture names its default is fully materialized at build
     * time*. So the string is read, dispatched on, the named build is constructed
     * fresh, and its present-at-build set is compared against every key the
     * fixture declares.
     *
     * Both directions therefore bite. Flipping the corpus to `"lazy"` builds the
     * deferring map, whose build-time present set is missing the derived keys, and
     * the arm fails. Breaking `materializeAll` so the eager build materializes
     * nothing (or drops a key) also fails it — the direction the earlier excuse
     * could not see. An unknown string is a hard error, never a skip.
     *
     * The two rejected shapes: `assertEquals("eager", …)` compares the fixture to
     * a literal in the runner, so the binding never enters the comparison
     * (`#lzconsumednotasserted`); and "lazily-kt exposes no default-mode selector"
     * is disproved by lazily-cpp and lazily-cs, which have no mode flag either and
     * both assert the behaviour.
     *
     * [declaredKeys] is every key the fixture declares; [buildEager] / [buildLazy]
     * each construct that strategy's map from scratch (fresh [Context], untouched
     * by the reads other arms perform) and return its present-at-build key set.
     */
    private fun assertDefaultModeMaterializesAtBuild(
        a: AssertionKeys,
        declaredKeys: Set<String>,
        buildEager: () -> Set<String>,
        buildLazy: () -> Set<String>,
    ) {
        a.assertKeyWith("default_mode") { want ->
            val mode = want.jsonPrimitive.content
            val presentAtBuild =
                when (mode) {
                    "eager" -> buildEager()
                    "lazy" -> buildLazy()
                    else -> error("unknown default_mode \"$mode\"")
                }
            assertEquals(
                declaredKeys,
                presentAtBuild,
                "a map built the fixture's default way ($mode) is materialized at build " +
                    "(default_mode_eager)",
            )
        }
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
            assertDefaultModeMaterializesAtBuild(
                a,
                declaredKeys = vals.keys,
                buildEager = {
                    val c = Context()
                    val m = ComputedMap<String, Int>()
                    m.materializeAll(c, vals.keys) { lookup(it) }
                    m.presentKeys().toSet()
                },
                buildLazy = { ComputedMap<String, Int>().presentKeys().toSet() },
            )
            a.assertKeyWith("eager_present") { want ->
                assertEquals(strings(want).toSet(), eager.presentKeys().toSet(), "eager_present")
            }
            // observe_canonical / eager_lazy_observationally_equivalent.
            // Descended, so the child tracker owns the key set: an entry added
            // to `observe` upstream is an unconsumed key rather than a value
            // compared by nothing (#lzsubblockkeyset).
            a.sub("observe") { o ->
                for (k in o.keys) {
                    o.assertKeyWith(k) { want ->
                        val w = want.jsonPrimitive.int
                        assertEquals(w, eager.get(ctx, k), "eager observe $k")
                        assertEquals(w, lazy.getOrInsertWith(ctx, k) { lookup(it) }, "lazy observe $k")
                    }
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
            assertDefaultModeMaterializesAtBuild(
                a,
                declaredKeys = vals.keys,
                buildEager = {
                    val c = Context()
                    val m = ComputedMap<String, Int>()
                    m.materializeAll(c, vals.keys) { lookup(it) }
                    m.presentKeys().toSet()
                },
                buildLazy = { ComputedMap<String, Int>().presentKeys().toSet() },
            )
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
            a.sub("observe") { o ->
                for (k in o.keys) {
                    o.assertKeyWith(k) { want ->
                        val w = want.jsonPrimitive.int
                        assertEquals(w, eager.get(eagerCtx, k), "eager observe $k")
                        assertEquals(w, lazy.getOrInsertWith(ctx, k) { lookup(it) }, "lazy observe $k")
                    }
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

    private fun v2Tag(tag: String): String =
        when (tag) {
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
        val entries =
            fixture
                .getValue("spec")
                .jsonObject
                .getValue("entries")
                .jsonObject
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
            // Same shape with the entry-kind split: source entries are present at
            // build under EVERY strategy, computed entries only under eager. So the
            // eager build holds all four declared entries and the lazy build only
            // the two sources — which is what makes the corpus flip fail here too.
            assertDefaultModeMaterializesAtBuild(
                a,
                declaredKeys = (cellKeys + slotKeys).toSet(),
                buildEager = {
                    val c = Context()
                    val cells = SourceMap<String, Int>(c)
                    for (k in cellKeys) cells.insert(k, lookup(k))
                    val slots = ComputedMap<String, Int>()
                    slots.materializeAll(c, slotKeys) { lookup(it) }
                    (cells.presentKeys() + slots.presentKeys()).toSet()
                },
                buildLazy = {
                    val c = Context()
                    val cells = SourceMap<String, Int>(c)
                    for (k in cellKeys) cells.insert(k, lookup(k))
                    (cells.presentKeys() + ComputedMap<String, Int>().presentKeys()).toSet()
                },
            )
            a.assertKeyWith("eager_present") { assertEquals(strings(it).toSet(), eagerPresent, "eager_present") }
            a.assertKeyWith("lazy_present_at_build") {
                assertEquals(strings(it).toSet(), lazyAtBuild, "lazy_present_at_build")
            }
            a.assertKeyWith("lazy_present_after_reads") {
                assertEquals(strings(it).toSet(), lazyAfter, "lazy_present_after_reads")
            }
            // Observational transparency across kinds.
            a.sub("observe") { o ->
                for (k in o.keys) {
                    o.assertKeyWith(k) { want ->
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
        }
    }
}
