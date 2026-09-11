package io.github.lazily

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays

/**
 * Replay-equivalence proof for a reactive graph (`#lzreplaykt`).
 *
 * The contract is `lazily-spec/docs/replay-equivalence.md`:
 *
 * > Given the same event log, a **rebuilt** graph observes the same values at
 * > every checkpoint. Any deviation is a defect in the graph, not a tolerance.
 *
 * Three obligations follow, and this file implements all three.
 *
 * 1. **The fingerprint is bound to the log that produced it.** [ReplayLog]
 *    carries a digest over its canonical bytes; [ReplayFingerprint] records that
 *    digest alongside the observed values, and [ReplayHarness.verify] compares
 *    it *before* it compares any value. A fingerprint recorded against a
 *    different log throws [ReplayLogMismatchException] and is never compared.
 *    The discipline is `tsift`'s: revalidate the recorded hash against the
 *    source bytes and deterministically suppress the cached answer when they
 *    disagree. It matters because two different logs can settle to the same
 *    values — `[+1,+2,+3]` and `[+3,+2,+1]` both sum to 6 — so a value-only
 *    comparison would *pass* and certify nothing about the log in front of it.
 *    The non-raising [ReplayHarness.check] refuses a stale fingerprint too: it
 *    is an unanswerable question, not a report.
 * 2. **Divergence is localized.** Every event is checkpointed by default
 *    (`stride = 1`), and a divergence names the first checkpoint's `seq` plus
 *    the label of the cell that differed. `stride` is part of the fingerprint,
 *    so a fingerprint sampled at one stride is refused by a harness sampling at
 *    another ([ReplayStrideMismatchException]) — equal log digest *plus* equal
 *    stride is what makes two checkpoint sequences comparable at all.
 * 3. **The observation encoding is canonical, or it fails.** See
 *    [canonicalBytes]. A value the encoding does not define raises
 *    [ReplayEncodingException] rather than falling back on `toString()`, whose
 *    default rendering embeds an identity hash and would report a *false*
 *    divergence on every run.
 *
 * **Hashing.** SHA-256 from `java.security.MessageDigest`, not the reference
 * implementation's BLAKE2b-256: the property relied on is collision resistance
 * over canonical bytes, the spec leaves both hash and byte layout
 * binding-chosen (a fingerprint is pinned next to a test in one language and is
 * never exchanged between bindings), and SHA-256 is in the JDK — matching
 * lazily-py's digests byte for byte would buy a Bouncy Castle dependency for
 * nothing.
 *
 * ```
 * class Counter : ReplayGraph {
 *     private var total = 0L
 *
 *     override fun apply(event: ReplayEvent) {
 *         total += event.payload as Long
 *     }
 *
 *     override fun observe(): Map<String, Any?> = mapOf("total" to total)
 * }
 *
 * val log = ReplayLog.fromRecords(listOf("add" to 1L, "add" to 2L, "add" to 3L))
 * val harness = ReplayHarness(::Counter)
 *
 * val fingerprint = harness.record(log) // pin it, or commit `toWire()`
 * harness.verify(log, fingerprint) // throws if the replay diverges
 * harness.prove(log) // record + re-replay in one call
 * ```
 */

/** The checkpoint sequence number for the state before any event was applied. */
const val REPLAY_INITIAL_SEQ: Long = -1L

private const val REPLAY_WIRE_SCHEMA_VERSION = 1

private const val REPLAY_PREVIEW_LIMIT = 120

// -- errors ------------------------------------------------------------------

/** A replay-equivalence proof could not be completed as stated. */
open class ReplayProofException(
    message: String,
) : RuntimeException(message)

/**
 * A value has no canonical byte encoding, so it cannot be fingerprinted.
 *
 * Thrown instead of falling back on `toString()`, whose JVM default embeds an
 * identity hash and would report a *false* divergence on every replay — the
 * exact failure this module exists to make impossible, arriving as a flaky test
 * instead of a real one.
 */
class ReplayEncodingException(
    message: String,
) : ReplayProofException(message)

/**
 * The fingerprint was recorded against a different event log.
 *
 * A distinct type so a driver routes on the TYPE rather than on a message
 * string: a stale fingerprint is never compared, so it can neither pass by
 * coincidence nor be misreported as a value divergence.
 */
