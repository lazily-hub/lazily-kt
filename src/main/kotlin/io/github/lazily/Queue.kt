package io.github.lazily

// -- Reactive queue ---------------------------------------------------------
//
// Native Kotlin implementation of the lazily-spec reactive queue
// (`lazily-spec/cell-model.md` § "Reactive queues"). A `QueueCell` is a FIFO
// collection composed of reactive cells — **not a new cell kind** — that adds
// queue semantics (push to tail, pop from head) to the reactive graph. It is
// specified as a **single-producer, single-consumer (SPSC)** primitive;
// **MPSC** (multi-producer) is a *usage rule* on the same primitive — multiple
// producers push inside a `Context.batch { … }` boundary, and the batch
// serializes the pushes into a deterministic order. There is no separate
// `MPSCQueueCell` type.
//
// ## Shell vs storage
//
// The reactive shell owns the reader-kind version cells (`head` / `len` /
// `is_empty` / `is_full` / `closed`) and the invalidation logic; it is
// storage-agnostic. The storage backend owns the actual FIFO data structure and
// is pluggable via [QueueStorage]. The default [VecDequeStorage] is an unbounded
// deque; a bounded variant exposes reactive backpressure via `is_full`. A
// distributed backend (`RaftQueueStorage`, future work per the distributed-queue
// PRD) or an external-broker adapter (`KafkaStorage`, etc.) plugs into the same
// reactive shell.
//
// ## Reader-kind invalidation
//
// Invalidation is scoped to **reader kind**, not to individual positions. A
// push invalidates `len` / `is_empty` readers (and `head` when transitioning
// from empty, and `is_full` when transitioning onto capacity); a pop invalidates
// `head` / `len` / `is_empty` readers (and `is_full` when transitioning off
// capacity). The head reader observes the *current* head value — after a pop,
// the head reader sees the next element (or `null`), not a stale value.
//
// This reader-kind independence is implemented for free by the existing
// `==` guard on [Context.setCell]: after each op the shell re-derives each
// reader-kind cell from the storage and writes it back, and a cell whose value
// did not change is not invalidated.

/** Sentinel stored in the `head` reader-kind cell when the queue is empty. */
private object NoHeadSentinel

/** The `head` cell holds either this singleton (empty) or a real element. */
private val NO_HEAD: Any = NoHeadSentinel

/**
 * Failure modes for [QueueStorage.tryPush] / [QueueCell.tryPush].
 *
 * `Full` and `Closed` are the two observable rejection reasons distinguished by
 * the shell's contract (`lazily-spec/cell-model.md` § "Storage backend
 * contract"). Neither changes queue state, so neither invalidates any reader.
 */
sealed class QueuePushError {
    /** The backend is bounded and at capacity. The overflow policy (block / drop-oldest / drop-newest / reject) is a backend property; the reference [VecDequeStorage] rejects. Distinct from [Closed]. */
    data object Full : QueuePushError()

    /** The queue is closed; push is rejected regardless of capacity. Terminal — once closed, a queue cannot be reopened. */
    data object Closed : QueuePushError()
}

/**
 * Failure modes for [QueueStorage.tryPop] / [QueueCell.tryPop].
 *
 * `Empty` and `Closed` are distinct observable signals: `Empty` means "try
 * again later," `Closed` means "the producer is done and the queue is drained."
 */
sealed class QueuePopError {
    /** The queue is open but contains no elements. */
    data object Empty : QueuePopError()

    /** The queue is closed and empty — the producer is done and all buffered elements have been consumed. Pop on a closed *non-empty* queue still drains (returns the next element); only closed+empty yields [Closed]. */
    data object Closed : QueuePopError()
}

/**
 * Outcome of [QueueCell.tryPop] / [QueueStorage.tryPop]: either a popped
 * [Value] or a [Failed] pop carrying the distinguishing [QueuePopError].
 */
sealed class QueuePop<out T : Any> {
    /** A successfully popped element. */
    data class Value<out T : Any>(val value: T) : QueuePop<T>()

    /** Pop failed — the queue is [QueuePopError.Empty] or [QueuePopError.Closed]. */
    data class Failed(val error: QueuePopError) : QueuePop<Nothing>()
}

// ---------------------------------------------------------------------------
// QueueStorage interface
// ---------------------------------------------------------------------------

