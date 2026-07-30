package io.github.lazily

import java.util.TreeMap

/**
 * `IngressCore` — the graph-agnostic admission algebra behind every ingress
 * flavor (`#designimplementtransport`).
 *
 * Same split [TopicCell]'s core makes for the broadcast family and `KeyedOrder`
 * makes for the map family, and for the same reason: deciding whether an inbound
 * envelope is *admissible* touches no reactive handle and awaits nothing, so the
 * single-threaded, thread-safe, and async shells share it verbatim — while
 * **reactivity deliberately stays out**. Invalidation is a graph write, so each
 * flavor mints its own per-scope readers on its own graph and clears them with the
 * storage lock released.
 *
 * Every mutator therefore returns an [IngressChange] — *which* reader kinds the
 * transition dirtied — rather than performing the invalidation itself. That return
 * value is the whole contract between the core and a shell, and it is a pure
 * function of the transition, which is what makes the plane portable across
 * flavors without re-deriving values per flavor.
 *
 * ## Transport-agnostic by construction
 *
 * The core never touches a transport. An envelope is a value ([IngressEnvelope])
 * carrying its own provenance — `generation`, `sequence`, `stampedAt` — so a
 * WebSocket frame, an RPC response, and a polled page are the *same* input once
 * decoded. That is what makes the admission decisions (stale rejection, dedupe,
 * reorder, freshness, backpressure) independent of how bytes arrived, and it is
 * why [IngressTransportKind] exists only to derive a *schedule*.
 *
 * ## What is a derive and what is a call
 *
 * Readiness, authority, and retry are **not** imperative refresh calls. They are
 * pure functions of scope state ([IngressScopeView.readiness],
 * [IngressScopeView.authority], [IngressScopeView.retry]) that each shell exposes
 * as a `Computed`. Freshness is time-dependent, so it enters through an explicit
 * [IngressCore.tick] rather than a hidden clock read — the same discipline
 * [TimerCell] uses, and the reason staleness transitions are deterministic in
 * tests.
 *
 * Spec: `lazily-spec/docs/transport-ingress.md`.
 */

/**
 * How envelopes reach a scope. Event delivery is the default and needs no
 * schedule; the other two exist so a host without an event channel still has a
 * *bounded* fallback rather than an unbounded refresh loop.
 */
enum class IngressTransportKind {
    /** Server-initiated delivery (WebSocket, SSE, in-proc channel). Preferred. */
    EventChannel,

    /** Client-initiated, but triggered by an out-of-band event rather than a timer. */
    RpcTriggered,

    /** Client-initiated on a bounded interval. The fallback of last resort. */
    BoundedPolling,
}

/**
 * When, if ever, a scope should ask the transport for more data.
 *
 * [pollInterval] is non-null only for [IngressTransportKind.BoundedPolling] —
 * making "we polled a transport that pushes" unrepresentable rather than merely
 * discouraged — and never zero.
 */
data class IngressSchedule(val kind: IngressTransportKind, val pollInterval: Long?) {
    companion object {
        /** Derive the schedule for [kind]; a poll bound exists only without event delivery. */
        fun forKind(kind: IngressTransportKind, pollInterval: Long): IngressSchedule =
            IngressSchedule(
                kind,
                when (kind) {
                    IngressTransportKind.BoundedPolling -> maxOf(1L, pollInterval)
                    IngressTransportKind.EventChannel, IngressTransportKind.RpcTriggered -> null
                },
            )
    }
}

/**
 * One decoded inbound message, with the provenance admission needs.
 *
 * [generation] fences a producer incarnation (a reconnect, a redeploy, a build
 * skew); [sequence] orders within a generation; [stampedAt] is the producer's
 * logical time, which is what freshness is measured against.
 */
data class IngressEnvelope<K : Any, T : Any>(
    val key: K,
    val generation: Long,
    val sequence: Long,
    val stampedAt: Long,
    val payload: T,
)

/**
 * Why an envelope was refused. Every entry is a *decision*, not a failure —
 * dropping a superseded envelope is correct behaviour and is receipted as such.
 */
enum class IngressDropReason {
    /** `generation` is below the scope's fence: a zombie producer. */
    StaleGeneration,

    /** `sequence` was already delivered in this generation. */
    DuplicateSequence,

    /** `sequence` is already sitting in the reorder buffer. */
    DuplicateBuffered,

    /** The reorder buffer is at `reorderWindow` and this envelope does not fill the gap. */
    ReorderWindowOverflow,

    /** `now - stampedAt` exceeds the freshness horizon. */
    Expired,

