package io.github.lazily

/**
 * Storage- and transport-independent replicated tree contract.
 *
 * Implementations expose a materialized [value], a compact causal frontier, and
 * deltas that are safe to replay or reorder. [mergeFrom] is the state-based
 * convenience operation over the same join-semilattice.
 */
interface CrdtTree<Self, VersionVector, Delta, Value>
where Self : CrdtTree<Self, VersionVector, Delta, Value> {
    fun versionVector(): VersionVector

    fun deltaSince(theirVersion: VersionVector): Delta

    fun applyDelta(delta: Delta): Boolean

    fun value(): Value

    fun mergeFrom(other: Self): Boolean
}
