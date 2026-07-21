package io.github.lazily

/**
 * Embedded-service plane (`#lzservice`) — the Kotlin port.
 *
 * See `lazily-spec/docs/service.md` and the formal model
 * `lazily-formal/LazilyFormal/Service.lean`. HealthCell / ReadinessCell /
 * DiscoveryCell / ServiceRegistry, each a pure compute core split from a
 * reactive cell projecting the composed view.
 */

/** Composed health status (worst component dominates). */
enum class Health { Healthy, Degraded, Unhealthy }

/** Composed liveness-probe core. Each probe reports `up` and `critical`. */
class HealthCore {
    private val probes = sortedMapOf<String, Pair<Boolean, Boolean>>() // name -> (up, critical)

    fun set(name: String, up: Boolean, critical: Boolean) {
        probes[name] = up to critical
    }

    fun health(): Health {
        var anyDown = false
        for ((up, critical) in probes.values) {
            if (!up && critical) return Health.Unhealthy
            if (!up) anyDown = true
        }
        return if (anyDown) Health.Degraded else Health.Healthy
    }
}

/** Reactive health: projects the aggregate onto a `Cell` for /health. */
class HealthCell(private val ctx: Context) {
    private val core = HealthCore()
    val healthCell: Source<Health> = ctx.source(Health.Healthy)

    private fun refresh() = healthCell.set(ctx, core.health())

    fun set(name: String, up: Boolean, critical: Boolean) = core.set(name, up, critical).also { refresh() }
    fun health(): Health = core.health()
}

/** Composed readiness-probe core: ready iff every condition holds. */
class ReadinessCore {
    private val conditions = sortedMapOf<String, Boolean>()
    fun set(name: String, ready: Boolean) {
        conditions[name] = ready
    }
    fun ready(): Boolean = conditions.values.all { it }
}

/** Reactive readiness: projects ready onto a `Cell` for /ready. */
class ReadinessCell(private val ctx: Context) {
    private val core = ReadinessCore()
    val readyCell: Source<Boolean> = ctx.source(true)

    private fun refresh() = readyCell.set(ctx, core.ready())

    fun set(name: String, ready: Boolean) = core.set(name, ready).also { refresh() }
    fun ready(): Boolean = core.ready()
}

/** Service-discovery core: service -> (endpoint, owner peer). A peer's departure
 *  (evict) removes its endpoints. */
class DiscoveryCore<P : Any> {
    private val entries = sortedMapOf<String, Pair<String, P>>()

    fun register(service: String, endpoint: String, peer: P) {
        entries[service] = endpoint to peer
    }
    fun deregister(service: String) {
        entries.remove(service)
    }
    fun evict(peer: P) {
        entries.entries.removeAll { it.value.second == peer }
    }
    fun resolve(service: String): String? = entries[service]?.first
    fun discovery(): Map<String, String> = entries.mapValues { it.value.first }
}

/** Reactive service discovery. */
class DiscoveryCell<P : Any>(private val ctx: Context) {
    private val core = DiscoveryCore<P>()
    val discoveryCell: Source<Any> = ctx.cell<Any>(emptyMap<String, String>())

    private fun refresh() = discoveryCell.set(ctx, core.discovery())

    fun register(service: String, endpoint: String, peer: P) =
        core.register(service, endpoint, peer).also { refresh() }
    fun deregister(service: String) = core.deregister(service).also { refresh() }
    fun evict(peer: P) = core.evict(peer).also { refresh() }
    fun resolve(service: String): String? = core.resolve(service)

    @Suppress("UNCHECKED_CAST")
    fun discovery(): Map<String, String> = ctx.get(discoveryCell) as Map<String, String>
}

/** A durable registry op (the ordered log entry). */
sealed class RegistryOp {
    data class Register(val service: String, val endpoint: String) : RegistryOp()
    data class Deregister(val service: String) : RegistryOp()
}

/** Durable service-registry core: an ordered log whose left-fold is the
 *  projection, so replay reconstructs it. */
class ServiceRegistryCore {
    private val log = mutableListOf<RegistryOp>()
    private var projection = sortedMapOf<String, String>()

    private fun apply(projection: MutableMap<String, String>, op: RegistryOp) {
        when (op) {
            is RegistryOp.Register -> projection[op.service] = op.endpoint
            is RegistryOp.Deregister -> projection.remove(op.service)
        }
    }

    fun register(service: String, endpoint: String) {
        val op = RegistryOp.Register(service, endpoint)
        apply(projection, op)
        log.add(op)
    }
    fun deregister(service: String) {
        val op = RegistryOp.Deregister(service)
        apply(projection, op)
        log.add(op)
    }
    fun replay() {
        val rebuilt = sortedMapOf<String, String>()
        for (op in log) apply(rebuilt, op)
        projection = rebuilt
    }
    fun projection(): Map<String, String> = projection.toMap()
}

/** Reactive durable service registry. */
class ServiceRegistry(private val ctx: Context) {
    private val core = ServiceRegistryCore()
    val projectionCell: Source<Any> = ctx.cell<Any>(emptyMap<String, String>())

    private fun refresh() = projectionCell.set(ctx, core.projection())

    fun register(service: String, endpoint: String) = core.register(service, endpoint).also { refresh() }
    fun deregister(service: String) = core.deregister(service).also { refresh() }
    fun replay() = core.replay().also { refresh() }

    @Suppress("UNCHECKED_CAST")
    fun projection(): Map<String, String> = ctx.get(projectionCell) as Map<String, String>
}
