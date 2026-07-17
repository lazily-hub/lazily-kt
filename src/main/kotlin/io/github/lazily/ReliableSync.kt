package io.github.lazily

/**
 * Reliable sync protocol (`#lzsync`).
 *
 * Delivery-reliability over the `Snapshot`/`Delta`/`CrdtSync` planes (`lazily-spec`
 * § Reliable Sync): gap recovery, at-least-once outbox, and OR-set / LWW liveness
 * cells. Correctness backstop: `lazily-formal` `ReliableSync.lean`; cross-language
 * pins: `lazily-spec/conformance/reliable-sync/`.
 *
 * Three pure-protocol pieces (identical logic in every binding, no I/O / clock /
 * storage engine): [ResyncCoordinator], [DurableOutbox] (+ [InMemoryOutbox]), and
 * the [OrSet] / [WireLwwRegister] liveness cells. The reverse-channel control
 * frames are [IpcMessage.ResyncRequestMessage] / [IpcMessage.OutboxAckMessage] —
 * variants on the same bidirectional plane as the state frames.
 */

/** Receiver decision for an inbound frame (spec § ResyncCoordinator). */
sealed interface ResyncAction {
    /** Apply the frame and advance the receiver epoch. */
    data object Apply : ResyncAction

    /** A gap was detected; request a fresh Snapshot covering [fromEpoch]. */
    data class RequestSnapshot(val fromEpoch: Long) : ResyncAction

    /** Drop the frame (re-delivery, malformed, suppressed duplicate request, or a control frame). */
    data object Ignore : ResyncAction
}

/**
 * Receiver-side reliable-sync coordinator. Holds `lastEpoch` (highest epoch fully
 * applied) and a `resyncing` flag (a RequestSnapshot is outstanding until a
 * covering Snapshot lands). `ingest` advances `lastEpoch` on [ResyncAction.Apply];
 * the caller MUST fold the frame's ops on Apply. Mirrors `ReliableSync.step`.
 */
class ResyncCoordinator(lastEpoch: Long = 0) {
    var lastEpoch: Long = lastEpoch
        private set
    var isResyncing: Boolean = false
        private set

    /** Classify + fold an inbound Delta; advances to `delta.epoch` on Apply (multi-epoch aware). */
    fun ingestDelta(delta: Delta): ResyncAction =
        when {
            delta.baseEpoch == lastEpoch ->
                if (delta.epoch >= delta.baseEpoch + 1) {
                    lastEpoch = delta.epoch
                    isResyncing = false
                    ResyncAction.Apply
                } else {
                    ResyncAction.Ignore // empty/backward epoch
                }
            delta.baseEpoch < lastEpoch -> ResyncAction.Ignore // already applied — re-delivery
            else -> // gap: baseEpoch > lastEpoch
                if (isResyncing) {
                    ResyncAction.Ignore // suppress duplicate request
                } else {
                    isResyncing = true
                    ResyncAction.RequestSnapshot(lastEpoch)
                }
        }

    /** Adopt a Snapshot — a full-state frame always applies. */
    fun ingestSnapshot(snapshotEpoch: Long): ResyncAction {
        lastEpoch = snapshotEpoch
        isResyncing = false
        return ResyncAction.Apply
    }

    /**
     * Classify an inbound [IpcMessage]. `CrdtSync` rides the CRDT plane and the
     * reverse-channel control frames are for the sender's driver, so both are
     * ignored by this data receiver.
     */
    fun ingest(msg: IpcMessage): ResyncAction =
        when (msg) {
            is IpcMessage.SnapshotMessage -> ingestSnapshot(msg.snapshot.epoch)
            is IpcMessage.DeltaMessage -> ingestDelta(msg.delta)
            is IpcMessage.CrdtSyncMessage,
            is IpcMessage.ResyncRequestMessage,
            is IpcMessage.OutboxAckMessage,
            -> ResyncAction.Ignore
        }

    /** The [IpcMessage.OutboxAckMessage] advertising this receiver's resume cursor. */
    fun ack(): IpcMessage = IpcMessage.ofOutboxAck(OutboxAck(lastEpoch))
}

/**
 * Sender-side at-least-once outbox contract (spec § DurableOutbox). Every frame is
 * durably [append]ed before it is sent, retained until the peer proves receipt
 * ([ackThrough]), and [replayFrom] a reconnect cursor re-sends everything unacked.
 * With the receiver's idempotent Ignore of already-applied deltas this is
 * at-least-once delivery with exactly-once effect.
 */
interface DurableOutbox {
    /** Persist [msg] at [epoch] before the send is attempted. */
    fun append(epoch: Long, msg: IpcMessage)

