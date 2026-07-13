package io.github.lazily

/** Dumb byte storage. [Outbox] owns all reliable-sync protocol semantics. */
interface OutboxStore {
    fun put(epoch: Long, frame: ByteArray)

    fun deleteThrough(epoch: Long)

    fun scanAfter(epoch: Long): List<Pair<Long, ByteArray>>

    fun loadCursor(): Long

    fun saveCursor(epoch: Long)
}

class InMemoryStore : OutboxStore {
    private val entries = sortedMapOf<Long, ByteArray>()
    private var cursor = 0L

    override fun put(epoch: Long, frame: ByteArray) {
        entries[epoch] = frame.copyOf()
    }

    override fun deleteThrough(epoch: Long) {
        entries.keys.removeAll { it <= epoch }
    }

    override fun scanAfter(epoch: Long): List<Pair<Long, ByteArray>> =
        entries.filterKeys { it > epoch }.map { (key, value) -> key to value.copyOf() }

    override fun loadCursor(): Long = cursor

    override fun saveCursor(epoch: Long) {
        cursor = maxOf(cursor, epoch)
    }
}

/** Process-local default with exactly the same protocol path as durable stores. */
class InMemoryOutbox : io.github.lazily.outbox.Outbox<InMemoryStore>(InMemoryStore())

/**
 * Minimal boundary an Android host can implement with a Room `@Dao`.
 * Keeping Room annotations in the application module avoids forcing Android
 * dependencies into the portable lazily JVM artifact.
 */
interface RoomOutboxDao {
    fun upsert(channel: String, epoch: Long, frame: ByteArray)

    fun deleteThrough(channel: String, epoch: Long)

    fun scanAfter(channel: String, epoch: Long): List<Pair<Long, ByteArray>>

    fun loadCursor(channel: String): Long?

    /**
     * Atomically persist `max(storedCursor, epoch)` for [channel]. A plain
     * replace/upsert is invalid because a stale Room handle could overwrite a
     * newer acknowledgement committed by another handle.
     */
    fun saveCursor(channel: String, epoch: Long)
}

/** Room/SQLite adapter; storage only, with protocol behavior inherited from [Outbox]. */
class RoomStore(
    private val dao: RoomOutboxDao,
    private val channel: String,
) : OutboxStore {
    override fun put(epoch: Long, frame: ByteArray) = dao.upsert(channel, epoch, frame)

    override fun deleteThrough(epoch: Long) = dao.deleteThrough(channel, epoch)

    override fun scanAfter(epoch: Long): List<Pair<Long, ByteArray>> = dao.scanAfter(channel, epoch)

    override fun loadCursor(): Long = dao.loadCursor(channel) ?: 0L

    override fun saveCursor(epoch: Long) = dao.saveCursor(channel, epoch)
}

typealias RoomOutbox = io.github.lazily.outbox.Outbox<RoomStore>
