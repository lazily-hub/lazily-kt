package io.github.lazily

/**
 * Thrown when a **disposed** node is read or written (`read_after_dispose`,
 * `#lzspecedgeindex`).
 *
 * Disposal is not a value. A binding that answers a read on a torn-down node
 * with its last-computed value, a zero, or a null makes "torn down"
 * indistinguishable from "legitimately this value", and a use-after-dispose bug
 * then surfaces as a wrong number far from its cause.
 *
 * ## Why an id, not a handle
 *
 * lazily-kt's three contexts all store nodes in a dense arena indexed by a
 * recycled integer id (the same shape as `lazily-rs`), so the only stable thing
 * to name here is the id and the kind the caller *expected* to find. That is
 * also the whole read-after-dispose test: a handle is stale exactly when the
 * arena slot is empty or holds a node of a different kind.
 *
 * Extends [IllegalStateException] so it is catchable specifically without
 * breaking callers that already catch the broader type the arena lookups used
 * to throw.
 */
class DisposedNodeException internal constructor(
    /** Arena id the stale handle named. */
    val nodeId: Int,
    /** Node kind the caller expected to find at [nodeId]. */
    val expectedKind: String,
) : IllegalStateException(
    "read after dispose: no live $expectedKind at node id $nodeId — it was disposed " +
        "(or its id was recycled onto a node of another kind)",
)

/**
 * A node in a [Context]'s reactive graph (`#lzspecedgeindex`).
 *
 * Sealed: [SlotHandle], [CellHandle], and [EffectHandle] are the only
 * implementations and the type cannot be implemented downstream. It exists so
 * [Context.dependentCount], [Context.dependencyCount], [Context.disposeNode],
 * and [TeardownScope] can accept any node kind *without* exposing the node's
 * edge lists or its cached value — the mirror of `lazily-rs`'s sealed
 * `GraphNode` trait, which exposes a node id and nothing else.
 *
 * The introspection surface is deliberately **counts, not collections**. A
 * caller can assert on graph shape without a path to the internals, without a
 * way to mutate an edge set, and without pinning a storage strategy: whether a
 * degree is served by [SmallEdgeList]'s inline pair, its linear scan, or its
 * promoted hash index is not part of the contract.
 *
 * Sealing is also what makes the three contexts non-interchangeable at the type
 * level — [ThreadSafeGraphNode] and [AsyncGraphNode] are separate hierarchies,
 * so a handle minted by one context cannot be handed to another's `dispose`.
 */
sealed interface GraphNode {
    /** Dense-arena id this handle names. Not a public identity — see the class doc. */
    val nodeId: Int
}

/** A node in a [ThreadSafeContext]'s reactive graph. See [GraphNode]. */
sealed interface ThreadSafeGraphNode {
    /** Dense-arena id this handle names. */
    val nodeId: Int
}

/** A node in an [AsyncContext]'s reactive graph. See [GraphNode]. */
sealed interface AsyncGraphNode {
    /** Dense-arena id this handle names. */
    val nodeId: Int
}
