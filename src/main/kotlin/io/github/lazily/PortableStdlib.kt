package io.github.lazily

import java.util.concurrent.CompletableFuture

/** Wire-stable reasons shared by the portable logical-clock primitives. */
enum class StdlibUnavailableReason(
    val wireName: String,
) {
    DeadlineOverflow("deadline_overflow"),
    ClockRegression("clock_regression"),
    OperationUnavailable("operation_unavailable"),
    CancellationUnavailable("cancellation_unavailable"),
}

/** Typed construction failure for a deadline that does not fit in an unsigned 64-bit tick. */
class StdlibUnavailableException(
    val reason: StdlibUnavailableReason,
) : IllegalArgumentException(reason.wireName)

/** Return [now] + [duration], failing rather than wrapping the logical clock. */
fun checkedDeadline(
    now: ULong,
    duration: ULong,
): ULong {
    if (duration > ULong.MAX_VALUE - now) {
        throw StdlibUnavailableException(StdlibUnavailableReason.DeadlineOverflow)
    }
    return now + duration
}

enum class TimerOutcome(
    val wireName: String,
) {
    Pending("pending"),
    Fired("fired"),
    Unavailable("unavailable"),
}

data class TimerObservation(
    val outcome: TimerOutcome,
    val deadline: ULong? = null,
    val firedAt: ULong? = null,
    val reason: StdlibUnavailableReason? = null,
)

/**
 * Deterministic single-shot timer driven entirely by caller-supplied logical ticks.
 *
 * The clock is unsigned and checked. A regressing observation reports a typed
 * unavailable result without changing the timer, while the first firing tick is
 * latched for all later observations.
 */
class Timer(
    now: ULong,
    duration: ULong,
) {
    val deadline: ULong = checkedDeadline(now, duration)

    private var lastNow: ULong = now
    private var firedAt: ULong? = null

    @Synchronized
    fun observe(now: ULong): TimerObservation {
        firedAt?.let {
            return TimerObservation(TimerOutcome.Fired, firedAt = it)
        }
        if (now < lastNow) {
            return TimerObservation(
                TimerOutcome.Unavailable,
                deadline = deadline,
                reason = StdlibUnavailableReason.ClockRegression,
            )
        }
        lastNow = now
        if (now >= deadline) {
            firedAt = now
            return TimerObservation(TimerOutcome.Fired, firedAt = now)
        }
        return TimerObservation(TimerOutcome.Pending, deadline = deadline)
    }

    /** JDK-only future adapter; the core has no scheduler or async-runtime dependency. */
    fun observeFuture(now: ULong): CompletableFuture<TimerObservation> = CompletableFuture.completedFuture(observe(now))
}

sealed interface TimeoutOperation<out T> {
    data object Pending : TimeoutOperation<Nothing>

    data class Completed<T>(
        val value: T,
    ) : TimeoutOperation<T>

    data object Unavailable : TimeoutOperation<Nothing>
}

enum class TimeoutCancellation(
    val wireName: String,
) {
    Pending("pending"),
    Cancelled("cancelled"),
    Unavailable("unavailable"),
}

enum class TimeoutOutcome(
    val wireName: String,
) {
    Pending("pending"),
    Completed("completed"),
    TimedOut("timed_out"),
    Cancelled("cancelled"),
    Unavailable("unavailable"),
}

data class TimeoutObservation<T>(
    val outcome: TimeoutOutcome,
    val deadline: ULong? = null,
    val value: T? = null,
    val reason: StdlibUnavailableReason? = null,
)

/**
 * Caller-driven timeout wrapper.
 *
 * At or after the exact deadline neither adapter is invoked. Before it, the
 * operation and cancellation adapters are each invoked exactly once, with
 * precedence completion > unavailable operation > cancellation > pending.
 * Terminal observations are latched and likewise invoke neither adapter.
 */
