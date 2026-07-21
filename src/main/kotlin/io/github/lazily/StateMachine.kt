package io.github.lazily

/**
 * A finite state machine backed by a reactive [Context] — the native Kotlin
 * counterpart to `StateMachine<S, E>` in lazily-rs / lazily-py / lazily-zig.
 *
 * The state lives in a [CellHandle] so any slot, signal, or effect that reads
 * [state] is automatically invalidated when the machine transitions. The
 * transition function is pure: `(state, event) -> next?`. Returning `null`
 * rejects the event (guard); returning a value accepts it and sets the cell.
 * A self-transition that returns an equal state is accepted but suppressed by
 * the cell's `==` guard, so no downstream cascade fires.
 *
 * Example:
 *
 * ```kotlin
 * val ctx = Context()
 * val m = StateMachine(ctx, "Red") { s, _: String ->
 *     when (s) {
 *         "Red" -> "Green"; "Green" -> "Yellow"; "Yellow" -> "Red"
 *         else -> null
 *     }
 * }
 * m.send("advance") // true
 * m.state           // "Green"
 * ```
 */
class StateMachine<S : Any, E>(
    private val ctx: Context,
    initial: S,
    private val transition: (S, E) -> S?,
) {
    private val stateId: Int = ctx.cellAny(initial)

    /** Send an event. Returns `true` if accepted, `false` if rejected (guard). */
    fun send(event: E): Boolean {
        val current = state
        val next = transition(current, event) ?: return false
        ctx.setCellAny(stateId, next)
        return true
    }

    /** The current state. Auto-subscribes when read inside a slot/signal/effect. */
    @Suppress("UNCHECKED_CAST")
    val state: S
        get() = ctx.getCellAny(stateId) as S

    /** The underlying cell id, for reactive composition via the owning [Context]. */
    fun stateId(): Int = stateId

    /**
     * Register an effect that fires with `(old, new)` whenever the machine
     * transitions to a different state. Not called on registration; only on
     * subsequent state changes. This is the FSM analog of on-enter/on-exit.
     * Dispose the returned handle to stop observing.
     */
    fun onTransition(handler: (old: S, new: S) -> Unit): Effect {
        var prev: S? = null
        var hasPrev = false
        return ctx.effect {
            val current = state
            if (hasPrev && prev != current) {
                @Suppress("UNCHECKED_CAST")
                handler(prev as S, current)
            }
            prev = current
            hasPrev = true
            null
        }
    }

    /** An eager signal that is `true` when the machine is in [target], else `false`. */
    fun stateIs(target: S): SignalHandle<Boolean> {
        val ids = ctx.signalAny { state == target }
        return SignalHandle(SlotHandle(ids.slot), Effect(ids.effect))
    }
}
