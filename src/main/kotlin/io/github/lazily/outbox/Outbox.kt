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
    var ackedThrough: Long = store.loadCursor()
        private set

    override fun append(epoch: Long, msg: IpcMessage) {
        store.put(epoch, msg.encodeJson())
    }

    override fun ackThrough(epoch: Long) {
        if (epoch <= ackedThrough) return
        store.saveCursor(epoch)
        store.deleteThrough(epoch)
        ackedThrough = epoch
    }

    override fun replayFrom(cursor: Long): List<Pair<Long, IpcMessage>> =
        store.scanAfter(maxOf(cursor, ackedThrough))
            .sortedBy { it.first }
            .map { (epoch, frame) -> epoch to IpcMessage.decodeJson(frame) }

    override fun retainedEpochs(): List<Long> = store.scanAfter(ackedThrough).map { it.first }.sorted()
}
