package io.github.lazily

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Runtime per-scenario replay ledger (`#lzscenariocoverage`) — rung 4 of the
 * conformance-evidence ladder.
 *
 * | rung | guard | proves |
 * |---|---|---|
 * | 1 | [ConformanceFixtures]' runtime manifest | the fixture was **opened** |
 * | 2 | [AssertionKeys]' unread check | every key of a block you reached was **read** |
 * | 3 | [AssertionKeys]' unasserted check | every read key reached a **comparison** |
 * | 4 | **this object** | every **scenario** in the fixture was **replayed** |
 *
 * Rungs 1-3 are blind to a half-replayed fixture *by construction*. Rung 1 asks
 * only whether the file was opened, and one scenario is enough for that. Rungs 2
 * and 3 only ever see the assertion blocks a runner actually reaches, so a
 * scenario nobody replays contributes no unread key and no unasserted key — it
 * is invisible to a guard that only inspects the scenarios you ran. A fixture
 * carrying four scenarios of which a runner drives three is green, counted as
 * covered, and silently proves three quarters of what it claims.
 *
 * So this ledger records what the runner **actually replayed**, exactly as the
 * fixture manifest records what it actually opened. A hand-authored list of
 * "scenarios this binding covers" is the thing being guarded against: it is a
 * claim, and a claim rots.
 *
 * Recording happens on the first read of a scenario's PAYLOAD — never at the
 * yield (`#lzscenariobodyskip`). Yielding is not replaying: a sequence cannot
 * tell a loop body that ran from one that `continue`d, so the yield-time
 * recording this replaced booked exactly the skip rung 4 exists to catch.
 * Prefer [of]; the booking rides on the scenario it hands over, so a runner
 * physically cannot replay one without being counted, and cannot be counted for
 * one it walked past:
 *
 * ```
 * for (sc in ConformanceScenarios.of("crdt-tree/algebra.json", fx)) replay(sc)
 * ```
 *
 * Where a runner reaches for one scenario by name instead of iterating, use
 * [pick] — its LOOKUP does not book either, because matching on a name walks
 * past every scenario ahead of the match. [record] is the last resort for a
 * runner that must iterate by hand; call it at the top of the loop body,
 * **after** any `continue`, so a skipped scenario does not record itself.
 *
 * Verification lives in `scripts/check-conformance-coverage.sh`, alongside the
 * `KNOWN_UNCOVERED` fixture allowlist, so there is one place to read what this
 * binding does not prove. It compares this ledger against the ids the fixtures
 * carry on disk, in both directions.
 */
object ConformanceScenarios {
    /** Where the positive "these scenarios actually replayed" ledger is written. */
    val ledgerPath: Path =
        Path.of(
            System.getenv("LAZILY_CONFORMANCE_SCENARIO_LEDGER")
                ?: "build/conformance-scenarios-replayed.txt",
        )

    /** How a scenario's id was resolved — reported so a positional fallback is visible. */
    enum class IdSource { ID, NAME, POSITIONAL }

    data class ScenarioId(
        val value: String,
        val source: IdSource,
    )

