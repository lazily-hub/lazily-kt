package io.github.lazily

// ---------------------------------------------------------------------------
// SmallEdgeList (#lzktsmalledgelist, #lzspecedgeindex)
// ---------------------------------------------------------------------------

/**
 * Promote to a hash-indexed edge set above this degree (`#lzspecedgeindex`).
 *
 * The dedup that keeps edge registration idempotent is a linear scan of the
 * dependent list. That is measurably the *faster* option at low degree — which
 * is the overwhelmingly common case, since most reactive nodes carry 0-2 edges —
 * but it makes building a width-N fan-out cost ~N^2/2 comparisons, so a wide
 * topic degrades to O(n^2) per propagation. An unconditional hash index
 * regresses the common case; an unconditional scan regresses wide fan-out.
 *
 * **The threshold is not portable and was measured here, not copied.** It is
 * where a scan of contiguous ints crosses the cost of one open-addressed probe,
 * so it moves with both. `lazily-rs` measured its own crossover at 170 with
 * SipHash and at 40 once ids were hashed with a multiply-shift finalizer — a 4x
 * shift from the hash function alone, which is why its constant is not
 * transferable.
 *
 * Measured here with `EdgeIndexCrossover`, ns per steady-state remove+re-add
 * churn cycle, medians of 31 samples:
 *
 * ```
 * degree    3     8    16    24    32    40    64   256  1024  4096
 * scan   8.98 10.55 12.71 16.19 16.44 18.63 23.40 86.54 302.5 1063.6
 * index  9.80 10.62 11.49 12.72 11.40 11.75 12.92 14.67  16.8   19.6
 * ```
 *
 * The indexed path is flat at ~10-13 ns; the scan fits `8.5 + 0.235 * degree`,
 * so the two **cross near degree 16** — well below `lazily-rs`'s 40, because
 * Kotlin's scan walks a boxed `ArrayList<Int>` (a pointer chase and
 * `Integer.equals` per element) while the index probes a raw `IntArray`. The
 * scan side is simply more expensive here than Rust's contiguous unboxed one.
 *
 * The constant is nonetheless set to **32, not 16**. Between those two degrees
 * the columns differ by at most ~5 ns per churn cycle, which is inside the
 * run-to-run spread, while promoting costs two `IntArray`s — a few hundred bytes
 * for a node with seventeen edges. 32 is the first degree where the win is
 * unambiguous (1.44x, and rising) and it halves how many nodes pay for the
 * index. That it coincides with `lazily-rs`'s current constant is a coincidence
 * of two different measurements, not a copy: the crossovers differ (16 vs 40).
 *
 * Overridable via `-Dlazily.edgeIndexThreshold=<n>` for that sweep only; it is a
 * static final read once at class init, so the JIT still folds it as a constant.
 */
internal val EDGE_INDEX_THRESHOLD: Int =
    System.getProperty("lazily.edgeIndexThreshold")?.toIntOrNull() ?: 32

/**
 * Hysteresis: demote well below the promote threshold, never at it.
 *
 * A dependent list oscillates by one on every recompute — `recomputeSlotNow`
 * removes the node from each dependency's dependent list and the tracked read
 * immediately re-registers it — so a single shared promote/demote boundary makes
 * a list sitting at the threshold tear down and rebuild its whole index on every
 * recompute. `lazily-rs` measured that at ~4x the steady-state cost, visible at
 * exactly threshold+1 and nowhere else. The gap absorbs the oscillation.
 */
internal val EDGE_INDEX_DEMOTE_THRESHOLD: Int =
    System.getProperty("lazily.edgeIndexDemoteThreshold")?.toIntOrNull()
        ?: ((EDGE_INDEX_THRESHOLD * 3) / 4)

/**
 * Audit lever: force edge *removal* back to the pre-index linear form
 * (`-Dlazily.forceScanRemove=true`), while leaving registration indexed.
 *
 * Registration and removal are separate quadratics and **do not compose**.
 * `lazily-zig` fixed registration, reverted `remove` to a scan, and watched
 * teardown return to its unfixed baseline — so a passing registration ladder
 * says nothing about the removal path. The only way to show removal is genuinely
 * O(1) is to put the scan back and measure the delta, which is what this flag is
 * for; [EdgeAudit] drives it.
 *
 * The forced path stays *correct*, not just slow: it removes in list order and
 * then repairs every index position after the hole, which is what an index
 * carrying membership but not positions is obliged to do. That is the shape
 * `lazily-dart` had before it started storing each dependent's position. So the
 * delta this flag exposes is purely the O(degree)-vs-O(1) cost, with no
 * behavioural difference to confound it.
 *
 * Static final, read once at class init, so the JIT folds it away entirely when
 * unset — the default path pays nothing for this being here.
 */
internal val EDGE_FORCE_SCAN_REMOVE: Boolean =
    System.getProperty("lazily.forceScanRemove")?.toBoolean() ?: false

