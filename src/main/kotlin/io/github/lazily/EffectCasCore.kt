package io.github.lazily

import java.util.TreeMap

/**
 * Generic keyed compare-and-swap effect authority (`#lzeffectcas`).
 *
 * The pure core owns effect identity, keyed single-flight, latest-wins
 * coalescing, logical-clock retry timing, reconnect fencing, exact receipt
 * matching, and the cursor of an ordered effect plan. Backends stay
 * application-owned: they observe an opaque authority token and execute one
 * atomic compare-and-apply ([CompareAndApply]). See
 * `lazily-spec/docs/durable-sinks.md#generic-effect-compare-and-swap-lzeffectcas`.
 *
 * Type parameters: `K` key, `A` opaque authority token, `P` plan-step payload,
 * `R` receipt, `E` adapter error.
 */

/** Stable, non-empty effect identity. A crash MUST NOT mint a new one for retained work. */
data class EffectIdentity(
    val value: String,
) : Comparable<EffectIdentity> {
    init {
        require(value.isNotEmpty()) { "an effect identity must be non-empty" }
    }

    override fun compareTo(other: EffectIdentity): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/** An ordered, non-empty sequence of idempotent effect payloads. */
class EffectPlan<P : Any>(
    steps: List<P>,
) {
    val steps: List<P> = steps.toList()

    init {
        require(this.steps.isNotEmpty()) { "an effect plan must contain at least one step" }
    }

    val size: Int get() = steps.size

    override fun equals(other: Any?): Boolean = other is EffectPlan<*> && other.steps == steps

    override fun hashCode(): Int = steps.hashCode()

    override fun toString(): String = "EffectPlan($steps)"

    companion object {
        fun <P : Any> single(payload: P): EffectPlan<P> = EffectPlan(listOf(payload))
    }
}

/** Immutable desired effect. One epoch names exactly one identity and plan. */
data class EffectIntent<K : Any, A : Any, P : Any>(
    val identity: EffectIdentity,
    val epoch: Long,
    val key: K,
    val expected: A,
    val plan: EffectPlan<P>,
)

/** Exact identity of one adapter call. */
data class EffectAttemptIdentity(
    val effect: EffectIdentity,
    val generation: Long,
    val epoch: Long,
    val step: Int,
    val attempt: Int,
)

/** A successful step receipt retained as lineage for later plan steps. */
data class EffectReceipt<out R : Any, out A : Any>(
    val attempt: EffectAttemptIdentity,
    val receipt: R,
    val authority: A,
)

/** The exact compare-and-apply call handed to an application adapter. */
data class EffectAttempt<K : Any, A : Any, P : Any, R : Any>(
    val identity: EffectAttemptIdentity,
    val key: K,
    val expected: A,
    val payload: P,
    val priorReceipts: List<EffectReceipt<R, A>>,
)

/** The only outcomes an application adapter may return. */
sealed interface EffectApply<out R : Any, out A : Any, out E : Any> {
    data class Applied<R : Any, A : Any>(val receipt: R, val authority: A) : EffectApply<R, A, Nothing>

    data class Superseded<A : Any>(val observed: A) : EffectApply<Nothing, A, Nothing>

    data class Retryable<E : Any>(val error: E) : EffectApply<Nothing, Nothing, E>

    data class Rejected<E : Any>(val error: E) : EffectApply<Nothing, Nothing, E>
}

/**
 * Narrow application-owned atomic authority boundary. The adapter MUST NOT retry,
 * choose a newer desired intent, clear retained work, or advance a frontier.
 */
interface CompareAndApply<K : Any, A : Any, P : Any, R : Any, E : Any> {
    /** A side-effect-free read of the backend's opaque authority. */
    fun observe(key: K): A

    fun compareAndApply(attempt: EffectAttempt<K, A, P, R>): EffectApply<R, A, E>
}

data class EffectPending<K : Any, A : Any, P : Any, R : Any>(
    val intent: EffectIntent<K, A, P>,
    val nextStep: Int,
    val expected: A,
    val receipts: List<EffectReceipt<R, A>>,
    val nextAttempt: Int,
    val readyAt: Long?,
)

data class EffectRetry<E : Any>(
    val attempt: EffectAttemptIdentity,
    val error: E,
    val retryAt: Long,
)

sealed interface EffectTerminal<out R : Any, out A : Any, out E : Any> {
    val identity: EffectIdentity
    val epoch: Long
    val receipts: List<EffectReceipt<R, A>>

    data class Applied<R : Any, A : Any>(
        override val identity: EffectIdentity,
        override val epoch: Long,
        override val receipts: List<EffectReceipt<R, A>>,
    ) : EffectTerminal<R, A, Nothing>

    data class Superseded<R : Any, A : Any>(
        override val identity: EffectIdentity,
        override val epoch: Long,
        val observed: A?,
        override val receipts: List<EffectReceipt<R, A>>,
    ) : EffectTerminal<R, A, Nothing>

    data class Rejected<R : Any, A : Any, E : Any>(
        override val identity: EffectIdentity,
        override val epoch: Long,
        val error: E,
        override val receipts: List<EffectReceipt<R, A>>,
    ) : EffectTerminal<R, A, E>
}

/** Reader image of one key. */
data class EffectCasKeyState<K : Any, A : Any, P : Any, R : Any, E : Any>(
    val pending: EffectPending<K, A, P, R>?,
    val inflight: EffectAttempt<K, A, P, R>?,
    val retry: EffectRetry<E>?,
    val appliedThrough: Long?,
    val terminal: EffectTerminal<R, A, E>?,
)

/** Reader image of the whole core, keys in ascending order. */
data class EffectCasSnapshot<K : Any, A : Any, P : Any, R : Any, E : Any>(
    val generation: Long,
    val keys: Map<K, EffectCasKeyState<K, A, P, R, E>>,
)

sealed interface EffectCasUpsert {
    data object Accepted : EffectCasUpsert

    data object Unchanged : EffectCasUpsert

    data class AlreadyApplied(val appliedThrough: Long) : EffectCasUpsert

    data class StaleEpoch(val current: Long) : EffectCasUpsert

    data object EpochConflict : EffectCasUpsert
}

sealed interface EffectCasClaim<out K : Any, out A : Any, out P : Any, out R : Any> {
    data class Claimed<K : Any, A : Any, P : Any, R : Any>(
        val attempt: EffectAttempt<K, A, P, R>,
    ) : EffectCasClaim<K, A, P, R>

    data object Empty : EffectCasClaim<Nothing, Nothing, Nothing, Nothing>

    data object Busy : EffectCasClaim<Nothing, Nothing, Nothing, Nothing>

    data class Waiting(val retryAt: Long) : EffectCasClaim<Nothing, Nothing, Nothing, Nothing>

    data class StaleGeneration(val current: Long) : EffectCasClaim<Nothing, Nothing, Nothing, Nothing>
}

sealed interface EffectCasApplied {
    data class StepApplied(val nextStep: Int) : EffectCasApplied

    data class Completed(val appliedThrough: Long) : EffectCasApplied

    data object Superseded : EffectCasApplied

    data object Duplicate : EffectCasApplied

    data object UnknownAttempt : EffectCasApplied

    data class StaleGeneration(val current: Long) : EffectCasApplied
}

sealed interface EffectCasFailure {
    data object Superseded : EffectCasFailure

    data class Retrying(val retryAt: Long) : EffectCasFailure

    data object Rejected : EffectCasFailure

    data object UnknownAttempt : EffectCasFailure

    data class StaleGeneration(val current: Long) : EffectCasFailure
}

sealed interface EffectCasOutcome {
    data class Applied(val outcome: EffectCasApplied) : EffectCasOutcome

    data class Failure(val outcome: EffectCasFailure) : EffectCasOutcome
}

sealed interface EffectCasReconnect {
    data class Advanced(
        val generation: Long,
        val requeued: Int,
        val superseded: Int,
    ) : EffectCasReconnect

    data class Unchanged(val generation: Long) : EffectCasReconnect

    data class StaleGeneration(val current: Long) : EffectCasReconnect
}

/** Whether a transition changed reader-visible state. */
data class EffectCasChange(
    val state: Boolean = false,
) {
    companion object {
        val NONE = EffectCasChange()
        val STATE = EffectCasChange(state = true)
    }
}

/** One transition's change plus its returned outcome. */
data class EffectCasTransition<out O>(
    val change: EffectCasChange,
    val result: O,
)

/**
 * Full-fidelity durable image of an [EffectCasCore] (`#lzeffectcasdurable`).
 *
 * [EffectCasSnapshot] is a reader projection: it omits the retained intent behind
 * an in-flight attempt and the last terminal intent, so it cannot restore the
 * core. This image carries every field the core needs to resume after a crash or
 * in another process. Keys are a list rather than a map so a non-string key
 * round-trips through any encoding; serialization itself is the host's choice.
 */
data class EffectCasDurableState<K : Any, A : Any, P : Any, R : Any, E : Any>(
    val generation: Long,
    val keys: List<EffectCasDurableKey<K, A, P, R, E>>,
)

/** Durable image of one key. */
data class EffectCasDurableKey<K : Any, A : Any, P : Any, R : Any, E : Any>(
    val key: K,
    val pending: EffectPending<K, A, P, R>?,
    val inflight: EffectCasDurableInflight<K, A, P, R>?,
    val retry: EffectRetry<E>?,
    val appliedThrough: Long?,
    val terminal: EffectTerminal<R, A, E>?,
    val terminalIntent: EffectIntent<K, A, P>?,
)

/** Durable image of an in-flight attempt and the retained work behind it. */
data class EffectCasDurableInflight<K : Any, A : Any, P : Any, R : Any>(
    val retained: EffectPending<K, A, P, R>,
    val attempt: EffectAttempt<K, A, P, R>,
)

/**
 * Why a durable image cannot be hydrated. Hydration never repairs an image: a
 * corrupt image is refused so no adapter call runs on invented state.
 */
enum class EffectCasHydrateError {
    /** Two entries name the same key. */
    DuplicateKey,

    /** A retained or terminal intent belongs to a different key. */
    KeyMismatch,

    /** A plan cursor points past the end of its plan. */
    CursorOutOfRange,

    /** An in-flight attempt does not describe its retained cursor. */
    AttemptMismatch,

    /** An in-flight attempt claims an actor generation newer than the image. */
    FutureGeneration,
}

/** Thrown by [EffectCasCore.hydrate] for a refused image. */
class EffectCasHydrateException(
    val error: EffectCasHydrateError,
) : IllegalArgumentException("refusing to hydrate effect-CAS image: $error")

/** Pure keyed effect-CAS orchestration authority. Not thread-safe; owns no thread, timer or I/O. */
class EffectCasCore<K : Comparable<K>, A : Any, P : Any, R : Any, E : Any>(
    generation: Long,
) {
    private class Inflight<K : Any, A : Any, P : Any, R : Any>(
        var retained: EffectPending<K, A, P, R>,
        val attempt: EffectAttempt<K, A, P, R>,
    )

    private class Entry<K : Any, A : Any, P : Any, R : Any, E : Any> {
        var pending: EffectPending<K, A, P, R>? = null
        var inflight: Inflight<K, A, P, R>? = null
        var retry: EffectRetry<E>? = null
        var appliedThrough: Long? = null
        var terminal: EffectTerminal<R, A, E>? = null
        var terminalIntent: EffectIntent<K, A, P>? = null

        fun view() = EffectCasKeyState(pending, inflight?.attempt, retry, appliedThrough, terminal)
    }

    private val keys = TreeMap<K, Entry<K, A, P, R, E>>()

    /** The current actor generation. */
    var generation: Long = generation
        private set

    fun snapshot(): EffectCasSnapshot<K, A, P, R, E> {
        val view = LinkedHashMap<K, EffectCasKeyState<K, A, P, R, E>>()
        for ((key, entry) in keys) view[key] = entry.view()
        return EffectCasSnapshot(generation, view)
    }

    fun state(key: K): EffectCasKeyState<K, A, P, R, E>? = keys[key]?.view()

    /** Keys with eligible pending work and no in-flight attempt at logical time [now]. */
    fun pendingKeys(now: Long): List<K> =
        keys.entries
            .filter { (_, entry) ->
                entry.inflight == null &&
                    entry.pending?.let { pending -> pending.readyAt == null || pending.readyAt <= now } == true
            }.map { it.key }

    fun upsertDesired(intent: EffectIntent<K, A, P>): EffectCasTransition<EffectCasUpsert> {
        val entry = keys.getOrPut(intent.key) { Entry() }
        entry.appliedThrough?.let { applied ->
            if (intent.epoch <= applied) {
                return EffectCasTransition(EffectCasChange.NONE, EffectCasUpsert.AlreadyApplied(applied))
            }
        }
        val pendingIntent = entry.pending?.intent
        val inflightIntent = entry.inflight?.retained?.intent
        val current = listOfNotNull(pendingIntent?.epoch, inflightIntent?.epoch, entry.terminalIntent?.epoch).maxOrNull()
        if (current != null) {
            if (intent.epoch < current) {
                return EffectCasTransition(EffectCasChange.NONE, EffectCasUpsert.StaleEpoch(current))
            }
            if (intent.epoch == current) {
                val same = pendingIntent == intent || inflightIntent == intent || entry.terminalIntent == intent
                return EffectCasTransition(
                    EffectCasChange.NONE,
                    if (same) EffectCasUpsert.Unchanged else EffectCasUpsert.EpochConflict,
                )
            }
        }
        entry.pending?.let { superseded ->
            entry.terminalIntent = superseded.intent
            entry.terminal =
                EffectTerminal.Superseded(superseded.intent.identity, superseded.intent.epoch, null, superseded.receipts)
        }
        entry.pending = EffectPending(intent, 0, intent.expected, emptyList(), 1, null)
        entry.retry = null
        return EffectCasTransition(EffectCasChange.STATE, EffectCasUpsert.Accepted)
    }

    fun claim(
        key: K,
        generation: Long,
        now: Long,
    ): EffectCasTransition<EffectCasClaim<K, A, P, R>> {
        if (generation != this.generation) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasClaim.StaleGeneration(this.generation))
        }
        val entry = keys[key] ?: return EffectCasTransition(EffectCasChange.NONE, EffectCasClaim.Empty)
        if (entry.inflight != null) return EffectCasTransition(EffectCasChange.NONE, EffectCasClaim.Busy)
        val pending = entry.pending ?: return EffectCasTransition(EffectCasChange.NONE, EffectCasClaim.Empty)
        val readyAt = pending.readyAt
        if (readyAt != null && now < readyAt) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasClaim.Waiting(readyAt))
        }
        entry.pending = null
        val attempt =
            EffectAttempt(
                identity =
                EffectAttemptIdentity(
                    effect = pending.intent.identity,
                    generation = generation,
                    epoch = pending.intent.epoch,
                    step = pending.nextStep,
                    attempt = pending.nextAttempt,
                ),
                key = pending.intent.key,
                expected = pending.expected,
                payload = pending.intent.plan.steps[pending.nextStep],
                priorReceipts = pending.receipts,
            )
        entry.inflight = Inflight(pending, attempt)
        return EffectCasTransition(EffectCasChange.STATE, EffectCasClaim.Claimed(attempt))
    }

    fun recordApplied(
        key: K,
        attempt: EffectAttemptIdentity,
        receipt: R,
        authority: A,
    ): EffectCasTransition<EffectCasApplied> {
        if (attempt.generation != generation) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasApplied.StaleGeneration(generation))
        }
        val entry = keys[key] ?: return EffectCasTransition(EffectCasChange.NONE, EffectCasApplied.UnknownAttempt)
        if (entry.terminal?.receipts?.any { it.attempt == attempt && it.receipt == receipt } == true) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasApplied.Duplicate)
        }
        val inflight = entry.inflight
        if (inflight == null || inflight.attempt.identity != attempt) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasApplied.UnknownAttempt)
        }
        entry.inflight = null
        val retained = inflight.retained
        val receipts = retained.receipts + EffectReceipt(attempt, receipt, authority)
        entry.retry = null

        if (entry.pending?.let { it.intent.epoch > retained.intent.epoch } == true) {
            entry.terminalIntent = retained.intent
            entry.terminal = EffectTerminal.Superseded(retained.intent.identity, retained.intent.epoch, authority, receipts)
            return EffectCasTransition(EffectCasChange.STATE, EffectCasApplied.Superseded)
        }

        val nextStep = retained.nextStep + 1
        if (nextStep < retained.intent.plan.size) {
            entry.pending =
                retained.copy(nextStep = nextStep, expected = authority, receipts = receipts, readyAt = null, nextAttempt = 1)
            return EffectCasTransition(EffectCasChange.STATE, EffectCasApplied.StepApplied(nextStep))
        }

        val epoch = retained.intent.epoch
        val applied = maxOf(entry.appliedThrough ?: epoch, epoch)
        entry.appliedThrough = applied
        entry.terminalIntent = retained.intent
        entry.terminal = EffectTerminal.Applied(retained.intent.identity, epoch, receipts)
        return EffectCasTransition(EffectCasChange.STATE, EffectCasApplied.Completed(applied))
    }

    fun recordSuperseded(
        key: K,
        attempt: EffectAttemptIdentity,
        observed: A,
    ): EffectCasTransition<EffectCasFailure> = finishFailed(key, attempt, FailureKind.Superseded(observed))

    fun recordRetryable(
        key: K,
        attempt: EffectAttemptIdentity,
        error: E,
        retryAt: Long,
    ): EffectCasTransition<EffectCasFailure> = finishFailed(key, attempt, FailureKind.Retryable(error, retryAt))

    fun recordRejected(
        key: K,
        attempt: EffectAttemptIdentity,
        error: E,
    ): EffectCasTransition<EffectCasFailure> = finishFailed(key, attempt, FailureKind.Rejected(error))

    /** Route one adapter outcome to its record operation. */
    fun recordOutcome(
        key: K,
        attempt: EffectAttemptIdentity,
        outcome: EffectApply<R, A, E>,
        retryAt: Long,
    ): EffectCasTransition<EffectCasOutcome> =
        when (outcome) {
            is EffectApply.Applied -> {
                val t = recordApplied(key, attempt, outcome.receipt, outcome.authority)
                EffectCasTransition(t.change, EffectCasOutcome.Applied(t.result))
            }
            is EffectApply.Superseded -> {
                val t = recordSuperseded(key, attempt, outcome.observed)
                EffectCasTransition(t.change, EffectCasOutcome.Failure(t.result))
            }
            is EffectApply.Retryable -> {
                val t = recordRetryable(key, attempt, outcome.error, retryAt)
                EffectCasTransition(t.change, EffectCasOutcome.Failure(t.result))
            }
            is EffectApply.Rejected -> {
                val t = recordRejected(key, attempt, outcome.error)
                EffectCasTransition(t.change, EffectCasOutcome.Failure(t.result))
            }
        }

    /**
     * Fence older receipts. Requeue each in-flight cursor with the same effect
     * identity and completed-step lineage unless a newer desired intent supersedes it.
     */
    fun reconnect(newGeneration: Long): EffectCasTransition<EffectCasReconnect> {
        if (newGeneration < generation) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasReconnect.StaleGeneration(generation))
        }
        if (newGeneration == generation) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasReconnect.Unchanged(generation))
        }
        generation = newGeneration
        var requeued = 0
        var superseded = 0
        for (entry in keys.values) {
            val inflight = entry.inflight ?: continue
            entry.inflight = null
            val retained = inflight.retained
            if (entry.pending?.let { it.intent.epoch > retained.intent.epoch } == true) {
                entry.terminalIntent = retained.intent
                entry.terminal =
                    EffectTerminal.Superseded(retained.intent.identity, retained.intent.epoch, null, retained.receipts)
                superseded++
            } else {
                entry.pending = retained.copy(nextAttempt = saturatingIncrement(retained.nextAttempt), readyAt = null)
                requeued++
            }
        }
        return EffectCasTransition(EffectCasChange.STATE, EffectCasReconnect.Advanced(newGeneration, requeued, superseded))
    }

    private sealed interface FailureKind<out A, out E> {
        data class Superseded<A>(val observed: A) : FailureKind<A, Nothing>

        data class Retryable<E>(val error: E, val retryAt: Long) : FailureKind<Nothing, E>

        data class Rejected<E>(val error: E) : FailureKind<Nothing, E>
    }

    private fun finishFailed(
        key: K,
        attempt: EffectAttemptIdentity,
        kind: FailureKind<A, E>,
    ): EffectCasTransition<EffectCasFailure> {
        if (attempt.generation != generation) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasFailure.StaleGeneration(generation))
        }
        val entry = keys[key] ?: return EffectCasTransition(EffectCasChange.NONE, EffectCasFailure.UnknownAttempt)
        val inflight = entry.inflight
        if (inflight == null || inflight.attempt.identity != attempt) {
            return EffectCasTransition(EffectCasChange.NONE, EffectCasFailure.UnknownAttempt)
        }
        entry.inflight = null
        val retained = inflight.retained
        val newerPending = entry.pending?.let { it.intent.epoch > retained.intent.epoch } == true
        val identity = retained.intent.identity
        val epoch = retained.intent.epoch
        return when (kind) {
            is FailureKind.Superseded -> {
                entry.retry = null
                entry.terminalIntent = retained.intent
                entry.terminal = EffectTerminal.Superseded(identity, epoch, kind.observed, retained.receipts)
                EffectCasTransition(EffectCasChange.STATE, EffectCasFailure.Superseded)
            }
            is FailureKind.Retryable ->
                if (!newerPending) {
                    entry.retry = EffectRetry(attempt, kind.error, kind.retryAt)
                    entry.pending =
                        retained.copy(nextAttempt = saturatingIncrement(retained.nextAttempt), readyAt = kind.retryAt)
                    EffectCasTransition(EffectCasChange.STATE, EffectCasFailure.Retrying(kind.retryAt))
                } else {
                    entry.retry = null
                    entry.terminalIntent = retained.intent
                    entry.terminal = EffectTerminal.Superseded(identity, epoch, null, retained.receipts)
                    EffectCasTransition(EffectCasChange.STATE, EffectCasFailure.Superseded)
                }
            is FailureKind.Rejected -> {
                entry.retry = null
                entry.terminalIntent = retained.intent
                entry.terminal = EffectTerminal.Rejected(identity, epoch, kind.error, retained.receipts)
                EffectCasTransition(EffectCasChange.STATE, EffectCasFailure.Rejected)
            }
        }
    }

    /** Full-fidelity image for crash recovery and cross-process persistence (`#lzeffectcasdurable`). */
    fun durableState(): EffectCasDurableState<K, A, P, R, E> =
        EffectCasDurableState(
            generation,
            keys.map { (key, entry) ->
                EffectCasDurableKey(
                    key = key,
                    pending = entry.pending,
                    inflight = entry.inflight?.let { EffectCasDurableInflight(it.retained, it.attempt) },
                    retry = entry.retry,
                    appliedThrough = entry.appliedThrough,
                    terminal = entry.terminal,
                    terminalIntent = entry.terminalIntent,
                )
            },
        )

    companion object {
        /**
         * Restore a core from [durableState] before the runtime becomes live. The
         * restored core is observably identical to the writer: same generation,
         * retained intents, cursors, retry times, receipts, and frontier. A
         * recovering actor then calls [reconnect] with a newer generation to fence
         * the crashed actor and replay its retained work under the same identity.
         *
         * @throws EffectCasHydrateException for a corrupt image; nothing is repaired.
         */
        fun <K : Comparable<K>, A : Any, P : Any, R : Any, E : Any> hydrate(
            state: EffectCasDurableState<K, A, P, R, E>,
        ): EffectCasCore<K, A, P, R, E> {
            fun refuse(error: EffectCasHydrateError): Nothing = throw EffectCasHydrateException(error)

            fun validateRetained(
                key: K,
                retained: EffectPending<K, A, P, R>,
            ) {
                if (retained.intent.key != key) refuse(EffectCasHydrateError.KeyMismatch)
                if (retained.nextStep < 0 || retained.nextStep >= retained.intent.plan.size) {
                    refuse(EffectCasHydrateError.CursorOutOfRange)
                }
            }
            val core = EffectCasCore<K, A, P, R, E>(state.generation)
            for (durable in state.keys) {
                durable.pending?.let { validateRetained(durable.key, it) }
                if (durable.terminalIntent != null && durable.terminalIntent.key != durable.key) {
                    refuse(EffectCasHydrateError.KeyMismatch)
                }
                val inflight =
                    durable.inflight?.let { image ->
                        val retained = image.retained
                        val attempt = image.attempt
                        validateRetained(durable.key, retained)
                        if (attempt.identity.generation > state.generation) refuse(EffectCasHydrateError.FutureGeneration)
                        val matches =
                            attempt.key == durable.key &&
                                attempt.identity.effect == retained.intent.identity &&
                                attempt.identity.epoch == retained.intent.epoch &&
                                attempt.identity.step == retained.nextStep &&
                                attempt.identity.attempt == retained.nextAttempt &&
                                attempt.expected == retained.expected &&
                                attempt.payload == retained.intent.plan.steps[retained.nextStep] &&
                                attempt.priorReceipts == retained.receipts
                        if (!matches) refuse(EffectCasHydrateError.AttemptMismatch)
                        Inflight(retained, attempt)
                    }
                val entry =
                    Entry<K, A, P, R, E>().apply {
                        pending = durable.pending
                        this.inflight = inflight
                        retry = durable.retry
                        appliedThrough = durable.appliedThrough
                        terminal = durable.terminal
                        terminalIntent = durable.terminalIntent
                    }
                if (core.keys.put(durable.key, entry) != null) refuse(EffectCasHydrateError.DuplicateKey)
            }
            return core
        }
    }

    private fun saturatingIncrement(value: Int) = if (value == Int.MAX_VALUE) value else value + 1
}
