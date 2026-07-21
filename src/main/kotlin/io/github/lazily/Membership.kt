package io.github.lazily

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Membership + failure detection (`#lzmemb`) — the Kotlin port.
 *
 * See `lazily-spec/docs/membership.md` and the formal model
 * `lazily-formal/LazilyFormal/Membership.lean`. A [MembershipCell] is a reactive
 * view of the live peer set backed by SWIM-style heartbeats + a **Phi-accrual**
 * failure detector; the derived [peerSet] is the `Alive` peers. The pure core
 * ([MembershipCore] + [PhiAccrual]) is the phi math + SWIM state machine, split
 * from the reactive cell projecting the alive set onto a `Cell`.
 */

/** Per-peer liveness state (SWIM). */
enum class PeerState { Alive, Suspect, Dead, Left }

/** A diff event over the membership cell. */
sealed class PeerChangeEvent<P> {
    data class Joined<P>(val peer: P) : PeerChangeEvent<P>()
    data class Left<P>(val peer: P) : PeerChangeEvent<P>()
    data class StateChanged<P>(val peer: P, val from: PeerState, val to: PeerState) :
        PeerChangeEvent<P>()
}

/** Tunables for the failure detector + SWIM state machine. */
data class MembershipConfig(
    val phiThreshold: Double = 8.0,
    val suspectTimeout: Long = 5,
    val maxSamples: Int = 100,
    val minStd: Double = 0.1,
)

/** Phi-accrual failure detector; `phi` uses the bit-portable Akka logistic
 *  approximation of the normal CDF so every binding agrees. */
class PhiAccrual(maxSamples: Int, private val minStd: Double) {
    private val maxSamples = maxOf(1, maxSamples)
    private val window = ArrayDeque<Double>()
    private var lastHeartbeat: Long? = null

    fun heartbeat(now: Long) {
        val last = lastHeartbeat
        if (last != null) {
            window.addLast((now - last).toDouble())
            while (window.size > maxSamples) window.removeFirst()
        }
        lastHeartbeat = now
    }

    private fun mean(): Double = window.sum() / window.size

    private fun std(mean: Double): Double {
        val v = window.sumOf { (it - mean) * (it - mean) } / window.size
        return maxOf(sqrt(v), minStd)
    }

    fun phi(now: Long): Double {
        val last = lastHeartbeat ?: return 0.0
        if (window.isEmpty()) return 0.0
        val elapsed = (now - last).toDouble()
        val mean = mean()
        val std = std(mean)
        val y = (elapsed - mean) / std
        val e = exp(-y * (1.5976 + 0.070566 * y * y))
        return if (elapsed > mean) -log10(e / (1 + e)) else -log10(1 - 1 / (1 + e))
    }
}

private class PeerRecord(var state: PeerState, val detector: PhiAccrual, var suspectSince: Long?)

/** The pure membership compute core: the SWIM state machine over a keyed peer
 *  map, driven by heartbeats and a logical clock. */
class MembershipCore<P : Comparable<P>>(private val config: MembershipConfig) {
    private val peers = sortedMapOf<P, PeerRecord>()

    private fun newDetector() = PhiAccrual(config.maxSamples, config.minStd)

    /** The current alive peer set (the reactive `PeerSet`). */
    fun aliveSet(): Set<P> =
        peers.filterValues { it.state == PeerState.Alive }.keys.toSortedSet()

    fun state(peer: P): PeerState? = peers[peer]?.state

    fun join(peer: P, now: Long): List<PeerChangeEvent<P>> {
        val detector = newDetector()
        detector.heartbeat(now)
        val prev = peers[peer]?.state
        peers[peer] = PeerRecord(PeerState.Alive, detector, null)
        return when {
            prev == null -> listOf(PeerChangeEvent.Joined(peer))
            prev == PeerState.Alive -> emptyList()
            else -> listOf(PeerChangeEvent.StateChanged(peer, prev, PeerState.Alive))
        }
    }

    fun heartbeat(peer: P, now: Long): List<PeerChangeEvent<P>> {
        val record = peers[peer] ?: return join(peer, now)
        record.detector.heartbeat(now)
        val from = record.state
        if (from != PeerState.Alive && from != PeerState.Left) {
            record.state = PeerState.Alive
            record.suspectSince = null
            return listOf(PeerChangeEvent.StateChanged(peer, from, PeerState.Alive))
        }
        return emptyList()
    }

    fun leave(peer: P, now: Long): List<PeerChangeEvent<P>> {
        val record = peers[peer] ?: return emptyList()
        if (record.state == PeerState.Left) return emptyList()
        record.state = PeerState.Left
        record.suspectSince = null
        return listOf(PeerChangeEvent.Left(peer))
    }

    fun tick(now: Long): List<PeerChangeEvent<P>> {
        val events = mutableListOf<PeerChangeEvent<P>>()
        for ((peer, record) in peers) {
            when (record.state) {
                PeerState.Alive ->
                    if (record.detector.phi(now) > config.phiThreshold) {
                        record.state = PeerState.Suspect
                        record.suspectSince = now
                        events.add(PeerChangeEvent.StateChanged(peer, PeerState.Alive, PeerState.Suspect))
                    }
                PeerState.Suspect -> {
                    val since = record.suspectSince
                    if (since != null && now - since >= config.suspectTimeout) {
                        record.state = PeerState.Dead
                        events.add(PeerChangeEvent.StateChanged(peer, PeerState.Suspect, PeerState.Dead))
                    }
                }
                PeerState.Dead, PeerState.Left -> {}
            }
        }
        return events
    }
}

/** Reactive membership: drives a [MembershipCore] and projects the alive set
 *  onto a `Cell` so the [peerSet] invalidates only on a set change. */
class MembershipCell<P : Comparable<P>>(private val ctx: Context, config: MembershipConfig) {
    private val core = MembershipCore<P>(config)
    val peerSetCell: Source<Any> = ctx.cell<Any>(emptySet<P>())

    private fun refresh() = peerSetCell.set(ctx, core.aliveSet())

    fun join(peer: P, now: Long): List<PeerChangeEvent<P>> = core.join(peer, now).also { refresh() }
    fun heartbeat(peer: P, now: Long): List<PeerChangeEvent<P>> =
        core.heartbeat(peer, now).also { refresh() }
    fun leave(peer: P, now: Long): List<PeerChangeEvent<P>> = core.leave(peer, now).also { refresh() }
    fun tick(now: Long): List<PeerChangeEvent<P>> = core.tick(now).also { refresh() }

    @Suppress("UNCHECKED_CAST")
    fun peerSet(): Set<P> = ctx.get(peerSetCell) as Set<P>

    fun state(peer: P): PeerState? = core.state(peer)
}
