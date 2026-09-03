package io.github.lazily

/** A latest projection waiting to be claimed by the durable sink. */
data class LatestDurableDesired<V : Any>(
    val epoch: Long,
    val value: V,
)

/** The exact generation-fenced projection owned by one sink attempt. */
data class LatestDurableEnvelope<K : Any, V : Any>(
    val generation: Long,
    val key: K,
    val epoch: Long,
    val value: V,
)

/** Immutable reader image for one key. */
data class LatestDurableEntry<K : Any, V : Any>(
    val key: K,
    val desired: LatestDurableDesired<V>?,
    val inflight: LatestDurableEnvelope<K, V>?,
    val durableThrough: Long?,
)

sealed interface LatestDurableUpsert {
    data object Accepted : LatestDurableUpsert

    data object Unchanged : LatestDurableUpsert

    data class AlreadyDurable(val durableThrough: Long) : LatestDurableUpsert

    data class StaleEpoch(val current: Long) : LatestDurableUpsert

    data object EpochConflict : LatestDurableUpsert
}

sealed interface LatestDurableClaim<out K : Any, out V : Any> {
    data class Claimed<K : Any, V : Any>(val envelope: LatestDurableEnvelope<K, V>) : LatestDurableClaim<K, V>

    data object Empty : LatestDurableClaim<Nothing, Nothing>

    data object Busy : LatestDurableClaim<Nothing, Nothing>

    data class StaleGeneration(val current: Long) : LatestDurableClaim<Nothing, Nothing>
}

sealed interface LatestDurableAck {
    data class Advanced(val durableThrough: Long) : LatestDurableAck

    data class Unchanged(val durableThrough: Long) : LatestDurableAck

    data object UnknownEpoch : LatestDurableAck

    data class StaleGeneration(val current: Long) : LatestDurableAck
}

sealed interface LatestDurableFailure {
    data object Pending : LatestDurableFailure

    data object Superseded : LatestDurableFailure

    data object UnknownEpoch : LatestDurableFailure

    data class StaleGeneration(val current: Long) : LatestDurableFailure
}

sealed interface LatestDurableReconnect {
    data class Advanced(
        val generation: Long,
        val requeued: Int,
        val superseded: Int,
    ) : LatestDurableReconnect

    data class Unchanged(val generation: Long) : LatestDurableReconnect

    data class StaleGeneration(val current: Long) : LatestDurableReconnect
}

/**
 * Pure keyed latest-durable projection state machine (`#lzlatestdurableprojection`).
 *
 * This is not ordinary FIFO egress: only the latest unclaimed value per key is
 * retained. [claim] moves that value into one generation-fenced in-flight slot.
 * Failure or reconnect returns the flight to pending unless a newer desired epoch
 * already supersedes it; only [ackApplied] advances [durableThrough].
 *
 * The class deliberately performs no graph writes and no I/O. Reactive shells
 * project its immutable entry images; the application-owned sink consumes claimed
 * [LatestDurableEnvelope] values and reports receipts back here.
 */
class LatestDurableProjectionCore<K : Any, V : Any>(initialGeneration: Long) {
    private data class Entry<K : Any, V : Any>(
        var desired: LatestDurableDesired<V>? = null,
        var inflight: LatestDurableEnvelope<K, V>? = null,
        var durableThrough: Long? = null,
    )

    private val entries = LinkedHashMap<K, Entry<K, V>>()

    var generation: Long = initialGeneration
        private set

    private fun entry(key: K): Entry<K, V> = entries.getOrPut(key) { Entry() }

    fun knownKeys(): Set<K> = entries.keys.toSet()

    fun snapshot(key: K): LatestDurableEntry<K, V> {
        val state = entry(key)
        return LatestDurableEntry(key, state.desired, state.inflight, state.durableThrough)
    }

    fun snapshots(): List<LatestDurableEntry<K, V>> = entries.keys.map(::snapshot)

    fun durableThrough(key: K): Long? = entry(key).durableThrough

    fun upsertDesired(
        key: K,
        epoch: Long,
        value: V,
    ): LatestDurableUpsert {
        val state = entry(key)
        state.durableThrough?.let { durable ->
            if (epoch <= durable) return LatestDurableUpsert.AlreadyDurable(durable)
        }
        val newestEpoch = listOfNotNull(state.desired?.epoch, state.inflight?.epoch).maxOrNull()
        if (newestEpoch != null) {
            if (epoch < newestEpoch) return LatestDurableUpsert.StaleEpoch(newestEpoch)
            if (epoch == newestEpoch) {
                val retainedValue =
                    state.desired?.takeIf { it.epoch == epoch }?.value
                        ?: state.inflight?.value
                return if (value == retainedValue) {
                    LatestDurableUpsert.Unchanged
                } else {
                    LatestDurableUpsert.EpochConflict
                }
            }
        }
        state.desired = LatestDurableDesired(epoch, value)
        return LatestDurableUpsert.Accepted
    }

    fun claim(
        key: K,
        generation: Long,
    ): LatestDurableClaim<K, V> {
        if (generation != this.generation) return LatestDurableClaim.StaleGeneration(this.generation)
        val state = entry(key)
        if (state.inflight != null) return LatestDurableClaim.Busy
        val desired = state.desired ?: return LatestDurableClaim.Empty
        val envelope = LatestDurableEnvelope(generation, key, desired.epoch, desired.value)
        state.desired = null
        state.inflight = envelope
        return LatestDurableClaim.Claimed(envelope)
    }

    fun ackApplied(
        key: K,
        generation: Long,
        epoch: Long,
    ): LatestDurableAck {
        if (generation != this.generation) return LatestDurableAck.StaleGeneration(this.generation)
        val state = entry(key)
        val inflight = state.inflight
        if (inflight == null || inflight.epoch != epoch) {
            val durable = state.durableThrough
            return if (durable != null && epoch <= durable) {
                LatestDurableAck.Unchanged(durable)
            } else {
                LatestDurableAck.UnknownEpoch
            }
        }
        state.inflight = null
        val previous = state.durableThrough
        val durable = maxOf(previous ?: epoch, epoch)
        state.durableThrough = durable
        return if (previous == null || epoch > previous) {
            LatestDurableAck.Advanced(durable)
        } else {
            LatestDurableAck.Unchanged(durable)
        }
    }

    fun failRetryable(
        key: K,
        generation: Long,
        epoch: Long,
    ): LatestDurableFailure {
        if (generation != this.generation) return LatestDurableFailure.StaleGeneration(this.generation)
        val state = entry(key)
        val inflight = state.inflight
        if (inflight == null || inflight.epoch != epoch) return LatestDurableFailure.UnknownEpoch
        state.inflight = null
        val desired = state.desired
        if (desired != null && desired.epoch > inflight.epoch) return LatestDurableFailure.Superseded
        state.desired = LatestDurableDesired(inflight.epoch, inflight.value)
        return LatestDurableFailure.Pending
    }

    fun reconnect(newGeneration: Long): LatestDurableReconnect {
        if (newGeneration < generation) return LatestDurableReconnect.StaleGeneration(generation)
        if (newGeneration == generation) return LatestDurableReconnect.Unchanged(generation)
        var requeued = 0
        var superseded = 0
        for (state in entries.values) {
            val inflight = state.inflight ?: continue
            val desired = state.desired
            if (desired != null && desired.epoch > inflight.epoch) {
                superseded++
            } else {
                state.desired = LatestDurableDesired(inflight.epoch, inflight.value)
                requeued++
            }
            state.inflight = null
        }
        generation = newGeneration
        return LatestDurableReconnect.Advanced(newGeneration, requeued, superseded)
    }
}