/**
 * Pluggable FIFO storage backend for a [QueueCell].
 *
 * The shell / storage split (`lazily-spec/cell-model.md` § "Reactive shell vs
 * storage backend") keeps the reactive shell storage-agnostic: the shell owns
 * the reader-kind version cells and invalidation logic, the backend owns the
 * actual FIFO data structure. The default backend is [VecDequeStorage]
 * (unbounded deque); future backends include `RaftQueueStorage` (embedded
 * consensus, per the distributed-queue PRD) and `KafkaStorage` /
 * `RedisStreamStorage` / `SqsStorage` (external-broker adapters).
 *
 * Conformance:
 *
 * 1. **FIFO order** — [tryPop] returns elements in [tryPush] order.
 * 2. **Cardinality compatibility** — its native producer/consumer shape is a
 *    superset of the shell's required shape (SPSC shell = any backend; MPSC
 *    usage requires a multi-writer backend).
 * 3. **Bounded contract (optional)** — a bounded backend exposes
 *    [capacity] as a non-null value and [tryPush] returns
 *    [QueuePushError.Full] at capacity. The overflow policy is a backend
 *    property.
 * 4. **Position identity** — invalidation is phrased over reader kind, not
 *    storage indices. The shell layers its own logical version counters (the
 *    reader-kind cells) above the storage.
 *
 * `is_empty` is deliberately NOT on this interface: emptiness is a shell-level
 * reader kind derived from [len].
 *
 * @param T element type (non-null)
 */
interface QueueStorage<T : Any> {
    /**
     * Append [value] to the tail. Returns [QueuePushError.Full] if bounded and
     * at capacity, or [QueuePushError.Closed] if the queue is closed. Returns
     * `null` on success. On error the queue state is unchanged.
     */
    fun tryPush(value: T): QueuePushError?

    /**
     * Remove and return the head element. Returns [QueuePop.Value] on success,
     * or [QueuePop.Failed] carrying [QueuePopError.Empty] if open and empty, or
     * [QueuePopError.Closed] if closed and empty. Pop on a closed *non-empty*
     * queue drains (returns the next element).
     */
    fun tryPop(): QueuePop<T>

    /**
     * **Optional capability.** Peek the current head element without removing
     * it, `null` when empty. The default returns `null` — a backend that cannot
     * peek (a raw channel) is fully conforming and simply has no `head` reader.
     */
    fun peek(): T? = null

    /** Current number of buffered elements. **Required.** */
    fun len(): Int

    /** **Optional capability.** Bounded capacity, or `null` for an unbounded backend (the default). When non-null, the shell exposes `is_full` as a reactive read. */
    fun capacity(): Int? = null

    /** Whether the queue has been closed. Close is terminal — once true, it stays true. */
    fun isClosed(): Boolean

    /** Close the queue. Idempotent — closing an already-closed queue is a no-op. After close, [tryPush] returns [QueuePushError.Closed]; [tryPop] continues to drain and returns [QueuePopError.Closed] only once empty. */
    fun close()
}

// ---------------------------------------------------------------------------
// VecDequeStorage — the reference unbounded/bounded backend
// ---------------------------------------------------------------------------

/**
 * The reference [QueueStorage] backend: an [ArrayDeque]-backed FIFO, optionally
 * bounded.
 *
 * The unbounded form (the default) is what [QueueCell.invoke] uses; the bounded
 * form ([secondary constructor][VecDequeStorage]) exposes reactive backpressure
 * via the shell's `is_full` reader. The overflow policy is **reject** —
 * [tryPush] at capacity returns [QueuePushError.Full] (elements are never
 * silently dropped); other backends may choose block / drop-oldest /
 * drop-newest.
 *
 * Serializes as a JSON array (element order = FIFO order) for conformance
 * fixture purposes, matching `lazily-spec/cell-model.md` § "Wire and snapshot
 * shape".
 */
