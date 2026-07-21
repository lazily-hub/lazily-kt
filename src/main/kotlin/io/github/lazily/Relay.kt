package io.github.lazily

/**
 * RelayCell backpressure plan (#relaycell), Phases 2–6 — the Kotlin port.
 *
 * See `lazily-spec/docs/relaycell.md` and `relaycell-backpressure-analysis.md`.
 * A [RelayCell] is an *algebra-typed conflating relay*: it accumulates a fast
 * ingress into a hot head (a [MergePolicy] fold), bounds it with a reactive
 * [BackpressurePolicy], and lets a slow egress drain the coalesced window. The
 * converged egress state is independent of the drain schedule whenever the merge
 * ⊕ is associative (the `relay_converges` invariant, pinned in LazilyFormal.Relay).
 *
 * Phase 2 [RelayCell] + [BackpressurePolicy] · Phase 3 [SpillStore] · Phase 4
 * [Transport] · Phase 5 [Outbox]/[Inbox] roles · Phase 6
 * [RatePolicy]/[WindowPolicy]/[ExpiryPolicy]/[PriorityStorage]/[KeyedRelay].
 * Time is a logical clock (a monotone tick) so behaviour is deterministic.
 */

/** Sentinel for an empty hot head — the reactive cells require a non-null [Any]. */
private object RelayEmpty

// -- Phase 2: RelayCell + BackpressurePolicy ---------------------------------

/** What a bound measures (analysis §4.4). The core meters [Count]. */
enum class BoundDim { Count, Bytes, Keys, Age }

/** The action taken when the hot head crosses `highWater` (analysis §4.4). */
enum class Overflow {
    /** Refuse ingress; the producer backpressures (observes `isFull`). Lossless. */
    Block,

    /** Discard the incoming op. Lossy. */
    DropNewest,

    /** Reset the window to the incoming op, discarding what accumulated. Lossy. */
    DropOldest,

    /** Keep merging — the coalescence *is* the bound. Requires `policy.conflates`. */
    Conflate,

    /** Page the accumulated window to a durable tail (Phase 3 [SpillStore]). */
    Spill,
}

/** Why a construction/merge-swap was rejected (analysis §4.3 flag validation). */
enum class RelayConfigError {
    /** `Conflate` chosen for a non-conflating policy (`RawFifo`). */
    ConflateNotBounding,
}

/** Thrown when a relay is constructed with an illegal overflow for its policy. */
class RelayConfigException(val error: RelayConfigError) : IllegalArgumentException(error.name)

/** The outcome of a single [RelayCell.ingress] op. */
enum class IngressOutcome {
    /** Merged into an empty window (window depth was 0). */
    Accepted,

    /** Merged into a non-empty window (coalesced with prior ops). */
    Conflated,

    /** Dropped by `DropNewest`/`DropOldest` overflow. */
    Dropped,

    /** Refused by `Block` overflow; the producer must retry after a drain. */
    Blocked,
}

/**
 * Reactive backpressure limits (analysis §4.4). Every field is a cell, so an
 * operator or an adaptive controller retunes it live and dependent relays react.
 * Hysteresis (`highWater` ≠ `lowWater`) prevents flapping.
 */
class BackpressurePolicy(
    ctx: Context,
    dimension: BoundDim,
    highWater: Long,
    lowWater: Long,
    overflow: Overflow,
) {
    val dimension: Source<BoundDim> = ctx.source(dimension)
    val highWater: Source<Long> = ctx.source(highWater)
    val lowWater: Source<Long> = ctx.source(lowWater)
    val overflow: Source<Overflow> = ctx.source(overflow)
}

/**
 * The algebra-typed conflating relay (Phase 2, in-proc core). The hot head is a
 * cell; `depth`/`isFull`/`isEmpty` are demand-driven slots, so an unobserved
 * relay costs `N·⊕` and no more (the merge cost law).
 *
 * Uses the erased `*Any` cell accessors for the hot head because `T` is a class
 * type parameter (not reified) — same reason as [MergeCell].
 */
