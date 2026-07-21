package io.github.lazily

/**
 * Presence + ephemeral plane (`#lzpresence`) — the Kotlin port.
 *
 * See `lazily-spec/docs/presence.md` and the formal model
 * `lazily-formal/LazilyFormal/Presence.lean`. The CRDT plane is durable;
 * collaborative apps also need an **ephemeral** plane that does not persist
 * (live cursors, presence). Each primitive is a pure compute core (a keyed map /
 * single value + TTL) split from a reactive cell projecting the live view.
 *
 * The ephemeral plane is distinct from the durable plane, tagged by the
 * [Ephemeral] / [Durable] markers so a durable sink can reject ephemeral values.
 */

/** Marker: a value on the ephemeral plane. MUST NOT be persisted. */
interface Ephemeral

/** Marker: a value that may be written to the durable outbox. */
interface Durable

/** Sentinel for "no ephemeral value" — the reactive cell requires a non-null [Any]. */
private object EphemeralNone

// -- Ephemeral single value --------------------------------------------------

/** Single-value auto-expiry core — "the last value seen in window N". */
class EphemeralCore<T : Any> : Ephemeral {
    private var value: T? = null
    private var expiry: Long = 0

    fun set(value: T, now: Long, ttl: Long) {
        this.value = value
        this.expiry = now + ttl
    }
    fun tick(now: Long) {
        if (value != null && now >= expiry) value = null
    }
    fun value(): T? = value
}

/** Reactive single-value ephemeral cell. */
class EphemeralCell<T : Any>(private val ctx: Context) : Ephemeral {
    private val core = EphemeralCore<T>()
    val valueCell: Source<Any> = ctx.cell<Any>(EphemeralNone)

    private fun refresh() = valueCell.set(ctx, core.value() ?: EphemeralNone)

    fun set(value: T, now: Long, ttl: Long) = core.set(value, now, ttl).also { refresh() }
    fun tick(now: Long) = core.tick(now).also { refresh() }

    @Suppress("UNCHECKED_CAST")
    fun value(): T? = ctx.get(valueCell).let { if (it === EphemeralNone) null else it as T }
}

// -- Keyed per-peer ephemeral map (presence + awareness) ---------------------

/** Per-key ephemeral map with TTL eviction — shared by presence and awareness. */
class EphemeralMapCore<K : Any, V : Any> : Ephemeral {
    private val entries = mutableMapOf<K, Pair<V, Long>>()

    fun set(key: K, value: V, now: Long, ttl: Long) {
        entries[key] = value to (now + ttl)
    }
    fun evict(key: K) {
        entries.remove(key)
    }
    fun tick(now: Long) {
        entries.entries.removeAll { now >= it.value.second }
    }
    fun get(key: K, now: Long): V? = entries[key]?.takeIf { now < it.second }?.first
    fun present(now: Long): Map<K, V> =
        entries.filterValues { now < it.second }.mapValues { it.value.first }
}

/** Reactive per-peer presence: heartbeat-kept, membership- and TTL-evicted. */
class PresenceCell<K : Any, V : Any>(private val ctx: Context, private val ttl: Long) : Ephemeral {
    private val core = EphemeralMapCore<K, V>()
    val presentCell: Source<Any> = ctx.cell<Any>(emptyMap<K, V>())

    private fun refreshAt(now: Long) {
        presentCell.set(ctx, core.present(now))
    }

    fun heartbeat(peer: K, value: V, now: Long) {
        core.set(peer, value, now, ttl); refreshAt(now)
    }
    fun evict(peer: K, now: Long) {
        core.evict(peer); refreshAt(now)
    }
    fun tick(now: Long) {
        core.tick(now); refreshAt(now)
    }

    @Suppress("UNCHECKED_CAST")
    fun present(): Map<K, V> = ctx.get(presentCell) as Map<K, V>
}

/** Reactive typed ephemeral broadcast (cursors/selections): last-writer-per-peer. */
class AwarenessCell<K : Any, V : Any>(private val ctx: Context, private val ttl: Long) : Ephemeral {
    private val core = EphemeralMapCore<K, V>()
    val presentCell: Source<Any> = ctx.cell<Any>(emptyMap<K, V>())

    private fun refreshAt(now: Long) {
        presentCell.set(ctx, core.present(now))
    }

    fun set(peer: K, value: V, now: Long) {
        core.set(peer, value, now, ttl); refreshAt(now)
    }
    fun tick(now: Long) {
        core.tick(now); refreshAt(now)
    }
    fun get(peer: K, now: Long): V? = core.get(peer, now)

    @Suppress("UNCHECKED_CAST")
    fun present(): Map<K, V> = ctx.get(presentCell) as Map<K, V>
}