class VecDequeStorage<T : Any> private constructor(
    private val elements: ArrayDeque<T>,
    private val capacity: Int?,
    private var closed: Boolean,
) : QueueStorage<T> {
    /** Create an unbounded storage (no capacity limit). */
    constructor() : this(ArrayDeque(), null, false)

    /**
     * Create a bounded storage that rejects pushes once it holds [capacity]
     * elements.
     *
     * @throws IllegalArgumentException if [capacity] == 0 (a zero-capacity queue can never accept an element and has no useful semantics).
     */
    constructor(capacity: Int) : this(ArrayDeque(capacity), capacity, false) {
        require(capacity > 0) { "VecDequeStorage capacity must be > 0" }
    }

    override fun tryPush(value: T): QueuePushError? {
        if (closed) return QueuePushError.Closed
        if (capacity != null && elements.size >= capacity) return QueuePushError.Full
        elements.addLast(value)
        return null
    }

    override fun tryPop(): QueuePop<T> = when {
        elements.isNotEmpty() -> QueuePop.Value(elements.removeFirst())
        closed -> QueuePop.Failed(QueuePopError.Closed)
        else -> QueuePop.Failed(QueuePopError.Empty)
    }

    override fun peek(): T? = elements.firstOrNull()

    override fun len(): Int = elements.size

    override fun capacity(): Int? = capacity

    override fun isClosed(): Boolean = closed

    override fun close() {
        closed = true
    }

    /** Snapshot the buffered elements in FIFO order. Non-reactive — for snapshot/serde and conformance-fixture verification. */
    fun elements(): List<T> = elements.toList()
}

// ---------------------------------------------------------------------------
// QueueCell — the reactive shell
// ---------------------------------------------------------------------------

/**
 * A reactive FIFO queue — SPSC primitive with an MPSC usage rule
 * (`#lzqueue`).
 *
 * The reactive shell wraps a pluggable [QueueStorage] backend (default
 * [VecDequeStorage]); the shell owns the reader-kind version cells (`head` /
 * `len` / `is_empty` / `is_full` / `closed`) and invalidates by reader kind — a
 * push to a non-empty queue does NOT invalidate the `head` reader, a pop does.
 *
 * Construct via the companion [invoke] overloads (unbounded / bounded
 * `VecDequeStorage`) or the primary constructor ([invoke] with a custom
 * [QueueStorage] backend). Cheap to share — the same [QueueCell] reference can
 * be handed to producer and consumer closures.
 *
 * @param T element type (non-null)
 * @param S storage backend type
 */
