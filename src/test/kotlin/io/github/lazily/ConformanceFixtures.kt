package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
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
    /** Canonical conformance root — the lazily-spec sibling, never a bundled copy. */
    val root: Path = Path.of(System.getenv("LAZILY_SPEC_DIR") ?: "../lazily-spec")
        .resolve("conformance")

    /** Where the positive "these fixtures actually ran" manifest is written. */
    val manifestPath: Path = Path.of(
        System.getenv("LAZILY_CONFORMANCE_MANIFEST") ?: "build/conformance-fixtures-loaded.txt",
    )

    private val loaded = ConcurrentSkipListSet<String>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { writeManifest() })
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
        return text
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
