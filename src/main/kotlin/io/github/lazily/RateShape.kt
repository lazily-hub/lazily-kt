package io.github.lazily

/**
 * Rate-shaping source operators (`#lzrateshape`) — the Kotlin port.
 *
 * See `lazily-spec/docs/rate-shaping.md` and the formal model
 * `lazily-formal/LazilyFormal/RateShape.lean`. These lift debounce / throttle /
 * time-sampling out of the relay egress so any `Reactive<T>` source can be
 * rate-shaped. (The relay policies `RatePolicy`/`WindowPolicy`/`ExpiryPolicy`
 * already live in [Relay]; this file adds the source-level operators.)
 *
 * Each operator is a pure compute **core** (the emit/drop decision) split from a
 * thin reactive **cell** that projects the emitted value onto a `Cell` so a
 * dropped input never invalidates dependents. Time is the same monotone logical
 * clock as `#lztime`.
 */

/** Sentinel for "no output emitted yet" — the reactive cell requires a non-null [Any]. */
private object RateShapeEmpty

// -- Debounce ---------------------------------------------------------------

/** Debounce core: coalesce inputs (KeepLatest) and emit the latest value only
 *  after `quiet` ticks with no new input; every input resets the deadline. */
class DebounceCore<T : Any>(private val quiet: Long) {
    private var pending: T? = null
    private var fireAt: Long = 0
    private var armed: Boolean = false

    fun input(now: Long, v: T) {
        pending = v
        fireAt = now + quiet
        armed = true
    }

    fun tick(now: Long): T? {
        if (armed && pending != null && fireAt <= now) {
            armed = false
            val p = pending
            pending = null
            return p
        }
        return null
    }
}

/** Reactive debounce over any `Reactive<T>` source. */
class DebounceCell<T : Any>(private val ctx: Context, quiet: Long) {
    private val core = DebounceCore<T>(quiet)
    val outputCell: CellHandle<Any> = ctx.cell(RateShapeEmpty)

    fun input(now: Long, v: T) = core.input(now, v)

    fun tick(now: Long): T? {
        val emitted = core.tick(now)
        if (emitted != null) ctx.setCell(outputCell, emitted)
        return emitted
    }

    @Suppress("UNCHECKED_CAST")
    fun output(): T? = ctx.getCell(outputCell).let { if (it === RateShapeEmpty) null else it as T }
}

// -- Throttle ---------------------------------------------------------------

enum class ThrottleEdge { Leading, Trailing }

/** Throttle core: at most one emit per `window`. */
class ThrottleCore<T : Any>(private val edge: ThrottleEdge, private val window: Long) {
    private var windowEnd: Long? = null
    private var windowStart: Long? = null
    private var pending: T? = null

    fun input(now: Long, v: T): T? =
        when (edge) {
            ThrottleEdge.Leading -> {
                val we = windowEnd
                if (we != null && now < we) {
                    null
                } else {
                    windowEnd = now + window
                    v
                }
            }
            ThrottleEdge.Trailing -> {
                if (windowStart == null) windowStart = now
                pending = v
                null
            }
        }

    fun tick(now: Long): T? {
        if (edge != ThrottleEdge.Trailing) return null
        val ws = windowStart ?: return null
        if (now >= ws + window && pending != null) {
            windowStart = null
            val p = pending
            pending = null
            return p
        }
        return null
    }
}

/** Reactive throttle over any `Reactive<T>` source. */
class ThrottleCell<T : Any>(private val ctx: Context, edge: ThrottleEdge, window: Long) {
    private val core = ThrottleCore<T>(edge, window)
    val outputCell: CellHandle<Any> = ctx.cell(RateShapeEmpty)

    fun input(now: Long, v: T): T? {
        val emitted = core.input(now, v)
        if (emitted != null) ctx.setCell(outputCell, emitted)
        return emitted
    }

    fun tick(now: Long): T? {
        val emitted = core.tick(now)
        if (emitted != null) ctx.setCell(outputCell, emitted)
        return emitted
    }

    @Suppress("UNCHECKED_CAST")
    fun output(): T? = ctx.getCell(outputCell).let { if (it === RateShapeEmpty) null else it as T }
}

// -- Sample -----------------------------------------------------------------

/** Sampling mode for [SampleCore]. */
sealed class SampleMode {
    data class Count(val n: Long) : SampleMode()
    data class Time(val period: Long) : SampleMode()
}

/** Deterministic sampling core. */
class SampleCore<T : Any>(private val mode: SampleMode) {
    private var counter: Long = 0
    private var next: Long = if (mode is SampleMode.Time) maxOf(1L, mode.period) else 0
    private var held: T? = null

    fun input(v: T): T? =
        when (mode) {
            is SampleMode.Count -> {
                val n = maxOf(1L, mode.n)
                counter += 1
                if (counter % n == 0L) v else null
            }
            is SampleMode.Time -> {
                held = v
                null
            }
        }

    fun tick(now: Long): T? {
        if (mode !is SampleMode.Time) return null
        val period = maxOf(1L, mode.period)
        if (now < next) return null
        val fires = (now - next) / period + 1
        next += fires * period
        return held // held latest persists across emits
    }
}

/** Reactive sampler over any `Reactive<T>` source. */
class SampleCell<T : Any>(private val ctx: Context, mode: SampleMode) {
    private val core = SampleCore<T>(mode)
    val outputCell: CellHandle<Any> = ctx.cell(RateShapeEmpty)

    fun input(v: T): T? {
        val emitted = core.input(v)
        if (emitted != null) ctx.setCell(outputCell, emitted)
        return emitted
    }

    fun tick(now: Long): T? {
        val emitted = core.tick(now)
        if (emitted != null) ctx.setCell(outputCell, emitted)
        return emitted
    }

    @Suppress("UNCHECKED_CAST")
    fun output(): T? = ctx.getCell(outputCell).let { if (it === RateShapeEmpty) null else it as T }
}

// -- Probabilistic sample ----------------------------------------------------

/** An injectable RNG so probabilistic sampling is deterministic under a seed. */
fun interface SampleRng {
    /** A draw in [0, 1). */
    fun nextDouble(): Double
}

/** A small deterministic SplitMix64 LCG — no external RNG dependency. */
class Lcg(seed: Long) : SampleRng {
    private var state: Long = seed

    override fun nextDouble(): Double {
        state += -0x61c8864680b583 // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47 // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15 // 0x94D049BB133111EB
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}

/** Probabilistic (tail) sampling core — a draw in [0,1) passes iff draw < rate. */
class ProbabilisticSampleCore(rate: Double) {
    val rate: Double = rate.coerceIn(0.0, 1.0)
    fun decide(draw: Double): Boolean = draw < rate
}

/** Reactive probabilistic sampler; owns an injectable [SampleRng]. */
class ProbabilisticSampleCell<T : Any>(
    private val ctx: Context,
    rate: Double,
    private val rng: SampleRng,
) {
    private val core = ProbabilisticSampleCore(rate)
    val outputCell: CellHandle<Any> = ctx.cell(RateShapeEmpty)

    fun input(v: T): T? = inputWithDraw(v, rng.nextDouble())

    fun inputWithDraw(v: T, draw: Double): T? {
        if (core.decide(draw)) {
            ctx.setCell(outputCell, v)
            return v
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun output(): T? = ctx.getCell(outputCell).let { if (it === RateShapeEmpty) null else it as T }
}
