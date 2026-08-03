package io.github.lazily

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.assertEquals

/**
 * Fixture-scoped ledger of prose-key discharges (`#lzprosekeyconvention`).
 *
 * A **prose key** is a key of a fixture's top-level `assertions` block whose
 * value is an English paragraph: it states an obligation and carries no value a
 * runner can compare against observed behaviour. The CORPUS declares which keys
 * those are, in `assertions.prose`; a binding never decides for itself.
 *
 * A prose key is **discharged**, never asserted and never excused. Discharging
 * it names the executable assertion keys that carry its obligation, and this
 * ledger verifies the naming — which is the whole point. "`epoch_disambiguation`
 * is discharged by `frame_epoch` and `blob_epoch`" is a falsifiable claim about
 * the run; "`epoch_disambiguation` is prose" is not, and an unfalsifiable excuse
 * is indistinguishable from the undocumented default this convention removes.
 *
 * The ledger is **fixture-scoped**, not block-scoped, because an obligation
 * stated in `assertions` is routinely carried by a per-scenario `expect` key:
 * `epoch_disambiguation` is discharged by `expect.frame_epoch` and
 * `expect.blob_epoch`, asserted long after the `assertions` block is finished.
 * So a named key is matched by NAME, in any block of that fixture, and
 * verification runs when the fixture's replay is done:
 *
 * ```
 * proseScope(path).use {
 *     meta.proseKey("epoch_disambiguation", listOf("frame_epoch", "blob_epoch"))
 *     …replay every scenario…
 *     verifyProse(path)
 * }
 * ```
 *
 * [proseScope] is what arms it. A run that discharges and never verifies is as
 * bad as an unconsumed key — the claim was made and nothing checked it — so
 * closing a scope with claims still pending fails.
 */
object ProseLedger {
    /** One pending claim: "[key] is discharged by [dischargedBy]". */
    data class Claim(
        val where: String,
        val key: String,
        val dischargedBy: List<String>,
    )

    private class State {
        /** Every key name ASSERTED anywhere in this fixture's run. */
        val asserted = mutableSetOf<String>()

        /** Discharge claims made but not yet verified. */
        val claims = mutableListOf<Claim>()
    }

    private val states = mutableMapOf<String, State>()

    @Synchronized
    private fun state(fixture: String): State = states.getOrPut(fixture) { State() }

    /** Book that [key] reached a comparison against [fixture]'s own value. */
    @Synchronized
    fun recordAsserted(
        fixture: String,
        key: String,
    ) {
        state(fixture).asserted += key
    }

    /** Record a discharge claim, to be verified by [verifyProse]. */
    @Synchronized
    fun claim(
        fixture: String,
        claim: Claim,
    ) {
        state(fixture).claims += claim
    }

    /** Keys this fixture's run has asserted so far. */
    @Synchronized
    fun assertedKeys(fixture: String): Set<String> = state(fixture).asserted.toSet()

    /** Claims awaiting verification. */
    @Synchronized
    fun pending(fixture: String): List<Claim> = state(fixture).claims.toList()

    @Synchronized
    fun clearPending(fixture: String) {
        state(fixture).claims.clear()
    }

    /** Drop everything known about [fixture]. For the tracker's own tests. */
    @Synchronized
    fun reset(fixture: String) {
        states.remove(fixture)
    }
}

/**
 * The seam that arms [verifyProse]: a scope closed with unverified discharge
 * claims fails.
 *
 * Wrap the whole replay of one fixture. `use` runs [ProseScope.close] on the way
 * out however the body leaves, so a runner that discharges a prose key and then
 * forgets to verify is reported rather than silently trusted.
 */
fun proseScope(fixture: String): ProseScope = ProseScope(fixture)

class ProseScope(
    private val fixture: String,
) : AutoCloseable {
    override fun close() {
        val pending = ProseLedger.pending(fixture)
        ProseLedger.clearPending(fixture)
        check(pending.isEmpty()) {
            "$fixture: verifyProse(\"$fixture\") was never called, so ${pending.size} discharge " +
                "claim(s) ${pending.map { it.key }} went unverified. A discharge is a claim about " +
                "what the run asserted; a claim nothing checks is exactly the unfalsifiable excuse " +
                "this convention replaces (#lzprosekeyconvention)"
        }
    }
}