class ReplayLogMismatchException(
    val expectedDigest: String,
    val actualDigest: String,
) : ReplayProofException(
    "fingerprint was recorded against a different event log (fingerprint " +
        "logDigest=$expectedDigest, replayed log digest=$actualDigest); re-record the " +
        "fingerprint against this log",
)

/**
 * The fingerprint was recorded at a different checkpoint stride.
 *
 * Separate from [ReplayLogMismatchException] because it is a different fault:
 * the log is the right one, but the two checkpoint sequences were never
 * comparable.
 */
class ReplayStrideMismatchException(
    val expectedStride: Int,
    val actualStride: Int,
) : ReplayProofException(
    "fingerprint was recorded at stride $expectedStride but this harness samples at " +
        "stride $actualStride; re-record it",
)

/** A replayed graph observed a different value than the fingerprint. */
class ReplayDivergenceException(
    divergences: List<ReplayDivergence>,
) : ReplayProofException(
    divergences.firstOrNull()?.let { first ->
        val extra = divergences.size - 1
        "replay diverged from the fingerprint: $first" + if (extra > 0) " (+$extra more)" else ""
    } ?: error("ReplayDivergenceException requires at least one divergence"),
) {
    val divergences: List<ReplayDivergence> = divergences.toList()

    /** The earliest divergence, which is the one worth reading. */
    val first: ReplayDivergence get() = this.divergences.first()
}

// -- canonical encoding ------------------------------------------------------

/**
 * A host value that supplies its own canonical form.
 *
 * The JVM has no structural equivalent of a Python dataclass that
 * [canonicalBytes] could walk without `kotlin-reflect`, and reflecting over an
 * arbitrary object is how a fingerprint ends up covering a field nobody meant to
 * observe. So a type that wants to be fingerprinted says so, and says what it
 * is: [canonicalForm] returns a value built out of the encoding's own defined
 * types, and the result is tagged with [canonicalTypeName] so two types with the
 * same shape are still two values.
 */
interface ReplayCanonical {
    /** A value made of the types [canonicalBytes] defines. */
    fun canonicalForm(): Any?

    /** The type tag mixed into the encoding, so equal shapes of different types differ. */
    val canonicalTypeName: String get() = this::class.qualifiedName ?: this::class.java.name
}

private val UNSIGNED_BYTES = Comparator<ByteArray> { a, b -> Arrays.compareUnsigned(a, b) }

private fun frame(
    tag: Char,
    body: ByteArray,
): ByteArray = "$tag${body.size}:".toByteArray(Charsets.UTF_8) + body

private fun join(parts: List<ByteArray>): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (part in parts) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

private fun canonical(
    value: Any?,
    path: String,
): ByteArray =
    when (value) {
        // Ordered so a subtype never reaches a supertype's arm: `ByteArray` before
        // the array arm, `Set` before `Collection`, `Map` before everything.
        null -> "n0:".toByteArray(Charsets.UTF_8)
        true -> "b1:1".toByteArray(Charsets.UTF_8)
        false -> "b1:0".toByteArray(Charsets.UTF_8)
        is ReplayCanonical ->
            frame(
                'd',
                frame('s', value.canonicalTypeName.toByteArray(Charsets.UTF_8)) +
                    canonical(value.canonicalForm(), "$path.canonicalForm"),
            )
        is Enum<*> -> {
            val declaring = value.javaClass.takeIf { it.isEnum } ?: value.javaClass.superclass
            frame(
                'e',
                frame('s', declaring.name.toByteArray(Charsets.UTF_8)) +
                    frame('s', value.name.toByteArray(Charsets.UTF_8)),
            )
        }
        is Byte, is Short, is Int, is Long, is BigInteger ->
            frame('i', value.toString().toByteArray(Charsets.UTF_8))
        is Float, is Double ->
            // The RAW bit pattern, not `toString()`: it is exact, it round-trips, and
            // it keeps distinct NaN payloads distinct where a shortest-round-trip
            // rendering folds them together.
            frame(
                'f',
                java.lang.Long
                    .toHexString(java.lang.Double.doubleToRawLongBits((value as Number).toDouble()))
                    .toByteArray(Charsets.UTF_8),
            )
        is CharSequence -> frame('s', value.toString().toByteArray(Charsets.UTF_8))
        is ByteArray -> frame('y', value.copyOf())
        is Map<*, *> ->
            // Sorted by ENCODED BYTES: insertion order is not part of the value, and
            // mixed-type keys are not mutually comparable.
            frame(
                'm',
                join(
                    value.entries
                        .map { (key, item) ->
                            canonical(key, "$path[key]") + canonical(item, "$path[$key]")
                        }.sortedWith(UNSIGNED_BYTES),
                ),
            )
        is Set<*> ->
            frame(
                't',
                join(value.map { canonical(it, "$path{}") }.sortedWith(UNSIGNED_BYTES)),
            )
        is Collection<*> ->
            frame(
                'l',
                join(value.mapIndexed { index, item -> canonical(item, "$path[$index]") }),
            )
        is Array<*> ->
            frame(
                'l',
                join(value.mapIndexed { index, item -> canonical(item, "$path[$index]") }),
            )
        else ->
            throw ReplayEncodingException(
                "$path: ${value.javaClass.name} has no canonical encoding; observe a plain " +
                    "value, a ReplayCanonical, or a map/set/collection of them instead — " +
                    "falling back on toString() would embed an identity hash and report a " +
                    "false divergence on every run",
            )
    }

