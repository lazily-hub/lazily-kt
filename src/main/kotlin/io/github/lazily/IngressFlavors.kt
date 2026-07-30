package io.github.lazily

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The `ThreadSafeContext` and `AsyncContext` flavors of the transport-agnostic
 * reactive ingress family (`#designimplementtransport`).
 *
 * Both wrap the same flavor-neutral [IngressCore], expose the same four reader
 * kinds per keyed scope plus the same three receipt channels, and clear exactly the
 * set the core reports. The family's claim is that all three flavors obey **one**
 * contract, which is why the conformance corpus replays against each of them
 * through one runner.
 *
 * ## Lock discipline
 *
 * A reader's compute closure runs *inside* the context lock and then takes the core
 * lock, so an op that invalidated while still holding the core lock would invert the
 * order and deadlock against a concurrent reader. Every op below therefore scopes
 * its core-lock section to a block that ends before the context is touched — which
 * is why `apply` is a separate step taking an already-computed [IngressChange]
 * rather than something an op does inline. Reader minting takes the context lock
 * first and the core lock second, matching a read.
 *
 * ## Multi-root invalidation is one frontier walk
 *
 * One admission can dirty a scope's value, readiness, authority, and retry plus a
 * receipt channel. Clearing them one at a time is one frontier walk each, and a
 * reader can interleave and observe the new value with the old authority —
 * precisely the partial fan-out a generation handoff must never expose. Both
 * flavors hand every root to a single `invalidateSlots`.
 *
 * ## Admission is not async-coloured
 *
 * This is the finding, not an oversight. Whether an envelope is admissible is a
 * function of the scope's fence, watermark, reorder buffer, and observed clock —
 * state the graph does not own and nothing has to await. The async reader kinds
 * therefore use [AsyncContext.computed] (synchronous compute, async graph) and
 * return plain values exactly like the other two flavors; there is no `settle` step
 * anywhere in the primitive. Awaiting belongs to the transport, and the transport is
 * outside the primitive by construction.
 */

/** The four reader kinds one keyed scope exposes on a [ThreadSafeContext] graph. */
class ThreadSafeIngressScopeReaders<T : Any> internal constructor(
    val value: ThreadSafeComputed<IngressReading<T>>,
    val readiness: ThreadSafeComputed<IngressReadiness>,
    val authority: ThreadSafeComputed<IngressReading<IngressAuthority>>,
    val retry: ThreadSafeComputed<IngressReading<IngressRetry>>,
)

/**
 * `Send + Sync` keyed, lifecycle-scoped reactive ingress: one admission plane per
 * key, with readiness, authority, and retry as derives rather than calls.
 */
