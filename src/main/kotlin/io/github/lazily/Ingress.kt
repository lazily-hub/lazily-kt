package io.github.lazily

/**
 * `IngressCell` — the single-threaded flavor of the transport-agnostic reactive
 * ingress family (`#designimplementtransport`).
 *
 * The admission algebra lives in the flavor-neutral [IngressCore]; this shell adds
 * only the reactivity — four guarded computeds per keyed scope plus three receipt
 * readers and a derived schedule, minted on *this* context's graph.
 *
 * ## Readiness, authority, and retry are derives, not refresh calls
 *
 * The point of the family: nothing here polls a connection to find out whether it
 * is healthy. [readiness], [authority], and [retry] are computeds over scope state,
 * so a consumer that reads readiness is a graph dependent of exactly the
 * transitions that can change it — and a transition that cannot (a buffered
 * out-of-order envelope, a tick inside the freshness horizon) invalidates nothing.
 * [IngressCore] returns the invalidation set for every transition, and this shell
 * clears precisely that set.
 *
 * ## Why four reader kinds per scope and not one
 *
 * Collapsing them into one reader would make an error deepen a backoff *and*
 * re-render a value that did not change. The four boundaries are distinct in the
 * algebra ([IngressScopeChange]), so they are distinct here.
 *
 * There is **no observer registry** anywhere in this family: invalidation is a
 * graph write against reader ids, and anything that survived an invalidation would
 * not be a graph edge (`#lzdartobservercow`).
 */

/** The four reader kinds one keyed scope exposes on a [Context] graph. */
class IngressScopeReaders<T : Any> internal constructor(
    val value: Computed<IngressReading<T>>,
    val readiness: Computed<IngressReadiness>,
    val authority: Computed<IngressReading<IngressAuthority>>,
    val retry: Computed<IngressReading<IngressRetry>>,
)

/**
 * A keyed, lifecycle-scoped reactive ingress: one admission plane per key, with
 * readiness, authority, and retry as derives rather than calls.
 *
 * Construction validates [IngressPolicy.overflow] against the merge algebra the way
 * [RelayCell] does — `Conflate` bounds nothing for a non-conflating `⊕` — and
 * throws [IngressConfigException] rather than silently accepting an unbounded plane.
 */