    private val replayed = ConcurrentSkipListSet<String>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { writeLedger() })
    }

    /**
     * Resolve a scenario's id in the order every binding shares: `id`, else
     * `name`, else the 0-based positional index spelled `#<n>`.
     *
     * The corpus is not uniform — 28 fixtures identify a scenario by `name`, the
     * three `stdlib` ones by `id`, and `collections/mergecell_algebra.json`
     * carries no identifier at all (its scenarios differ only by `policy`). The
     * positional fallback exists so this guard is not blocked on a shared-corpus
     * edit; it is [IdSource.POSITIONAL]-tagged and reported by the guard rather
     * than silently accepted, because that visibility is what makes the corpus
     * gap fixable upstream later.
     */
    fun idOf(
        scenario: JsonObject,
        index: Int,
    ): ScenarioId {
        (scenario["id"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return ScenarioId(it, IdSource.ID) }
        (scenario["name"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return ScenarioId(it, IdSource.NAME) }
        return ScenarioId("#$index", IdSource.POSITIONAL)
    }

    /**
     * Keys that IDENTIFY or narrate a scenario rather than drive one
     * (`#lzscenariobodyskip`).
     *
     * Reading only these is looking at the LABEL, not replaying: a dispatch chain
     * that reads `name`, matches no arm and falls through has replayed nothing,
     * and a by-name lookup walks past every scenario ahead of its match. Booking
     * on those reads is what let a skipped body book itself. No scenario in the
     * corpus carries only these, so every one is reachable through some payload
     * key.
     */
    val LABEL_KEYS: Set<String> =
        setOf("comment", "description", "id", "label", "name", "note", "notes", "reason", "why")

    /**
     * A scenario's own map, booking the replay on the first read of its PAYLOAD.
     *
     * `JsonObject` delegates every `Map` member to the map it is constructed
     * with, so wrapping the map — rather than the element — keeps every runner's
     * `scenario["steps"]`, `scenario.getValue("expect")` and `scenario.jsonObject`
     * working unchanged while the booking rides on the read.
     *
     * `containsKey` deliberately does NOT book: membership is how a dispatch
     * chain probes a shape it may not handle, and probing is not replaying.
     */
    private class BookingScenario(
        private val content: Map<String, JsonElement>,
        private val book: () -> Unit,
    ) : Map<String, JsonElement> {
        override val size: Int get() = content.size

        override fun isEmpty(): Boolean = content.isEmpty()

        override fun containsKey(key: String): Boolean = content.containsKey(key)

        override fun containsValue(value: JsonElement): Boolean = content.containsValue(value)

        override fun get(key: String): JsonElement? {
            if (key !in LABEL_KEYS) book()
            return content[key]
        }

        override val keys: Set<String> get() = content.keys.also { book() }

        override val values: Collection<JsonElement> get() = content.values.also { book() }

        override val entries: Set<Map.Entry<String, JsonElement>>
            get() = content.entries.also { book() }
    }

    private fun view(
        fixture: String,
        scenario: JsonObject,
        index: Int,
    ): JsonObject = JsonObject(BookingScenario(scenario) { record(fixture, scenario, index) })

    /**
     * Iterate [fx]'s `scenarios`, each booked when its PAYLOAD is first read.
     *
     * Yielding is not replaying (`#lzscenariobodyskip`). This used to record at
     * the yield, which cannot tell a loop body that ran from one that `continue`d
     * — a sequence sees the same thing either way — so a skipped scenario booked
     * itself and rung 4 stayed silent about the very defect it exists for.
     * lazily-py proved that against the contract's own probe; this is the port of
     * its fix. Booking now rides on the scenario handed to the loop, so a
     * `continue` past the payload leaves it unbooked and a `break` leaves the rest
     * unbooked too.
     */
    fun of(
        fixture: String,
        fx: JsonObject,
    ): Sequence<JsonObject> = indexed(fixture, fx).map { it.value }

    /** [of], keeping the positional index for runners that label by it. */
    fun indexed(
        fixture: String,
        fx: JsonObject,
    ): Sequence<IndexedValue<JsonObject>> {
        val scenarios =
            fx["scenarios"] as? JsonArray
                ?: error("$fixture: fixture carries no `scenarios` array")
        return scenarios.asSequence().mapIndexed { i, el ->
            IndexedValue(i, view(fixture, el.jsonObject, i))
        }
    }

    /**
     * Return the single scenario of [fx] whose resolved id is [id].
     *
     * For runners that reach for one scenario by name rather than iterating —
     * the shape where a skipped scenario is easiest to not notice, because
     * nothing enumerates the ones you did not name.
     *
     * The LOOKUP does not book (`#lzscenariobodyskip`): matching on the id walks
     * past every scenario ahead of it, and selecting a scenario is not replaying
     * it. The returned view books when the caller reads its payload.
     */
    fun pick(
        fixture: String,
        fx: JsonObject,
        id: String,
    ): JsonObject {
        val scenarios =
            fx["scenarios"] as? JsonArray
                ?: error("$fixture: fixture carries no `scenarios` array")
        scenarios.forEachIndexed { i, el ->
            val sc = el.jsonObject
            if (idOf(sc, i).value == id) {
                return view(fixture, sc, i)
            }
        }
        error(
            "$fixture: no scenario resolves to id '$id'. Present: " +
                scenarios.mapIndexed { i, el -> idOf(el.jsonObject, i).value },
        )
    }

    /** Record [scenario] at [index] of [fixture] as replayed; returns its resolved id. */
    fun record(
        fixture: String,
        scenario: JsonObject,
        index: Int,
    ): String {
        val sid = idOf(scenario, index)
        replayed.add("$fixture\t${sid.value}\t${sid.source.name.lowercase()}")
        return sid.value
    }

    /** Scenario ledger entries recorded so far in this JVM, as `fixture\tid\tsource`. */
    fun ledger(): Set<String> = replayed.toSortedSet()

    @Synchronized
    fun writeLedger() {
        if (replayed.isEmpty()) return
        runCatching {
            ledgerPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(ledgerPath, replayed.toSortedSet().joinToString("\n", postfix = "\n"))
        }
    }
}
