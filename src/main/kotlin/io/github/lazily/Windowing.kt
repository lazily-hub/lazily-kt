package io.github.lazily

/**
 * Stream windowing (`#lzwindow`) — the Kotlin port.
 *
 * See `lazily-spec/docs/windowing.md` and the formal model
 * `lazily-formal/LazilyFormal/Windowing.lean`. Window aggregation *is* a merge:
 * the aggregate of a window equals the associative fold of its elements. The
 * cores take a `merge: (T, T) -> T` fold (e.g. Sum = `{ a, b -> a + b }`) so any
 * associative aggregate composes. Each core is split from a reactive cell
 * projecting the last emitted aggregate.
 */

/** Sentinel for "nothing emitted yet" — the reactive cell requires a non-null [Any]. */
private object WindowEmpty

private fun <T> foldWindow(items: List<T>, merge: (T, T) -> T): T? = items.reduceOrNull(merge)

// -- Tumbling (count) --------------------------------------------------------

class TumblingCountCore<T : Any>(n: Long, private val merge: (T, T) -> T) {
    private val n = maxOf(1L, n)
    private var acc: T? = null
    private var count: Long = 0

    fun push(v: T): T? {
        acc = acc?.let { merge(it, v) } ?: v
        count += 1
        if (count >= n) {
            count = 0
            val e = acc
            acc = null
            return e
        }
        return null
    }
}

// -- Tumbling (time) ---------------------------------------------------------

class TumblingTimeCore<T : Any>(period: Long, private val merge: (T, T) -> T) {
    private val period = maxOf(1L, period)
    private var next = this.period
    private var acc: T? = null

    fun push(now: Long, v: T) {
        acc = acc?.let { merge(it, v) } ?: v
    }

    fun tick(now: Long): T? {
        if (now < next) return null
        while (next <= now) next += period
        val e = acc
        acc = null
        return e
    }
}

// -- Sliding (count) ---------------------------------------------------------

class SlidingCore<T : Any>(size: Long, slide: Long, private val merge: (T, T) -> T) {
    private val size = maxOf(1, size.toInt())
    private val slide = maxOf(1L, slide)
    private val buffer = ArrayDeque<T>()
    private var since: Long = 0

    fun push(v: T): T? {
        buffer.addLast(v)
        while (buffer.size > size) buffer.removeFirst()
        since += 1
        if (since >= slide) {
            since = 0
            return foldWindow(buffer.toList(), merge)
        }
        return null
    }
}

// -- Session (gap-based) -----------------------------------------------------

class SessionCore<T : Any>(private val gap: Long, private val merge: (T, T) -> T) {
    private var acc: T? = null
    private var last: Long? = null

    fun push(now: Long, v: T): T? {
        val l = last
        val idleBreak = l != null && now - l > gap && acc != null
        return if (idleBreak) {
            val emit = acc
            acc = v
            last = now
            emit
        } else {
            acc = acc?.let { merge(it, v) } ?: v
            last = now
            null
        }
    }

    fun flush(now: Long): T? {
        val l = last
        return if (l != null && now - l > gap && acc != null) {
            val emit = acc
            acc = null
            emit
        } else {
            null
        }
    }
}

// -- Reactive cells ----------------------------------------------------------

/** Shared reactive-cell projection: last emitted aggregate on a `Cell`. */
private class WindowOutput<T : Any>(private val ctx: Context) {
    val cell: Source<Any> = ctx.cell<Any>(WindowEmpty)
    fun emit(e: T?): T? {
        if (e != null) cell.set(ctx, e)
        return e
    }
    @Suppress("UNCHECKED_CAST")
    fun value(): T? = ctx.get(cell).let { if (it === WindowEmpty) null else it as T }
}

class TumblingCountWindow<T : Any>(ctx: Context, n: Long, merge: (T, T) -> T) {
    private val core = TumblingCountCore(n, merge)
    private val out = WindowOutput<T>(ctx)
    val outputCell: Source<Any> get() = out.cell
    fun push(v: T): T? = out.emit(core.push(v))
    fun output(): T? = out.value()
}

class TumblingTimeWindow<T : Any>(ctx: Context, period: Long, merge: (T, T) -> T) {
    private val core = TumblingTimeCore(period, merge)
    private val out = WindowOutput<T>(ctx)
    val outputCell: Source<Any> get() = out.cell
    fun push(now: Long, v: T) = core.push(now, v)
    fun tick(now: Long): T? = out.emit(core.tick(now))
    fun output(): T? = out.value()
}

class SlidingWindow<T : Any>(ctx: Context, size: Long, slide: Long, merge: (T, T) -> T) {
    private val core = SlidingCore(size, slide, merge)
    private val out = WindowOutput<T>(ctx)
    val outputCell: Source<Any> get() = out.cell
    fun push(v: T): T? = out.emit(core.push(v))
    fun output(): T? = out.value()
}

class SessionWindow<T : Any>(ctx: Context, gap: Long, merge: (T, T) -> T) {
    private val core = SessionCore(gap, merge)
    private val out = WindowOutput<T>(ctx)
    val outputCell: Source<Any> get() = out.cell
    fun push(now: Long, v: T): T? = out.emit(core.push(now, v))
    fun flush(now: Long): T? = out.emit(core.flush(now))
    fun output(): T? = out.value()
}