/**
 * Encode [value] to type-tagged, length-framed, order-stable bytes.
 *
 * The equality classes this pins are the part every binding must agree on:
 *
 * | Property | Requirement |
 * |---|---|
 * | Mapping order | `{a:1, b:2}` and `{b:2, a:1}` are the same value |
 * | Set order | `{1,2,3}` and `{3,1,2}` are the same value |
 * | Sequence order | `[1,2]` and `[2,1]` are different values |
 * | Type tagging | `1`, `"1"`, `1.0`, `true` and the byte string `1` are five different values |
 * | Member framing | `["a","bc"]` and `["ab","c"]` are different values |
 *
 * The last row is the one that is easy to get wrong: concatenating member
 * encodings without a length makes those two sequences identical, and a harness
 * that cannot tell them apart certifies a graph that reshaped its own output.
 * Every frame here is `tag` + decimal length + `:` + body, so no concatenation
 * of members can be confused for another.
 *
 * @throws ReplayEncodingException for a value the encoding does not define.
 */
fun canonicalBytes(value: Any?): ByteArray = canonical(value, "value")

/** The lowercase SHA-256 hex digest of [canonicalBytes] of [value]. */
fun canonicalDigest(value: Any?): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(canonicalBytes(value))
        .joinToString("") { "%02x".format(it) }

private fun preview(value: Any?): String {
    val text = runCatching { value.toString() }.getOrElse { return "<unprintable>" }
    return if (text.length > REPLAY_PREVIEW_LIMIT) {
        text.take(REPLAY_PREVIEW_LIMIT - 1) + "…"
    } else {
        text
    }
}

// -- the log -----------------------------------------------------------------

/** One entry of an ordered event log. */
data class ReplayEvent(
    val seq: Long,
    val name: String,
    val payload: Any? = null,
) : ReplayCanonical {
    init {
        require(seq >= 0) { "event seq must be non-negative, got $seq" }
        require(name.isNotEmpty()) { "event name must be non-empty" }
    }

    override val canonicalTypeName: String get() = "ReplayEvent"

    override fun canonicalForm(): Any? = mapOf("seq" to seq, "name" to name, "payload" to payload)
}

/**
 * An ordered event log with a digest over its canonical bytes.
 *
 * Sequence numbers must strictly increase; they do NOT have to be contiguous,
 * because an ack-truncated durable outbox replays real epochs and renumbering
 * them would hide a truncated prefix that the log digest otherwise catches.
 */
class ReplayLog(
    events: List<ReplayEvent>,
) : Iterable<ReplayEvent> {
    val events: List<ReplayEvent> = events.toList()

    /** The digest the fingerprint is bound to. */
    val digest: String

    init {
        var previous: Long? = null
        for (event in this.events) {
            val last = previous
            require(last == null || event.seq > last) {
                "event log must be strictly increasing in seq, got ${event.seq} after $last"
            }
            previous = event.seq
        }
        digest = canonicalDigest(this.events)
    }

    val size: Int get() = events.size

    operator fun get(index: Int): ReplayEvent = events[index]

    override fun iterator(): Iterator<ReplayEvent> = events.iterator()

    companion object {
        /** A log from already-numbered events. */
        fun of(vararg events: ReplayEvent): ReplayLog = ReplayLog(events.toList())

        /** A log from `(name, payload)` pairs, numbered `0..n-1`. */
        fun fromRecords(records: List<Pair<String, Any?>>): ReplayLog =
            ReplayLog(
                records.mapIndexed { index, (name, payload) ->
                    ReplayEvent(seq = index.toLong(), name = name, payload = payload)
                },
            )
    }
}