/**
 * Open-addressed `int -> int` map, element to its position in the edge list.
 *
 * A `HashMap<Int, Int>` would box both halves of every entry, which at width 1M
 * costs more than the edge list it indexes and would show up directly as
 * bytes/subscriber growth. Two `IntArray`s with linear probing cost 8 bytes per
 * slot and never allocate per entry.
 *
 * Keys are node ids: non-negative, internally allocated, sequential, and never
 * attacker-controlled, so [mix] is a plain multiply-xor-shift finalizer rather
 * than a collision-resistant hash. Sequential ids land in well-separated
 * buckets. `EMPTY` (-1) is the vacant-slot sentinel, which no id can collide
 * with.
 */
private class EdgeIndexMap(expected: Int) {
    private var keys: IntArray
    private var values: IntArray
    private var mask: Int
    private var size: Int = 0
    private var growAt: Int

    init {
        var cap = 8
        // Keep the load factor at or below 0.5 for cheap linear probing.
        while (cap < expected * 2) cap = cap shl 1
        keys = IntArray(cap) { EMPTY }
        values = IntArray(cap)
        mask = cap - 1
        growAt = cap / 2
    }

    /** splitmix32 finalizer: full avalanche in a handful of multiply-xor-shifts. */
    private fun mix(key: Int): Int {
        var h = key
        h = (h xor (h ushr 16)) * 0x21f0aaad
        h = (h xor (h ushr 15)) * 0x735a2d97
        return (h xor (h ushr 15)) and mask
    }

    /** Position of [key], or -1 if absent. */
    fun get(key: Int): Int {
        var i = mix(key)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return -1
            if (k == key) return values[i]
            i = (i + 1) and mask
        }
    }

    fun put(key: Int, value: Int) {
        var i = mix(key)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) {
                keys[i] = key
                values[i] = value
                size++
                if (size >= growAt) grow()
                return
            }
            if (k == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
    }

    /**
     * Remove [key], returning its position, or -1 if absent.
     *
     * Uses backward-shift deletion rather than a tombstone: tombstones would
     * accumulate under the remove/re-register churn of every recompute and
     * degrade probing back toward linear, which is the cost this whole index
     * exists to avoid.
     */
    fun remove(key: Int): Int {
        var i = mix(key)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) return -1
            if (k == key) break
            i = (i + 1) and mask
        }
        val found = values[i]
        size--
        // Backward-shift: close the gap by pulling forward any following entry
        // whose ideal slot is at or before the hole.
        var hole = i
        var j = i
        while (true) {
            j = (j + 1) and mask
            val k = keys[j]
            if (k == EMPTY) break
            val ideal = mix(k)
            // Is `ideal` cyclically within (hole, j]? If not, k may move to hole.
            val holeToJ = (j - hole) and mask
            val idealToJ = (j - ideal) and mask
            if (idealToJ >= holeToJ) {
                keys[hole] = k
                values[hole] = values[j]
                hole = j
            }
        }
        keys[hole] = EMPTY
        return found
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val cap = oldKeys.size shl 1
        keys = IntArray(cap) { EMPTY }
        values = IntArray(cap)
        mask = cap - 1
        growAt = cap / 2
        size = 0
        for (i in oldKeys.indices) {
            if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i])
        }
    }

    companion object {
        const val EMPTY = -1
    }
}

/**
 * Dependency/dependent edge container.
 *
 * Three regimes, so that neither the common case nor the wide case pays for the
 * other:
 *
 * - **0-2 edges** (`#lzktsmalledgelist`) — inlined in two `Int` fields, zero
 *   allocation. Most reactive nodes never leave this regime.
 * - **3 to [EDGE_INDEX_THRESHOLD] edges** — an `ArrayList<Int>` with a linear
 *   dedup scan, which at this degree beats hashing.
 * - **above [EDGE_INDEX_THRESHOLD]** (`#lzspecedgeindex`) — additionally carries
 *   an [EdgeIndexMap] of element to position, so `add` and `remove` are both
 *   amortized O(1) in degree instead of O(degree).
 *
 * **Insertion order** is preserved in the first two regimes, matching the
 * `LinkedHashSet` this replaced (effect scheduling walks dependent lists, so
 * order is observable there). A promoted list swap-removes instead, because an
 * order-preserving removal shifts every later element and would invalidate every
 * later index entry — reintroducing the O(degree) removal the index exists to
 * remove. That trade is confined to lists wider than the threshold, where the
 * relative order of a hot topic's dependents is not something the reactive
 * contract fixes: the contract fixes the edge *set*.
 *
 * **A recycled id cannot inherit an index.** The index is a field of this
 * object, not a side table keyed by owner, and a `Node` — with a fresh
 * `SmallEdgeList` — is allocated on every `cell`/`computed`/`effect`, including
 * when `allocId` hands back a recycled id. [clear] also drops the index. The
 * aliasing hazard that a side table has is structurally absent here.
 */
internal class SmallEdgeList : MutableIterable<Int> {
    // State encoding:
    //   count == 0 : empty
    //   count == 1 : [a] holds element 0
    //   count == 2 : [a] holds element 0, [b] holds element 1
    //   count >= 3 : [list] holds every element; a/b unused
    // `index`, when non-null, maps element -> its position in [list]. It exists
    // only while count > EDGE_INDEX_DEMOTE_THRESHOLD; between the demote and
    // promote thresholds it may or may not exist, which is what the hysteresis
    // buys, so every fast path tolerates its absence rather than asserting on it.
    private var a: Int = 0
    private var b: Int = 0
    private var list: ArrayList<Int>? = null
    private var index: EdgeIndexMap? = null
    private var count: Int = 0

