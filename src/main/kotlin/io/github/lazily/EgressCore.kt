package io.github.lazily

/**
 * Transport-agnostic reactive egress core (`#lzegress`).
 *
 * The send-side mirror of [IngressCore]. [EgressCore] owns delivery authority:
 * sequence assignment, the unacknowledged window, a monotone cumulative
 * acknowledgement watermark, the retry budget, and the producer-generation fence.
 * It owns no graph handles and performs no I/O. `DurableOutbox`-style persistence
 * and `RelayCell`-style conflation/backpressure stay separate layers ahead of or
 * behind it; see `lazily-spec/docs/transport-egress.md`.
 *
 * Every transition returns the reader kinds it changed as an [EgressChange], so a
 * shell ([EgressCell], [ThreadSafeEgressCell], [AsyncEgressCell]) clears exactly
 * that set. A no-op or stale-generation transition reports [EgressChange.NONE] and
 * invalidates nothing.
 */

/** Egress policy. [retryBudget] counts retries after the first attempt. */
data class EgressPolicy(
    val inflightLimit: Int = 64,
    val retryBudget: Int = 3,
    val retryBase: Long = 10,
    val retryCeiling: Long = 1_000,
) {
    /** The first configuration defect, or `null` when the policy is admissible. */
    fun validate(): EgressConfigError? =
        when {
            inflightLimit <= 0 -> EgressConfigError.ZeroInflightLimit
            retryBase <= 0 -> EgressConfigError.ZeroRetryBase
            retryCeiling < retryBase -> EgressConfigError.RetryCeilingBelowBase
            retryBudget < 0 -> EgressConfigError.NegativeRetryBudget
            else -> null
        }
}

/** Why an [EgressPolicy] cannot construct an [EgressCore]. */
enum class EgressConfigError {
    ZeroInflightLimit,
    ZeroRetryBase,
    RetryCeilingBelowBase,

    /** Kotlin has no unsigned budget type; a negative budget is refused rather than wrapped. */
    NegativeRetryBudget,
}

/** Thrown by the [EgressCore] constructor for an inadmissible [EgressPolicy]. */
class EgressConfigException(
    val error: EgressConfigError,
) : IllegalArgumentException("invalid egress policy: $error")

/**
 * One send attempt. The `(generation, sequence)` pair is the transport idempotency
 * identity.
 */
data class EgressEnvelope<T : Any>(
    val generation: Long,
    val sequence: Long,
    val attempt: Int,
    val payload: T,
)

/** Retry evidence for the most recent failed send. */
data class EgressRetry(
    val sequence: Long,
    val attempt: Int,
    val backoff: Long,
    val exhausted: Boolean,
)

/**
 * The Kotlin reactive kernel's `Computed<T : Any>` cannot carry `null`, so the
 * optional reader kinds (`acked_through`, `retry`) project this one-field holder,
 * exactly as [IngressReading] does for ingress. Equality is structural.
 */
data class EgressReading<V : Any>(
    val value: V?,
)

sealed interface EgressClaim<out T : Any> {
    data class Claimed<T : Any>(val envelope: EgressEnvelope<T>) : EgressClaim<T>

    data object Empty : EgressClaim<Nothing>

    data object WindowFull : EgressClaim<Nothing>

    data class StaleGeneration(val current: Long) : EgressClaim<Nothing>
}

sealed interface EgressAck {
    data class Advanced(val through: Long) : EgressAck

    data class Unchanged(val through: Long?) : EgressAck

    /**
     * Refused: `through` names a sequence this producer never assigned
     * (`through >= nextSequence`). Nothing changes and nothing is invalidated —
     * acknowledging unsent records would let the watermark run ahead of the
     * sequence space and silently prune future enqueues.
     */
    data class BeyondSent(val nextSequence: Long) : EgressAck

    data class StaleGeneration(val current: Long) : EgressAck
}

sealed interface EgressFailure {
    data class Retrying(val retry: EgressRetry) : EgressFailure

    data class Exhausted(val retry: EgressRetry) : EgressFailure

    data object UnknownSequence : EgressFailure

    data class StaleGeneration(val current: Long) : EgressFailure
}

sealed interface EgressRetryAction {
    data class Scheduled(val sequence: Long) : EgressRetryAction

    data object UnknownSequence : EgressRetryAction

    data class StaleGeneration(val current: Long) : EgressRetryAction
}

