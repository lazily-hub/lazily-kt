package io.github.lazily

/**
 * Temporal source primitives (`#lztime`) — the Kotlin port.
 *
 * See `lazily-spec/docs/temporal-sources.md` and the formal model
 * `lazily-formal/LazilyFormal/Temporal.lean`. Time is a monotone **logical
 * clock** (`now: Long`) exactly like [RatePolicy]/[WindowPolicy]; a binding
 * drives the sources from its own runtime timer by feeding a non-decreasing
 * `now`.
 *
 * Each source is a pure compute **core** (`TimerCore`/`IntervalCore`/`CronCore`/
 * `DeadlineCore`) split from a thin reactive **cell** that projects the core's
 * fire edge onto a [Context] cell so dependents invalidate *only on an actual
 * fire* (the backend-portability rule). Cores are `BytesPayload`-eligible;
 * `DeadlineCell` carries an opaque value (`PyObjectPayload`).
 */

/** A value paired with a liveness state: [Live] until its deadline, then [Expired]. */
sealed class Deadlined<out T> {
    abstract val value: T
    data class Live<out T>(override val value: T) : Deadlined<T>()
    data class Expired<out T>(override val value: T) : Deadlined<T>()

    val isExpired: Boolean get() = this is Expired
}

// -- Single-shot timer -------------------------------------------------------

/** Single-shot core: fires exactly once at the first tick with `now >= fireAt`. */
class TimerCore(private val fireAt: Long) {
    var fired: Boolean = false
        private set

    /** Advance to `now`; returns the fire edge (true only on the first fire). */
    fun tick(now: Long): Boolean {
        if (fired || now < fireAt) return false
        fired = true
        return true
    }

    fun nextFire(): Long? = if (fired) null else fireAt
}

/** Reactive single-shot timer: edge-only invalidation of `fired`/`value`. */
class TimerCell(private val ctx: Context, fireAt: Long) {
    private val core = TimerCore(fireAt)
    val firedCell: Source<Boolean> = ctx.source(false)

    fun tick(now: Long): Boolean {
        val edge = core.tick(now)
        if (edge) firedCell.set(ctx, true)
        return edge
    }

    fun hasFired(): Boolean = ctx.get(firedCell)
    fun value(): Unit? = if (ctx.get(firedCell)) Unit else null
    fun nextFire(): Long? = core.nextFire()
}

// -- Periodic interval -------------------------------------------------------

/** Periodic core: boundaries at `period, 2*period, ...`; a tick counts every
 * boundary in `(frontier, now]`, so a jump past several counts them all. */
class IntervalCore(period: Long) {
    private val period: Long = if (period < 1L) 1L else period
    private var next: Long = this.period
    var count: Long = 0L
        private set

    private fun firesThisTick(now: Long): Long =
        if (now < next) 0L else (now - next) / period + 1L

    fun tick(now: Long): Boolean {
        val fires = firesThisTick(now)
        if (fires == 0L) return false
        count += fires
        next += fires * period
        return true
    }

    fun nextFire(): Long = next
}

/** Reactive periodic interval: invalidates only when `count` changes. */
class IntervalCell(private val ctx: Context, period: Long) {
    private val core = IntervalCore(period)
    val countCell: Source<Long> = ctx.source(0L)

    fun tick(now: Long): Boolean {
        val edge = core.tick(now)
        if (edge) countCell.set(ctx, core.count)
        return edge
    }

    fun count(): Long = ctx.get(countCell)
    fun nextFire(): Long = core.nextFire()
}

// -- Cron pattern ------------------------------------------------------------

/** Pattern-periodic core: a tick `m >= 1` fires iff `m mod cycle` is in
 * `offsets` — an interval with a match set (a cron expression's shape). */
class CronCore(cycle: Long, offsets: List<Long>) {
    private val cycle: Long = if (cycle < 1L) 1L else cycle
    private val offsets: List<Long> =
        offsets.map { ((it % this.cycle) + this.cycle) % this.cycle }.distinct().sorted()
    private var cursor: Long = 0L
    var count: Long = 0L
        private set

    /** Count of `m in 1..=n` with `m mod cycle == o` (`0 <= o < cycle`). */
    private fun countUpto(n: Long, o: Long): Long =
        if (o == 0L) n / cycle else if (o <= n) (n - o) / cycle + 1L else 0L

    private fun matchesIn(lo: Long, hi: Long): Long =
        offsets.sumOf { countUpto(hi, it) - countUpto(lo, it) }

    fun tick(now: Long): Boolean {
        if (now <= cursor) {
            cursor = maxOf(cursor, now)
            return false
        }
        val fires = matchesIn(cursor, now)
        cursor = now
        if (fires == 0L) return false
        count += fires
        return true
    }

    fun nextFire(): Long? {
        if (offsets.isEmpty()) return null
        val start = cursor + 1L
        val base = start / cycle * cycle
        for (cyc in 0L..1L) {
            val block = base + cyc * cycle
            for (o in offsets) {
                val cand = block + o
                if (cand >= start) return cand
            }
        }
        return null
    }
}

/** Reactive cron source: same reactive contract as [IntervalCell]. */
class CronCell(private val ctx: Context, cycle: Long, offsets: List<Long>) {
    private val core = CronCore(cycle, offsets)
    val countCell: Source<Long> = ctx.source(0L)

    fun tick(now: Long): Boolean {
        val edge = core.tick(now)
        if (edge) countCell.set(ctx, core.count)
        return edge
    }

    fun count(): Long = ctx.get(countCell)
    fun nextFire(): Long? = core.nextFire()
}

// -- Value + deadline --------------------------------------------------------

/** Deadline core (bytes-eligible): a [TimerCore] over the deadline. */
class DeadlineCore(deadline: Long) {
    private val timer = TimerCore(deadline)
    val isExpired: Boolean get() = timer.fired

    fun tick(now: Long): Boolean = timer.tick(now)
    fun nextFire(): Long? = timer.nextFire()
}

/** Reactive value + deadline: flips `Live(v) -> Expired(v)` at the deadline,
 * preserving the value; `state` invalidates only on the expiry edge. */
class DeadlineCell<T : Any>(private val ctx: Context, private val value: T, deadline: Long) {
    private val core = DeadlineCore(deadline)
    val expiredCell: Source<Boolean> = ctx.source(false)

    fun tick(now: Long): Boolean {
        val edge = core.tick(now)
        if (edge) expiredCell.set(ctx, true)
        return edge
    }

    fun state(): Deadlined<T> =
        if (ctx.get(expiredCell)) Deadlined.Expired(value) else Deadlined.Live(value)

    fun isExpired(): Boolean = ctx.get(expiredCell)
    fun nextFire(): Long? = core.nextFire()
}