class RelayCell<T : Any>(
    private val ctx: Context,
    val policy: BackpressurePolicy,
    val mergePolicy: MergePolicy<T>,
) {
    private val headId: Int = ctx.cellAny(RelayEmpty)
    private val pending: Source<Long> = ctx.source(0L)

    /** Demand-driven reader: current window depth (`Count`). */
    val depth: Computed<Long> = ctx.computed { getCell(pending) }

    /** Demand-driven reader: depth ≥ `highWater`. */
    val isFull: Computed<Boolean> =
        ctx.computed { getCell(pending) >= getCell(policy.highWater) }

    /** Demand-driven reader: the window is empty (nothing to drain). */
    val isEmpty: Computed<Boolean> = ctx.computed { getCellAny(headId) === RelayEmpty }

    init {
        if (ctx.get(policy.overflow) == Overflow.Conflate && !mergePolicy.conflates) {
            throw RelayConfigException(RelayConfigError.ConflateNotBounding)
        }
    }

    /** Whether the current overflow choice is legal for [mergePolicy]. */
    fun overflowIsLegal(): Boolean =
        ctx.get(policy.overflow) != Overflow.Conflate || mergePolicy.conflates

    /** Current window depth (`Count`). */
    fun depth(): Long = ctx.get(depth)

    /** Whether the window is at/over `highWater`. */
    fun isFull(): Boolean = ctx.get(isFull)

    /** Whether the window is empty. */
    fun isEmpty(): Boolean = ctx.get(isEmpty)

    private fun readFull(): Boolean = ctx.get(pending) >= ctx.get(policy.highWater)

    @Suppress("UNCHECKED_CAST")
    private fun mergeIntoHead(op: T) {
        val cur = ctx.getCellAny(headId)
        val next: Any = if (cur === RelayEmpty) op else mergePolicy.merge(cur as T, op)
        ctx.setCellAny(headId, next)
    }

    /**
     * Ingest one op. Applies the reactive overflow policy when the window is at
     * `highWater`; otherwise merges the op into the hot head under [mergePolicy].
     */
    fun ingress(op: T): IngressOutcome {
        val wasEmpty = ctx.get(pending) == 0L
        if (readFull()) {
            when (ctx.get(policy.overflow)) {
                Overflow.Block -> return IngressOutcome.Blocked
                Overflow.DropNewest -> return IngressOutcome.Dropped
                Overflow.DropOldest -> {
                    ctx.setCellAny(headId, op)
                    pending.set(ctx, 1L)
                    return IngressOutcome.Dropped
                }
                // Conflate keeps merging; Spill degrades to Conflate until wired.
                Overflow.Conflate, Overflow.Spill -> {}
            }
        }
        mergeIntoHead(op)
        pending.set(ctx, ctx.get(pending) + 1L)
        return if (wasEmpty) IngressOutcome.Accepted else IngressOutcome.Conflated
    }

    /**
     * Drain the coalesced window: take the hot head's value and reset the window.
     * Returns `null` for an empty window. `relay_converges` guarantees the egress
     * fold equals the flat fold of every ingested op, for any drain schedule.
     */
    @Suppress("UNCHECKED_CAST")
    fun drain(): T? {
        val cur = ctx.getCellAny(headId)
        if (cur !== RelayEmpty) {
            ctx.setCellAny(headId, RelayEmpty)
            pending.set(ctx, 0L)
            return cur as T
        }
        return null
    }

    /** Peek the current coalesced window without draining. */
    @Suppress("UNCHECKED_CAST")
    fun peek(): T? {
        val cur = ctx.getCellAny(headId)
        return if (cur === RelayEmpty) null else cur as T
    }
}

// -- Phase 3: SpillStore -----------------------------------------------------

/** How spilled windows are laid out on the durable tail (analysis §6). */
enum class SpillMode {
    /** Merge each spilled window into the open page until it fills — minimizes
     *  disk (keep-latest / semilattice). One page holds a coalesced run. */
    CompactOnWrite,

    /** Append each spilled window as its own page — preserves increments for an
     *  accumulating (non-idempotent) policy that must not double-count. */
    AppendCompact,
}

/** One immutable cold page: a coalesced window summary plus its manifest entry. */
data class SpillPage<T>(val id: Long, var summary: T, var bytes: Long)