class Timeout<T>(
    now: ULong,
    duration: ULong,
) {
    val deadline: ULong = checkedDeadline(now, duration)

    private var lastNow: ULong = now
    private var terminal: TimeoutObservation<T>? = null

    fun poll(
        now: ULong,
        operation: () -> TimeoutOperation<T>,
        cancellation: () -> TimeoutCancellation,
    ): TimeoutObservation<T> {
        beginPoll(now)?.let { return it }
        val operationResult = operation()
        val cancellationResult = cancellation()
        return finishPoll(operationResult, cancellationResult)
    }

    /**
     * CompletableFuture adapter over the same state machine.
     *
     * Suppliers are not called on a deadline/terminal fast path. When polling
     * is permitted, both suppliers are started exactly once before either
     * result is inspected.
     */
    fun pollFuture(
        now: ULong,
        operation: () -> CompletableFuture<TimeoutOperation<T>>,
        cancellation: () -> CompletableFuture<TimeoutCancellation>,
    ): CompletableFuture<TimeoutObservation<T>> {
        beginPoll(now)?.let { return CompletableFuture.completedFuture(it) }
        val operationFuture = invokeFuture(operation)
        val cancellationFuture = invokeFuture(cancellation)
        return operationFuture.thenCombine(cancellationFuture) { operationResult, cancellationResult ->
            finishPoll(operationResult, cancellationResult)
        }
    }

    @Synchronized
    private fun beginPoll(now: ULong): TimeoutObservation<T>? {
        terminal?.let { return it }
        if (now < lastNow) {
            return latch(
                TimeoutObservation(
                    TimeoutOutcome.Unavailable,
                    reason = StdlibUnavailableReason.ClockRegression,
                ),
            )
        }
        lastNow = now
        if (now >= deadline) {
            return latch(TimeoutObservation(TimeoutOutcome.TimedOut))
        }
        return null
    }

    @Synchronized
    private fun finishPoll(
        operation: TimeoutOperation<T>,
        cancellation: TimeoutCancellation,
    ): TimeoutObservation<T> {
        terminal?.let { return it }
        return when (operation) {
            is TimeoutOperation.Completed ->
                latch(
                    TimeoutObservation(TimeoutOutcome.Completed, value = operation.value),
                )

            TimeoutOperation.Unavailable ->
                latch(
                    TimeoutObservation(
                        TimeoutOutcome.Unavailable,
                        reason = StdlibUnavailableReason.OperationUnavailable,
                    ),
                )

            TimeoutOperation.Pending ->
                when (cancellation) {
                    TimeoutCancellation.Cancelled ->
                        latch(
                            TimeoutObservation(TimeoutOutcome.Cancelled),
                        )

                    TimeoutCancellation.Unavailable ->
                        latch(
                            TimeoutObservation(
                                TimeoutOutcome.Unavailable,
                                reason = StdlibUnavailableReason.CancellationUnavailable,
                            ),
                        )

                    TimeoutCancellation.Pending ->
                        TimeoutObservation(TimeoutOutcome.Pending, deadline = deadline)
                }
        }
    }

    private fun latch(observation: TimeoutObservation<T>): TimeoutObservation<T> {
        terminal = observation
        return observation
    }
}

enum class RevisionBarrierOutcome(
    val wireName: String,
) {
    Pending("pending"),
    Satisfied("satisfied"),
    TimedOut("timed_out"),
    Cancelled("cancelled"),
    Disposed("disposed"),
    Unavailable("unavailable"),
}

data class RevisionBarrierObservation(
    val outcome: RevisionBarrierOutcome,
    val revision: ULong,
    val generation: ULong,
    val reason: StdlibUnavailableReason? = null,
)

/**
 * Revision authority with a separate wake generation.
 *
 * Only an accepted increasing revision advances either counter. Registration
 * rechecks the revision after the waiter is conceptually installed, deadlines
 * precede predicates and cancellation, disposal is terminal, and effect
 * receipts are deliberately not authority for progress.
 */
