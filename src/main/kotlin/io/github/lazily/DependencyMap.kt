package io.github.lazily

/**
 * Exact-key dependency availability.
 *
 * Unpublished is a value of a stable reactive source, not absence from a map.
 * Publication is therefore a normal `Unavailable -> Available(value)` source
 * transition and never needs a membership epoch or request/ack handshake.
 */
sealed class DependencyAvailability<out V : Any> {
    data object Unavailable : DependencyAvailability<Nothing>()

    data class Available<V : Any>(
        val value: V,
    ) : DependencyAvailability<V>()
}

/** Single-threaded exact-key dependency publication. */
class DependencyMap<K : Any, V : Any>(
    private val ctx: Context,
) {
    private val sources = SourceMap<K, DependencyAvailability<V>>(ctx)

    fun observeDependency(
        key: K,
        ops: ComputeOps = ctx,
    ): DependencyAvailability<V> {
        if (!sources.containsNow(key)) sources.insert(key, DependencyAvailability.Unavailable)
        return sources.get(key, ops)
    }

    fun publish(
        key: K,
        value: V,
    ) {
        val state = DependencyAvailability.Available(value)
        if (sources.containsNow(key)) sources.setValue(key, state) else sources.insert(key, state)
    }

    fun unpublish(key: K) {
        if (sources.containsNow(key)) {
            sources.setValue(key, DependencyAvailability.Unavailable)
        } else {
            sources.insert(key, DependencyAvailability.Unavailable)
        }
    }

    fun handle(key: K): Source<DependencyAvailability<V>>? =
        if (sources.containsNow(key)) sources.value(key) else null

    val presentCount: Int get() = sources.presentCount
}

/** Thread-safe exact-key dependency publication. */
class ThreadSafeDependencyMap<K : Any, V : Any> {
    private val sources = ThreadSafeSourceMap<K, DependencyAvailability<V>>()

    fun observeDependency(
        ctx: ThreadSafeContext,
        key: K,
    ): DependencyAvailability<V> {
        sources.entry(ctx, key) { DependencyAvailability.Unavailable }
        return sources.observe(ctx, key)
    }

    fun publish(
        ctx: ThreadSafeContext,
        key: K,
        value: V,
    ) = sources.set(ctx, key, DependencyAvailability.Available(value))

    fun unpublish(
        ctx: ThreadSafeContext,
        key: K,
    ) = sources.set(ctx, key, DependencyAvailability.Unavailable)

    fun handle(key: K): ThreadSafeSource<DependencyAvailability<V>>? = sources.handle(key)

    val presentCount: Int get() = sources.presentCount
}

/** Async-flavor exact-key dependency publication. */
class AsyncDependencyMap<K : Any, V : Any> {
    private val sources = AsyncSourceMap<K, DependencyAvailability<V>>()

    fun observeDependency(
        ctx: AsyncContext,
        key: K,
        cc: AsyncComputeContext? = null,
    ): DependencyAvailability<V> {
        sources.entry(ctx, key) { DependencyAvailability.Unavailable }
        return sources.get(ctx, key, cc)
            ?: error("dependency source disappeared after materialization for key $key")
    }

    fun publish(
        ctx: AsyncContext,
        key: K,
        value: V,
    ) = sources.set(ctx, key, DependencyAvailability.Available(value))

    fun unpublish(
        ctx: AsyncContext,
        key: K,
    ) = sources.set(ctx, key, DependencyAvailability.Unavailable)

    fun handle(key: K): AsyncContext.AsyncSource<DependencyAvailability<V>>? = sources.handle(key)

    val presentCount: Int get() = sources.presentCount
}