    /** The peer proved receipt through [epoch]; retained frames `<= epoch` MAY be pruned. */
    fun ackThrough(epoch: Long)

    /** Retained frames with `epoch > cursor`, ascending. */
    fun replayFrom(cursor: Long): List<Pair<Long, IpcMessage>>

    /** Epochs still retained (not yet acked), ascending — diagnostics/tests. */
    fun retainedEpochs(): List<Long>
}

/**
 * An observed-remove set (OR-set) liveness cell. A `(doc, pid)` is present iff some
 * add-tag is not shadowed by a remove that observed it (add-wins over a stale
 * remove). Join is the union of both tag sets — a semilattice, so out-of-order and
 * duplicate delivery converge (`ReliableSync.joinOR_*`).
 */
class OrSet {
    // #lzktreliableset: hash-backed sets — `present()`/`add`/membership are O(1)
    // (the OR-set is only ever queried by membership and union, never iterated
    // in order, so the TreeSet's O(log n) ordering is unused overhead).
    private val adds = hashSetOf<String>()
    private val removes = hashSetOf<String>()

    fun add(tag: String) {
        adds.add(tag)
    }

    fun removeObserved(tags: Iterable<String>) {
        removes.addAll(tags)
    }

    fun present(): Boolean = adds.any { it !in removes }

    fun join(other: OrSet) {
        adds.addAll(other.adds)
        removes.addAll(other.removes)
    }
}

/**
 * A last-writer-wins register liveness cell (per-pid `alive`, owner lease), keyed
 * by [WireStamp] `(wallTime, logical, peer)` total order: the highest stamp wins.
 * Join is the stamp-max, a semilattice (`ReliableSync.joinReg_*`).
 */
class WireLwwRegister<V>(stamp: WireStamp, value: V) {
    var stamp: WireStamp = stamp
        private set
    var value: V = value
        private set

    /** Write [value] at [stamp] iff it dominates the current stamp. */
    fun set(stamp: WireStamp, value: V) {
        if (stampGreater(stamp, this.stamp)) {
            this.stamp = stamp
            this.value = value
        }
    }

    /** Join another replica's register (keep the higher stamp). */
    fun join(other: WireLwwRegister<V>) = set(other.stamp, other.value)

    companion object {
        /** Total order `(wallTime, logical, peer)` — the wire mirror of the HLC stamp. */
        fun stampGreater(a: WireStamp, b: WireStamp): Boolean =
            when {
                a.wallTime != b.wallTime -> a.wallTime > b.wallTime
                a.logical != b.logical -> a.logical > b.logical
                else -> a.peer > b.peer
            }
    }
}

// ---------------------------------------------------------------------------
// SyncDriver + transport seam (`#sync-driver`, `#lzsync-transport-seam`).
//
// The loop shape that wires an outbound producer to a transport through the
// outbox and drives resync on reconnect. It owns no clock and no runtime: the
// host calls [SyncDriver.tick] from its own scheduler, so the driver stays a
// pure state machine over injected seams. Semantics are normative and identical
// across bindings (spec § SyncDriver); the seams carry no wire form of their own
// — what crosses the wire is the codec-encoded [IpcMessage] frame.
// ---------------------------------------------------------------------------

/**
 * Outbound transport seam (spec § Transport seam). Deliver exactly one
 * already-encoded protocol frame. [send] MAY fail and MAY be lossy: `false`
 * means the frame was *not* durably handed to the peer. At-least-once is a
 * [SyncDriver] property, not a sink property — on a failed send the driver keeps
 * the frame in the [DurableOutbox] and replays it after the next reconnect, so a
 * sink is free to be a plain best-effort write that never buffers or retries.
 */
fun interface IpcSink {
    /** Deliver one frame; `true` if handed to the peer, `false` on transport failure. */
    fun send(message: IpcMessage): Boolean
}

/**
 * Inbound transport seam (spec § Transport seam). Poll for the next frame
 * without blocking: `null` means the source is *currently exhausted or closed*
 * (the driver treats it as "no inbound progress this tick" and returns — it never
 * parks). A thrown exception is the **reconnect signal**: it surfaces from
 * [SyncDriver.tick] as [SyncDriverSourceException]; the host re-establishes the
 * byte carrier and calls [SyncDriver.onReconnect].
 */
fun interface IpcSource {
    /** The next frame, or `null` when currently exhausted/closed. Throws to signal a read failure. */
    fun recv(): IpcMessage?
}

/**
 * Monotonic clock seam (spec § SyncDriver — policy injected, no runtime in core).
 * The driver never schedules itself; the host calls [SyncDriver.tick] on its own
 * cadence and supplies wall-free monotonic millis so the driver can timestamp a
 * stall signal without owning a clock source.
 */