class ThreadSafeIngressCell<K : Any, T : Any>(
    private val ctx: ThreadSafeContext,
    policy: IngressPolicy = IngressPolicy(),
    mergePolicy: MergePolicy<T> = keepLatest(),
    kind: IngressTransportKind = IngressTransportKind.EventChannel,
    pollInterval: Long = 25,
) {
    private val lock = ReentrantLock()
    private val core = IngressCore<K, T>(policy, mergePolicy)
    private val scopeReaders = HashMap<K, ThreadSafeIngressScopeReaders<T>>()

    private val acceptedReader = receiptReader(IngressReceiptChannel.Accepted)
    private val droppedReader = receiptReader(IngressReceiptChannel.Dropped)
    private val errorsReader = receiptReader(IngressReceiptChannel.Error)

    private val transportKindCell = ctx.source(kind)
    private val pollIntervalCell = ctx.source(pollInterval)
    private val scheduleReader: ThreadSafeComputed<IngressSchedule> =
        ctx.computed {
            IngressSchedule.forKind(get(transportKindCell), get(pollIntervalCell))
        }

    private fun receiptReader(
        channel: IngressReceiptChannel,
    ): ThreadSafeComputed<List<IngressReceipt<K>>> =
        ThreadSafeComputed(ctx.slotAny(memo = true) { lock.withLock { core.receipts(channel) } })

    /**
     * Mint (or return) one scope's four readers. Minted **off** the core lock: a read
     * holds the context lock before it enters the compute and takes the core lock, so
     * the reverse order here would deadlock with a concurrent read.
     */
    fun readers(key: K): ThreadSafeIngressScopeReaders<T> {
        lock.withLock { scopeReaders[key] }?.let { return it }
        val minted =
            ThreadSafeIngressScopeReaders<T>(
                value =
                    ThreadSafeComputed(
                        ctx.slotAny(memo = true) {
                            IngressReading(lock.withLock { core.peek(key) })
                        },
                    ),
                readiness =
                    ThreadSafeComputed(
                        ctx.slotAny(memo = true) { lock.withLock { core.readiness(key) } },
                    ),
                authority =
                    ThreadSafeComputed(
                        ctx.slotAny(memo = true) {
                            IngressReading(lock.withLock { core.authority(key) })
                        },
                    ),
                retry =
                    ThreadSafeComputed(
                        ctx.slotAny(memo = true) {
                            IngressReading(lock.withLock { core.retry(key) })
                        },
                    ),
            )
        return lock.withLock { scopeReaders.getOrPut(key) { minted } }
    }

    /** Clear exactly the reported set, in one lock-held frontier walk. */
    private fun apply(change: IngressChange<K>) {
        if (change.isEmpty) return
        val roots = ArrayList<Int>(change.scopes.size * 4 + 3)
        for ((key, scopeChange) in change.scopes) {
            val reader = readers(key)
            if (scopeChange.value) roots += reader.value.id
            if (scopeChange.readiness) roots += reader.readiness.id
            if (scopeChange.authority) roots += reader.authority.id
            if (scopeChange.retry) roots += reader.retry.id
        }
        if (change.acceptedReceipts) roots += acceptedReader.id
        if (change.droppedReceipts) roots += droppedReader.id
        if (change.errorReceipts) roots += errorsReader.id
        if (roots.isNotEmpty()) ctx.invalidateSlots(roots.toIntArray())
    }

    // -- Mutators ----------------------------------------------------------

    /** Open (or reopen) a keyed scope at [generation]. */
    fun open(key: K, generation: Long) = apply(lock.withLock { core.open(key, generation) })

    /** Admit one decoded envelope. */
    fun admit(envelope: IngressEnvelope<K, T>): IngressAdmission {
        val (change, admission) = lock.withLock { core.admit(envelope) }
        apply(change)
        return admission
    }

    /** Suspend a scope, retaining its window and watermark. */
    fun suspend(key: K): ReplayRequest? {
        val (change, request) = lock.withLock { core.suspend(key) }
        apply(change)
        return request
    }

    /** Reconnect a scope at [generation], clearing its error streak. */
    fun reconnect(key: K, generation: Long): ReplayRequest {
        val (change, request) = lock.withLock { core.reconnect(key, generation) }
        apply(change)
        return request
    }

    /** Close a scope. */
    fun close(key: K) = apply(lock.withLock { core.close(key) })

    /** Record a transport/decode failure, deepening the scope's backoff. */
    fun fail(key: K, error: IngressError) = apply(lock.withLock { core.fail(key, error) })

    /** Advance logical time. */
    fun tick(now: Long) = apply(lock.withLock { core.tick(now) })

    /** Drain a scope's coalesced window. */
    fun drain(key: K): T? {
        val (change, value) = lock.withLock { core.drain(key) }
        apply(change)
        return value
    }

    /** Admit a decoded batch, then replay any gap the algebra still reports. */
    fun pump(transport: IngressTransport<K, T>): List<IngressAdmission> {
        val batch = transport.drain()
        val outcomes = ArrayList<IngressAdmission>(batch.size)
        val touched = LinkedHashSet<K>()
        for (envelope in batch) {
            touched += envelope.key
            outcomes += admit(envelope)
        }
        for (key in touched) {
            val view = lock.withLock { core.view(key) } ?: continue
            if (view.hasGap) {
                transport.requestReplay(key, ReplayRequest(view.generation, view.resumeFrom))
            }
        }
        return outcomes
    }

    // -- Reactive reads ----------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <V : Any> reading(handle: ThreadSafeComputed<IngressReading<V>>): V? =
        (ctx.getSlotAny(handle.id) as IngressReading<V>).value

    /** The coalesced window awaiting drain. */
    fun value(key: K): T? = reading(readers(key).value)

    /** Derived readiness. */
    fun readiness(key: K): IngressReadiness =
        ctx.getSlotAny(readers(key).readiness.id) as IngressReadiness

    /** Derived authority; `null` for a closed or unknown scope. */
    fun authority(key: K): IngressAuthority? = reading(readers(key).authority)

    /** Derived retry decision; `null` while no error is outstanding. */
    fun retry(key: K): IngressRetry? = reading(readers(key).retry)

    @Suppress("UNCHECKED_CAST")
    private fun receipts(
        handle: ThreadSafeComputed<List<IngressReceipt<K>>>,
    ): List<IngressReceipt<K>> = ctx.getSlotAny(handle.id) as List<IngressReceipt<K>>

    /** Reactive read: accepted receipts, oldest first. */
    fun accepted(): List<IngressReceipt<K>> = receipts(acceptedReader)

    /** Reactive read: dropped receipts, oldest first. */
    fun dropped(): List<IngressReceipt<K>> = receipts(droppedReader)

    /** Reactive read: error receipts, oldest first. */
    fun errors(): List<IngressReceipt<K>> = receipts(errorsReader)

    /** Reactive read: the derived delivery schedule. */
    fun schedule(): IngressSchedule = ctx.get(scheduleReader)

    fun acceptedHandle(): ThreadSafeComputed<List<IngressReceipt<K>>> = acceptedReader

    fun droppedHandle(): ThreadSafeComputed<List<IngressReceipt<K>>> = droppedReader

    fun errorsHandle(): ThreadSafeComputed<List<IngressReceipt<K>>> = errorsReader

    fun scheduleHandle(): ThreadSafeComputed<IngressSchedule> = scheduleReader

    /** Retune the transport live. */
    fun setTransport(kind: IngressTransportKind) = ctx.set(transportKindCell, kind)

    /** Retune the poll bound live. */
    fun setPollInterval(interval: Long) = ctx.set(pollIntervalCell, interval)

    // -- Non-reactive projections -----------------------------------------

    /** Non-reactive projection of a scope, for assertions and diagnostics. */
    fun view(key: K): IngressScopeView? = lock.withLock { core.view(key) }

    /** The bounds in force. */
    fun policy(): IngressPolicy = core.policy

    /** Every known scope key. */
    fun scopeKeys(): List<K> = lock.withLock { core.scopeKeys() }
}