    /** The hot window is at `highWater` under a bounding overflow policy. */
    Backpressure,

    /** The scope is closed; it admits nothing until reopened. */
    ScopeClosed,
}

/**
 * A transport- or decode-level failure attributed to a scope. Distinct from a
 * drop: an error means we could not *decide*, so it drives retry.
 */
enum class IngressError {
    /** The transport closed or reset under us. */
    TransportClosed,

    /** The frame could not be decoded into an envelope. */
    DecodeFailed,

    /** The producer reported that our generation is no longer authoritative. */
    AuthorityLost,
}

/** The outcome of admitting one envelope. */
sealed class IngressAdmission {
    /** Delivered in order, and the window holds exactly this one op. */
    data class Accepted(val deliveredThrough: Long) : IngressAdmission()

    /** Delivered in order and coalesced with at least one other op. */
    data class Conflated(val deliveredThrough: Long) : IngressAdmission()

    /** Held pending an earlier sequence. Nothing is visible yet. */
    data class Buffered(val gapFrom: Long) : IngressAdmission()

    /** A newer producer incarnation took over: expectations reset, envelope delivered. */
    data class GenerationHandoff(val from: Long, val to: Long) : IngressAdmission()

    /** Refused, with the reason receipted. */
    data class Dropped(val reason: IngressDropReason) : IngressAdmission()

    /** Refused by [Overflow.Block]; the producer must retry after a drain. */
    data object Blocked : IngressAdmission()

    /** Whether the envelope became visible to readers. */
    val isDelivered: Boolean
        get() = this is Accepted || this is Conflated || this is GenerationHandoff
}

/**
 * Where a scope is in its lifecycle. Scopes are keyed and independent: closing one
 * never touches another.
 */
enum class IngressLifecycle {
    /** Opened, nothing delivered yet. */
    Opening,

    /** Delivering. */
    Live,

    /** Disconnected but retained: state and cursors survive for replay. */
    Suspended,

    /** Terminal until reopened. Admits nothing. */
    Closed,
}

/** The derived answer to "can a consumer trust this scope right now?". */
enum class IngressReadiness {
    /** No such scope. */
    Unknown,

    /** Open, nothing delivered yet. */
    Warming,

    /** Delivered and inside the freshness horizon. */
    Ready,

    /** Delivered, but the newest accepted stamp is older than the horizon. */
    Stale,

    /** Disconnected; retained state may be replayed. */
    Suspended,

    /** Terminal. */
    Closed,
}

/**
 * What the scope currently claims authority over — the fence plus the in-order
 * watermark a replay request must resume from.
 */
data class IngressAuthority(
    val generation: Long,
    val deliveredThrough: Long?,
    val stampedAt: Long,
)

/** The derived retry decision for a scope that has errored. */
data class IngressRetry(val attempt: Int, val backoff: Long, val resumeFrom: Long)

/** What a reconnect needs from the transport to close its gap. */
data class ReplayRequest(val generation: Long, val fromSequence: Long)

/** Bounds and taxes, all flavor-neutral. */
data class IngressPolicy(
    /** How many out-of-order envelopes may be held per scope. `0` drops every gap. */
    val reorderWindow: Int = 8,
    /** `now - stampedAt` above this is stale; an *arriving* envelope that old expires. */
    val freshnessHorizon: Long = 1_000,
    /** Merged-op count at which [overflow] engages. */
    val highWater: Long = 64,
    /** What to do at [highWater] — the relay algebra's policy, unchanged. */
    val overflow: Overflow = Overflow.Conflate,
    /** Retained receipts, oldest evicted first. */
    val receiptCapacity: Int = 256,
    /** First retry backoff; doubles per consecutive error. */
    val retryBase: Long = 10,
    /** Backoff clamp. */
    val retryCeiling: Long = 10_000,
)

/** Why a policy was refused at construction time. */
enum class IngressConfigError {
    /** [Overflow.Conflate] chosen for a non-conflating merge policy. */
    ConflateNotBounding,

    /** A zero receipt capacity would discard every receipt it just minted. */
    ZeroReceiptCapacity,
}

/**
 * Thrown when an ingress is constructed with a policy the merge algebra cannot
 * bound — the same construction-time validation [RelayCell] performs.
 */
class IngressConfigException(val error: IngressConfigError) :
    IllegalArgumentException(error.name)

/**
 * Which receipt channel a receipt belongs to. The three are separate reader kinds
 * because they have separate consumers: a projection wants accepts, a dashboard
 * wants drops, a supervisor wants errors.
 */
enum class IngressReceiptChannel { Accepted, Dropped, Error }