/**
 * A [ReplayLog] over a reliable-sync outbox's retained frames.
 *
 * [DurableOutbox.replayFrom] is already the replay source a reconnect drains;
 * this makes it the fingerprinted one too. Outbox epochs become event seqs, so a
 * truncated prefix shows up in the log digest rather than silently shifting every
 * event.
 *
 * The payload is the frame's canonical JSON BYTES rather than the decoded
 * [IpcMessage]: those bytes are what the outbox retained and what a peer would be
 * re-sent, the byte string has a defined canonical encoding, and a graph that
 * wants the message decodes them itself.
 */
fun replayLogFromOutbox(
    outbox: DurableOutbox,
    cursor: Long = 0,
    name: String = "frame",
): ReplayLog =
    ReplayLog(
        outbox.replayFrom(cursor).map { (epoch, message) ->
            ReplayEvent(seq = epoch, name = name, payload = message.encodeJson())
        },
    )

// -- the fingerprint ---------------------------------------------------------

/**
 * Per-cell digests observed after applying events through [seq].
 *
 * [seq] is [REPLAY_INITIAL_SEQ] for the state before any event was applied.
 */
class ReplayCheckpoint(
    val seq: Long,
    cells: List<Pair<String, String>>,
) : ReplayCanonical {
    /** Label → digest, sorted by label so the order is the value's, not the graph's. */
    val cells: List<Pair<String, String>> = cells.sortedBy { it.first }

    override val canonicalTypeName: String get() = "ReplayCheckpoint"

    override fun canonicalForm(): Any? = listOf<Any?>(seq, cells.map { listOf(it.first, it.second) })

    fun asMap(): Map<String, String> = cells.toMap()

    override fun equals(other: Any?): Boolean =
        other is ReplayCheckpoint && other.seq == seq && other.cells == cells

    override fun hashCode(): Int = 31 * seq.hashCode() + cells.hashCode()

    override fun toString(): String = "ReplayCheckpoint(seq=$seq, cells=${asMap()})"

    companion object {
        fun of(
            seq: Long,
            observed: Map<String, Any?>,
        ): ReplayCheckpoint =
            ReplayCheckpoint(
                seq = seq,
                cells = observed.map { (label, value) -> label to canonicalDigest(value) },
            )
    }
}

/** A recorded, log-bound observation of a replayed graph. */
class ReplayFingerprint(
    val logDigest: String,
    val stride: Int,
    checkpoints: List<ReplayCheckpoint>,
) {
    val checkpoints: List<ReplayCheckpoint> = checkpoints.toList()

    /** A digest over the whole fingerprint, for pinning it as one opaque value. */
    val digest: String

    init {
        require(stride >= 1) { "stride must be >= 1, got $stride" }
        require(this.checkpoints.isNotEmpty()) {
            "a fingerprint needs at least the initial checkpoint"
        }
        digest = canonicalDigest(listOf<Any?>(logDigest, stride, this.checkpoints))
    }

    /** The last checkpoint — the end state of the replay. */
    val final: ReplayCheckpoint get() = checkpoints.last()

    /** A JSON-safe form, so a fingerprint can be committed next to a test. */
    fun toWire(): Map<String, Any?> =
        mapOf(
            "schema_version" to REPLAY_WIRE_SCHEMA_VERSION,
            "log_digest" to logDigest,
            "stride" to stride,
            "checkpoints" to
                checkpoints.map { checkpoint ->
                    mapOf("seq" to checkpoint.seq, "cells" to checkpoint.asMap())
                },
        )

    override fun equals(other: Any?): Boolean = other is ReplayFingerprint && other.digest == digest

    override fun hashCode(): Int = digest.hashCode()

    override fun toString(): String =
        "ReplayFingerprint(logDigest=$logDigest, stride=$stride, checkpoints=${checkpoints.size})"

    companion object {
        /** Rebuild from [toWire], rejecting an unknown schema version. */
        @Suppress("UNCHECKED_CAST")
        fun fromWire(wire: Map<String, Any?>): ReplayFingerprint {
            val version = (wire["schema_version"] as? Number)?.toInt()
            if (version != REPLAY_WIRE_SCHEMA_VERSION) {
                throw ReplayProofException(
                    "unsupported replay fingerprint schema_version $version, expected " +
                        "$REPLAY_WIRE_SCHEMA_VERSION",
                )
            }
            val checkpoints =
                requireNotNull(wire["checkpoints"] as? List<Map<String, Any?>>) {
                    "replay fingerprint wire form carries no `checkpoints`"
                }
            return ReplayFingerprint(
                logDigest = wire["log_digest"].toString(),
                stride = (wire["stride"] as Number).toInt(),
                checkpoints =
                checkpoints.map { checkpoint ->
                    ReplayCheckpoint(
                        seq = (checkpoint["seq"] as Number).toLong(),
                        cells =
                        (checkpoint["cells"] as Map<String, String>).map { (k, v) ->
                            k to v
                        },
                    )
                },
            )
        }
    }
}

