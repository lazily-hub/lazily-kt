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
 */
class AssertionKeys(
    private val where: String,
    private val obj: JsonObject,
) {
    private val consumed = mutableSetOf<String>()
    private val asserted = mutableSetOf<String>()
    private val excused = mutableMapOf<String, String>()

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
     * Assert [actual] against the fixture's `Long` value for [key].
     *
     * [actual] is by-name because the key is optional and the observable is
     * often unreachable when the fixture does not carry it.
     */
    fun assertLong(
        key: String,
        actual: () -> Long,
    ) {
        val want = long(key) ?: return
        asserted += key
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's `Int` value for [key]. */
    fun assertInt(
        key: String,
        actual: () -> Int,
    ) {
        val want = int(key) ?: return
        asserted += key
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's boolean value for [key]. */
    fun assertBoolean(
        key: String,
        actual: () -> Boolean,
    ) {
        val want = boolean(key) ?: return
        asserted += key
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's string value for [key]. */
    fun assertString(
        key: String,
        actual: () -> String?,
    ) {
        val want = string(key) ?: return
        asserted += key
        assertEquals(want, actual(), "$where: $key")
    }

    /** Assert [actual] against the fixture's string-array value for [key]. */
    fun assertStrings(
        key: String,
        actual: () -> List<String>,
    ) {
        val want = strings(key) ?: return
        asserted += key
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
        val want = this[key] ?: return
        asserted += key
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

        val unasserted = (present - asserted - excused.keys).sorted()
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