class RevisionBarrier(
    revision: ULong,
    private val requiredRevision: ULong,
    private val deadline: ULong?,
) {
    private var revision: ULong = revision
    private var generation: ULong = 0u
    private var lastNow: ULong? = null
    private var terminal: RevisionBarrierObservation? = null

    fun observe(
        now: ULong,
        predicate: Boolean,
        cancellation: () -> TimeoutCancellation,
    ): RevisionBarrierObservation {
        beginObserve(now, predicate)?.let { return it }
        return finishCancellation(cancellation())
    }

    /**
     * CompletableFuture cancellation adapter. It remains caller-driven: no
     * scheduler, timer, coroutine scope, or executor is owned by the barrier.
     */
    fun observeFuture(
        now: ULong,
        predicate: Boolean,
        cancellation: () -> CompletableFuture<TimeoutCancellation>,
    ): CompletableFuture<RevisionBarrierObservation> {
        beginObserve(now, predicate)?.let { return CompletableFuture.completedFuture(it) }
        return invokeFuture(cancellation).thenApply(::finishCancellation)
    }

    @Synchronized
    fun registerRecheck(
        now: ULong,
        observedRevision: ULong,
        predicate: Boolean,
    ): RevisionBarrierObservation {
        terminal?.let { return it }
        rejectClockRegression(now)?.let { return it }
        if (deadline != null && now >= deadline) {
            return latch(RevisionBarrierOutcome.TimedOut)
        }
        acceptRevision(observedRevision)
        if (predicate && revision >= requiredRevision) {
            return latch(RevisionBarrierOutcome.Satisfied)
        }
        return snapshot()
    }

    @Synchronized
    fun advance(
        revision: ULong,
        predicate: Boolean,
    ): RevisionBarrierObservation {
        terminal?.let { return it }
        acceptRevision(revision)
        if (predicate && this.revision >= requiredRevision) {
            return latch(RevisionBarrierOutcome.Satisfied)
        }
        return snapshot()
    }

    @Synchronized
    fun dispose(): RevisionBarrierObservation = terminal ?: latch(RevisionBarrierOutcome.Disposed)

    /** Receipts can wake host waiters, but cannot change revision authority. */
    @Synchronized
    fun receipt(
        @Suppress("UNUSED_PARAMETER") key: String,
    ): RevisionBarrierObservation = snapshot()

    @Synchronized
    private fun beginObserve(
        now: ULong,
        predicate: Boolean,
    ): RevisionBarrierObservation? {
        terminal?.let { return it }
        rejectClockRegression(now)?.let { return it }
        if (deadline != null && now >= deadline) {
            return latch(RevisionBarrierOutcome.TimedOut)
        }
        if (predicate && revision >= requiredRevision) {
            return latch(RevisionBarrierOutcome.Satisfied)
        }
        return null
    }

    @Synchronized
    private fun finishCancellation(cancellation: TimeoutCancellation): RevisionBarrierObservation {
        terminal?.let { return it }
        return when (cancellation) {
            TimeoutCancellation.Cancelled -> latch(RevisionBarrierOutcome.Cancelled)
            TimeoutCancellation.Unavailable ->
                latch(
                    RevisionBarrierOutcome.Unavailable,
                    StdlibUnavailableReason.CancellationUnavailable,
                )

            TimeoutCancellation.Pending -> snapshot()
        }
    }

    private fun acceptRevision(candidate: ULong) {
        if (candidate > revision) {
            revision = candidate
            generation += 1u
        }
    }

    private fun rejectClockRegression(now: ULong): RevisionBarrierObservation? {
        val previous = lastNow
        if (previous != null && now < previous) {
            return latch(
                RevisionBarrierOutcome.Unavailable,
                StdlibUnavailableReason.ClockRegression,
            )
        }
        lastNow = now
        return null
    }

    private fun latch(
        outcome: RevisionBarrierOutcome,
        reason: StdlibUnavailableReason? = null,
    ): RevisionBarrierObservation {
        val observation = RevisionBarrierObservation(outcome, revision, generation, reason)
        terminal = observation
        return observation
    }

    private fun snapshot(): RevisionBarrierObservation =
        terminal ?: RevisionBarrierObservation(
            RevisionBarrierOutcome.Pending,
            revision,
            generation,
        )
}

private fun <T> invokeFuture(supplier: () -> CompletableFuture<T>): CompletableFuture<T> =
    try {
        supplier()
    } catch (error: Throwable) {
        CompletableFuture.failedFuture(error)
    }