/**
 * Verify every discharge claim made for [fixture] against what its run actually
 * asserted — rule 6, the rule the whole convention exists for.
 *
 * Call at the END of the fixture's replay: a named key is matched by name in any
 * block of the fixture, so a claim made against the `assertions` block is only
 * decidable once the per-scenario `expect` blocks have run.
 */
fun verifyProse(fixture: String) {
    val claims = ProseLedger.pending(fixture)
    val asserted = ProseLedger.assertedKeys(fixture)
    val broken =
        claims.mapNotNull { claim ->
            val missing = claim.dischargedBy.filter { it !in asserted }
            if (missing.isEmpty()) null else "${claim.key} names $missing (at ${claim.where})"
        }
    ProseLedger.clearPending(fixture)
    check(broken.isEmpty()) {
        "$fixture: discharge(s) $broken name assertion key(s) this fixture's run never ASSERTED. " +
            "A discharge names the executable keys that carry the paragraph's obligation, and the " +
            "naming is checked so the excuse becomes falsifiable — a key that was read, excused, " +
            "renamed, or never reached carries nothing. Name keys the run really asserts, or fix " +
            "the assertion that stopped running (#lzprosekeyconvention). Asserted here: " +
            "${asserted.sorted()}"
    }
}

/**
 * Consumption- and assertion-tracking view over a conformance fixture's
 * assertion block — `assertions`, `expected`, `expect`
 * (`#lzassertunknownkeys`, `#lzconsumednotasserted`).
 *
 * Three rungs of the same ladder, each one level below the last:
 *
 * | rung | guard | proves |
 * |---|---|---|
 * | 1 | the runtime fixture manifest (`scripts/check-conformance-coverage.sh`) | the fixture was **opened** |
 * | 2 | [requireAllSatisfied]'s unread check (`#lzassertunknownkeys`) | every key was **read** |
 * | 3 | [requireAllSatisfied]'s unasserted check (`#lzconsumednotasserted`) | every key reached a **comparison against the fixture's own value** |
 *
 * Rung 2 is what this class originally did, and it is fail-open one level down.
 * A runner can read a key and then do nothing with it — a named skip inside a
 * consuming loop, a value bound and never compared, or a comparison against a
 * hardcoded literal instead of the fixture's value. The tracker sees the read
 * and goes green while editing the fixture changes nothing.
 *
 * So the reads below ([get], [has], [string], [long], [int], [boolean], [obj],
 * [array], [strings], [withPrefix]) mark a key **consumed** and nothing more. A
 * key becomes **asserted** only by going through one of the assertion entry
 * points ([assertLong], [assertInt], [assertBoolean], [assertString],
 * [assertStrings], [assertKeyWith]), each of which hands the fixture's own value
 * to the comparison. An arm that compares against a literal therefore never
 * marks its key, and [requireAllSatisfied] names it.
 *
 * Where a key genuinely cannot be asserted at a call site, the runner says so
 * out loud with [excuseKey] and a reason. Excuses are checked in **both
 * directions**, exactly as `KNOWN_UNCOVERED` is: excusing a key the same run
 * also asserts, or excusing a key the fixture does not carry, is a failure,
 * because the excuse has gone stale and is now hiding nothing.
 *
 * Usage:
 * ```
 * assertions.consuming("$fixture assertions") { a ->
 *     a.assertLong("epoch") { snapshot.epoch }
 *     a.assertKeyWith("type_tags") { want ->
 *         assertEquals(want.jsonArray.map { it.jsonPrimitive.content }.toSet(), actualTags)
 *     }
 *     a.excuseKey("seed", "drives the replay; it is an input, not an observable")
 * }
 * ```
 *
 * A fourth kind of key answers to none of the three rungs. A **prose key** —
 * declared by the corpus in `assertions.prose` — carries an English paragraph,
 * so there is nothing to compare and an excuse would be unfalsifiable. It is
 * [discharged][proseKey] instead, by naming the executable keys that carry its
 * obligation; [ProseLedger] then checks the naming against what the fixture's
 * run really asserted (`#lzprosekeyconvention`).
 */
