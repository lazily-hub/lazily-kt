package io.github.lazily.outbox

import io.github.lazily.DurableOutbox
import io.github.lazily.IpcMessage
import io.github.lazily.OutboxStore

/**
 * The single append/ack/replay protocol implementation for every storage
 * backend. Stores never interpret frames or decide retention policy.
 *
 * This lives in the `outbox` namespace because the root package already has a
 * relay-role `Outbox<T>` type.
 */
open class Outbox<S : OutboxStore>(val store: S) : DurableOutbox {
    private var localAckedThrough: Long = store.loadCursor()

    val ackedThrough: Long
        get() = maxOf(localAckedThrough, store.loadCursor())

    override fun append(epoch: Long, msg: IpcMessage) {
        store.put(epoch, msg.encodeJson())
    }

    override fun ackThrough(epoch: Long) {
        val target = maxOf(epoch, localAckedThrough, store.loadCursor())
        if (target > localAckedThrough) store.saveCursor(target)
        store.deleteThrough(target)
        localAckedThrough = target
    }

    override fun replayFrom(cursor: Long): List<Pair<Long, IpcMessage>> =
        store.scanAfter(maxOf(cursor, ackedThrough))
            .sortedBy { it.first }
            .map { (epoch, frame) -> epoch to IpcMessage.decodeJson(frame) }

    override fun retainedEpochs(): List<Long> = store.scanAfter(ackedThrough).map { it.first }.sorted()
}
