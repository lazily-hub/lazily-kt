package io.github.lazily

// -- Manufactured identity for text (#lzstableid) ---------------------------
//
// Native Kotlin port of `lazily-rs::stable_id`. Plain markdown has no node ids,
// so keyed reconciliation has nothing to match on. This module *manufactures*
// stable identity from text in three layers of decreasing certainty:
//
// 1. **Anchored ids** — an in-band marker/id on a block. Exact, survives an
//    arbitrary rewrite of the block's body.
// 2. **Content-derived keys** — a hash of the block's *normalized* text, so an
//    unchanged block keeps its key across reflow/rewrap/reorder even with no
//    anchor.
// 3. **Alignment** — for a block whose content changed (no exact match), match
//    it to a predecessor by **similarity** (word-LCS ratio) so an *edit* is
//    distinguished from a real *insert*. A true rewrite reads as insert+remove.
//
// `assignStableKeys` is the bridge to keyed reconciliation: it returns one
// stable key per new block, reusing a matched/edited block's key so identity
// flows through an edit (the reconciler emits `Update`, not remove+insert).

/**
 * A text block, optionally carrying an in-band anchor/id.
 *
 * @property anchor in-band stable id, if the source provides one
 * @property text the block's raw text
 */
data class Block(val anchor: String?, val text: String) {
    companion object {
        /** A block with no anchor. */
        fun text(text: String): Block = Block(anchor = null, text = text)

        /** A block with an in-band anchor id. */
        fun anchored(anchor: String, text: String): Block = Block(anchor = anchor, text = text)
    }
}

/**
 * A manufactured identity key for a block: an anchor id, or a content hash of
 * the normalized text. Keys carry an `a:`/`c:` prefix so the anchored and
 * content keyspaces never collide.
 */
sealed class BlockKey {
    /** From an in-band anchor — survives a full rewrite of the block body. */
    data class Anchored(val id: String) : BlockKey()

    /** Hash of normalized content — survives reflow/reorder, changes on edit. */
    data class Content(val hash: Long) : BlockKey()

    /** A stable string form usable as a reconciliation key. */
    fun asString(): String = when (this) {
        is Anchored -> "a:$id"
        is Content -> "c:%016x".format(hash)
    }
}

/**
 * Normalize a block's text so reflow/rewrap/indent changes don't change its
 * content key: collapse all whitespace runs to single spaces and trim.
 */
private fun normalize(text: String): String =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

/** Deterministic 64-bit FNV-1a hash over the *normalized* text. */
private fun contentHash(text: String): Long {
    val norm = normalize(text)
    var h = 0xcbf29ce484222325UL
    for (b in norm.encodeToByteArray()) {
        h = h xor (b.toLong() and 0xff).toULong()
        h *= 0x100000001b3UL
    }
    return h.toLong()
}

/** The identity key for a block: its anchor if present, else a content hash. */
fun blockKey(b: Block): BlockKey = when (b.anchor) {
    null -> BlockKey.Content(contentHash(b.text))
    else -> BlockKey.Anchored(b.anchor)
}

/**
 * How a new block relates to the old sequence.
 */
sealed class Match {
    /** Exact key match (anchor or content hash) — identity preserved. */
    data class Same(val old: Int) : Match()

    /** Matched to a predecessor by similarity; the content changed (an edit). */
    data class Edited(val old: Int, val similarity: Float) : Match()

    /** No match — a genuine insertion. */
    data object Inserted : Match()
}

/**
 * The alignment of a new block sequence against an old one.
 *
 * @property newMatches one entry per new block, in order
 * @property removed old block indices that were not matched
 */
data class Alignment(val newMatches: List<Match>, val removed: List<Int>)

/**
 * Word-LCS similarity ratio in `[0,1]`: `2·|LCS| / (|a|+|b|)` over whitespace
 * tokens (the difflib/Myers-style ratio). 1.0 = identical token sequence.
 */
fun similarity(a: String, b: String): Float {
    val aw = a.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val bw = b.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (aw.isEmpty() && bw.isEmpty()) return 1.0f
    val lcs = lcsLen(aw, bw)
    return (2f * lcs) / (aw.size + bw.size)
}

private fun lcsLen(a: List<String>, b: List<String>): Int {
    val dp = IntArray(b.size + 1)
    for (x in a) {
        var prev = 0
        for (j in b.indices) {
            val cur = dp[j + 1]
            dp[j + 1] = if (x == b[j]) prev + 1 else maxOf(dp[j + 1], dp[j])
            prev = cur
        }
    }
    return dp[b.size]
}

/** Minimum similarity for an unmatched block to be classified as `Edited`. */
const val EDIT_THRESHOLD: Float = 0.5f

/**
 * Align [new] against [old], manufacturing identity. Exact key matches (anchor,
 * then content hash) carry identity directly; remaining new blocks are matched
 * to the most-similar unmatched old block above [EDIT_THRESHOLD] (nearest index
 * breaks ties) and classified `Edited`, else `Inserted`. Unmatched old blocks
 * are `removed`.
 */
fun align(old: List<Block>, new: List<Block>): Alignment {
    val oldKeys = old.map { blockKey(it) }
    val newKeys = new.map { blockKey(it) }
    val oldUsed = BooleanArray(old.size)
    val newMatches = arrayOfNulls<Match>(new.size)

    // Pass 1: exact key match in order (anchor or content hash). Equal content
    // blocks are consumed left-to-right so duplicates pair up deterministically.
    for (ni in newKeys.indices) {
        val nk = newKeys[ni]
        val oi = (0 until old.size).firstOrNull { !oldUsed[it] && oldKeys[it] == nk }
        if (oi != null) {
            oldUsed[oi] = true
            newMatches[ni] = Match.Same(oi)
        }
    }

    // Pass 2: similarity match for the still-unmatched new blocks.
    for (ni in newMatches.indices) {
        if (newMatches[ni] != null) continue
        var best: Pair<Int, Float>? = null
        for (oi in old.indices) {
            if (oldUsed[oi]) continue
            val sim = similarity(new[ni].text, old[oi].text)
            val better = best == null ||
                sim > best!!.second ||
                (sim == best!!.second && kotlin.math.abs(oi - ni) < kotlin.math.abs(best!!.first - ni))
            if (better) best = oi to sim
        }
        if (best == null) {
            newMatches[ni] = Match.Inserted
            continue
        }
        val (oi, sim) = best
        if (sim >= EDIT_THRESHOLD) {
            oldUsed[oi] = true
            newMatches[ni] = Match.Edited(oi, sim)
        } else {
            newMatches[ni] = Match.Inserted
        }
    }

    val removed = (0 until old.size).filter { !oldUsed[it] }
    return Alignment(newMatches.map { it!! }, removed)
}

/**
 * One stable key per **new** block, suitable as the keyed-reconciliation key
 * set. A `Same`/`Edited` block reuses its matched old block's key so identity
 * flows through an edit (the reconciler emits `Update`, not remove+insert). An
 * `Inserted` block gets its own anchor/content key.
 */
fun assignStableKeys(old: List<Block>, new: List<Block>): List<String> {
    val oldKeys = old.map { blockKey(it).asString() }
    val alignment = align(old, new)
    return alignment.newMatches.mapIndexed { ni, m ->
        when (m) {
            is Match.Same -> oldKeys[m.old]
            is Match.Edited -> oldKeys[m.old]
            is Match.Inserted -> blockKey(new[ni]).asString()
        }
    }
}