/** Why one cell did not replay to its recorded digest. */
enum class ReplayDivergenceKind(
    /** The stable spelling the canonical corpus and every sibling binding use. */
    val wireName: String,
) {
    /** Both sides observed the cell and the digests differ. */
    VALUE("value"),

    /** The fingerprint has the cell and the replay did not observe it. */
    MISSING("missing"),

    /** The replay observed a cell the fingerprint does not carry. */
    UNEXPECTED("unexpected"),
}

/** One cell that did not replay to its recorded digest. */
data class ReplayDivergence(
    val seq: Long,
    val label: String,
    val kind: ReplayDivergenceKind,
    val expected: String?,
    val actual: String?,
    val observed: String? = null,
) {
    override fun toString(): String {
        val where = if (seq == REPLAY_INITIAL_SEQ) "initial state" else "event seq=$seq"
        return when (kind) {
            ReplayDivergenceKind.MISSING -> "$where: cell '$label' was not observed on replay"
            ReplayDivergenceKind.UNEXPECTED ->
                "$where: cell '$label' appeared on replay but is not in the fingerprint"
            ReplayDivergenceKind.VALUE ->
                "$where: cell '$label' expected $expected but replayed $actual" +
                    (observed?.let { ", observed $it" } ?: "")
        }
    }
}

// -- the graph under proof ---------------------------------------------------

/**
 * What the harness needs from the graph it rebuilds.
 *
 * [apply] advances the graph by exactly one event; [observe] returns the cell
 * values the fingerprint covers, keyed by a stable label.
 */
interface ReplayGraph {
    fun apply(event: ReplayEvent)

    fun observe(): Map<String, Any?>
}

/**
 * Rebuild a graph from an event log and prove it replays identically.
 *
 * [build] is called once per replay and must return a **fresh** graph — a
 * harness that reuses one instance proves nothing, since the state it would
 * compare against is the state it already has.
 *
 * [stride] checkpoints every `stride`-th event; the initial state and the final
 * state are always checkpointed. It is recorded in the fingerprint, so a
 * fingerprint cannot be compared against a replay that sampled differently.
 */
