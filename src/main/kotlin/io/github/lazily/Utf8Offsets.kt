package io.github.lazily

// -- UTF-8 wire offset conversion (#lzlosstree) -----------------------------
//
// The lossless-tree protocol carries leaf-local text offsets as **UTF-8 byte
// offsets** (lazily-spec § Offset policy): agent-doc documents are UTF-8 files
// and parser spans are byte ranges. No binding may treat UTF-16 code units as
// wire offsets. The JVM's `String` is UTF-16, and this library's [TextCrdt] is
// `Char`-granular (one element per UTF-16 code unit), so the two byte-taking
// mutators must convert a wire byte offset to the host index model here:
//
// - [byteToUtf16] — for `editLeaf`, whose delete/insert run against the leaf's
//   [TextCrdt] at UTF-16 `Char` indices.
// - [byteToCodePoint] / [codePointToUtf16] — for `splitLeaf`, whose wire
//   `at_char` is a Unicode **scalar (code-point) count** so it means the same
//   split point in every binding regardless of the host string model.
//
// Every conversion rejects an offset that is out of range or does not land on a
// UTF-8 character boundary (returns `null`), so a bad offset fails closed rather
// than silently corrupting text.
object Utf8Offsets {
    /** UTF-8 byte length of the character whose code point is [cp]. */
    private fun utf8Len(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /**
     * UTF-8 byte offset [byte] into [s] → the UTF-16 code-unit index at that
     * position, or `null` if [byte] is out of range or not on a char boundary.
     */
    fun byteToUtf16(s: String, byte: Int): Int? {
        if (byte < 0) return null
        var b = 0
        var i = 0
        while (b < byte) {
            if (i >= s.length) return null // past end
            val cp = s.codePointAt(i)
            b += utf8Len(cp)
            i += Character.charCount(cp)
            if (b > byte) return null // offset falls inside this character
        }
        return i
    }

    /**
     * UTF-8 byte offset [byte] into [s] → the number of Unicode scalars (code
     * points) before it — the wire `at_char` value — or `null` if [byte] is out
     * of range or not on a char boundary.
     */
    fun byteToCodePoint(s: String, byte: Int): Int? {
        if (byte < 0) return null
        var b = 0
        var i = 0
        var cp = 0
        while (b < byte) {
            if (i >= s.length) return null
            val c = s.codePointAt(i)
            b += utf8Len(c)
            i += Character.charCount(c)
            cp += 1
            if (b > byte) return null
        }
        return cp
    }

    /**
     * A Unicode scalar (code-point) count [cpCount] → the UTF-16 code-unit index
     * in [s], clamped into range (matching the reference's `at_char.min(len)`).
     */
    fun codePointToUtf16(s: String, cpCount: Int): Int {
        val total = s.codePointCount(0, s.length)
        val n = cpCount.coerceIn(0, total)
        return s.offsetByCodePoints(0, n)
    }
}
