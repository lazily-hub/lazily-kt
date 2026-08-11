package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Canonical conformance fixture loader (`#lzspecconf`).
 *
 * Fixtures are read **only** from the canonical lazily-spec sibling checkout at
 * `../lazily-spec/conformance` (override with `LAZILY_SPEC_DIR`), mirroring
 * `lazily-rs`'s `SPEC_DIR` constant.
 *
 * There is deliberately **no bundled fallback**. lazily-kt used to copy the
 * fixtures into `src/test/resources/conformance/` and fall back to them when the
 * sibling was absent — which is exactly what makes spec drift invisible: a
 * fixture the spec has since changed keeps replaying its stale local copy and
 * reports green. Worse, the fallback silently covered whole areas the spec has
 * that lazily-kt never bundled, so those replays skipped in CI while passing.
 *
 * Absence is therefore a loud, explicit failure — never a silent skip.
 *
 * Because an absence guard alone cannot catch shadowing or a refactored-away
 * replay, every successful load is recorded and flushed to
 * `build/conformance-fixtures-loaded.txt` on JVM shutdown so CI can *positively*
 * assert which fixtures actually ran.
 */
object ConformanceFixtures {
    /** The lazily-spec sibling checkout — corpus, schemas, and proto all hang off it. */
    val specRoot: Path = Path.of(System.getenv("LAZILY_SPEC_DIR") ?: "../lazily-spec")

    /**
     * Canonical conformance root — the lazily-spec sibling, never a bundled copy.
     *
     * `LAZILY_SPEC_CONFORMANCE_DIR` names the CORPUS directly and wins, matching
     * every sibling binding and this repo's own `scripts/check-conformance-coverage.sh`,
     * which has read it first all along. The test JVM did not, so the two disagreed:
     * pointing only that variable at a scratch corpus left the suite replaying the
     * DEFAULT one, green, having proved nothing about the bytes you named
     * (`#lzoverrideallrunnersaudit`). Measured — a truncated fixture under
     * `LAZILY_SPEC_CONFORMANCE_DIR` alone produced 447 passing tests and exit 0.
     *
     * That is the `#lzzigingressspecdir` failure by a different cause: not a
     * hardcoded path, but a variable name the reader never looked at. It is worse
     * than an unsupported override, because the caller has every reason to think it
     * took effect.
     */
    val root: Path =
        System.getenv("LAZILY_SPEC_CONFORMANCE_DIR")?.takeIf { it.isNotEmpty() }?.let(Path::of)
            ?: specRoot.resolve("conformance")

    /** Where the positive "these fixtures actually ran" manifest is written. */
    val manifestPath: Path =
        Path.of(
            System.getenv("LAZILY_CONFORMANCE_MANIFEST") ?: "build/conformance-fixtures-loaded.txt",
        )

    /** Where the "these fixture-level `assertions` blocks were BOUND" ledger is written. */
    val assertionBlockLedgerPath: Path =
        Path.of(
            System.getenv("LAZILY_CONFORMANCE_ASSERTION_BLOCK_LEDGER")
                ?: "build/conformance-assertion-blocks.txt",
        )

    private val loaded = ConcurrentSkipListSet<String>()

    /**
     * Top-level `assertions` blocks the corpus carries, per fixture this run
     * opened — rung 0 of the conformance-evidence ladder (`#lznullformblind`).
     *
     * Every other rung is scoped to a block a runner ALREADY OPENED. The unread
     * check, the unasserted check and the prose ledger all live inside
     * [AssertionKeys], so a fixture-level `assertions` block that no runner ever
     * binds to an [AssertionKeys] reports nothing at all: its keys are not
     * unread, because nothing was reading. lazily-dart found two such blocks in
     * its own suite, eight silent keys including a load-bearing anti-spoof
     * invariant. No grep finds that — the evidence is the absence of a call.
     *
     * So the blocks are inventoried here at READ time, and [AssertionKeys] books
     * one as bound when it is constructed over it. What is left over is the
     * answer.
     */
    private val declaredBlocks = ConcurrentHashMap<String, JsonObject>()

