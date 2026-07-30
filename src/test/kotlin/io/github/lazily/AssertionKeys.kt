package io.github.lazily

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Consumption-tracking view over a conformance fixture's assertion block —
 * `assertions`, `expected`, `expect` (`#lzassertunknownkeys`).
 *
 * The failure this exists to prevent is one level below "was the fixture
 * replayed". A runner that reads named keys out of an assertion object and lets
 * anything it does not recognise fall through reports the fixture as replayed
 * while never checking the field the fixture exists for: the `wire` round-trips,
 * the suite goes green, and the assertion proves nothing. `delta_zero_copy_arrow`
 * carried a `first_op_payload_backend` discriminator no binding read; adding that
 * one arm fixed the instance but not the property — the next unread key would be
 * just as invisible.
 *
 * Every accessor here marks its key consumed whether or not the key is present,
 * so an *optional* assertion is still declared. [requireAllConsumed] then fails
 * loudly, naming the offending key and the fixture, if the fixture carried a key
 * the runner never asked for.
 *
 * Usage:
 * ```
 * assertions.consuming("$fixture assertions") { a ->
 *     a.long("epoch")?.let { assertEquals(it, snapshot.epoch, "epoch") }
 * }
 * ```
 */
class AssertionKeys(
    private val where: String,
    private val obj: JsonObject,
) {
    private val consumed = mutableSetOf<String>().apply { addAll(NARRATIVE) }

    /** Every key the fixture actually carries. */
    val keys: Set<String> get() = obj.keys

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

    /**
     * Declare [key] consumed without reading it here.
     *
     * Only for keys a *different* code path in the same replay evaluates. Never
     * use it to silence a key nothing evaluates — that is the allowlist this
     * class exists to replace.
     */
    fun consume(vararg key: String) {
        consumed += key
    }

    fun string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun long(key: String): Long? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.long

    fun int(key: String): Int? = long(key)?.toInt()

    /** Booleans, tolerating the `"true"`/`"false"` string spelling the corpus also uses. */
    fun boolean(key: String): Boolean? = when (val e = this[key]) {
        null -> null
        is JsonPrimitive -> e.booleanOrNull ?: when (e.contentOrNull) {
            "true" -> true
            "false" -> false
            else -> error("$where: assertion key '$key' is not a boolean: $e")
        }
        else -> error("$where: assertion key '$key' is not a boolean: $e")
    }

    fun obj(key: String): JsonObject? = (this[key] as? JsonObject)

    fun array(key: String): JsonArray? = (this[key] as? JsonArray)

    fun strings(key: String): List<String>? =
        array(key)?.map { it.jsonPrimitive.content }

    /**
     * Consume every key starting with [prefix] and return the matches.
     *
     * For the corpus's parameterised spellings such as `resync_after_epoch_10`,
     * where the suffix is data rather than a distinct assertion.
     */
    fun withPrefix(prefix: String): List<Pair<String, JsonElement>> =
        obj.entries
            .filter { it.key.startsWith(prefix) }
            .onEach { consumed += it.key }
            .map { it.key to it.value }

    /**
     * Fail when the fixture carried an assertion key this runner never asked
     * for. Names the key and the fixture, because "some assertion went unread"
     * is not actionable.
     */
    fun requireAllConsumed() {
        val unread = (obj.keys - consumed).sorted()
        check(unread.isEmpty()) {
            "$where: assertion key(s) $unread are present in the fixture but were never " +
                "consumed by this runner. Replaying the fixture without evaluating its " +
                "assertion reports green while proving nothing — implement the assertion " +
                "rather than ignoring the key (#lzassertunknownkeys)"
        }
    }

    companion object {
        /** Prose keys that carry no assertion and are documentation only. */
        val NARRATIVE = setOf("note", "notes", "comment", "description", "why", "reason_note")
    }
}

/**
 * Run [block] against a consumption-tracking view of this assertion object and
 * then fail if any key in it went unread. [where] should identify the fixture
 * (and step, where there is one) so the error is actionable.
 */
inline fun JsonObject.consuming(where: String, block: (AssertionKeys) -> Unit) {
    val keys = AssertionKeys(where, this)
    block(keys)
    keys.requireAllConsumed()
}