/** The decision a receipt records. */
sealed class IngressReceiptOutcome {
    /** Delivered, with the resulting watermark. */
    data class Accepted(val deliveredThrough: Long, val conflated: Boolean) :
        IngressReceiptOutcome()

    /** Refused by a decision. */
    data class Dropped(val reason: IngressDropReason) : IngressReceiptOutcome()

    /** Could not be decided. */
    data class Error(val error: IngressError) : IngressReceiptOutcome()
}

/** One durable record of an admission decision. */
data class IngressReceipt<K : Any>(
    /** Monotone receipt offset, stable across eviction. */
    val offset: Long,
    val key: K,
    val generation: Long,
    /** Sequence the decision was made for, when there was one. */
    val sequence: Long?,
    val outcome: IngressReceiptOutcome,
) {
    /** The channel this receipt is read from. */
    val channel: IngressReceiptChannel
        get() = when (outcome) {
            is IngressReceiptOutcome.Accepted -> IngressReceiptChannel.Accepted
            is IngressReceiptOutcome.Dropped -> IngressReceiptChannel.Dropped
            is IngressReceiptOutcome.Error -> IngressReceiptChannel.Error
        }
}

/**
 * Which of a scope's reader kinds a transition dirtied.
 *
 * Four kinds exist because they have four different invalidation boundaries: a
 * buffered envelope moves nothing but its own gap, a `tick` across the horizon
 * moves only readiness, and an error moves only retry.
 */
data class IngressScopeChange(
    val value: Boolean = false,
    val readiness: Boolean = false,
    val authority: Boolean = false,
    val retry: Boolean = false,
) {
    /** Nothing changed — the shell must not clear a slot. */
    val isEmpty: Boolean get() = !(value || readiness || authority || retry)

    fun union(other: IngressScopeChange): IngressScopeChange =
        IngressScopeChange(
            value || other.value,
            readiness || other.readiness,
            authority || other.authority,
            retry || other.retry,
        )

    companion object {
        val none = IngressScopeChange()
        val all = IngressScopeChange(value = true, readiness = true, authority = true, retry = true)
        val readinessOnly = IngressScopeChange(readiness = true)
        val valueOnly = IngressScopeChange(value = true)
        val retryOnly = IngressScopeChange(retry = true)

        /**
         * What materializing a previously-unknown scope changes: an unknown scope
         * reads `Unknown`/`null`, so its first appearance moves readiness and
         * authority — and nothing else. A reader that observed a key before it
         * opened must learn that it did.
         */
        val creation = IngressScopeChange(readiness = true, authority = true)
    }
}

/**
 * The pure invalidation set of one transition: the whole contract between the core
 * and a flavor shell.
 */
data class IngressChange<K : Any>(
    /** Per-scope dirty reader kinds, in transition order. */
    val scopes: List<Pair<K, IngressScopeChange>> = emptyList(),
    val acceptedReceipts: Boolean = false,
    val droppedReceipts: Boolean = false,
    val errorReceipts: Boolean = false,
) {
    /** Whether this transition dirtied nothing at all. */
    val isEmpty: Boolean
        get() = scopes.isEmpty() && !acceptedReceipts && !droppedReceipts && !errorReceipts
}

private class ChangeBuilder<K : Any> {
    private val scopes = ArrayList<Pair<K, IngressScopeChange>>(2)
    private var accepted = false
    private var dropped = false
    private var errors = false

    fun mark(key: K, change: IngressScopeChange) {
        if (!change.isEmpty) scopes.add(key to change)
    }

    fun markChannel(channel: IngressReceiptChannel) {
        when (channel) {
            IngressReceiptChannel.Accepted -> accepted = true
            IngressReceiptChannel.Dropped -> dropped = true
            IngressReceiptChannel.Error -> errors = true
        }
    }

    fun build(): IngressChange<K> = IngressChange(scopes.toList(), accepted, dropped, errors)
}

/**
 * Read-only projection of one scope, from which every derive is computed.
 *
 * A shell's reader closures call these and nothing else, which is why the three
 * flavors cannot disagree about readiness, authority, or retry.
 */