class AssertionKeys(
    private val where: String,
    private val obj: JsonObject,
    /**
     * The fixture this block belongs to, for the fixture-scoped
     * [prose ledger][ProseLedger]. Runners spell [where] as `"$path $scenario"`,
     * so the default recovers the path.
     */
    private val fixture: String = where.substringBefore(' '),
) {
    private val consumed = mutableSetOf<String>()
    private val asserted = mutableSetOf<String>()
    private val excused = mutableMapOf<String, String>()
    private val discharged = mutableSetOf<String>()

    /**
     * The keys THIS block declares to be prose, read straight off the object so
     * the read does not consume `prose` — that key is consumed and asserted by
     * the discharged-set comparison in [requireAllSatisfied], and nowhere else.
     */
    init {
        // Rung 0: book this block as BOUND if it is a fixture's top-level
        // `assertions` block. Everything below is scoped to a block a runner
        // opened, so an `assertions` block nobody binds is silent — no unread
        // key, no unasserted key, no discharge (`#lznullformblind`).
        ConformanceFixtures.noteBound(obj)
    }

    private val declaredProse: Set<String> =
        when (val declaration = obj["prose"]) {
            null -> emptySet()
            is JsonArray -> declaration.map { it.jsonPrimitive.content }.toSet()
            else -> error("$where: `assertions.prose` must be an array of sibling key names, got $declaration")
        }

    /** Every key the fixture actually carries. */
    val keys: Set<String> get() = obj.keys

    // -- reads: they mark a key consumed, never asserted ---------------------

    /** Mark [key] consumed and return its raw element (null when absent). */
    operator fun get(key: String): JsonElement? {
        consumed += key
        return obj[key]
    }

    /** Mark [key] consumed and report whether the fixture carries it. */
    fun has(key: String): Boolean {
        consumed += key
        return obj.containsKey(key)
    }

    fun string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun long(key: String): Long? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.long

    fun int(key: String): Int? = long(key)?.toInt()

    /** Booleans, tolerating the `"true"`/`"false"` string spelling the corpus also uses. */
    fun boolean(key: String): Boolean? =
        when (val e = this[key]) {
            null -> null
            is JsonPrimitive ->
                e.booleanOrNull ?: when (e.contentOrNull) {
                    "true" -> true
                    "false" -> false
                    else -> error("$where: assertion key '$key' is not a boolean: $e")
                }
            else -> error("$where: assertion key '$key' is not a boolean: $e")
        }

    fun obj(key: String): JsonObject? = (this[key] as? JsonObject)

    fun array(key: String): JsonArray? = (this[key] as? JsonArray)

    fun strings(key: String): List<String>? = array(key)?.map { it.jsonPrimitive.content }

    /**
     * Consume every key starting with [prefix] and return the matches.
     *
     * For the corpus's parameterised spellings such as `resync_after_epoch_10`,
     * where the suffix is data rather than a distinct assertion. The matches are
     * reads like any other: each still has to reach an assertion entry point.
     */
    fun withPrefix(prefix: String): List<Pair<String, JsonElement>> =
        obj.entries
            .filter { it.key.startsWith(prefix) }
            .onEach { consumed += it.key }
            .map { it.key to it.value }

    // -- assertion entry points ---------------------------------------------

    /**
     * Refuse to assert or excuse a key the corpus declared prose — rules 1 and 2
     * of `#lzprosekeyconvention`.
     *
     * Called BEFORE the read, because reading a paragraph through a typed entry
     * point throws a parse error that says nothing about why the call is wrong.
     */
    private fun guardNotProse(
        key: String,
        verb: String,
    ) {
        check(key !in declaredProse) {
            "$where: '$key' is declared prose by the corpus (`assertions.prose`), so $verb it is a " +
                "failure. Comparing an English paragraph — or a tally derived from one — pins " +
                "WORDING, not behaviour: a copy-edit reddens the run and a library regression does " +
                "not; and an excuse for it is unfalsifiable. Discharge it instead with " +
                "proseKey(\"$key\", listOf(…)), naming the executable keys that carry its " +
                "obligation (#lzprosekeyconvention)"
        }
    }

    /** Book [key] as having reached a comparison against the fixture's own value. */
    private fun markAsserted(key: String) {
        asserted += key
        ProseLedger.recordAsserted(fixture, key)
    }

    /**
     * Discharge the prose [key] by naming the executable assertion keys that
     * carry its obligation (`#lzprosekeyconvention`).
     *
     * Not an assertion and not an excuse: the paragraph states an obligation
     * some OTHER key proves, so the runner says which, and [verifyProse] checks
     * that the fixture's run really asserted each one. Fails here when the key
     * is not declared prose (rule 3), when nothing is named (rule 5), or when a
     * named key is itself prose (rule 7); [verifyProse] covers rule 6 and
     * [requireAllSatisfied] covers rule 4.
     */
    fun proseKey(
        key: String,
        dischargedBy: List<String>,
    ) {
        check(key in declaredProse) {
            "$where: '$key' is not listed in this block's `assertions.prose`, so it is not the " +
                "corpus's to call prose — a binding does not decide that for itself. Assert it, or " +
                "get the corpus to declare it (#lzprosekeyconvention)"
        }
        check(dischargedBy.isNotEmpty()) {
            "$where: proseKey('$key') names no keys. A discharge that names nothing is the " +
                "free-text excuse this convention replaces, spelled differently " +
                "(#lzprosekeyconvention)"
        }
        val namedProse = dischargedBy.filter { it in declaredProse }.sorted()
        check(namedProse.isEmpty()) {
            "$where: proseKey('$key') is discharged by $namedProse, which are themselves prose. " +
                "A paragraph cannot discharge a paragraph — name an EXECUTABLE key the run " +
                "asserts (#lzprosekeyconvention)"
        }
        consumed += key
        check(discharged.add(key)) { "$where: '$key' is discharged twice" }
        ProseLedger.claim(fixture, ProseLedger.Claim(where, key, dischargedBy))
    }

    /**
     * Assert [actual] against the fixture's `Long` value for [key].
     *
     * [actual] is by-name because the key is optional and the observable is
     * often unreachable when the fixture does not carry it.
     */
    fun assertLong(
        key: String,
        actual: () -> Long,
    ) {
        guardNotProse(key, "asserting")
        val want = long(key) ?: return
        markAsserted(key)
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's `Int` value for [key]. */
    fun assertInt(
        key: String,
        actual: () -> Int,
    ) {
        guardNotProse(key, "asserting")
        val want = int(key) ?: return
        markAsserted(key)
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's boolean value for [key]. */
    fun assertBoolean(
        key: String,
        actual: () -> Boolean,
    ) {
        guardNotProse(key, "asserting")
        val want = boolean(key) ?: return
        markAsserted(key)
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's string value for [key]. */
    fun assertString(
        key: String,
        actual: () -> String?,
    ) {
        guardNotProse(key, "asserting")
        val want = string(key) ?: return
        markAsserted(key)
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's string-array value for [key]. */
    fun assertStrings(
        key: String,
        actual: () -> List<String>,
    ) {
        guardNotProse(key, "asserting")
        val want = strings(key) ?: return
        markAsserted(key)
        assertEquals(want, actual(), "$where: $key")
    }

    /**
     * Hand the fixture's raw value for [key] to [check] and mark the key
     * asserted.
     *
     * The escape hatch for comparisons that are not equality — a tolerance, set
     * containment, a regex, a decode the typed entry points above do not cover,
     * or a nested block. The point is that the fixture's value must reach the
     * comparison, not that the comparison must be `==`: a [check] that ignores
     * its argument is the very defect this class exists to name, and there is no
     * way to detect that from here, so do not write one — use [excuseKey].
     */
    fun assertKeyWith(
        key: String,
        check: (JsonElement) -> Unit,
    ) {
        guardNotProse(key, "asserting")
        val want = this[key] ?: return
        markAsserted(key)
        check(want)
    }

    /**
     * Declare that [key] is satisfied without a comparison at this call site,
     * and say why.
     *
     * The fallback for a key with nothing to compare — a discriminator that
     * selects a code path rather than a value to check, or a fact this binding
     * proves somewhere else. [reason] must name where the fact is proven
     * instead, or why it is unprovable here.
     *
     * Checked in both directions by [requireAllSatisfied]: excusing a key that
     * is also asserted, or one the fixture does not carry, fails. Prefer
     * converting the excuse into a real assertion — excusing is the fallback.
     */
    fun excuseKey(
        key: String,
        reason: String,
    ) {
        guardNotProse(key, "excusing")
        require(reason.isNotBlank()) {
            "$where: excuseKey('$key') needs a reason — an excuse without one is an allowlist entry"
        }
        consumed += key
        excused[key] = reason
    }

    /**
     * Fail when a key the fixture carries went unread, went unasserted, or was
     * excused staler than the run it guards.
     *
     * Named per key and per fixture, because "some assertion went unread" is not
     * actionable. Unread is checked first so deleting an assertion arm outright
     * still reports as rung 2 rather than as rung 3.
     */
    fun requireAllSatisfied() {
        // Rule 4, first, because it is the comparison that CONSUMES and ASSERTS
        // `prose` itself. A declared key nobody discharged would otherwise
        // report as an ordinary unread key — true, but it hides which rule was
        // broken, and a NARRATIVE-named prose key (`note`) would not report at
        // all.
        if (obj.containsKey("prose")) {
            check(discharged == declaredProse) {
                val forgotten = (declaredProse - discharged).sorted()
                val extra = (discharged - declaredProse).sorted()
                "$where: the discharged set does not match `assertions.prose`. Never discharged: " +
                    "$forgotten. Discharged but not declared: $extra. The corpus declares which " +
                    "keys are paragraphs; this comparison is what makes a forgotten one FAIL " +
                    "rather than vanish (#lzprosekeyconvention)"
            }
            consumed += "prose"
            markAsserted("prose")
        }

        val present = obj.keys - NARRATIVE

        val unread = (present - consumed).sorted()
        check(unread.isEmpty()) {
            "$where: assertion key(s) $unread are present in the fixture but were never " +
                "consumed by this runner. Replaying the fixture without evaluating its " +
                "assertion reports green while proving nothing — implement the assertion " +
                "rather than ignoring the key (#lzassertunknownkeys)"
        }

        val staleExcuses = excused.keys.filter { it in asserted || it !in obj.keys }.sorted()
        check(staleExcuses.isEmpty()) {
            "$where: excuse(s) ${staleExcuses.map { "$it (${excused[it]})" }} are stale — the " +
                "key is either asserted by this same run or absent from the fixture, so the " +
                "excuse hides nothing and only disarms the guard. Delete it " +
                "(#lzconsumednotasserted)"
        }

        val unasserted = (present - asserted - excused.keys - discharged).sorted()
        check(unasserted.isEmpty()) {
            "$where: assertion key(s) $unasserted were READ but never asserted. Reading a key " +
                "marks it consumed and proves nothing: a named skip, a value bound and never " +
                "compared, and a comparison against a hardcoded literal all leave the fixture " +
                "free to change without failing anything. Route the key through assertLong / " +
                "assertInt / assertBoolean / assertString / assertStrings / assertKeyWith so " +
                "the fixture's own value reaches the comparison, or excuseKey it with a reason " +
                "(#lzconsumednotasserted)"
        }
    }

    companion object {
        /** Prose keys that carry no assertion and are documentation only. */
        val NARRATIVE = setOf("note", "notes", "comment", "description", "why", "reason_note")
    }
}

/**
 * Run [block] against a consumption- and assertion-tracking view of this
 * assertion object, then fail if any key in it went unread, went unasserted, or
 * carried a stale excuse. [where] should identify the fixture (and step, where
 * there is one) so the error is actionable.
 */
inline fun JsonObject.consuming(
    where: String,
    block: (AssertionKeys) -> Unit,
) {
    val keys = AssertionKeys(where, this)
    block(keys)
    keys.requireAllSatisfied()
}
