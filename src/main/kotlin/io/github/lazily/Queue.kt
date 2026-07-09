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

    /** Peek the current head element without removing it. `null` when empty. */
    fun peek(): T?

    /** Current number of buffered elements. */
    fun len(): Int

    /** Bounded capacity, or `null` for an unbounded backend. When non-null, the shell exposes `is_full` as a reactive read. */
    fun capacity(): Int?

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

    // Reader-kind version cells. The shell re-derives these from storage after
    // each op; the `==` guard on `setCell` means a cell whose value did not
    // change is not invalidated — this is what implements reader-kind
    // independence for free.
    private val headCell: CellHandle<Any>
    private val lenCell: CellHandle<Int>
    private val isEmptyCell: CellHandle<Boolean>
    private val isFullCell: CellHandle<Boolean>
    private val closedCell: CellHandle<Boolean>

    init {
        val len0 = storage.len()
        val cap0 = storage.capacity()
        headCell = CellHandle(ctx.cellAny(storage.peek() ?: NO_HEAD))
        lenCell = ctx.cell(len0)
        isEmptyCell = ctx.cell(len0 == 0)
        isFullCell = ctx.cell(cap0?.let { len0 >= it } ?: false)
        closedCell = ctx.cell(storage.isClosed())
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
    private fun syncContent() {
        val s = storage
        val lenVal = s.len()
        val isFullVal = s.capacity()?.let { lenVal >= it } ?: false
        val headVal = s.peek() ?: NO_HEAD
        val isEmptyVal = lenVal == 0
        // Batch the writes: a push/pop is a single atomic op, so its reader-kind
        // cells must transition together. Without the batch, each setCell
        // flushes effects immediately and an observer could glitch (len bumped
        // before is_full flips).
        ctx.batch {
            setCellAny(headCell.id, headVal)
            setCell(lenCell, lenVal)
            setCell(isEmptyCell, isEmptyVal)
            setCell(isFullCell, isFullVal)
        }
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
        val err = storage.tryPush(value)
        if (err == null) syncContent()
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
        val result = storage.tryPop()
        if (result is QueuePop.Value) syncContent()
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
        val v = ctx.getCell(headCell)
        return if (v === NO_HEAD) null else v as T
    }

    /** Reactive read of the number of buffered elements. Invalidated whenever the count changes (every successful push/pop). */
    fun len(): Int = ctx.getCell(lenCell)

    /** Reactive emptiness check. Invalidated only on the empty ↔ non-empty transition. */
    fun isEmpty(): Boolean = ctx.getCell(isEmptyCell)

    /** Reactive fullness check (only meaningful when the backend is bounded). Invalidated on the full ↔ not-full transition — this is the backpressure signal: a producer observes `isFull` and backs off; a consumer's pop that transitions full → not-full invalidates the producer's `isFull` subscription and the producer resumes. For an unbounded backend this is always `false` and never invalidates. */
    fun isFull(): Boolean = ctx.getCell(isFullCell)

    /** Reactive read of the closed flag. Invalidated only on the open → closed transition. */
    fun isClosed(): Boolean = ctx.getCell(closedCell)

    /** Handle to the `head` reader-kind cell, for wiring derived computeds directly. Subscribe-to-head semantics: invalidated on head-value change. */
    fun headHandle(): CellHandle<Any> = headCell

    // -- Non-reactive storage access ---------------------------------------

    /** The backend's capacity, or `null` if unbounded. */
    fun capacity(): Int? = storage.capacity()

    /** Handles to the reader-kind cells, for advanced wiring (e.g. effects that subscribe to multiple reader kinds). */
    fun readerHandles(): QueueReaderHandles =
        QueueReaderHandles(headCell, lenCell, isEmptyCell, isFullCell, closedCell)
}

/**
 * Snapshot the buffered elements of a [VecDequeStorage]-backed queue in FIFO
 * order. Non-reactive — for debugging, snapshot/serde, and conformance-fixture
 * verification. There is no reactive random-access `queue[N]` reader;
 * per-position reactivity is the domain of `CellMap`, not `QueueCell`.
 */
fun <T : Any> QueueCell<T, VecDequeStorage<T>>.elements(): List<T> = storage.elements()

/** Handles to all five reader-kind cells of a [QueueCell], for effects that need to subscribe to several reader kinds at once. */
class QueueReaderHandles(
    /** The head value cell (`NO_HEAD` sentinel when empty). */
    val head: CellHandle<Any>,
    /** The element count cell. */
    val len: CellHandle<Int>,
    /** Whether the queue is empty. */
    val isEmpty: CellHandle<Boolean>,
    /** Whether the queue is at capacity (bounded backpressure signal). */
    val isFull: CellHandle<Boolean>,
    /** Whether the queue has been closed. */
    val closed: CellHandle<Boolean>,
)

// ---------------------------------------------------------------------------
// TopicCell / WorkQueueCell — future-work stubs
// ---------------------------------------------------------------------------

// `TopicCell` (SPMC broadcast / MPMC pub-sub) and `WorkQueueCell` (true MPMC
// with exclusive handoff) are genuinely distinct primitives — they differ in
// *invalidation model and handoff semantics*, not in producer/consumer
// cardinality (see `lazily-spec/cell-model.md` § "Future queue primitives").
//
// They are reserved for future work and are NOT in v1 conformance:
//
// - **TopicCell** — every subscriber receives every pushed element. Each
//   subscriber maintains its own cursor; the topic retains elements until all
//   cursors have advanced past them (GC frontier = slowest subscriber). Lands
//   with the distributed-queue PRD Phase 3. Formal stub:
//   `lazily-formal/LazilyFormal/TopicCell.lean`.
//
// - **WorkQueueCell** — N consumers compete for elements from a shared FIFO;
//   each element is delivered to exactly one consumer (exclusive handoff). This
//   requires an authority (designated leader peer) to serialize pop-assignment —
//   pure CRDT cannot provide it. Lands with the distributed-queue PRD Phase 2
//   (consensus core). Formal stub:
//   `lazily-formal/LazilyFormal/WorkQueueCell.lean`.