data class IngressScopeView(
    val lifecycle: IngressLifecycle,
    val generation: Long,
    val deliveredThrough: Long?,
    val stampedAt: Long,
    val buffered: Int,
    val windowDepth: Long,
    val consecutiveErrors: Int,
    /** Logical now, as of the last [IngressCore.tick]. */
    val observedNow: Long,
    val policy: IngressPolicy,
) {
    /** Whether the newest delivered stamp is inside the freshness horizon. */
    val isFresh: Boolean
        get() = (observedNow - stampedAt).coerceAtLeast(0L) <= policy.freshnessHorizon

    /**
     * Derived readiness. A scope that has never delivered is [IngressReadiness.Warming],
     * not [IngressReadiness.Stale], because there is no stamp to be old.
     */
    val readiness: IngressReadiness
        get() = when (lifecycle) {
            IngressLifecycle.Closed -> IngressReadiness.Closed
            IngressLifecycle.Suspended -> IngressReadiness.Suspended
            IngressLifecycle.Opening -> IngressReadiness.Warming
            IngressLifecycle.Live ->
                when {
                    deliveredThrough == null -> IngressReadiness.Warming
                    isFresh -> IngressReadiness.Ready
                    else -> IngressReadiness.Stale
                }
        }

    /** Derived authority. A closed scope claims none. */
    val authority: IngressAuthority?
        get() =
            if (lifecycle == IngressLifecycle.Closed) null
            else IngressAuthority(generation, deliveredThrough, stampedAt)

    /** The first sequence not yet delivered in order. */
    val resumeFrom: Long get() = deliveredThrough?.plus(1) ?: 0L

    /**
     * Whether the scope is holding a gap open — an out-of-order buffer that a
     * replay, not a retry, is the fix for.
     */
    val hasGap: Boolean get() = buffered > 0

    /**
     * Derived retry. `null` while no error is outstanding — a healthy scope has no
     * backoff, rather than a zero one.
     */
    val retry: IngressRetry?
        get() {
            if (consecutiveErrors == 0) return null
            val shift = (consecutiveErrors - 1).coerceIn(0, 62)
            val factor = 1L shl shift
            val scaled =
                if (policy.retryBase != 0L && policy.retryBase > Long.MAX_VALUE / factor) {
                    Long.MAX_VALUE
                } else {
                    policy.retryBase * factor
                }
            return IngressRetry(consecutiveErrors, minOf(scaled, policy.retryCeiling), resumeFrom)
        }
}

/**
 * A nullable reader projection.
 *
 * The Kotlin reactive kernel's `Computed<T : Any>` cannot carry `null`, so a
 * reader whose value is optional (`Computed<Option<T>>` in lazily-rs) projects
 * this one-field holder instead. Equality is structural, so the kernel's `==`
 * guard behaves exactly as it does on the Rust `Option`.
 */
data class IngressReading<V : Any>(val value: V?)

/**
 * A decoded source of envelopes.
 *
 * The core never calls this — a shell's `pump` does — which is exactly what keeps
 * admission independent of delivery. Implementations decode; they do not decide.
 */
interface IngressTransport<K : Any, T : Any> {
    /** How this transport delivers. Drives [IngressSchedule] and nothing else. */
    fun kind(): IngressTransportKind

    /** Take everything decoded since the last call. Never blocks. */
    fun drain(): List<IngressEnvelope<K, T>>

    /**
     * Ask the producer to resend from `request.fromSequence`. Returns whether the
     * transport could carry the request — a polling transport that cannot address
     * history answers `false`, which is what makes "this gap will never close"
     * observable rather than silent.
     */
    fun requestReplay(key: K, request: ReplayRequest): Boolean
}

/**
 * An in-process event channel: the reference [IngressTransport], and the one the
 * conformance corpus replays against. [kind] is configurable so one implementation
 * exercises all three delivery modes — including the `BoundedPolling` case that
 * cannot serve a replay.
 */
class InProcIngress<K : Any, T : Any>(private val kind: IngressTransportKind) :
    IngressTransport<K, T> {
    private val inbound = ArrayDeque<IngressEnvelope<K, T>>()
    private val replayLog = ArrayList<Pair<K, ReplayRequest>>()

    /** Queue one envelope for the next [drain]. */
    fun push(envelope: IngressEnvelope<K, T>) {
        inbound.addLast(envelope)
    }

    /** Replay requests observed so far, oldest first. */
    fun replays(): List<Pair<K, ReplayRequest>> = replayLog.toList()

    override fun kind(): IngressTransportKind = kind

    override fun drain(): List<IngressEnvelope<K, T>> {
        val batch = inbound.toList()
        inbound.clear()
        return batch
    }

    override fun requestReplay(key: K, request: ReplayRequest): Boolean {
        // A bounded poll has no addressable history: it can only wait for the next
        // page, so it cannot honour a replay.
        if (kind == IngressTransportKind.BoundedPolling) return false
        replayLog.add(key to request)
        return true
    }
}