    /** Index by key set, so [noteBound] does not scan the whole inventory. */
    private val blocksByShape = ConcurrentHashMap<Set<String>, MutableList<String>>()

    private val boundBlocks = ConcurrentSkipListSet<String>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { writeManifest() })
        Runtime.getRuntime().addShutdownHook(Thread { writeAssertionBlockLedger() })
    }

    /** True when the canonical sibling checkout is present. */
    fun present(): Boolean = Files.isDirectory(root)

    /**
     * Hard-fail with an actionable message when the canonical fixtures are
     * absent. Never degrade to a bundled copy and never quietly return.
     */
    fun requireRoot() {
        check(present()) {
            "canonical conformance fixtures missing at ${root.toAbsolutePath()} — " +
                "clone lazily-spec as a sibling (git clone --depth 1 " +
                "https://github.com/lazily-hub/lazily-spec.git ../lazily-spec) or set " +
                "LAZILY_SPEC_DIR. Refusing to fall back to a bundled copy: that is how " +
                "spec drift goes unnoticed (#lzspecconf)."
        }
    }

    fun path(rel: String): Path = root.resolve(rel)

    /** Read a canonical fixture by spec-relative path, e.g. `collections/mergecell_algebra.json`. */
    fun read(rel: String): String {
        requireRoot()
        val p = path(rel)
        check(Files.exists(p)) {
            "missing canonical conformance fixture '$rel' (looked in ${p.toAbsolutePath()}). " +
                "The spec may have renamed or removed it — update the replay, do not bundle a copy."
        }
        val text = Files.readString(p)
        loaded.add(rel)
        declareAssertionBlock(rel, text)
        return text
    }

    /** Inventory [rel]'s top-level `assertions` block, if it carries one. */
    private fun declareAssertionBlock(
        rel: String,
        text: String,
    ) {
        val block =
            runCatching { Json.parseToJsonElement(text) }
                .getOrNull()
                ?.let { it as? JsonObject }
                ?.get("assertions") as? JsonObject ?: return
        if (declaredBlocks.putIfAbsent(rel, block) == null) {
            blocksByShape.computeIfAbsent(block.keys) { mutableListOf() }.let { bucket ->
                synchronized(bucket) { bucket.add(rel) }
            }
        }
    }

    /**
     * Book a fixture-level `assertions` block as BOUND to a tracker.
     *
     * Matched by CONTENT rather than by the caller's `where` string: runners
     * spell that inconsistently — `"codec/x.json assertions"` in one and
     * `"x.json assertions"` in another — and a ledger keyed on a label a runner
     * chooses is one the runner can be wrong about.
     */
    fun noteBound(block: JsonObject) {
        val candidates = blocksByShape[block.keys] ?: return
        val snapshot = synchronized(candidates) { candidates.toList() }
        for (rel in snapshot) {
            if (declaredBlocks[rel] == block) boundBlocks.add(rel)
        }
    }

    /**
     * Fixtures this run OPENED that carry a top-level `assertions` block no
     * runner ever bound to an [AssertionKeys]. Non-empty means silent keys.
     */
    fun unboundAssertionBlocks(): Set<String> = declaredBlocks.keys.toSortedSet() - boundBlocks

    @Synchronized
    fun writeAssertionBlockLedger() {
        if (declaredBlocks.isEmpty()) return
        runCatching {
            assertionBlockLedgerPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val lines =
                declaredBlocks.keys
                    .toSortedSet()
                    .joinToString("\n", postfix = "\n") { rel ->
                        "$rel\t${if (rel in boundBlocks) "bound" else "UNBOUND"}"
                    }
            Files.writeString(assertionBlockLedgerPath, lines)
        }
    }

    /** Record a fixture replayed through a path this object did not read directly. */
    fun record(rel: String) {
        loaded.add(rel)
    }

    /** Fixtures loaded so far in this JVM. */
    fun loadedFixtures(): Set<String> = loaded.toSortedSet()

    @Synchronized
    fun writeManifest() {
        if (loaded.isEmpty()) return
        runCatching {
            manifestPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(manifestPath, loaded.toSortedSet().joinToString("\n", postfix = "\n"))
        }
    }
}