/**
 * A paged durable tail for a [RelayCell] (Phase 3, in-memory reference backend).
 * Holds a hot page in RAM plus immutable cold pages, a bounded manifest, an
 * egress cursor, and ack-before-reclaim. Memory is `O(hot) + O(manifest)`.
 */
class SpillStore<T : Any>(
    private val mode: SpillMode,
    pageSize: Long,
    private val mergePolicy: MergePolicy<T>,
) {
    private val pageSize: Long = maxOf(1L, pageSize)
    private val pages: MutableList<SpillPage<T>> = mutableListOf()
    private var openFill: Long = 0
    private var nextId: Long = 0

    /** Pages acked from the front (reclaimable) — the egress cursor. */
    private var acked: Int = 0

    /**
     * Spill one coalesced window summary to the durable tail. `AppendCompact`
     * always opens a new page; `CompactOnWrite` merges into the open page until
     * it reaches `pageSize`, then seals it.
     */
    fun spill(window: T, bytes: Long) {
        when (mode) {
            SpillMode.AppendCompact -> pushPage(window, bytes)
            SpillMode.CompactOnWrite -> {
                if (openFill >= pageSize || pages.isEmpty()) {
                    pushPage(window, bytes)
                    openFill = 1
                } else {
                    val last = pages.last()
                    last.summary = mergePolicy.merge(last.summary, window)
                    last.bytes += bytes
                    openFill += 1
                }
            }
        }
    }

    private fun pushPage(summary: T, bytes: Long) {
        pages.add(SpillPage(nextId, summary, bytes))
        nextId += 1
    }

    /** The manifest: `(id, bytes)` for every live page (bounded metadata). */
    fun manifest(): List<Pair<Long, Long>> = pages.map { it.id to it.bytes }

    /** Pages the egress has not yet acked (at/after the ack cursor). */
    fun pendingPages(): List<SpillPage<T>> = pages.subList(acked, pages.size).toList()

    fun pageCount(): Int = pages.size

    /** Ack every page through `id` (inclusive), advancing the reclaim cursor. */
    fun ackThrough(id: Long) {
        while (acked < pages.size && pages[acked].id <= id) {
            acked += 1
        }
    }

    /** Drop acked pages (durable reclaim). Manifest/cursor stay consistent. */
    fun reclaim() {
        if (acked > 0) {
            // #lzktsublistclear: one O(acked) bulk drop instead of acked × O(N)
            // removeAt(0) shifts (was O(N²) over a reclaim sweep).
            pages.subList(0, acked).clear()
            acked = 0
        }
    }

    /** Fold every live cold page (oldest first) into `s0`. */
    fun foldPages(s0: T): T = pages.fold(s0) { acc, p -> mergePolicy.merge(acc, p.summary) }

    /**
     * Reconstruction (`spill_lossless`). Fold the cold tail then the hot head —
     * reproduces the flat fold of every op the relay ever ingested.
     */
    fun reconstruct(s0: T, hot: T?): T {
        val cold = foldPages(s0)
        return if (hot == null) cold else mergePolicy.merge(cold, hot)
    }

    /**
     * Crash replay. Re-deliver every unacked page from the ack cursor into
     * `downstream`. For an idempotent policy re-applying an already-delivered page
     * is a no-op (`spill_replay_idempotent`), so at-least-once replay converges.
     */
    fun replayUnacked(downstream: T): T =
        pendingPages().fold(downstream) { acc, p -> mergePolicy.merge(acc, p.summary) }
}

// -- Phase 4: Transport ------------------------------------------------------

/**
 * A pluggable delivery mechanism for relay ops. The merge algebra — not the
 * transport — guarantees converged state (`transport_independent`), so transports
 * may differ across bindings and still converge.
 */
interface RelayTransport<T> {
    /** Enqueue an op for delivery. */
    fun deliver(op: T)

    /** Pull the next ready frame (empty when nothing is ready). */
    fun poll(): List<T>

    /** Whether any op is still buffered for delivery. */
    fun hasPending(): Boolean
}