/** The four reader kinds one keyed scope exposes on an [AsyncContext] graph. */
class AsyncIngressScopeReaders<T : Any> internal constructor(
    val value: AsyncContext.AsyncComputed<IngressReading<T>>,
    val readiness: AsyncContext.AsyncComputed<IngressReadiness>,
    val authority: AsyncContext.AsyncComputed<IngressReading<IngressAuthority>>,
    val retry: AsyncContext.AsyncComputed<IngressReading<IngressRetry>>,
)

/**
 * `AsyncContext` keyed, lifecycle-scoped reactive ingress. The reader kinds are
 * synchronous computes on the async graph, because admission is not async-coloured.
 */
class AsyncIngressCell<K : Any, T : Any>(
    private val ctx: AsyncContext,
    policy: IngressPolicy = IngressPolicy(),
    mergePolicy: MergePolicy<T> = keepLatest(),
    kind: IngressTransportKind = IngressTransportKind.EventChannel,
    pollInterval: Long = 25,
) {
    private val lock = ReentrantLock()
    private val core = IngressCore<K, T>(policy, mergePolicy)
    private val scopeReaders = HashMap<K, AsyncIngressScopeReaders<T>>()

    private val acceptedReader = receiptReader(IngressReceiptChannel.Accepted)
    private val droppedReader = receiptReader(IngressReceiptChannel.Dropped)
    private val errorsReader = receiptReader(IngressReceiptChannel.Error)

    private val transportKindCell = ctx.source(kind)
    private val pollIntervalCell = ctx.source(pollInterval)
    private val scheduleReader: AsyncContext.AsyncComputed<IngressSchedule> =
        ctx.computed {
            IngressSchedule.forKind(get(transportKindCell), get(pollIntervalCell))
        }

    private fun receiptReader(
        channel: IngressReceiptChannel,
    ): AsyncContext.AsyncComputed<List<IngressReceipt<K>>> =
        ctx.computed { lock.withLock { core.receipts(channel) } }

    /** Mint (or return) one scope's four readers, off the core lock. */
    fun readers(key: K): AsyncIngressScopeReaders<T> {
        lock.withLock { scopeReaders[key] }?.let { return it }
        val minted =
            AsyncIngressScopeReaders<T>(
                value = ctx.computed { IngressReading(lock.withLock { core.peek(key) }) },
                readiness = ctx.computed { lock.withLock { core.readiness(key) } },
                authority = ctx.computed { IngressReading(lock.withLock { core.authority(key) }) },
                retry = ctx.computed { IngressReading(lock.withLock { core.retry(key) }) },
            )
        return lock.withLock { scopeReaders.getOrPut(key) { minted } }
    }

    /** Clear exactly the reported set, in one frontier walk. */
    private fun apply(change: IngressChange<K>) {
        if (change.isEmpty) return
        val roots = ArrayList<Int>(change.scopes.size * 4 + 3)
        for ((key, scopeChange) in change.scopes) {
            val reader = readers(key)
            if (scopeChange.value) roots += reader.value.id
            if (scopeChange.readiness) roots += reader.readiness.id
            if (scopeChange.authority) roots += reader.authority.id
            if (scopeChange.retry) roots += reader.retry.id
        }
        if (change.acceptedReceipts) roots += acceptedReader.id
        if (change.droppedReceipts) roots += droppedReader.id
        if (change.errorReceipts) roots += errorsReader.id
        if (roots.isNotEmpty()) ctx.invalidateSlots(roots.toIntArray())
    }

    // -- Mutators ----------------------------------------------------------

    /** Open (or reopen) a keyed scope at [generation]. */
    fun open(key: K, generation: Long) = apply(lock.withLock { core.open(key, generation) })

    /** Admit one decoded envelope. */
    fun admit(envelope: IngressEnvelope<K, T>): IngressAdmission {
        val (change, admission) = lock.withLock { core.admit(envelope) }
        apply(change)
        return admission
    }

    /** Suspend a scope, retaining its window and watermark. */
    fun suspend(key: K): ReplayRequest? {
        val (change, request) = lock.withLock { core.suspend(key) }
        apply(change)
        return request
    }

    /** Reconnect a scope at [generation], clearing its error streak. */
    fun reconnect(key: K, generation: Long): ReplayRequest {
        val (change, request) = lock.withLock { core.reconnect(key, generation) }
        apply(change)
        return request
    }

    /** Close a scope. */
    fun close(key: K) = apply(lock.withLock { core.close(key) })

    /** Record a transport/decode failure, deepening the scope's backoff. */
    fun fail(key: K, error: IngressError) = apply(lock.withLock { core.fail(key, error) })

    /** Advance logical time. */
    fun tick(now: Long) = apply(lock.withLock { core.tick(now) })

    /** Drain a scope's coalesced window. */
    fun drain(key: K): T? {
        val (change, value) = lock.withLock { core.drain(key) }
        apply(change)
        return value
    }

    /** Admit a decoded batch, then replay any gap the algebra still reports. */
    fun pump(transport: IngressTransport<K, T>): List<IngressAdmission> {
        val batch = transport.drain()
        val outcomes = ArrayList<IngressAdmission>(batch.size)
        val touched = LinkedHashSet<K>()
        for (envelope in batch) {
            touched += envelope.key
            outcomes += admit(envelope)
        }
        for (key in touched) {
            val view = lock.withLock { core.view(key) } ?: continue
            if (view.hasGap) {
                transport.requestReplay(key, ReplayRequest(view.generation, view.resumeFrom))
            }
        }
        return outcomes
    }

    // -- Reactive reads ----------------------------------------------------
    //
    // The reader kinds resolve inline (synchronous compute on the async graph), so
    // these return plain values. A caller composing an async derived node uses the
    // `AsyncComputeContext` overloads so the dependency edge is registered.

    /** The coalesced window awaiting drain. */
    fun value(key: K): T? = requireNotNull(ctx.get(readers(key).value)).value

    fun value(key: K, compute: AsyncComputeContext): T? =
        requireNotNull(compute.get(readers(key).value)).value

    /** Derived readiness. */
    fun readiness(key: K): IngressReadiness = requireNotNull(ctx.get(readers(key).readiness))

    fun readiness(key: K, compute: AsyncComputeContext): IngressReadiness =
        requireNotNull(compute.get(readers(key).readiness))

    /** Derived authority; `null` for a closed or unknown scope. */
    fun authority(key: K): IngressAuthority? = requireNotNull(ctx.get(readers(key).authority)).value

    fun authority(key: K, compute: AsyncComputeContext): IngressAuthority? =
        requireNotNull(compute.get(readers(key).authority)).value

    /** Derived retry decision; `null` while no error is outstanding. */
    fun retry(key: K): IngressRetry? = requireNotNull(ctx.get(readers(key).retry)).value

    fun retry(key: K, compute: AsyncComputeContext): IngressRetry? =
        requireNotNull(compute.get(readers(key).retry)).value

    /** Reactive read: accepted receipts, oldest first. */
    fun accepted(): List<IngressReceipt<K>> = requireNotNull(ctx.get(acceptedReader))

    /** Reactive read: dropped receipts, oldest first. */
    fun dropped(): List<IngressReceipt<K>> = requireNotNull(ctx.get(droppedReader))

    /** Reactive read: error receipts, oldest first. */
    fun errors(): List<IngressReceipt<K>> = requireNotNull(ctx.get(errorsReader))

    /** Reactive read: the derived delivery schedule. */
    fun schedule(): IngressSchedule = requireNotNull(ctx.get(scheduleReader))

    fun acceptedHandle(): AsyncContext.AsyncComputed<List<IngressReceipt<K>>> = acceptedReader

    fun droppedHandle(): AsyncContext.AsyncComputed<List<IngressReceipt<K>>> = droppedReader

    fun errorsHandle(): AsyncContext.AsyncComputed<List<IngressReceipt<K>>> = errorsReader

    fun scheduleHandle(): AsyncContext.AsyncComputed<IngressSchedule> = scheduleReader

    /** Retune the transport live. */
    fun setTransport(kind: IngressTransportKind) = ctx.set(transportKindCell, kind)

    /** Retune the poll bound live. */
    fun setPollInterval(interval: Long) = ctx.set(pollIntervalCell, interval)

    // -- Non-reactive projections -----------------------------------------

    /** Non-reactive projection of a scope, for assertions and diagnostics. */
    fun view(key: K): IngressScopeView? = lock.withLock { core.view(key) }

    /** The bounds in force. */
    fun policy(): IngressPolicy = core.policy

    /** Every known scope key. */
    fun scopeKeys(): List<K> = lock.withLock { core.scopeKeys() }
}