/**
 * Keyed lifecycle scopes, an admission algebra, and a bounded receipt log. No
 * context, no reactive handles, no invalidation — each flavor wraps this in its own
 * lock and owns its own graph.
 *
 * The merge algebra is a **runtime** [MergePolicy] rather than a type parameter,
 * matching every other lazily-kt family: Kotlin has neither a zero-cost type-level
 * policy nor default type arguments.
 */
class IngressCore<K : Any, T : Any>(
    val policy: IngressPolicy,
    private val mergePolicy: MergePolicy<T>,
) {
    init {
        // Validate the overflow choice against the merge algebra the way
        // `RelayCell` does: `Conflate` bounds nothing for a non-conflating ⊕.
        if (policy.overflow == Overflow.Conflate && !mergePolicy.conflates) {
            throw IngressConfigException(IngressConfigError.ConflateNotBounding)
        }
        if (policy.receiptCapacity <= 0) {
            throw IngressConfigException(IngressConfigError.ZeroReceiptCapacity)
        }
        require(policy.reorderWindow >= 0) { "IngressPolicy.reorderWindow must be non-negative" }
    }

    private data class Stamp(
        val lifecycle: IngressLifecycle,
        val generation: Long,
        val deliveredThrough: Long?,
        val hasWindow: Boolean,
    )

    private inner class Scope(var generation: Long) {
        var lifecycle: IngressLifecycle = IngressLifecycle.Opening
        var deliveredThrough: Long? = null
        var stampedAt: Long = 0
        val pending: TreeMap<Long, Pair<T, Long>> = TreeMap()
        var window: T? = null
        var windowDepth: Long = 0
        var consecutiveErrors: Int = 0

        fun view(now: Long): IngressScopeView =
            IngressScopeView(
                lifecycle,
                generation,
                deliveredThrough,
                stampedAt,
                pending.size,
                windowDepth,
                consecutiveErrors,
                now,
                policy,
            )

        fun nextExpected(): Long = deliveredThrough?.plus(1) ?: 0L

        /**
         * Everything a reader can observe *about shape rather than payload*. The
         * buffered path diffs these to derive its invalidation set, so "a buffered
         * envelope invalidates nothing" is a computed fact rather than a claim —
         * and the handoff-then-buffer case (which clears the window) cannot slip
         * through.
         */
        fun stamp(): Stamp = Stamp(lifecycle, generation, deliveredThrough, window != null)

        fun liveOrOpening(): IngressLifecycle =
            if (deliveredThrough != null) IngressLifecycle.Live else IngressLifecycle.Opening
    }

    /**
     * What the admission algebra decided, before any receipt is minted. Splitting
     * the decision from its bookkeeping keeps the scope mutation from overlapping
     * the receipt log.
     */
    private sealed interface Decision {
        data class Refuse(val reason: IngressDropReason) : Decision
        data object Block : Decision
        data class Buffered(val gapFrom: Long) : Decision
        data class Delivered(
            val deliveredThrough: Long,
            val conflated: Boolean,
            val handoff: Pair<Long, Long>?,
        ) : Decision
    }

    private val scopes = LinkedHashMap<K, Scope>()
    private val receipts = ArrayDeque<IngressReceipt<K>>()
    private var nextReceiptOffset = 0L
    private var observedNow = 0L

    /** Every known scope key, for a shell rebuilding its reader table. */
    fun scopeKeys(): List<K> = scopes.keys.toList()

    /** Read-only projection of one scope, or `null` when unknown. */
    fun view(key: K): IngressScopeView? = scopes[key]?.view(observedNow)

    /**
     * Readiness of a scope. Unknown scopes are [IngressReadiness.Unknown] rather
     * than an error: a reader may legitimately observe a key before it opens.
     */
    fun readiness(key: K): IngressReadiness = view(key)?.readiness ?: IngressReadiness.Unknown

    /** Authority claimed by a scope. */
    fun authority(key: K): IngressAuthority? = view(key)?.authority

    /** Retry decision for a scope. */
    fun retry(key: K): IngressRetry? = view(key)?.retry

    /** The coalesced window awaiting drain. */
    fun peek(key: K): T? = scopes[key]?.window

    /** Receipts on one channel, oldest first. */
    fun receipts(channel: IngressReceiptChannel): List<IngressReceipt<K>> =
        receipts.filter { it.channel == channel }

    /** Logical now, as of the last [tick]. */
    fun observedNow(): Long = observedNow

    /**
     * Open (or reopen) a scope at [generation].
     *
     * Reopening a suspended scope preserves its watermark so a replay can resume
     * from the gap; reopening a *closed* scope resets it, because a closed scope's
     * producer is gone and its sequence space is not resumable.
     */
    fun open(key: K, generation: Long): IngressChange<K> {
        val change = ChangeBuilder<K>()
        val existing = scopes[key]
        if (existing == null) {
            scopes[key] = Scope(generation)
            change.mark(key, IngressScopeChange.creation)
            return change.build()
        }
        val before = Triple(existing.lifecycle, existing.generation, existing.deliveredThrough)
        val scope: Scope
        if (existing.lifecycle == IngressLifecycle.Closed) {
            scope = Scope(generation)
            scopes[key] = scope
        } else {
            scope = existing
            scope.lifecycle = scope.liveOrOpening()
            if (generation > scope.generation) {
                scope.generation = generation
                scope.deliveredThrough = null
                scope.pending.clear()
            }
        }
        val after = Triple(scope.lifecycle, scope.generation, scope.deliveredThrough)
        if (before != after) {
            change.mark(
                key,
                IngressScopeChange(
                    value = false,
                    readiness = before.first != after.first,
                    authority = true,
                    retry = false,
                ),
            )
        }
        return change.build()
    }

    /**
     * Suspend a scope: retain state and cursors, stop delivering. Returns the
     * replay request a reconnect will need, or `null` when there was nothing to
     * suspend.
     */
    fun suspend(key: K): Pair<IngressChange<K>, ReplayRequest?> {
        val change = ChangeBuilder<K>()
        val scope = scopes[key] ?: return change.build() to null
        if (scope.lifecycle == IngressLifecycle.Suspended ||
            scope.lifecycle == IngressLifecycle.Closed
        ) {
            return change.build() to null
        }
        scope.lifecycle = IngressLifecycle.Suspended
        change.mark(key, IngressScopeChange.readinessOnly)
        return change.build() to ReplayRequest(scope.generation, scope.nextExpected())
    }

    /**
     * Reconnect a scope at [generation], clearing the error streak.
     *
     * A higher generation is a producer handoff: the sequence space restarts, so
     * the buffered reorder window and the coalesced value are discarded rather than
     * replayed against a fence they no longer belong to.
     */
    fun reconnect(key: K, generation: Long): Pair<IngressChange<K>, ReplayRequest> {
        val change = ChangeBuilder<K>()
        val created = !scopes.containsKey(key)
        val scope = scopes.getOrPut(key) { Scope(generation) }
        val handoff = generation > scope.generation
        val hadWindow = scope.window != null
        if (handoff) {
            scope.generation = generation
            scope.deliveredThrough = null
            scope.pending.clear()
            scope.window = null
            scope.windowDepth = 0
        }
        val beforeLifecycle = scope.lifecycle
        scope.lifecycle = scope.liveOrOpening()
        val hadErrors = scope.consecutiveErrors > 0
        scope.consecutiveErrors = 0
        val base =
            IngressScopeChange(
                value = handoff && hadWindow,
                readiness = beforeLifecycle != scope.lifecycle,
                authority = handoff,
                retry = hadErrors,
            )
        change.mark(key, if (created) base.union(IngressScopeChange.creation) else base)
        return change.build() to ReplayRequest(scope.generation, scope.nextExpected())
    }

    /** Close a scope. It admits nothing and claims no authority until reopened. */
    fun close(key: K): IngressChange<K> {
        val change = ChangeBuilder<K>()
        val scope = scopes[key] ?: return change.build()
        if (scope.lifecycle == IngressLifecycle.Closed) return change.build()
        val hadWindow = scope.window != null
        val hadErrors = scope.consecutiveErrors > 0
        scope.lifecycle = IngressLifecycle.Closed
        scope.pending.clear()
        scope.window = null
        scope.windowDepth = 0
        scope.consecutiveErrors = 0
        change.mark(
            key,
            IngressScopeChange(
                value = hadWindow,
                readiness = true,
                authority = true,
                retry = hadErrors,
            ),
        )
        return change.build()
    }

    /**
     * Advance logical time. Only scopes that *crossed* the freshness horizon are
     * dirtied — a tick inside the horizon invalidates nothing, which is what keeps
     * a polling shell from re-rendering on every tick.
     */
    fun tick(now: Long): IngressChange<K> {
        val change = ChangeBuilder<K>()
        if (now == observedNow) return change.build()
        val before = observedNow
        observedNow = now
        for ((key, scope) in scopes) {
            if (scope.view(before).readiness != scope.view(now).readiness) {
                change.mark(key, IngressScopeChange.readinessOnly)
            }
        }
        return change.build()
    }

    /** Record a transport/decode failure against a scope, deepening its backoff. */
    fun fail(key: K, error: IngressError): IngressChange<K> {
        val change = ChangeBuilder<K>()
        val created = !scopes.containsKey(key)
        val scope = scopes.getOrPut(key) { Scope(0) }
        if (scope.consecutiveErrors < Int.MAX_VALUE) scope.consecutiveErrors += 1
        val base = IngressScopeChange.retryOnly
        change.mark(key, if (created) base.union(IngressScopeChange.creation) else base)
        change.markChannel(
            pushReceipt(
                IngressReceipt(
                    0,
                    key,
                    scope.generation,
                    null,
                    IngressReceiptOutcome.Error(error),
                ),
            ),
        )
        return change.build()
    }

    /**
     * Drain a scope's coalesced window, resetting its depth. Returns `null` for an
     * empty window and dirties nothing.
     *
     * A drain is an *egress*, not an ack: it never moves the watermark, so a replay
     * after a drain still resumes from the same sequence.
     */
    fun drain(key: K): Pair<IngressChange<K>, T?> {
        val change = ChangeBuilder<K>()
        val scope = scopes[key] ?: return change.build() to null
        val value = scope.window ?: return change.build() to null
        scope.window = null
        scope.windowDepth = 0
        change.mark(key, IngressScopeChange.valueOnly)
        return change.build() to value
    }

    /**
     * Admit one envelope, applying — in this order — scope lifecycle, the
     * generation fence, freshness, generation handoff, dedupe, ordering, and
     * backpressure, then the merge.
     *
     * The order is the contract: a zombie generation is rejected before its stale
     * sequence is consulted, and an expired envelope is rejected before it can
     * occupy a reorder slot.
     */
    fun admit(envelope: IngressEnvelope<K, T>): Pair<IngressChange<K>, IngressAdmission> {
        val key = envelope.key
        val created = !scopes.containsKey(key)
        val before = scopes[key]?.stamp()
        val scope = scopes.getOrPut(key) { Scope(envelope.generation) }
        val decision = decide(scope, envelope)

        // A refused envelope must not leave a scope behind: an expired or blocked
        // message for a key we do not track is not an admission plane, and
        // materializing one would report a readiness change that never happened.
        val admitted = decision is Decision.Buffered || decision is Decision.Delivered
        if (created && !admitted) scopes.remove(key)

        val change = ChangeBuilder<K>()
        val fence = scopes[key]?.generation ?: envelope.generation

        return when (decision) {
            is Decision.Refuse -> {
                change.markChannel(
                    pushReceipt(
                        IngressReceipt(
                            0,
                            key,
                            fence,
                            envelope.sequence,
                            IngressReceiptOutcome.Dropped(decision.reason),
                        ),
                    ),
                )
                change.build() to IngressAdmission.Dropped(decision.reason)
            }
            is Decision.Block -> {
                change.markChannel(
                    pushReceipt(
                        IngressReceipt(
                            0,
                            key,
                            fence,
                            envelope.sequence,
                            IngressReceiptOutcome.Dropped(IngressDropReason.Backpressure),
                        ),
                    ),
                )
                change.build() to IngressAdmission.Blocked
            }
            is Decision.Buffered -> {
                // A buffered envelope mints no receipt, and for an already-current
                // scope it dirties no reader, because nothing a reader can observe
                // moved. Two cases are NOT invisible and are derived rather than
                // assumed: the scope's own first appearance (it moves off
                // `Unknown`), and a generation handoff that buffers — which resets
                // the fence, the watermark, and the window before parking it.
                var scopeChange =
                    if (created) IngressScopeChange.creation else IngressScopeChange.none
                val after = scopes[key]?.stamp()
                if (before != null && after != null) {
                    scopeChange =
                        scopeChange.union(
                            IngressScopeChange(
                                value = before.hasWindow != after.hasWindow,
                                readiness = before.lifecycle != after.lifecycle ||
                                    (before.deliveredThrough == null) !=
                                    (after.deliveredThrough == null),
                                authority = before.generation != after.generation ||
                                    before.deliveredThrough != after.deliveredThrough,
                                retry = false,
                            ),
                        )
                }
                change.mark(key, scopeChange)
                change.build() to IngressAdmission.Buffered(decision.gapFrom)
            }
            is Decision.Delivered -> {
                change.mark(key, IngressScopeChange.all)
                change.markChannel(
                    pushReceipt(
                        IngressReceipt(
                            0,
                            key,
                            fence,
                            envelope.sequence,
                            IngressReceiptOutcome.Accepted(
                                decision.deliveredThrough,
                                decision.conflated,
                            ),
                        ),
                    ),
                )
                val handoff = decision.handoff
                val admission =
                    when {
                        handoff != null ->
                            IngressAdmission.GenerationHandoff(handoff.first, handoff.second)
                        decision.conflated ->
                            IngressAdmission.Conflated(decision.deliveredThrough)
                        else -> IngressAdmission.Accepted(decision.deliveredThrough)
                    }
                change.build() to admission
            }
        }
    }

    /**
     * The admission algebra proper: pure over one scope, mutating only that scope,
     * minting nothing.
     */
    private fun decide(scope: Scope, envelope: IngressEnvelope<K, T>): Decision {
        if (scope.lifecycle == IngressLifecycle.Closed) {
            return Decision.Refuse(IngressDropReason.ScopeClosed)
        }
        if (envelope.generation < scope.generation) {
            return Decision.Refuse(IngressDropReason.StaleGeneration)
        }
        if ((observedNow - envelope.stampedAt).coerceAtLeast(0L) > policy.freshnessHorizon) {
            return Decision.Refuse(IngressDropReason.Expired)
        }

        var handoff: Pair<Long, Long>? = null
        if (envelope.generation > scope.generation) {
            // A handoff is a baseline reset, not a continuation: the new
            // incarnation's first envelope is authoritative, so the old
            // incarnation's undrained window and buffered successors are discarded
            // rather than folded into it. Merging a superseded delta into a fresh
            // baseline is exactly the build-skew corruption the generation fence
            // exists to prevent, and it is the same rule `reconnect` applies.
            handoff = scope.generation to envelope.generation
            scope.generation = envelope.generation
            scope.deliveredThrough = null
            scope.pending.clear()
            scope.window = null
            scope.windowDepth = 0
        }

        val expected = scope.nextExpected()
        if (envelope.sequence < expected) {
            return Decision.Refuse(IngressDropReason.DuplicateSequence)
        }
        if (envelope.sequence > expected) {
            if (scope.pending.containsKey(envelope.sequence)) {
                return Decision.Refuse(IngressDropReason.DuplicateBuffered)
            }
            if (scope.pending.size >= policy.reorderWindow) {
                return Decision.Refuse(IngressDropReason.ReorderWindowOverflow)
            }
            scope.pending[envelope.sequence] = envelope.payload to envelope.stampedAt
            return Decision.Buffered(expected)
        }

        // In order. Backpressure is checked here and not earlier: refusing an
        // in-order envelope leaves a gap the reorder buffer cannot close, so
        // `Block` must be observable by the producer as its own outcome.
        if (scope.windowDepth >= policy.highWater) {
            when (policy.overflow) {
                Overflow.Block -> return Decision.Block
                Overflow.DropNewest -> return Decision.Refuse(IngressDropReason.Backpressure)
                Overflow.DropOldest -> {
                    scope.window = null
                    scope.windowDepth = 0
                }
                // `Conflate` *is* the bound; `Spill` degrades to it until a durable
                // tail is wired, exactly as `RelayCell` does.
                Overflow.Conflate, Overflow.Spill -> Unit
            }
        }

        var conflated = mergeInto(scope, envelope.payload, envelope.stampedAt)
        scope.deliveredThrough = envelope.sequence
        scope.lifecycle = IngressLifecycle.Live
        scope.consecutiveErrors = 0
        var deliveredThrough = envelope.sequence

        // Flush every buffered successor this delivery unblocked. One invalidation
        // covers the whole flush: readers observe the coalesced window, never a
        // partial replay.
        while (true) {
            val next = scope.nextExpected()
            val buffered = scope.pending.remove(next) ?: break
            conflated = mergeInto(scope, buffered.first, buffered.second) || conflated
            scope.deliveredThrough = next
            deliveredThrough = next
        }

        return Decision.Delivered(deliveredThrough, conflated, handoff)
    }

    /**
     * Merge one payload into a scope's hot head. Returns whether it coalesced with
     * an existing window.
     */
    private fun mergeInto(scope: Scope, payload: T, stampedAt: Long): Boolean {
        val current = scope.window
        val conflated: Boolean
        if (current == null) {
            scope.window = payload
            conflated = false
        } else {
            scope.window = mergePolicy.merge(current, payload)
            conflated = true
        }
        scope.windowDepth += 1
        scope.stampedAt = maxOf(scope.stampedAt, stampedAt)
        return conflated
    }

    private fun pushReceipt(receipt: IngressReceipt<K>): IngressReceiptChannel {
        val stamped = receipt.copy(offset = nextReceiptOffset)
        nextReceiptOffset += 1
        receipts.addLast(stamped)
        while (receipts.size > policy.receiptCapacity) receipts.removeFirst()
        return stamped.channel
    }
}