/** `InProc` — direct delivery: every buffered op is handed over in one frame. */
class InProcTransport<T> : RelayTransport<T> {
    private val buf: ArrayDeque<T> = ArrayDeque()

    override fun deliver(op: T) {
        buf.addLast(op)
    }

    override fun poll(): List<T> {
        val out = buf.toList()
        buf.clear()
        return out
    }

    override fun hasPending(): Boolean = buf.isNotEmpty()
}

/**
 * A *framed* transport — models `CrossThread`/`Ipc`/`Ws`: ops are delivered in
 * bounded frames of at most `frameSize` (an MTU / batch boundary).
 */
class FramedTransport<T>(frameSize: Int) : RelayTransport<T> {
    private val frameSize: Int = maxOf(1, frameSize)
    private val buf: ArrayDeque<T> = ArrayDeque()

    override fun deliver(op: T) {
        buf.addLast(op)
    }

    override fun poll(): List<T> {
        val n = minOf(frameSize, buf.size)
        val out = ArrayList<T>(n)
        repeat(n) { out.add(buf.removeFirst()) }
        return out
    }

    override fun hasPending(): Boolean = buf.isNotEmpty()
}

// -- Phase 5: Outbox / Inbox roles -------------------------------------------

/**
 * The app → transport send side (analysis §4.7). Backpressures the local producer
 * directly via `isFull`. Default overflow `Conflate` (the state-broadcast case).
 */
class Outbox<T : Any>(
    ctx: Context,
    highWater: Long,
    mergePolicy: MergePolicy<T>,
    dimension: BoundDim = BoundDim.Count,
    overflow: Overflow = Overflow.Conflate,
) {
    val relay: RelayCell<T> =
        RelayCell(
            ctx,
            BackpressurePolicy(ctx, dimension, highWater, highWater / 2, overflow),
            mergePolicy,
        )

    /** The local producer sends an op. A `Blocked` outcome is the producer's
     *  backpressure signal — it should await a drain before retrying. */
    fun send(op: T): IngressOutcome = relay.ingress(op)

    /** The transport drains the coalesced window for egress. */
    fun drain(): T? = relay.drain()

    /** The producer-facing backpressure signal (window at/over the watermark). */
    fun isFull(): Computed<Boolean> = relay.isFull
}

/**
 * The transport → app receive side (analysis §4.7). Cannot block the remote
 * directly; backpressure is a **credit meter** the app replenishes.
 */
class Inbox<T : Any>(
    ctx: Context,
    highWater: Long,
    private val maxCredits: Long,
    mergePolicy: MergePolicy<T>,
    overflow: Overflow = Overflow.Conflate,
) {
    val relay: RelayCell<T> =
        RelayCell(
            ctx,
            BackpressurePolicy(ctx, BoundDim.Count, highWater, highWater / 2, overflow),
            mergePolicy,
        )
    private var creditsRemaining: Long = maxCredits

    /** Whether the transport may deliver another message (a credit is available).
     *  When `false`, the transport must stop reading → the remote throttles. */
    fun ready(): Boolean = creditsRemaining > 0

    /** Credits currently available to the remote. */
    fun credits(): Long = creditsRemaining

    /** The transport delivers a received op. Consumes a credit; the caller MUST
     *  have checked [ready] (a delivery without credit still applies but drives
     *  credits to zero, signalling the remote to stop). */
    fun receive(op: T): IngressOutcome {
        creditsRemaining = maxOf(0L, creditsRemaining - 1)
        return relay.ingress(op)
    }

    /** The app consumes the coalesced window and replenishes `n` credits (up to
     *  the budget), re-opening the remote's flow. */
    fun consume(replenish: Long): T? {
        val out = relay.drain()
        creditsRemaining = minOf(creditsRemaining + replenish, maxCredits)
        return out
    }
}

// -- Phase 6: extra reactive policies ----------------------------------------

/**
 * Case 9 — rate-limited egress (token bucket). A drain is permitted only when a
 * token is available. Refilled `refillPerTick` tokens per logical tick, capped at
 * `capacity`.
 */
class RatePolicy(private val capacity: Long, private val refillPerTick: Long) {
    private var tokensRemaining: Long = capacity