class QueueCell<T : Any, S : QueueStorage<T>>(
    private val ctx: Context,
    storage: S,
) {
    /** The backing storage (exposed for [VecDequeStorage]-specific extensions). */
    internal val storage: S = storage

    // Demand-driven reader-kinds: memoized Slots deriving from storage (were
    // eagerly-Set Cells). Each re-derives on first read after invalidation; the
    // shell invalidates only the ones that provably changed on an op (see
    // [invalidateReaders]). `closed` stays a Cell (a direct input, set by
    // [close]). The head slot holds either [NO_HEAD] (empty / no peek) or a real
    // element (it is typed `Any` because a Slot value cannot be null).
    private val headSlot: SlotHandle<Any>
    private val lenSlot: SlotHandle<Int>
    private val isEmptySlot: SlotHandle<Boolean>
    private val isFullSlot: SlotHandle<Boolean>
    private val closedCell: CellHandle<Boolean>

    // `capacity` is an optional, fixed backend capability — cache it once.
    private val cap: Int?

    init {
        val s = storage
        cap = s.capacity()
        val bound = cap
        headSlot = ctx.memo { s.peek() ?: NO_HEAD }
        lenSlot = ctx.memo { s.len() }
        isEmptySlot = ctx.memo { s.len() == 0 }
        isFullSlot = ctx.memo { bound?.let { s.len() >= it } ?: false }
        closedCell = ctx.cell(s.isClosed())
    }

    companion object {
        /** Create an unbounded queue (the default reference backend). */
        fun <T : Any> unbounded(ctx: Context): QueueCell<T, VecDequeStorage<T>> =
            QueueCell(ctx, VecDequeStorage())

        /**
         * Create a bounded queue with [capacity]. Exposes reactive backpressure
         * via [isFull]: a pop that transitions full → not-full invalidates
         * `is_full` readers.
         *
         * @throws IllegalArgumentException if [capacity] == 0.
         */
        fun <T : Any> bounded(ctx: Context, capacity: Int): QueueCell<T, VecDequeStorage<T>> =
            QueueCell(ctx, VecDequeStorage(capacity))
    }

    /**
     * Re-derive the reader-kind cells from storage and write them back, in one
     * atomic invalidation pass (a [Context.batch] groups the writes so an
     * observer never sees a partial state — e.g. `len` bumped but `is_full` not
     * yet flipped). The `==` guard on `setCell` suppresses invalidation for any
     * cell whose value did not change — this is the reader-kind independence
     * law. `closed` is intentionally NOT touched here: it only changes via
     * [close].
     */
    private fun invalidateReaders(lenBefore: Int, lenAfter: Int, headChanged: Boolean) {
        // Collect the ids of exactly the reader Slots whose value provably
        // changed, then invalidate them together (one flush) so an observer never
        // sees a partial state. No reader value is derived here — each Slot
        // re-derives lazily on its next read; an unobserved reader pays nothing.
        val ids = IntArray(4)
        var n = 0
        ids[n++] = lenSlot.id // len always changes on a successful op
        if ((lenBefore == 0) != (lenAfter == 0)) ids[n++] = isEmptySlot.id
        val bound = cap
        if (bound != null && (lenBefore >= bound) != (lenAfter >= bound)) ids[n++] = isFullSlot.id
        if (headChanged) ids[n++] = headSlot.id
        ctx.invalidateSlots(ids.copyOf(n))
    }

    /**
     * Append [value] to the tail of the queue.
     *
     * Returns [QueuePushError.Full] if bounded and at capacity (reject policy —
     * the default [VecDequeStorage] never silently drops), or
     * [QueuePushError.Closed] if the queue is closed. Returns `null` on success.
     * On error the queue state is unchanged and no reader is invalidated.
     *
     * Invalidates `head` (only when transitioning from empty), `len`, and
     * `is_empty` readers as appropriate; `is_full` when transitioning onto
     * capacity. Does not touch `closed`.
     */
    fun tryPush(value: T): QueuePushError? {
        val lenBefore = storage.len()
        val err = storage.tryPush(value)
        // Head changes on a push only when the queue was empty.
        if (err == null) invalidateReaders(lenBefore, lenBefore + 1, lenBefore == 0)
        return err
    }

    /**
     * Remove and return the head element.
     *
     * Returns [QueuePop.Value] on success, or [QueuePop.Failed] carrying
     * [QueuePopError.Empty] if open and empty, or [QueuePopError.Closed] if
     * closed and empty. Pop on a closed *non-empty* queue drains (returns the
     * next element).
     *
     * Invalidates `head` (always — the head value changes), `len`, and
     * `is_empty` (when transitioning to empty) readers as appropriate; `is_full`
     * when transitioning off capacity.
     */
    fun tryPop(): QueuePop<T> {
        val lenBefore = storage.len()
        val result = storage.tryPop()
        // A successful pop always advances head and decrements len.
        if (result is QueuePop.Value) invalidateReaders(lenBefore, lenBefore - 1, true)
        return result
    }

    /**
     * Close the queue. Idempotent — closing an already-closed queue is a no-op
     * (no invalidation). Terminal — once closed, a queue cannot be reopened.
     * After close, [tryPush] returns [QueuePushError.Closed]; [tryPop] continues
     * to drain and returns [QueuePopError.Closed] only once empty.
     *
     * Invalidates the `closed` reader only on the false → true transition.
     */
    fun close() {
        if (storage.isClosed()) return
        storage.close()
        ctx.setCell(closedCell, true)
    }

    // -- Reactive reader-kind reads ----------------------------------------

    /** Reactive read of the current head value. `null` when the queue is empty. A reader is invalidated when the head value *changes* — every pop, and a push only when transitioning from empty. */
    @Suppress("UNCHECKED_CAST")
    fun head(): T? {
        val v = ctx.get(headSlot)
        return if (v === NO_HEAD) null else v as T
    }

    /** Reactive read of the number of buffered elements. Invalidated whenever the count changes (every successful push/pop). */
    fun len(): Int = ctx.get(lenSlot)

    /** Reactive emptiness check. Invalidated only on the empty ↔ non-empty transition. */
    fun isEmpty(): Boolean = ctx.get(isEmptySlot)

    /** Reactive fullness check (only meaningful when the backend is bounded). Invalidated on the full ↔ not-full transition — this is the backpressure signal: a producer observes `isFull` and backs off; a consumer's pop that transitions full → not-full invalidates the producer's `isFull` subscription and the producer resumes. For an unbounded backend this is always `false` and never invalidates. */
    fun isFull(): Boolean = ctx.get(isFullSlot)

    /** Reactive read of the closed flag. Invalidated only on the open → closed transition. */
    fun isClosed(): Boolean = ctx.getCell(closedCell)

    /** Handle to the `head` reader-kind Slot, for wiring derived computeds directly. Subscribe-to-head semantics: invalidated on head-value change. */
    fun headHandle(): SlotHandle<Any> = headSlot

    // -- Non-reactive storage access ---------------------------------------

    /** The backend's capacity, or `null` if unbounded. Cached at construction. */
    fun capacity(): Int? = cap

    /** Handles to the reader-kinds, for advanced wiring (e.g. effects that subscribe to multiple reader kinds). The four derived reader-kinds are Slots; `closed` is a Cell. */
    fun readerHandles(): QueueReaderHandles =
        QueueReaderHandles(headSlot, lenSlot, isEmptySlot, isFullSlot, closedCell)
}

