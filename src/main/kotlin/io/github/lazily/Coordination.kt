package io.github.lazily

/**
 * Distributed coordination (`#lzcoord`) — the Kotlin port.
 *
 * See `lazily-spec/docs/coordination.md` and the formal model
 * `lazily-formal/LazilyFormal/Coordination.lean`. Lease / leader / lock /
 * semaphore / barrier + quorum primitives, each a pure compute core split from a
 * reactive cell projecting the salient reader. Time is the logical clock.
 */

/** Sentinel for "no holder / no leader" — the reactive cell requires a non-null [Any]. */
private object CoordEmpty

// -- Lease + fencing token ---------------------------------------------------

/** Single-writer lease authority with a monotone fencing token. */
class LeaseCore<P : Any> {
    private var holder: P? = null
    private var expiry: Long = 0
    var fence: Long = 0
        private set

    private fun isExpired(now: Long) = holder != null && now >= expiry
    fun isHeld(now: Long) = holder != null && !isExpired(now)
    fun holder(now: Long): P? = if (isHeld(now)) holder else null

    fun acquire(peer: P, now: Long, ttl: Long): Long? {
        if (holder == null || isExpired(now)) {
            fence += 1
            holder = peer
            expiry = now + ttl
            return fence
        }
        if (holder == peer) {
            expiry = now + ttl // renew keeps fence
            return fence
        }
        return null
    }

    fun renew(peer: P, now: Long, ttl: Long): Boolean {
        if (isHeld(now) && holder == peer) {
            expiry = now + ttl
            return true
        }
        return false
    }

    fun release(peer: P) {
        if (holder == peer) holder = null
    }

    fun tick(now: Long): Boolean {
        if (isExpired(now)) {
            holder = null
            return true
        }
        return false
    }
}

/** Reactive lease: projects the holder onto a `Cell`. */
class LeaseCell<P : Any>(private val ctx: Context) {
    private val core = LeaseCore<P>()
    val holderCell: Source<Any> = ctx.cell<Any>(CoordEmpty)

    private fun refresh(now: Long) = holderCell.set(ctx, core.holder(now) ?: CoordEmpty)

    fun acquire(peer: P, now: Long, ttl: Long): Long? = core.acquire(peer, now, ttl).also { refresh(now) }
    fun renew(peer: P, now: Long, ttl: Long): Boolean = core.renew(peer, now, ttl).also { refresh(now) }
    fun release(peer: P, now: Long) = core.release(peer).also { refresh(now) }
    fun tick(now: Long): Boolean = core.tick(now).also { refresh(now) }

    fun holder(now: Long): P? = core.holder(now)
    fun isHeld(now: Long): Boolean = core.isHeld(now)
    fun fence(): Long = core.fence
}

// -- Leader / follower / candidate -------------------------------------------

enum class LeaderRole { Leader, Follower, Candidate }

/** Reactive leadership over a lease from node `me`'s perspective. */
class LeaderCell<P : Any>(private val ctx: Context, private val me: P) {
    private val core = LeaseCore<P>()
    val currentLeaderCell: Source<Any> = ctx.cell<Any>(CoordEmpty)

    private fun refresh(now: Long) = currentLeaderCell.set(ctx, core.holder(now) ?: CoordEmpty)

    fun campaign(now: Long, ttl: Long): LeaderRole {
        core.acquire(me, now, ttl)
        refresh(now)
        return role(now)
    }

    fun contend(peer: P, now: Long, ttl: Long): LeaderRole {
        core.acquire(peer, now, ttl)
        refresh(now)
        return role(now)
    }

    fun tick(now: Long): LeaderRole {
        core.tick(now)
        refresh(now)
        return role(now)
    }

    fun currentLeader(now: Long): P? = core.holder(now)

    fun role(now: Long): LeaderRole =
        when (core.holder(now)) {
            null -> LeaderRole.Candidate
            me -> LeaderRole.Leader
            else -> LeaderRole.Follower
        }
}

// -- Distributed lock + fencing ----------------------------------------------

/** Reactive distributed mutex over a lease + fencing token. */
class LockCell<P : Any>(private val ctx: Context) {
    private val core = LeaseCore<P>()
    val isLockedCell: Source<Boolean> = ctx.source(false)

    private fun refresh(now: Long) = isLockedCell.set(ctx, core.isHeld(now))

    fun acquire(peer: P, now: Long, ttl: Long): Long? = core.acquire(peer, now, ttl).also { refresh(now) }
    fun release(peer: P, now: Long) = core.release(peer).also { refresh(now) }
    fun tick(now: Long): Boolean = core.tick(now).also { refresh(now) }

    /** Whether `fence` is the current (non-stale) fencing token. */
    fun validate(fence: Long): Boolean = core.fence == fence

    fun isLocked(now: Long): Boolean = core.isHeld(now)
    fun fence(): Long = core.fence
}

// -- Semaphore ---------------------------------------------------------------

/** Bounded permit pool compute core. */
class SemaphoreCore(private val capacity: Long) {
    private var acquired: Long = 0
    fun available(): Long = capacity - acquired
    fun acquire(): Boolean {
        if (acquired < capacity) {
            acquired += 1
            return true
        }
        return false
    }
    fun release() {
        if (acquired > 0) acquired -= 1
    }
}

/** Reactive semaphore: projects `permitsAvailable` onto a `Cell`. */
class SemaphoreCell(private val ctx: Context, capacity: Long) {
    private val core = SemaphoreCore(capacity)
    val permitsAvailableCell: Source<Long> = ctx.source(capacity)

    private fun refresh() = permitsAvailableCell.set(ctx, core.available())

    fun acquire(): Boolean = core.acquire().also { refresh() }
    fun release() = core.release().also { refresh() }
    fun permitsAvailable(): Long = ctx.get(permitsAvailableCell)
}

// -- Barrier / quorum --------------------------------------------------------

/** Wait-for-N gate over distinct arriving peers. */
class BarrierCore<P : Any>(private val required: Long) {
    private val arrived = mutableSetOf<P>()
    fun arrive(peer: P): Boolean {
        arrived.add(peer)
        return isOpen()
    }
    fun count(): Long = arrived.size.toLong()
    fun isOpen(): Boolean = count() >= required
}

/** Reactive wait-for-N gate. A quorum is a barrier with `required = total/2 + 1`. */
class BarrierCell<P : Any>(private val ctx: Context, required: Long) {
    private val core = BarrierCore<P>(required)
    val isOpenCell: Source<Boolean> = ctx.source(core.isOpen())

    private fun refresh() = isOpenCell.set(ctx, core.isOpen())

    fun arrive(peer: P): Boolean = core.arrive(peer).also { refresh() }
    fun count(): Long = core.count()
    fun isOpen(): Boolean = ctx.get(isOpenCell)

    companion object {
        /** A quorum gate: opens at strict majority of `total`. */
        fun <P : Any> quorum(ctx: Context, total: Long): BarrierCell<P> =
            BarrierCell(ctx, total / 2 + 1)
    }
}