class ReplayHarness(
    private val build: () -> ReplayGraph,
    val stride: Int = 1,
) {
    init {
        require(stride >= 1) { "stride must be >= 1, got $stride" }
    }

    /** Replay [log] once and record what the graph observed. */
    fun record(log: ReplayLog): ReplayFingerprint = replay(log).first

    /**
     * Replay [log] and return the divergences from [fingerprint].
     *
     * Non-raising for value divergence, so a caller can report all of them. Still
     * throws [ReplayLogMismatchException] for a fingerprint recorded against a
     * different log and [ReplayStrideMismatchException] for one recorded at a
     * different stride: comparing either would answer a question nobody asked.
     */
    fun check(
        log: ReplayLog,
        fingerprint: ReplayFingerprint,
    ): List<ReplayDivergence> {
        val (replayed, observed) = replay(log)
        revalidate(fingerprint, replayed)
        return compare(fingerprint, replayed, observed)
    }

    /**
     * Replay [log] and throw unless it matches [fingerprint] exactly.
     *
     * Returns the freshly recorded fingerprint, which equals [fingerprint].
     */
    fun verify(
        log: ReplayLog,
        fingerprint: ReplayFingerprint,
    ): ReplayFingerprint {
        val (replayed, observed) = replay(log)
        revalidate(fingerprint, replayed)
        val divergences = compare(fingerprint, replayed, observed)
        if (divergences.isNotEmpty()) throw ReplayDivergenceException(divergences)
        return replayed
    }

    /**
     * Record [log] and re-replay it, throwing on any divergence.
     *
     * The self-check: no external fingerprint is needed to catch a graph that is
     * not a pure function of its log, because two replays of the same log in the
     * same process already disagree.
     */
    fun prove(
        log: ReplayLog,
        replays: Int = 2,
    ): ReplayFingerprint {
        require(replays >= 2) { "prove needs at least 2 replays to compare, got $replays" }
        val fingerprint = record(log)
        repeat(replays - 1) { verify(log, fingerprint) }
        return fingerprint
    }

    // -- internals -----------------------------------------------------------

    /** Bind the fingerprint to these exact log bytes BEFORE comparing any value. */
    private fun revalidate(
        fingerprint: ReplayFingerprint,
        replayed: ReplayFingerprint,
    ) {
        if (fingerprint.logDigest != replayed.logDigest) {
            throw ReplayLogMismatchException(
                expectedDigest = fingerprint.logDigest,
                actualDigest = replayed.logDigest,
            )
        }
        if (fingerprint.stride != replayed.stride) {
            throw ReplayStrideMismatchException(
                expectedStride = fingerprint.stride,
                actualStride = replayed.stride,
            )
        }
    }

    private fun replay(log: ReplayLog): Pair<ReplayFingerprint, List<Map<String, Any?>>> {
        val graph = build()
        var sample = graph.observe().toMap()
        val checkpoints = mutableListOf(ReplayCheckpoint.of(REPLAY_INITIAL_SEQ, sample))
        val observed = mutableListOf(sample)
        val total = log.size
        log.forEachIndexed { index, event ->
            graph.apply(event)
            if ((index + 1) % stride == 0 || index + 1 == total) {
                sample = graph.observe().toMap()
                checkpoints += ReplayCheckpoint.of(event.seq, sample)
                observed += sample
            }
        }
        return ReplayFingerprint(
            logDigest = log.digest,
            stride = stride,
            checkpoints = checkpoints,
        ) to observed
    }

    private fun compare(
        expected: ReplayFingerprint,
        actual: ReplayFingerprint,
        observed: List<Map<String, Any?>>,
    ): List<ReplayDivergence> {
        val divergences = mutableListOf<ReplayDivergence>()
        for (index in 0 until minOf(expected.checkpoints.size, actual.checkpoints.size)) {
            val want = expected.checkpoints[index]
            val got = actual.checkpoints[index]
            val wantCells = want.asMap()
            val gotCells = got.asMap()
            for (label in (wantCells.keys + gotCells.keys).sorted()) {
                val wantDigest = wantCells[label]
                val gotDigest = gotCells[label]
                if (wantDigest == gotDigest) continue
                val kind =
                    when {
                        gotDigest == null -> ReplayDivergenceKind.MISSING
                        wantDigest == null -> ReplayDivergenceKind.UNEXPECTED
                        else -> ReplayDivergenceKind.VALUE
                    }
                val sampled = observed.getOrNull(index).orEmpty()
                divergences +=
                    ReplayDivergence(
                        seq = want.seq,
                        label = label,
                        kind = kind,
                        expected = wantDigest,
                        actual = gotDigest,
                        observed = if (label in sampled) preview(sampled[label]) else null,
                    )
            }
            // The first diverging checkpoint is the actionable one; later ones are
            // almost always the same defect carried forward.
            if (divergences.isNotEmpty()) break
        }
        if (expected.checkpoints.size != actual.checkpoints.size && divergences.isEmpty()) {
            // Same log digest and stride, so this cannot come from sampling — it means
            // `observe` or `apply` changed the checkpoint count.
            throw ReplayProofException(
                "fingerprint has ${expected.checkpoints.size} checkpoints but the replay " +
                    "produced ${actual.checkpoints.size} for the same log",
            )
        }
        return divergences
    }
}
