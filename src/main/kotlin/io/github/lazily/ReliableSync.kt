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

/** In-memory [DurableOutbox] — correct within a process lifetime; the default. */
class InMemoryOutbox : DurableOutbox {
    private val entries = mutableListOf<Pair<Long, IpcMessage>>()
    var ackedThrough: Long = 0
        private set

    override fun append(epoch: Long, msg: IpcMessage) {
        entries.add(epoch to msg)
    }

    override fun ackThrough(epoch: Long) {
        if (epoch > ackedThrough) ackedThrough = epoch
        entries.retainAll { (e, _) -> e > ackedThrough }
    }

    override fun replayFrom(cursor: Long): List<Pair<Long, IpcMessage>> =
        entries.filter { (e, _) -> e > cursor }.sortedBy { it.first }

    override fun retainedEpochs(): List<Long> = entries.map { it.first }.sorted()
}

/**
 * An observed-remove set (OR-set) liveness cell. A `(doc, pid)` is present iff some
 * add-tag is not shadowed by a remove that observed it (add-wins over a stale
 * remove). Join is the union of both tag sets — a semilattice, so out-of-order and
 * duplicate delivery converge (`ReliableSync.joinOR_*`).
 */
class OrSet {
    private val adds = sortedSetOf<String>()
    private val removes = sortedSetOf<String>()

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