    fun tokens(): Long = tokensRemaining

    /** Try to consume one token for an egress; returns `true` if paced through. */
    fun tryEgress(): Boolean {
        return if (tokensRemaining > 0) {
            tokensRemaining -= 1
            true
        } else {
            false
        }
    }

    /** Advance the logical clock, refilling the bucket (saturating at capacity). */
    fun tick() {
        tokensRemaining = minOf(tokensRemaining + refillPerTick, capacity)
    }
}

/**
 * Case 8 — time-windowed coalescence (debounce/throttle). Flushes when it reaches
 * `windowOps` ops or on an explicit `tick`. Because a window is just a flush
 * group, associativity keeps the converged state unchanged.
 */
class WindowPolicy(windowOps: Long) {
    private val windowOps: Long = maxOf(1L, windowOps)
    private var pending: Long = 0

    /** Record one ingress; returns `true` when the window is full and should flush. */
    fun onIngress(): Boolean {
        pending += 1
        return if (pending >= windowOps) {
            pending = 0
            true
        } else {
            false
        }
    }

    /** The debounce/throttle interval elapsed: flush whatever is pending. */
    fun tick(): Boolean {
        return if (pending > 0) {
            pending = 0
            true
        } else {
            false
        }
    }
}

/**
 * Case 10 — TTL / deadline expiry. Drops elements whose age exceeds `ttl` against
 * a logical clock. Lossy-by-age (explicit); used to shed cold data.
 */
class ExpiryPolicy(private val ttl: Long) {
    private var nowTick: Long = 0

    fun advance(by: Long) {
        nowTick += by
    }

    fun now(): Long = nowTick

    /** Whether an element stamped at `stampedAt` is still live (not expired). */
    fun isLive(stampedAt: Long): Boolean = nowTick - stampedAt <= ttl

    /** Retain only the live elements of a timestamped batch (drop the aged tail). */
    fun <T> retainLive(batch: List<Pair<Long, T>>): List<T> =
        batch.filter { isLive(it.first) }.map { it.second }
}

/**
 * Case 11 — priority egress. Ingress carries a priority; egress pops the highest
 * priority first (**not** FIFO), FIFO within equal priority. Reordering, so sound
 * for a commutative merge downstream (`reorder_adjacent`).
 */
class PriorityStorage<T> {
    private data class Entry<T>(val priority: Long, val seq: Long, val value: T)

    private val items: MutableList<Entry<T>> = mutableListOf()
    private var seq: Long = 0

    fun push(priority: Long, value: T) {
        items.add(Entry(priority, seq, value))
        seq += 1
    }

    /** Pop the highest-priority element (FIFO within equal priority). */
    fun pop(): T? {
        if (items.isEmpty()) return null
        var best = 0
        for (i in 1 until items.size) {
            val a = items[i]
            val b = items[best]
            if (a.priority > b.priority || (a.priority == b.priority && a.seq < b.seq)) {
                best = i
            }
        }
        return items.removeAt(best).value
    }

    fun size(): Int = items.size

    fun isEmpty(): Boolean = items.isEmpty()
}

/**
 * Case 18 — keyed sharding. N independent relays keyed by `K`; an op routes to its
 * key's shard. Merging *across* shards requires a **commutative** merge. The
 * converged per-key state equals a single relay per key.
 */
class KeyedRelay<K, T : Any>(
    private val ctx: Context,
    private val highWater: Long,
    private val overflow: Overflow,
    private val mergePolicy: MergePolicy<T>,
) {
    private val shards: MutableMap<K, RelayCell<T>> = mutableMapOf()

    /** Route `op` to `key`'s shard, creating the shard on first use. */
    fun ingress(key: K, op: T): IngressOutcome {
        val relay =
            shards.getOrPut(key) {
                RelayCell(
                    ctx,
                    BackpressurePolicy(ctx, BoundDim.Count, highWater, highWater / 2, overflow),
                    mergePolicy,
                )
            }
        return relay.ingress(op)
    }

    /** Drain a key's coalesced window. */
    fun drain(key: K): T? = shards[key]?.drain()

    fun keys(): Set<K> = shards.keys
}
