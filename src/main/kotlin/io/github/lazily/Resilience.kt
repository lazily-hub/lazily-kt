package io.github.lazily

/**
 * Fault-tolerance primitives (`#lzresilience`) — the Kotlin port.
 *
 * See `lazily-spec/docs/resilience.md` and the formal model
 * `lazily-formal/LazilyFormal/Resilience.lean`. Circuit breaker / retry /
 * bulkhead / timeout, each a pure compute core split from a reactive cell
 * projecting the salient reader.
 */

/** Circuit-breaker state. */
enum class BreakerState { Closed, Open, HalfOpen }

/** Circuit-breaker core: a sliding window of outcomes trips Closed->Open at the
 *  failure threshold; Open->HalfOpen at the deadline; a HalfOpen success closes. */
class CircuitBreakerCore(window: Int, failureThreshold: Int, private val resetTimeout: Long) {
    private val window = maxOf(1, window)
    private val failureThreshold = maxOf(1, failureThreshold)
    var state: BreakerState = BreakerState.Closed
        private set
    private val outcomes = ArrayDeque<Boolean>() // true = success
    private var openUntil: Long = 0

    private fun failures() = outcomes.count { !it }

    fun allow(now: Long): Boolean =
        when (state) {
            BreakerState.Closed -> true
            BreakerState.Open ->
                if (now >= openUntil) {
                    state = BreakerState.HalfOpen
                    true
                } else {
                    false
                }
            BreakerState.HalfOpen -> true
        }

    fun record(success: Boolean, now: Long) {
        when (state) {
            BreakerState.HalfOpen ->
                if (success) {
                    state = BreakerState.Closed
                    outcomes.clear()
                } else {
                    state = BreakerState.Open
                    openUntil = now + resetTimeout
                }
            BreakerState.Closed -> {
                outcomes.addLast(success)
                while (outcomes.size > window) outcomes.removeFirst()
                if (failures() >= failureThreshold) {
                    state = BreakerState.Open
                    openUntil = now + resetTimeout
                }
            }
            BreakerState.Open -> {}
        }
    }
}

/** Reactive circuit breaker: projects the state onto a `Cell`. */
class CircuitBreakerCell(
    private val ctx: Context,
    window: Int,
    failureThreshold: Int,
    resetTimeout: Long,
) {
    private val core = CircuitBreakerCore(window, failureThreshold, resetTimeout)
    val stateCell: Source<BreakerState> = ctx.source(BreakerState.Closed)

    private fun refresh() = stateCell.set(ctx, core.state)

    fun allow(now: Long): Boolean = core.allow(now).also { refresh() }
    fun record(success: Boolean, now: Long) = core.record(success, now).also { refresh() }
    fun state(): BreakerState = core.state
}

/** Exponential-backoff core: delay(attempt) = min(cap, base * 2^attempt),
 *  saturating to cap. */
class RetryPolicyCore(private val base: Long, private val cap: Long) {
    private var attempt: Int = 0

    fun delay(attempt: Int): Long {
        if (attempt >= 63) return cap
        val shifted = base shl attempt
        return if (shifted < base) cap else minOf(cap, shifted)
    }

    fun nextDelay(): Long {
        val d = delay(attempt)
        attempt += 1
        return d
    }

    fun reset() {
        attempt = 0
    }
}

/** Reactive retry policy: projects the current delay onto a `Cell`. */
class RetryPolicyCell(private val ctx: Context, base: Long, cap: Long) {
    private val core = RetryPolicyCore(base, cap)
    val delayCell: Source<Long> = ctx.source(0L)

    fun nextDelay(): Long {
        val d = core.nextDelay()
        delayCell.set(ctx, d)
        return d
    }
    fun reset() {
        core.reset()
        delayCell.set(ctx, 0L)
    }
    fun delay(ops: ComputeOps = ctx): Long = ops.get(delayCell)
}

/** Bounded isolation-pool core. */
class BulkheadCore(private val capacity: Long) {
    var inUse: Long = 0
        private set
    fun acquire(): Boolean {
        if (inUse < capacity) {
            inUse += 1
            return true
        }
        return false
    }
    fun release() {
        if (inUse > 0) inUse -= 1
    }
}

/** Reactive bulkhead: projects permitsInUse onto a `Cell`. */
class BulkheadCell(private val ctx: Context, capacity: Long) {
    private val core = BulkheadCore(capacity)
    val inUseCell: Source<Long> = ctx.source(0L)

    private fun refresh() = inUseCell.set(ctx, core.inUse)

    fun acquire(): Boolean = core.acquire().also { refresh() }
    fun release() = core.release().also { refresh() }
    fun permitsInUse(ops: ComputeOps = ctx): Long = ops.get(inUseCell)
}

/** Deadline-bounded call core. */
class TimeoutCore {
    private var deadline: Long = 0
    private var armed = false
    var timedOut = false
        private set

    fun arm(now: Long, timeout: Long) {
        deadline = now + timeout
        armed = true
        timedOut = false
    }
    fun tick(now: Long): Boolean {
        if (armed && !timedOut && now >= deadline) {
            timedOut = true
            return true
        }
        return false
    }
}

/** Reactive timeout: projects isTimedOut onto a `Cell`. */
class TimeoutCell(private val ctx: Context) {
    private val core = TimeoutCore()
    val timedOutCell: Source<Boolean> = ctx.source(false)

    private fun refresh() = timedOutCell.set(ctx, core.timedOut)

    fun arm(now: Long, timeout: Long) = core.arm(now, timeout).also { refresh() }
    fun tick(now: Long): Boolean = core.tick(now).also { refresh() }
    fun isTimedOut(ops: ComputeOps = ctx): Boolean = ops.get(timedOutCell)
}
