package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

    /**
     * The raw `LAZILY_SPEC_SCHEMAS_DIR` value, kept so [requireSchemasRoot] can
     * tell "the caller named a schemas tree" from "nobody overrode anything".
     */
    private val schemasOverride: String? =
        System.getenv("LAZILY_SPEC_SCHEMAS_DIR")?.takeIf { it.isNotEmpty() }

    /**
     * Canonical JSON Schema root — `specRoot/schemas` unless
     * `LAZILY_SPEC_SCHEMAS_DIR` names another tree (`#lzspecschemasoverride`).
     *
     * [root] redirects the CORPUS and nothing else, so a probe that needed to
     * perturb a SCHEMA had nowhere to point: its only option was editing the
     * shared `../lazily-spec` checkout, which reddens all ten bindings at once
     * and dirties a repo other sessions are reading. The schemas seam therefore
     * gets its own variable rather than riding on the corpus one — a scratch
     * corpus carries no `schemas/` at all, and a scratch schemas tree carries no
     * corpus, so the two overrides have to be independent to be useful.
     *
     * Unset, this resolves exactly where it always did, so an ordinary run is
     * unaffected.
     */
    val schemasRoot: Path = schemasOverride?.let(Path::of) ?: specRoot.resolve("schemas")

    /**
     * True when [schemasRoot] is somewhere other than `specRoot/schemas`.
     *
     * Not simply `schemasOverride != null`: the Gradle `test` task forwards the
     * variable UNCONDITIONALLY — it has to, so the input fingerprint and the test
     * JVM cannot disagree — so the variable being set proves nothing about whether
     * anyone redirected anything. The PATH is the evidence.
     */
    private val schemasRedirected: Boolean =
        schemasRoot.normalize() != specRoot.resolve("schemas").normalize()

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
     * Every assertion-bearing block the corpus carries, per SITE, across the
     * fixtures this run opened — rung 0 of the conformance-evidence ladder
     * (`#lznullformblind`).
     *
     * Every other rung is scoped to a block a runner ALREADY OPENED. The unread
     * check, the unasserted check and the prose ledger all live inside
     * [AssertionKeys], so a block no runner ever binds to an [AssertionKeys]
     * reports nothing at all: its keys are not unread, because nothing was
     * reading. lazily-dart found two such blocks in its own suite, eight silent
     * keys including a load-bearing anti-spoof invariant. No grep finds that —
     * the evidence is the absence of a call.
     *
     * So the blocks are inventoried here at READ time, and [AssertionKeys] books
     * one as bound when it is constructed over it. What is left over is the
     * answer.
     *
     * Keyed by SITE — `"<fixture>|<path>"` — and not by fixture (`#lzktblockwalk`).
     * This map used to be `Map<String, JsonObject>` keyed on the fixture path,
     * which made ONE block per fixture the data structure's ceiling rather than an
     * incidental choice: the walk could not widen at all while the inventory could
     * not hold a second block. That ceiling held rung 0 to 18 sites of the 725 the
     * same 148 opened fixtures carry — 2.5%, the narrowest in the family — and the
     * magnitude guard pinned the narrow number faithfully while it did so.
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

    /**
     * Hard-fail when [schemasRoot] is not a readable directory.
     *
     * Fails CLOSED on an explicit `LAZILY_SPEC_SCHEMAS_DIR` that cannot be read:
     * a typo'd or unbuilt scratch tree is a BROKEN PROBE, and the two outcomes
     * that must never happen are a skip and a silent fall back to the default
     * schemas — either one reports green having proved nothing about the bytes
     * the caller named, which is the `#lzoverrideallrunnersaudit` failure
     * (447 tests, exit 0, DEFAULT corpus) in a second seam.
     */
    fun requireSchemasRoot() {
        if (Files.isDirectory(schemasRoot)) return
        check(!schemasRedirected) {
            "LAZILY_SPEC_SCHEMAS_DIR names '$schemasOverride' " +
                "(${schemasRoot.toAbsolutePath()}), which is not a readable directory. " +
                "Refusing to fall back to ${specRoot.resolve("schemas").toAbsolutePath()}: " +
                "validating against the DEFAULT schemas under an explicit override reports " +
                "green about bytes nobody tested (#lzspecschemasoverride)."
        }
        error(
            "canonical schemas directory missing at ${schemasRoot.toAbsolutePath()} — " +
                "clone lazily-spec as a sibling (git clone --depth 1 " +
                "https://github.com/lazily-hub/lazily-spec.git ../lazily-spec) or set " +
                "LAZILY_SPEC_DIR / LAZILY_SPEC_SCHEMAS_DIR (#lzspecschemasoverride).",
        )
    }

    /** Resolve a schema by schemas-relative path, e.g. `agent-doc-state.json`. */
    fun schemaPath(rel: String): Path = schemasRoot.resolve(rel)

    /** Read a canonical JSON Schema by schemas-relative path. Absence is loud, never a skip. */
    fun readSchema(rel: String): String {
        requireSchemasRoot()
        val p = schemaPath(rel)
        check(Files.exists(p)) {
            "missing canonical schema '$rel' (looked in ${p.toAbsolutePath()}). " +
                "The spec may have renamed or removed it — update the reader, do not pin a copy " +
                "in source (#lzspecschemasoverride)."
        }
        return Files.readString(p)
    }

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
        declareAssertionBlocks(rel, text)
        return text
    }

    /**
     * Inventory every assertion-bearing block [rel] carries, at every depth.
     *
     * THE WALK (`#lzktblockwalk`). `scripts/check-conformance-coverage.sh` re-runs
     * this same rule over the corpus on disk to derive the expected magnitude, so
     * the two must stay in lock step — the twin is marked THE WALK there too.
     *
     * A tracked NAME in [BLOCK_NAMES] is a site when its value is a JSON OBJECT,
     * and one site PER PLAIN-OBJECT ELEMENT when its value is a JSON ARRAY. Three
     * clauses carry the weight and none is a detail:
     *
     *  - an ARRAY-valued tracked key emits one site per plain-object ELEMENT,
     *    labelled `<path>[<index>]` (`#lzarrayelementsites`). "A runner binds the
     *    elements, not the array" was always this rule's own parenthetical, and it
     *    used to stop there — the array is not an object and its elements are list
     *    items rather than tracked keys, so they were descended into and dropped.
     *    `signaling/anti_spoof_session.json` holds its expected outbound signaling
     *    frames that way, 12 elements across 8 steps, and every one was invisible
     *    to rung 0 while [SignalingProtocolTest] read and asserted it. The SITE is
     *    the element: a label per ARRAY would collapse a step's frames into one
     *    name, so two individually falsifiable frames would stop being
     *    individually nameable, which is the set-identity failure the site
     *    dimension exists to catch.
     *
     *    ONE level, PLAIN OBJECTS, TRUE indexes. A nested element (`expect[0][1]`)
     *    is not directly under a tracked key and gets no site — the element pass
     *    is entered only from the tracked-key branch. A scalar, null or array
     *    element emits nothing. And the index is the element's real position, so
     *    `[{...}, 3, {...}]` is `expect[0]` and `expect[2]`, never `[0]`/`[1]`.
     *  - a site is EMITTED AND NOT DESCENDED INTO. Without that, a fixture's
     *    `expect` nested inside its own `assertions` becomes a second, separately
     *    bindable site that no tracker can reach without first unwrapping the
     *    block above it — an unbindable-by-construction site, the same defect in
     *    the other direction. [AssertionKeys.sub] and `consumingNested` guard
     *    everything beneath an emitted block, and deliberately do NOT book a
     *    rung-0 bind. An emitted array ELEMENT is covered by the same clause.
     *  - an UNTRACKED key is not a block whatever its value, so `scenarios[0]`
     *    and `steps[3]` are still not sites.
     */
    private fun declareAssertionBlocks(
        rel: String,
        text: String,
    ) {
        for ((siteId, block) in blockSitesOf(rel, text)) {
            if (declaredBlocks.putIfAbsent(siteId, block) == null) {
                blocksByShape.computeIfAbsent(block.keys) { mutableListOf() }.let { bucket ->
                    synchronized(bucket) { bucket.add(siteId) }
                }
            }
        }
    }

    /**
     * The walk as a PURE function: every site [text] carries, keyed
     * `"<rel>|<path>"`, without touching the inventory.
     *
     * One walk, three callers — the inventory above, the clone-equality proof in
     * `AssertionBlockDigestCloneTest`, and the twin in
     * `scripts/check-conformance-coverage.sh`. A second traversal written for any
     * one of them is a second rule that can drift from this one.
     */
    fun blockSitesOf(
        rel: String,
        text: String,
    ): Map<String, JsonObject> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyMap()
        val sites = LinkedHashMap<String, JsonObject>()
        walkForBlocks(rel, root, "", sites)
        return sites
    }

    /** As [blockSitesOf], over an element a caller has ALREADY parsed. */
    fun blockSitesOf(
        rel: String,
        root: JsonElement,
    ): Map<String, JsonObject> {
        val sites = LinkedHashMap<String, JsonObject>()
        walkForBlocks(rel, root, "", sites)
        return sites
    }

    private fun walkForBlocks(
        rel: String,
        element: JsonElement,
        path: String,
        sites: MutableMap<String, JsonObject>,
    ) {
        when (element) {
            is JsonObject ->
                for ((key, value) in element) {
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    when {
                        key in BLOCK_NAMES && value is JsonObject ->
                            sites["$rel|$childPath"] = value
                        // The widening (`#lzarrayelementsites`). One site per
                        // plain-object element, at its TRUE index; anything else in
                        // the array is descended into as before, which is what keeps
                        // the rule to one level — a nested array reaches this walk
                        // through the JsonArray arm below, never through here.
                        key in BLOCK_NAMES && value is JsonArray ->
                            value.forEachIndexed { index, item ->
                                val itemPath = "$childPath[$index]"
                                if (item is JsonObject) {
                                    sites["$rel|$itemPath"] = item
                                } else {
                                    walkForBlocks(rel, item, itemPath, sites)
                                }
                            }
                        else -> walkForBlocks(rel, value, childPath, sites)
                    }
                }
            is JsonArray ->
                element.forEachIndexed { index, value ->
                    walkForBlocks(rel, value, "$path[$index]", sites)
                }
            else -> Unit
        }
    }

    /**
     * Book an assertion block as BOUND to a tracker.
     *
     * Matched by CONTENT rather than by the caller's `where` string: runners
     * spell that inconsistently — `"codec/x.json assertions"` in one and
     * `"x.json assertions"` in another — and a ledger keyed on a label a runner
     * chooses is one the runner can be wrong about. One content match books EVERY
     * site carrying those bytes, which is the honest reading of a content-keyed
     * ledger: two sites spelled identically are one claim, and a tracker over
     * those bytes has read it.
     *
     * The block handed in must be the LOADER'S OWN value, reached by indexing the
     * element [read] returned. A runner that re-parses the fixture text and passes
     * a rebuilt object binds nothing whenever the two parses disagree on so much
     * as a number's spelling — lazily-cpp lost 71 of 96 sites to exactly that,
     * its clone digesting `5` as `5.000000` (`#lzrunnerownjsonclone`).
     */
    fun noteBound(block: JsonObject) {
        val candidates = blocksByShape[block.keys] ?: return
        val snapshot = synchronized(candidates) { candidates.toList() }
        for (siteId in snapshot) {
            if (declaredBlocks[siteId] == block) boundBlocks.add(siteId)
        }
    }

    /**
     * Sites this run OPENED that no runner ever bound to an [AssertionKeys].
     * Non-empty means silent keys.
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
                    .joinToString("\n", postfix = "\n") { siteId ->
                        val state = if (siteId in boundBlocks) "bound" else "UNBOUND"
                        "$siteId\t$state\t${blockDigest(declaredBlocks.getValue(siteId))}"
                    }
            Files.writeString(assertionBlockLedgerPath, lines)
        }
    }

    /**
     * Content digest of one inventoried assertion block — the SECOND dimension of
     * the rung-0 magnitude (`#lzblocksitepin`).
     *
     * The site count and the distinct-digest count are each blind to what the other
     * sees, in opposite directions:
     *
     *  - a **site** count absorbs a CONTENT edit. Respelling one block exactly like
     *    another's leaves the site count untouched while the corpus has genuinely
     *    lost a distinct claim (measured in lazily-cs: 743 sites unchanged, 634
     *    digests down to 633).
     *  - a **digest** count absorbs the DELETION of a block whose bytes recur
     *    elsewhere (109 of lazily-py's 729 sites carry a recurring shape).
     *
     * So both are recorded here and both are asserted EQUAL against the same walk
     * re-run over the corpus on disk by `scripts/check-conformance-coverage.sh`.
     *
     * The encoding is canonical and self-delimiting rather than "serialize back to
     * JSON": the script's twin has to produce byte-identical input for the same
     * block, and JSON string escaping is the one place two implementations reliably
     * disagree. Every scalar is tagged and every string is UTF-8 LENGTH-PREFIXED,
     * so no value can be confused with the punctuation around it, and object keys
     * are emitted in sorted order because [JsonObject] equality — which [noteBound]
     * matches on — is order-insensitive. Numbers keep their RAW source token: that
     * is what kotlinx hands back and what the twin's JSON reader is configured to
     * preserve, and normalizing either side would fold `1` into `1.0`.
     */
    fun blockDigest(block: JsonObject): String {
        val canonical = StringBuilder()
        appendCanonical(block, canonical)
        val bytes =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun appendCanonical(
        element: JsonElement,
        out: StringBuilder,
    ) {
        when (element) {
            // Checked BEFORE JsonPrimitive: JsonNull IS one, and its `content` is the
            // bare string "null", which would otherwise digest identically to the raw
            // number token of a (nonexistent, but unguarded) literal spelled the same.
            is JsonNull -> out.append('z')
            is JsonObject -> {
                out.append('o').append(element.size).append('{')
                for (key in element.keys.sorted()) {
                    appendCanonicalString(key, out)
                    appendCanonical(element.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('a').append(element.size).append('[')
                for (value in element) appendCanonical(value, out)
                out.append(']')
            }
            is JsonPrimitive ->
                if (element.isString) {
                    appendCanonicalString(element.content, out)
                } else {
                    // Numbers AND booleans. `content` is the raw source token for both,
                    // so `true` and the string "true" stay distinguishable by their tag.
                    out.append('n').append(element.content).append(';')
                }
        }
    }

    private fun appendCanonicalString(
        value: String,
        out: StringBuilder,
    ) {
        out.append('s')
            .append(value.toByteArray(StandardCharsets.UTF_8).size)
            .append(':')
            .append(value)
    }

    /**
     * The names the canonical corpus gives an assertion-bearing block.
     *
     * All five, because the corpus uses all five and a rung that only knows one of
     * them is blind to the rest by construction. Derived from the canonical
     * listing rather than from this binding's runners: a name set taken from what
     * kt happens to read today could never surface a block kt does not read, which
     * is the entire question rung 0 asks.
     */
    private val BLOCK_NAMES =
        setOf("assertions", "expect", "expect_after", "expect_initial", "expected")

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
