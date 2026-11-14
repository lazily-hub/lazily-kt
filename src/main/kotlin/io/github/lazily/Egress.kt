package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reactive egress shells (`#lzegress`) — the [Context], [ThreadSafeContext] and
 * [AsyncContext] flavors of one family over the shared [EgressCore].
 *
 * ```text
 * EgressCore<T>                 sequence, window, watermark, retry, fence
 * ├── EgressCell<T>             Context shell
 * ├── ThreadSafeEgressCell<T>   ThreadSafeContext shell
 * └── AsyncEgressCell<T>        AsyncContext shell
 *           │
 *           └── one attached Effect ──send──▶ transport
 * ```
 *
 * The four projections (`pending`, `inflight`, `ackedThrough`, `retry`) are
 * Computeds over the core. Transport I/O lives in exactly one Effect per
 * attachment ([EgressCell.attachTransport] and its siblings): the Effect observes
 * `pending` and `inflight`, asks the core to claim eligible envelopes, and sends
 * them. The core decides whether a claim, acknowledgement, failure, retry, or
 * reconnect is admissible; every op clears exactly the reader kinds its
 * [EgressChange] names, in one `invalidateSlots` frontier walk.
 *
 * ## Lock discipline (thread-safe and async flavors)
 *
 * A reader's compute runs inside the context lock and then takes the core lock, so
 * every op below scopes its core-lock section to a block that ends before the
 * context is touched — exactly as [ThreadSafeIngressCell] does.
 *
 * ## Only the attachment is async-coloured
 *
 * [AsyncEgressCell]'s delivery-state readers use [AsyncContext.computed]
 * (synchronous compute, async graph) and return plain values like the other two
 * flavors. Only [AsyncEgressCell.attachTransport] is async-coloured: it runs on
 * [AsyncContext.effectAsync] and awaits an [AsyncEgressTransport].
 */

/**
 * The only transport seam egress owns. Durability and framing remain separate
 * adapters around the envelope.
 */
fun interface EgressTransport<T : Any> {
    /**
     * `true` means the transport accepted the send attempt. Domain delivery is
     * proven later by an acknowledgement through the cell's `ack`.
     */
    fun send(envelope: EgressEnvelope<T>): Boolean
}

/** The async-coloured transport seam [AsyncEgressCell.attachTransport] awaits. */
fun interface AsyncEgressTransport<T : Any> {
    suspend fun send(envelope: EgressEnvelope<T>): Boolean
}

/** Single-threaded reactive egress over a [Context]. */
class EgressCell<T : Any>(
    private val ctx: Context,
    generation: Long,
    policy: EgressPolicy = EgressPolicy(),
) {
    private val core = EgressCore<T>(generation, policy)

    private val pendingReader: Computed<List<EgressEnvelope<T>>> = ctx.computed { core.pending() }
    private val inflightReader: Computed<List<EgressEnvelope<T>>> = ctx.computed { core.inflight() }
    private val ackedThroughReader: Computed<EgressReading<Long>> = ctx.computed { EgressReading(core.ackedThrough) }
    private val retryReader: Computed<EgressReading<EgressRetry>> = ctx.computed { EgressReading(core.retry) }

    private fun <R> apply(transition: EgressTransition<R>): R {
        val change = transition.change
        if (!change.isEmpty) {
            val roots = ArrayList<Int>(4)
            if (change.pending) roots += pendingReader.id
            if (change.inflight) roots += inflightReader.id
            if (change.ackedThrough) roots += ackedThroughReader.id
            if (change.retry) roots += retryReader.id
            ctx.invalidateSlots(roots.toIntArray())
        }
        return transition.result
    }

    val generation: Long get() = core.generation

    val nextSequence: Long get() = core.nextSequence

    fun enqueue(payload: T): Long = apply(core.enqueue(payload))

    fun claim(generation: Long): EgressClaim<T> = apply(core.claim(generation))

    fun ack(
        generation: Long,
        through: Long,
    ): EgressAck = apply(core.ack(generation, through))

    fun fail(
        generation: Long,
        sequence: Long,
    ): EgressFailure = apply(core.fail(generation, sequence))

    fun retryNow(
        generation: Long,
        sequence: Long,
    ): EgressRetryAction = apply(core.retryNow(generation, sequence))

    fun reconnect(generation: Long): EgressReconnect = apply(core.reconnect(generation))

    fun pending(): List<EgressEnvelope<T>> = ctx.get(pendingReader)

    fun inflight(): List<EgressEnvelope<T>> = ctx.get(inflightReader)

    fun ackedThrough(): Long? = ctx.get(ackedThroughReader).value

    fun retry(): EgressRetry? = ctx.get(retryReader).value

    fun pendingHandle(): Computed<List<EgressEnvelope<T>>> = pendingReader

    fun inflightHandle(): Computed<List<EgressEnvelope<T>>> = inflightReader

    fun ackedThroughHandle(): Computed<EgressReading<Long>> = ackedThroughReader

    fun retryHandle(): Computed<EgressReading<EgressRetry>> = retryReader

    /**
     * Attach one transport Effect at the current producer generation.
     *
     * A reconnect does not mutate this Effect's captured generation, so the old
     * attachment becomes inert at the core fence; callers attach one replacement
     * Effect for the new transport incarnation.
     */
    fun attachTransport(transport: EgressTransport<T>): Effect {
        val attached = core.generation
        return ctx.effect {
            // `inflight` is a dependency even though the core stays the authority:
            // an acknowledgement reopens the send window and must re-run this same
            // attachment Effect.
            get(inflightReader)
            if (get(pendingReader).isNotEmpty()) {
                while (true) {
                    val claim = claim(attached) as? EgressClaim.Claimed<T> ?: break
                    if (!transport.send(claim.envelope)) {
                        fail(attached, claim.envelope.sequence)
                        break
                    }
                }
            }
            null
        }
    }
}