/**
 * Snapshot the buffered elements of a [VecDequeStorage]-backed queue in FIFO
 * order. Non-reactive — for debugging, snapshot/serde, and conformance-fixture
 * verification. There is no reactive random-access `queue[N]` reader;
 * per-position reactivity is the domain of `CellMap`, not `QueueCell`.
 */
fun <T : Any> QueueCell<T, VecDequeStorage<T>>.elements(): List<T> = storage.elements()

/** Handles to all five reader-kinds of a [QueueCell], for effects that need to subscribe to several reader kinds at once. The four derived reader-kinds are demand-driven Slots; `closed` is a Cell (a direct input). */
class QueueReaderHandles(
    /** The head value slot (`NO_HEAD` sentinel when empty). */
    val head: SlotHandle<Any>,
    /** The element count slot. */
    val len: SlotHandle<Int>,
    /** Whether the queue is empty. */
    val isEmpty: SlotHandle<Boolean>,
    /** Whether the queue is at capacity (bounded backpressure signal). */
    val isFull: SlotHandle<Boolean>,
    /** Whether the queue has been closed. */
    val closed: CellHandle<Boolean>,
)

// ---------------------------------------------------------------------------
// TopicCell — broadcast log with independent reactive subscriber cursors
// ---------------------------------------------------------------------------

/** Whether a [TopicCell] subscription survives disconnect and holds safe GC. */
enum class TopicDurability {
    /** Cursor state persists while disconnected and participates in retention. */
    Durable,

    /** Session state is removed on disconnect and never participates in retention. */
    Ephemeral,
}

/** Public state for one stable topic subscriber. */
data class TopicSubscriptionSnapshot(
    /** Absolute offset of the next element to read. */
    val cursor: Long,
    val durability: TopicDurability,
    val connected: Boolean,
)

/** Atomic state required to recreate a [TopicCell] without moving durable cursors. */
data class TopicSnapshot<T : Any>(
    val baseOffset: Long = 0,
    val elements: List<T> = emptyList(),
    val subscriptions: Map<String, TopicSubscriptionSnapshot> = emptyMap(),
)

/** Result of subscribing one stable identity. */
enum class TopicSubscribeOutcome {
    Created,
    Reconnected,
    AlreadyConnected,
}

private data class TopicSubscription(
    var cursor: Long,
    val durability: TopicDurability,
    var connected: Boolean,
)

/**
 * Broadcast topic: every subscriber receives every published element using an
 * independent, non-destructive cursor (`#lztopiccell`).
 *
 * Each subscriber owns a demand-driven reactive unread-suffix Slot. Publishing
 * invalidates every connected subscriber; advance/disconnect/reconnect invalidate
 * only the named subscriber. Safe GC removes only offsets below the slowest
 * durable cursor and invalidates no subscriber.
 */