sealed interface EgressReconnect {
    data class Advanced(val generation: Long, val replayed: Int) : EgressReconnect

    data class Unchanged(val generation: Long) : EgressReconnect

    data class StaleGeneration(val current: Long) : EgressReconnect
}

/** Reader-kind invalidations emitted by one transition. */
data class EgressChange(
    val pending: Boolean = false,
    val inflight: Boolean = false,
    val ackedThrough: Boolean = false,
    val retry: Boolean = false,
) {
    val isEmpty: Boolean get() = !pending && !inflight && !ackedThrough && !retry

    companion object {
        val NONE = EgressChange()
    }
}

/** One transition's reader-kind change plus its returned outcome. */
data class EgressTransition<out R>(
    val change: EgressChange,
    val result: R,
)

/** Pure egress delivery authority. Not thread-safe; shells own synchronization. */
class EgressCore<T : Any>(
    generation: Long,
    val policy: EgressPolicy = EgressPolicy(),
) {
    private class Record<T : Any>(
        var generation: Long,
        val sequence: Long,
        var attempt: Int,
        val payload: T,
    ) {
        fun envelope() = EgressEnvelope(generation, sequence, attempt, payload)
    }

    init {
        policy.validate()?.let { throw EgressConfigException(it) }
    }

    /** The current producer incarnation. */
    var generation: Long = generation
        private set

    /** The sequence the next [enqueue] assigns. */
    var nextSequence: Long = 0
        private set

    /** The cumulative acknowledgement watermark, `null` before the first ACK. */
    var ackedThrough: Long? = null
        private set

    /** The most recent failure's retry evidence. */
    var retry: EgressRetry? = null
        private set

    private val pendingRecords = ArrayDeque<Record<T>>()
    private val inflightRecords = java.util.TreeMap<Long, Record<T>>()
    private val failedRecords = java.util.TreeMap<Long, Record<T>>()

    fun pending(): List<EgressEnvelope<T>> = pendingRecords.map { it.envelope() }

    fun inflight(): List<EgressEnvelope<T>> = inflightRecords.values.map { it.envelope() }

    /** Assign the next sequence and place the value in the pending projection. */
    fun enqueue(payload: T): EgressTransition<Long> {
        val sequence = nextSequence
        nextSequence = saturatingIncrement(nextSequence)
        pendingRecords.addLast(Record(generation, sequence, 0, payload))
        return EgressTransition(EgressChange(pending = true), sequence)
    }

    /**
     * Move one pending value into the unacknowledged window.
     *
     * The caller supplies the generation captured by its transport attachment. An
     * attachment from a superseded incarnation cannot claim or replay.
     */
    fun claim(generation: Long): EgressTransition<EgressClaim<T>> {
        if (generation != this.generation) {
            return EgressTransition(EgressChange.NONE, EgressClaim.StaleGeneration(this.generation))
        }
        if (inflightRecords.size >= policy.inflightLimit) {
            return EgressTransition(EgressChange.NONE, EgressClaim.WindowFull)
        }
        val record = pendingRecords.removeFirstOrNull()
            ?: return EgressTransition(EgressChange.NONE, EgressClaim.Empty)
        record.generation = this.generation
        record.attempt = saturatingIncrement(record.attempt)
        val envelope = record.envelope()
        inflightRecords[record.sequence] = record
        val retryChanged = retry?.sequence == envelope.sequence
        if (retryChanged) retry = null
        return EgressTransition(
            EgressChange(pending = true, inflight = true, retry = retryChanged),
            EgressClaim.Claimed(envelope),
        )
    }

    /** Advance the acknowledgement watermark and prune acknowledged records. */
    fun ack(
        generation: Long,
        through: Long,
    ): EgressTransition<EgressAck> {
        if (generation != this.generation) {
            return EgressTransition(EgressChange.NONE, EgressAck.StaleGeneration(this.generation))
        }
        if (through >= nextSequence) {
            return EgressTransition(EgressChange.NONE, EgressAck.BeyondSent(nextSequence))
        }
        val current = ackedThrough
        if (current != null && through <= current) {
            return EgressTransition(EgressChange.NONE, EgressAck.Unchanged(current))
        }
        val inflightBefore = inflightRecords.size
        val pendingBefore = pendingRecords.size
        ackedThrough = through
        inflightRecords.headMap(through, true).clear()
        failedRecords.headMap(through, true).clear()
        pendingRecords.removeAll { it.sequence <= through }
        val retryChanged = retry?.let { it.sequence <= through } == true
        if (retryChanged) retry = null
        return EgressTransition(
            EgressChange(
                pending = pendingRecords.size != pendingBefore,
                inflight = inflightRecords.size != inflightBefore,
                ackedThrough = true,
                retry = retryChanged,
            ),
            EgressAck.Advanced(through),
        )
    }

    /** Park a failed in-flight record while budget remains; exhaustion is terminal. */
    fun fail(
        generation: Long,
        sequence: Long,
    ): EgressTransition<EgressFailure> {
        if (generation != this.generation) {
            return EgressTransition(EgressChange.NONE, EgressFailure.StaleGeneration(this.generation))
        }
        val record = inflightRecords.remove(sequence)
            ?: return EgressTransition(EgressChange.NONE, EgressFailure.UnknownSequence)
        val exhausted = record.attempt > policy.retryBudget
        val evidence = EgressRetry(sequence, record.attempt, backoff(record.attempt), exhausted)
        retry = evidence
        if (!exhausted) failedRecords[sequence] = record
        return EgressTransition(
            EgressChange(inflight = true, retry = true),
            if (exhausted) EgressFailure.Exhausted(evidence) else EgressFailure.Retrying(evidence),
        )
    }

    /** Make a parked failed record eligible again once its derived backoff elapsed. */
    fun retryNow(
        generation: Long,
        sequence: Long,
    ): EgressTransition<EgressRetryAction> {
        if (generation != this.generation) {
            return EgressTransition(EgressChange.NONE, EgressRetryAction.StaleGeneration(this.generation))
        }
        val record = failedRecords.remove(sequence)
            ?: return EgressTransition(EgressChange.NONE, EgressRetryAction.UnknownSequence)
        insertPendingOrdered(record)
        val retryChanged = retry?.sequence == sequence
        if (retryChanged) retry = null
        return EgressTransition(
            EgressChange(pending = true, retry = retryChanged),
            EgressRetryAction.Scheduled(sequence),
        )
    }

    /**
     * Fence the previous attachment and replay every unacknowledged in-flight or
     * retry-parked record through the new generation. The ACK watermark and the
     * sequence space survive; only the producer incarnation changes.
     */
    fun reconnect(generation: Long): EgressTransition<EgressReconnect> {
        if (generation < this.generation) {
            return EgressTransition(EgressChange.NONE, EgressReconnect.StaleGeneration(this.generation))
        }
        if (generation == this.generation) {
            return EgressTransition(EgressChange.NONE, EgressReconnect.Unchanged(this.generation))
        }
        this.generation = generation
        val replayed = inflightRecords.size + failedRecords.size
        for (record in inflightRecords.values) insertPendingOrdered(record)
        inflightRecords.clear()
        for (record in failedRecords.values) insertPendingOrdered(record)
        failedRecords.clear()
        for (record in pendingRecords) record.generation = generation
        val retryChanged = retry != null
        retry = null
        // Every pending envelope was rewritten to the new generation, so the
        // `pending` projection changed whenever anything is pending — not only
        // when in-flight or parked records were replayed into it.
        val pendingChanged = replayed > 0 || pendingRecords.isNotEmpty()
        return EgressTransition(
            EgressChange(pending = pendingChanged, inflight = replayed > 0, retry = retryChanged),
            EgressReconnect.Advanced(generation, replayed),
        )
    }

    private fun insertPendingOrdered(record: Record<T>) {
        val position = pendingRecords.indexOfFirst { it.sequence > record.sequence }
        if (position < 0) pendingRecords.addLast(record) else pendingRecords.add(position, record)
    }

    /** `min(retryCeiling, retryBase * 2^(attempt - 1))`, saturating. */
    private fun backoff(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 62)
        val factor = 1L shl shift
        val scaled =
            if (policy.retryBase > Long.MAX_VALUE / factor) Long.MAX_VALUE else policy.retryBase * factor
        return minOf(scaled, policy.retryCeiling)
    }

    private fun saturatingIncrement(value: Long) = if (value == Long.MAX_VALUE) value else value + 1

    private fun saturatingIncrement(value: Int) = if (value == Int.MAX_VALUE) value else value + 1
}