fun interface Clock {
    /** Milliseconds from an arbitrary fixed origin; monotonic, non-decreasing. */
    fun nowMillis(): Long
}

/**
 * Sender-side answer to a peer's [ResyncRequest] (spec § SyncDriver). When a
 * receiver detects a gap it can no longer close from retained deltas it asks for
 * a covering `Snapshot`; the host plugs its projection in here to produce one at
 * `epoch >= fromEpoch`. This is the app-supplied half of `resync_convergence`.
 */
fun interface SnapshotProvider {
    /** A full-state [IpcMessage.SnapshotMessage] covering [fromEpoch] (its `epoch` MUST be `>= fromEpoch`). */
    fun snapshot(fromEpoch: Long): IpcMessage
}

/**
 * What one [SyncDriver.tick] accomplished (spec § SyncDriver). [applied] are the
 * inbound `Snapshot`/`Delta`/`CrdtSync` frames the host MUST fold into its
 * projection this tick — the driver has already advanced the receiver cursor for
 * them, so folding is the caller's remaining obligation.
 */
data class Progress(
    /** Data frames pushed to the sink this tick (fresh enqueues + reconnect replays). */
    val sent: Int = 0,
    /** Inbound frames the host must fold into its projection (`Apply`ed). */
    val applied: List<IpcMessage> = emptyList(),
    /** A gap was detected inbound and a [ResyncRequest] was emitted to the peer. */
    val resyncRequested: Boolean = false,
    /** Inbound [ResyncRequest]s answered with a provider snapshot this tick. */
    val snapshotsServed: Int = 0,
    /** The peer's ack cursor after this tick (our outbox retention / resume point). */
    val peerAckedThrough: Long = 0,
    /** Outbox frames still unacked (retained for reconnect replay). */
    val retained: Int = 0,
)

/**
 * Surfaced by [SyncDriver.tick] when the inbound [IpcSource] read fails. A *sink*
 * failure, by contrast, is retain-and-stall (reported through [Progress]/stall
 * signals), **not** an exception. On this signal the host re-establishes the byte
 * carrier and calls [SyncDriver.onReconnect], after which the next [SyncDriver.tick]
 * replays the unacked outbox suffix from the peer ack cursor and re-advertises the
 * receiver cursor.
 */
class SyncDriverSourceException(cause: Throwable? = null) :
    RuntimeException("inbound IpcSource read failed — reconnect and call onReconnect()", cause)

/**
 * Full-duplex reliable-sync loop driver (spec § SyncDriver). One driver drives one
 * peer connection over a caller-supplied [IpcSink]/[IpcSource] pair. It composes the
 * three pure-protocol pieces into the loop shape the spec pins:
 *
 * 1. **resync-on-reconnect** — [onReconnect] arms a replay of the unacked outbox
 *    suffix from the peer's ack cursor;
 * 2. **drain** — pop host-enqueued outbound data frames, [DurableOutbox.append]
 *    each *before* sending (at-least-once durability), send via the sink;
 *    a send error leaves the frame in the outbox (unacked) and stalls the drain;
 * 3. **receive** — read inbound frames, route control frames (`OutboxAck` → advance
 *    retention; `ResyncRequest` → answer with a provider snapshot) and feed data
 *    frames through the [ResyncCoordinator] (`Apply` → hand to the host + owe an ack;
 *    `RequestSnapshot` → emit a `ResyncRequest`; `Ignore` → drop);
 * 4. **advertise** — if anything was applied, send an `OutboxAck` carrying the new
 *    receiver cursor (retried until it lands).
 *
 * The driver owns no threads, no clock source, and no storage engine — the host
 * injects all three ([Clock], the transport pair, the [DurableOutbox]) and decides
 * the tick cadence. Threading and backoff are host policy; backpressure is a host
 * concern surfaced through [isStalled]/[stalledFor] and [Progress.retained].
 */