/** `Send + Sync` reactive egress over a [ThreadSafeContext]. */
class ThreadSafeEgressCell<T : Any>(
    private val ctx: ThreadSafeContext,
    generation: Long,
    policy: EgressPolicy = EgressPolicy(),
) {
    private val lock = ReentrantLock()
    private val core = EgressCore<T>(generation, policy)

    private val pendingReader: ThreadSafeComputed<List<EgressEnvelope<T>>> =
        ctx.computed { lock.withLock { core.pending() } }
    private val inflightReader: ThreadSafeComputed<List<EgressEnvelope<T>>> =
        ctx.computed { lock.withLock { core.inflight() } }
    private val ackedThroughReader: ThreadSafeComputed<EgressReading<Long>> =
        ctx.computed { EgressReading(lock.withLock { core.ackedThrough }) }
    private val retryReader: ThreadSafeComputed<EgressReading<EgressRetry>> =
        ctx.computed { EgressReading(lock.withLock { core.retry }) }

    /** Run [op] under the core lock, then clear its change set with the core lock released. */
    private inline fun <R> mutate(op: EgressCore<T>.() -> EgressTransition<R>): R {
        val transition = lock.withLock { core.op() }
        val change = transition.change
        if (!change.isEmpty) {
            val roots = ArrayList<Int>(4)
            if (change.pending) roots += pendingReader.id
            if (change.inflight) roots += inflightReader.id
            if (change.ackedThrough) roots += ackedThroughReader.id
            if (change.retry) roots += retryReader.id
            ctx.invalidateSlots(roots.toIntArray())
        }
        return transition.result
    }

    val generation: Long get() = lock.withLock { core.generation }

    val nextSequence: Long get() = lock.withLock { core.nextSequence }

    fun enqueue(payload: T): Long = mutate { enqueue(payload) }

    fun claim(generation: Long): EgressClaim<T> = mutate { claim(generation) }

    fun ack(
        generation: Long,
        through: Long,
    ): EgressAck = mutate { ack(generation, through) }

    fun fail(
        generation: Long,
        sequence: Long,
    ): EgressFailure = mutate { fail(generation, sequence) }

    fun retryNow(
        generation: Long,
        sequence: Long,
    ): EgressRetryAction = mutate { retryNow(generation, sequence) }

    fun reconnect(generation: Long): EgressReconnect = mutate { reconnect(generation) }

    fun pending(): List<EgressEnvelope<T>> = ctx.get(pendingReader)

    fun inflight(): List<EgressEnvelope<T>> = ctx.get(inflightReader)

    fun ackedThrough(): Long? = ctx.get(ackedThroughReader).value

    fun retry(): EgressRetry? = ctx.get(retryReader).value

    fun pendingHandle(): ThreadSafeComputed<List<EgressEnvelope<T>>> = pendingReader

    fun inflightHandle(): ThreadSafeComputed<List<EgressEnvelope<T>>> = inflightReader

    fun ackedThroughHandle(): ThreadSafeComputed<EgressReading<Long>> = ackedThroughReader

    fun retryHandle(): ThreadSafeComputed<EgressReading<EgressRetry>> = retryReader

    /** Attach one transport Effect at the current producer generation. */
    fun attachTransport(transport: EgressTransport<T>): ThreadSafeEffectHandle {
        val attached = generation
        return ctx.effect {
            get(inflightReader)
            if (get(pendingReader).isNotEmpty()) {
                while (true) {
                    val claim = claim(attached) as? EgressClaim.Claimed<T> ?: break
                    if (!transport.send(claim.envelope)) {
                        fail(attached, claim.envelope.sequence)
                        break
                    }
                }
            }
            null
        }
    }
}