class IngressCell<K : Any, T : Any>(
    private val ctx: Context,
    policy: IngressPolicy = IngressPolicy(),
    mergePolicy: MergePolicy<T> = keepLatest(),
    kind: IngressTransportKind = IngressTransportKind.EventChannel,
    pollInterval: Long = 25,
) {
    private val core = IngressCore<K, T>(policy, mergePolicy)
    private val scopeReaders = HashMap<K, IngressScopeReaders<T>>()

    private val acceptedReader = receiptReader(IngressReceiptChannel.Accepted)
    private val droppedReader = receiptReader(IngressReceiptChannel.Dropped)
    private val errorsReader = receiptReader(IngressReceiptChannel.Error)

    private val transportKindCell = ctx.source(kind)
    private val pollIntervalCell = ctx.source(pollInterval)
    private val scheduleReader: Computed<IngressSchedule> =
        ctx.computed {
            IngressSchedule.forKind(get(transportKindCell), get(pollIntervalCell))
        }

    private fun receiptReader(channel: IngressReceiptChannel): Computed<List<IngressReceipt<K>>> =
        Computed(ctx.slotAny { core.receipts(channel) })

    /**
     * Mint (or return) one scope's four readers. Idempotent, so a consumer may hold
     * a handle for a key that has not opened yet — an unknown scope reads
     * `Unknown`/`null` rather than erroring.
     */
    fun readers(key: K): IngressScopeReaders<T> =
        scopeReaders.getOrPut(key) {
            IngressScopeReaders(
                value = Computed(ctx.slotAny { IngressReading(core.peek(key)) }),
                readiness = Computed(ctx.slotAny { core.readiness(key) }),
                authority = Computed(ctx.slotAny { IngressReading(core.authority(key)) }),
                retry = Computed(ctx.slotAny { IngressReading(core.retry(key)) }),
            )
        }

    /**
     * Apply one core-reported invalidation set. Every affected reader is cleared in
     * a **single** frontier walk, so no reader observes a partial fan-out — a
     * generation handoff must not be visible as "new value, old authority".
     */
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
    fun open(
        key: K,
        generation: Long,
    ) = apply(core.open(key, generation))

    /** Admit one decoded envelope. */
    fun admit(envelope: IngressEnvelope<K, T>): IngressAdmission {
        val (change, admission) = core.admit(envelope)
        apply(change)
        return admission
    }

    /**
     * Suspend a scope, retaining its window and watermark. Returns the replay
     * request a reconnect will need.
     */
    fun suspend(key: K): ReplayRequest? {
        val (change, request) = core.suspend(key)
        apply(change)
        return request
    }

    /** Reconnect a scope at [generation], clearing its error streak. */
    fun reconnect(
        key: K,
        generation: Long,
    ): ReplayRequest {
        val (change, request) = core.reconnect(key, generation)
        apply(change)
        return request
    }

    /** Close a scope. It admits nothing and claims no authority until reopened. */
    fun close(key: K) = apply(core.close(key))

    /** Record a transport/decode failure, deepening the scope's backoff. */
    fun fail(
        key: K,
        error: IngressError,
    ) = apply(core.fail(key, error))

    /** Advance logical time. Only scopes that crossed the freshness horizon dirty. */
    fun tick(now: Long) = apply(core.tick(now))

    /** Drain a scope's coalesced window. A drain is an egress, never an ack. */
    fun drain(key: K): T? {
        val (change, value) = core.drain(key)
        apply(change)
        return value
    }

    /**
     * Admit everything [transport] has decoded, then ask it to replay any gap still
     * open. Returns the admission outcomes in arrival order.
     *
     * This is the only method that touches a transport, and it makes no decision of
     * its own: the gap it replays is the one the algebra reports.
     */
    fun pump(transport: IngressTransport<K, T>): List<IngressAdmission> {
        val batch = transport.drain()
        val outcomes = ArrayList<IngressAdmission>(batch.size)
        val touched = LinkedHashSet<K>()
        for (envelope in batch) {
            touched += envelope.key
            outcomes += admit(envelope)
        }
        for (key in touched) {
            val view = core.view(key) ?: continue
            if (view.hasGap) {
                transport.requestReplay(key, ReplayRequest(view.generation, view.resumeFrom))
            }
        }
        return outcomes
    }

    // -- Reactive reads ----------------------------------------------------
    //
    // Erased `*Any` accessors rather than the reified genus `get`, because the
    // payload type is a class type parameter (the same reason `MergeCell` reads
    // through `getCellAny`). Every read still registers the tracked edge, so a read
    // inside a compute is reactive.

    @Suppress("UNCHECKED_CAST")
    private fun <V : Any> reading(
        ops: ComputeOps,
        handle: Computed<IngressReading<V>>,
    ): V? = (ops.getSlotAny(handle.id) as IngressReading<V>).value

    /** The coalesced window awaiting drain. */
    fun value(
        key: K,
        ops: ComputeOps = ctx,
    ): T? = reading(ops, readers(key).value)

    /** Derived readiness. */
    @Suppress("UNCHECKED_CAST")
    fun readiness(
        key: K,
        ops: ComputeOps = ctx,
    ): IngressReadiness = ops.getSlotAny(readers(key).readiness.id) as IngressReadiness

    /** Derived authority; `null` for a closed or unknown scope. */
    fun authority(
        key: K,
        ops: ComputeOps = ctx,
    ): IngressAuthority? = reading(ops, readers(key).authority)

    /** Derived retry decision; `null` while no error is outstanding. */
    fun retry(
        key: K,
        ops: ComputeOps = ctx,
    ): IngressRetry? = reading(ops, readers(key).retry)

    @Suppress("UNCHECKED_CAST")
    private fun receipts(
        ops: ComputeOps,
        handle: Computed<List<IngressReceipt<K>>>,
    ): List<IngressReceipt<K>> = ops.getSlotAny(handle.id) as List<IngressReceipt<K>>

    /** Reactive read: accepted receipts, oldest first. */
    fun accepted(ops: ComputeOps = ctx): List<IngressReceipt<K>> = receipts(ops, acceptedReader)

    /** Reactive read: dropped receipts, oldest first. */
    fun dropped(ops: ComputeOps = ctx): List<IngressReceipt<K>> = receipts(ops, droppedReader)

    /** Reactive read: error receipts, oldest first. */
    fun errors(ops: ComputeOps = ctx): List<IngressReceipt<K>> = receipts(ops, errorsReader)

    /** Reactive read: the derived delivery schedule. */
    fun schedule(ops: ComputeOps = ctx): IngressSchedule = ops.get(scheduleReader)

    /** Handle for the accepted-receipt reader. */
    fun acceptedHandle(): Computed<List<IngressReceipt<K>>> = acceptedReader

    /** Handle for the dropped-receipt reader. */
    fun droppedHandle(): Computed<List<IngressReceipt<K>>> = droppedReader

    /** Handle for the error-receipt reader. */
    fun errorsHandle(): Computed<List<IngressReceipt<K>>> = errorsReader

    /** Handle for the schedule reader. */
    fun scheduleHandle(): Computed<IngressSchedule> = scheduleReader

    /**
     * Retune the transport live: falling back from an event channel to bounded
     * polling is a source write, so every schedule dependent reacts.
     */
    fun setTransport(kind: IngressTransportKind) = ctx.set(transportKindCell, kind)

    /** Retune the poll bound live. */
    fun setPollInterval(interval: Long) = ctx.set(pollIntervalCell, interval)

    // -- Non-reactive projections -----------------------------------------

    /** Non-reactive projection of a scope, for assertions and diagnostics. */
    fun view(key: K): IngressScopeView? = core.view(key)

    /** The bounds in force. */
    fun policy(): IngressPolicy = core.policy

    /** Every known scope key. */
    fun scopeKeys(): List<K> = core.scopeKeys()
}