class SyncDriver(
    private val sink: IpcSink,
    private val source: IpcSource,
    /** Borrowable outbox (diagnostics / durable-store flush). */
    val outbox: DurableOutbox,
    private val clock: Clock,
    private val provider: SnapshotProvider,
    lastEpoch: Long = 0,
) {
    private val coordinator = ResyncCoordinator(lastEpoch)

    /** Host-enqueued outbound data frames staged before append-then-send. */
    private val pending = ArrayDeque<Pair<Long, IpcMessage>>()

    /** Highest epoch the peer has acked — our outbox retention + reconnect resume cursor. */
    private var peerAckedThrough: Long = 0

    /** We applied an inbound frame and owe the peer an `OutboxAck` (retried until sent). */
    private var ackOwed: Boolean = false

    /** A reconnect happened; the next tick replays the unacked outbox suffix. */
    private var replayPending: Boolean = false

    /** Millis since the last sink send failure; `null` when the sink is healthy. */
    private var stalledSince: Long? = null

    /**
     * Stage an outbound data frame at [epoch] for the next tick's drain. [epoch] is
     * the frame's accepted-event count (`Delta.epoch` / `Snapshot.epoch`); it becomes
     * the outbox retention key.
     */
    fun enqueue(epoch: Long, msg: IpcMessage) {
        pending.addLast(epoch to msg)
    }

    /**
     * Signal that the transport was re-established; the next [tick] replays the
     * unacked outbox suffix and re-advertises our receiver cursor.
     */
    fun onReconnect() {
        replayPending = true
        ackOwed = true
        stalledSince = null
    }

    /** The receiver's current applied epoch. */
    fun lastEpoch(): Long = coordinator.lastEpoch

    /** Whether the sink is currently stalled (last send failed, awaiting reconnect). */
    fun isStalled(): Boolean = stalledSince != null

    /**
     * Millis the sink has been stalled as of [now], or `0` when healthy — a backoff
     * signal for the host scheduler (which owns cadence/backoff policy).
     */
    fun stalledFor(now: Long): Long = stalledSince?.let { (now - it).coerceAtLeast(0) } ?: 0

    /**
     * Run one loop pass (drain → retain → receive → resync). Sink failures
     * retain-and-stall (not an exception); only an inbound source read failure throws
     * [SyncDriverSourceException].
     */
    fun tick(): Progress {
        val now = clock.nowMillis()
        var sent = 0
        val applied = mutableListOf<IpcMessage>()
        var resyncRequested = false
        var snapshotsServed = 0

        // 1. resync-on-reconnect: replay the unacked outbox suffix, oldest first.
        if (replayPending) {
            replayPending = false
            for ((_, msg) in outbox.replayFrom(peerAckedThrough)) {
                if (sink.send(msg)) {
                    sent += 1
                } else {
                    stalledSince = now
                    replayPending = true // finish the replay after the next reconnect
                    break
                }
            }
        }

        // 2. drain fresh enqueues: append-before-send, retain-and-stop on failure.
        //    A pre-existing stall (a prior failed send, no reconnect yet) skips the
        //    drain entirely — do not push into a sink already known to be down.
        while (stalledSince == null) {
            val (epoch, msg) = pending.firstOrNull() ?: break
            outbox.append(epoch, msg)
            pending.removeFirst()
            if (sink.send(msg)) {
                sent += 1
                stalledSince = null
            } else {
                // Retained in the outbox (unacked) → replayed on reconnect.
                stalledSince = now
                break
            }
        }

        // 3. receive: route control frames + feed data frames through the coordinator.
        while (true) {
            val msg =
                try {
                    source.recv()
                } catch (e: SyncDriverSourceException) {
                    throw e
                } catch (e: Exception) {
                    throw SyncDriverSourceException(e)
                } ?: break
            when (msg) {
                is IpcMessage.OutboxAckMessage -> {
                    val through = msg.ack.throughEpoch
                    if (through > peerAckedThrough) peerAckedThrough = through
                    outbox.ackThrough(through)
                }
                is IpcMessage.ResyncRequestMessage -> {
                    val snap = provider.snapshot(msg.request.fromEpoch)
                    if (sink.send(snap)) snapshotsServed += 1 else stalledSince = now
                }
                is IpcMessage.CrdtSyncMessage -> {
                    // Idempotent anti-entropy plane — the host folds it directly.
                    applied.add(msg)
                }
                is IpcMessage.SnapshotMessage, is IpcMessage.DeltaMessage -> {
                    when (val action = coordinator.ingest(msg)) {
                        is ResyncAction.Apply -> {
                            ackOwed = true
                            applied.add(msg)
                        }
                        is ResyncAction.RequestSnapshot -> {
                            val req = IpcMessage.ofResyncRequest(ResyncRequest(action.fromEpoch))
                            if (sink.send(req)) resyncRequested = true else stalledSince = now
                        }
                        is ResyncAction.Ignore -> {}
                    }
                }
            }
        }

        // 4. advertise our receiver cursor if we applied anything (retry until sent).
        if (ackOwed && sink.send(coordinator.ack())) {
            ackOwed = false
        }

        return Progress(
            sent = sent,
            applied = applied,
            resyncRequested = resyncRequested,
            snapshotsServed = snapshotsServed,
            peerAckedThrough = peerAckedThrough,
            retained = outbox.retainedEpochs().size,
        )
    }
}