    val size: Int get() = count

    val indices: IntRange get() = 0 until count

    fun isEmpty(): Boolean = count == 0

    fun isNotEmpty(): Boolean = count != 0

    operator fun get(index: Int): Int {
        val l = list
        return when {
            l != null -> l[index]
            index == 0 -> a
            index == 1 -> b
            else -> throw IndexOutOfBoundsException("SmallEdgeList[$index] (size=$count)")
        }
    }

    operator fun contains(element: Int): Boolean {
        val idx = index
        if (idx != null) return idx.get(element) >= 0
        val l = list
        return when {
            l != null -> element in l
            count == 0 -> false
            count == 1 -> a == element
            else -> a == element || b == element // count == 2
        }
    }

    /**
     * Insertion-order append with dedup. Returns true iff newly added.
     *
     * Below the demote threshold there is provably no index, so a short list
     * never touches the map — testing an absent key costs more than the short
     * scan it would replace.
     */
    fun add(element: Int): Boolean {
        val l = list
        if (l != null) {
            if (count > EDGE_INDEX_DEMOTE_THRESHOLD) {
                val idx = index
                if (idx != null) {
                    if (idx.get(element) >= 0) return false
                    idx.put(element, count)
                    l.add(element)
                    count++
                    return true
                }
            }
            if (element in l) return false
            l.add(element)
            count++
            if (count > EDGE_INDEX_THRESHOLD && index == null) buildIndex(l)
            return true
        }
        if (contains(element)) return false
        when (count) {
            0 -> { a = element; count = 1 }
            1 -> { b = element; count = 2 }
            else -> {
                // count == 2 → promote to ArrayList on the 3rd add.
                val newList = ArrayList<Int>(4)
                newList.add(a)
                newList.add(b)
                newList.add(element)
                list = newList
                count = 3
            }
        }
        return true
    }

    private fun buildIndex(l: ArrayList<Int>) {
        val idx = EdgeIndexMap(l.size)
        for (i in l.indices) idx.put(l[i], i)
        index = idx
    }

    /**
     * Remove [element] if present.
     *
     * Order of the remaining elements is preserved on the unindexed paths. An
     * indexed list swap-removes; see the class doc for why.
     */
    fun remove(element: Int): Boolean {
        val l = list
        if (l != null) {
            if (count > EDGE_INDEX_DEMOTE_THRESHOLD) {
                val idx = index
                if (idx != null && EDGE_FORCE_SCAN_REMOVE) {
                    // Audit-only naive form; see EDGE_FORCE_SCAN_REMOVE.
                    val i = l.indexOf(element)
                    if (i < 0) return false
                    l.removeAt(i)
                    count--
                    idx.remove(element)
                    for (k in i until l.size) idx.put(l[k], k)
                    if (count <= EDGE_INDEX_DEMOTE_THRESHOLD) index = null
                    return true
                }
                if (idx != null) {
                    val pos = idx.remove(element)
                    if (pos < 0) return false
                    val last = l.removeAt(l.size - 1)
                    count--
                    if (pos < l.size) {
                        l[pos] = last
                        idx.put(last, pos)
                    }
                    // Demote only well below the promote threshold, so a list
                    // hovering at the boundary does not rebuild every recompute.
                    if (count <= EDGE_INDEX_DEMOTE_THRESHOLD) index = null
                    return true
                }
            }
            val i = l.indexOf(element)
            if (i < 0) return false
            l.removeAt(i)
            count--
            return true
        }
        when (count) {
            0 -> return false
            1 -> {
                if (a != element) return false
                a = 0
                count = 0
                return true
            }
            else -> { // count == 2
                when {
                    a == element -> {
                        a = b
                        b = 0
                        count = 1
                        return true
                    }
                    b == element -> {
                        b = 0
                        count = 1
                        return true
                    }
                    else -> return false
                }
            }
        }
    }

    fun clear() {
        a = 0
        b = 0
        list = null
        index = null
        count = 0
    }

    /** Snapshot to an immutable list (defensive copy). */
    fun toList(): List<Int> {
        val l = list
        return when {
            l != null -> ArrayList(l)
            count == 0 -> emptyList()
            count == 1 -> listOf(a)
            else -> listOf(a, b)
        }
    }

    /** Whether this list currently carries a hash index (testing). */
    internal fun isIndexed(): Boolean = index != null

    override fun iterator(): MutableIterator<Int> = object : MutableIterator<Int> {
        private var index = 0
        override fun hasNext(): Boolean = index < count
        override fun next(): Int {
            if (index >= count) throw NoSuchElementException("SmallEdgeList iterator exhausted")
            val v = this@SmallEdgeList[index]
            index++
            return v
        }
        override fun remove() =
            throw UnsupportedOperationException("SmallEdgeList iterator.remove is not supported")
    }
}