class TopicCell<T : Any>(
    private val ctx: Context,
    initial: TopicSnapshot<T> = TopicSnapshot(),
) {
    private var baseOffset: Long = initial.baseOffset
    private val retained = ArrayDeque<T>(initial.elements.size)
    private val subscriptions = LinkedHashMap<String, TopicSubscription>()
    private val readers = HashMap<String, SlotHandle<List<T>>>()

    init {
        require(baseOffset >= 0) { "TopicCell baseOffset must be non-negative" }
        retained.addAll(initial.elements)
        val end = baseOffset + retained.size
        for ((id, sub) in initial.subscriptions) {
            require(sub.cursor in baseOffset..end) {
                "TopicCell cursor for `$id` must be within the retained absolute offset range"
            }
            require(sub.durability != TopicDurability.Ephemeral || sub.connected) {
                "Disconnected ephemeral TopicCell subscription `$id` must be removed"
            }
            subscriptions[id] = TopicSubscription(sub.cursor, sub.durability, sub.connected)
        }
        for (id in subscriptions.keys) ensureReader(id)
    }

    private fun ensureReader(id: String): SlotHandle<List<T>> =
        readers.getOrPut(id) {
            SlotHandle(
                ctx.slotAny(memo = true) {
                    val sub = subscriptions[id]
                    if (sub == null || !sub.connected) {
                        emptyList<T>()
                    } else {
                        retained.drop((sub.cursor - baseOffset).coerceAtLeast(0).toInt())
                    }
                },
            )
        }

    /**
     * Create a cursor at the current tail, or reconnect an existing durable id
     * without moving its cursor. Existing state owns the id's durability.
     */
    fun subscribe(id: String, durability: TopicDurability): TopicSubscribeOutcome {
        val existing = subscriptions[id]
        if (existing != null) {
            if (existing.connected) return TopicSubscribeOutcome.AlreadyConnected
            existing.connected = true
            ctx.invalidateSlots(intArrayOf(ensureReader(id).id))
            return TopicSubscribeOutcome.Reconnected
        }
        subscriptions[id] = TopicSubscription(endOffset(), durability, connected = true)
        ensureReader(id)
        return TopicSubscribeOutcome.Created
    }

    /** Reconnect a durable id; unknown ids are created at the current tail. */
    fun reconnect(id: String): TopicSubscribeOutcome = subscribe(id, TopicDurability.Durable)

    /**
     * Disconnect one subscriber. Durable state remains offline at the same
     * cursor; ephemeral state is removed. Returns whether state changed.
     */
    fun disconnect(id: String): Boolean {
        val sub = subscriptions[id] ?: return false
        if (!sub.connected) return false
        if (sub.durability == TopicDurability.Ephemeral) subscriptions.remove(id) else sub.connected = false
        val reader = if (sub.durability == TopicDurability.Ephemeral) readers.remove(id) else readers[id]
        if (reader != null) ctx.invalidateSlots(intArrayOf(reader.id))
        return true
    }

    /** Append exactly one element, leaving every cursor unchanged. */
    fun publish(value: T): Long {
        val offset = endOffset()
        retained.addLast(value)
        val roots =
            subscriptions
                .filterValues { it.connected && it.cursor <= offset }
                .keys
                .mapNotNull { readers[it]?.id }
                .toIntArray()
        ctx.invalidateSlots(roots)
        return offset
    }

    /** Reactive unread suffix for one connected subscriber. */
    @Suppress("UNCHECKED_CAST")
    fun readStream(id: String): List<T> {
        val reader = readers[id] ?: return emptyList()
        return ctx.getSlotAny(reader.id) as List<T>
    }

    /** Reactive element at the subscriber's cursor, or null at the tail/offline. */
    fun read(id: String): T? = readStream(id).firstOrNull()

    /** Advance only [id], returning the element it passed. */
    fun advance(id: String): T? {
        val sub = subscriptions[id] ?: return null
        if (!sub.connected || sub.cursor >= endOffset()) return null
        val value = retained.elementAt((sub.cursor - baseOffset).toInt())
        sub.cursor += 1
        readers[id]?.let { ctx.invalidateSlots(intArrayOf(it.id)) }
        return value
    }

    /**
     * Remove the prefix below the minimum durable cursor, or everything when no
     * durable subscription exists. Absolute cursors remain unchanged.
     */
    fun gc(): Int {
        val frontier =
            subscriptions.values
                .asSequence()
                .filter { it.durability == TopicDurability.Durable }
                .map { it.cursor }
                .minOrNull() ?: endOffset()
        val remove = (frontier - baseOffset).toInt()
        repeat(remove) { retained.removeFirst() }
        baseOffset = frontier
        return remove
    }

    fun baseOffset(): Long = baseOffset

    fun endOffset(): Long = baseOffset + retained.size

    /** Non-reactive retained-log snapshot. */
    fun elements(): List<T> = retained.toList()

    fun subscription(id: String): TopicSubscriptionSnapshot? =
        subscriptions[id]?.let { TopicSubscriptionSnapshot(it.cursor, it.durability, it.connected) }

    /** Handle to [id]'s demand-driven reactive unread suffix. */
    fun readerHandle(id: String): SlotHandle<List<T>>? = readers[id]

    /** Atomic durable/live-state snapshot suitable for restart. */
    fun snapshot(): TopicSnapshot<T> =
        TopicSnapshot(
            baseOffset = baseOffset,
            elements = retained.toList(),
            subscriptions =
                subscriptions.mapValues { (_, sub) ->
                    TopicSubscriptionSnapshot(sub.cursor, sub.durability, sub.connected)
                },
        )
}

// WorkQueueCell remains separate future work: exclusive handoff across N
// consumers requires an authority to serialize assignment.