/**
 * [AsyncContext] reactive egress. Delivery authority and the four readers are
 * synchronous; only [attachTransport] is async-coloured.
 */
class AsyncEgressCell<T : Any>(
    private val ctx: AsyncContext,
    generation: Long,
    policy: EgressPolicy = EgressPolicy(),
) {
    private val lock = ReentrantLock()
    private val core = EgressCore<T>(generation, policy)

    private val pendingReader: AsyncContext.AsyncComputed<List<EgressEnvelope<T>>> =
        ctx.computed { lock.withLock { core.pending() } }
    private val inflightReader: AsyncContext.AsyncComputed<List<EgressEnvelope<T>>> =
        ctx.computed { lock.withLock { core.inflight() } }
    private val ackedThroughReader: AsyncContext.AsyncComputed<EgressReading<Long>> =
        ctx.computed { EgressReading(lock.withLock { core.ackedThrough }) }
    private val retryReader: AsyncContext.AsyncComputed<EgressReading<EgressRetry>> =
        ctx.computed { EgressReading(lock.withLock { core.retry }) }

    private inline fun <R> mutate(op: EgressCore<T>.() -> EgressTransition<R>): R {
        val transition = lock.withLock { core.op() }
        val change = transition.change
        if (!change.isEmpty) {
            val roots = ArrayList<Int>(4)
            if (change.pending) roots += pendingReader.id
            if (change.inflight) roots += inflightReader.id
            if (change.ackedThrough) roots += ackedThroughReader.id
            if (change.retry) roots += retryReader.id
            ctx.invalidateSlots(roots.toIntArray())
        }
        return transition.result
    }

    private fun <V : Any> read(handle: AsyncContext.AsyncComputed<V>): V =
        checkNotNull(ctx.get(handle)) { "synchronous egress projection did not resolve inline" }

    val generation: Long get() = lock.withLock { core.generation }

    val nextSequence: Long get() = lock.withLock { core.nextSequence }

    fun enqueue(payload: T): Long = mutate { enqueue(payload) }

    fun claim(generation: Long): EgressClaim<T> = mutate { claim(generation) }

    fun ack(
        generation: Long,
        through: Long,
    ): EgressAck = mutate { ack(generation, through) }

    fun fail(
        generation: Long,
        sequence: Long,
    ): EgressFailure = mutate { fail(generation, sequence) }

    fun retryNow(
        generation: Long,
        sequence: Long,
    ): EgressRetryAction = mutate { retryNow(generation, sequence) }

    fun reconnect(generation: Long): EgressReconnect = mutate { reconnect(generation) }

    fun pending(): List<EgressEnvelope<T>> = read(pendingReader)

    fun inflight(): List<EgressEnvelope<T>> = read(inflightReader)

    fun ackedThrough(): Long? = read(ackedThroughReader).value

    fun retry(): EgressRetry? = read(retryReader).value

    fun pendingHandle(): AsyncContext.AsyncComputed<List<EgressEnvelope<T>>> = pendingReader

    fun inflightHandle(): AsyncContext.AsyncComputed<List<EgressEnvelope<T>>> = inflightReader

    fun ackedThroughHandle(): AsyncContext.AsyncComputed<EgressReading<Long>> = ackedThroughReader

    fun retryHandle(): AsyncContext.AsyncComputed<EgressReading<EgressRetry>> = retryReader

    /**
     * Attach one async transport Effect at the current producer generation. The
     * readers it observes stay synchronous; the send is awaited.
     */
    fun attachTransport(transport: AsyncEgressTransport<T>): AsyncContext.AsyncEffectHandle {
        val attached = generation
        return ctx.effectAsync {
            get(inflightReader)
            val hasPending = get(pendingReader)?.isNotEmpty() == true
            if (hasPending) {
                while (true) {
                    val claim = claim(attached) as? EgressClaim.Claimed<T> ?: break
                    if (!transport.send(claim.envelope)) {
                        fail(attached, claim.envelope.sequence)
                        break
                    }
                }
            }
            null
        }
    }
}
