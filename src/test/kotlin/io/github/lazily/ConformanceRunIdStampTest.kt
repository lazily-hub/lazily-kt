package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Couples the TWO spellings of the run-id stamp (`#lzstampprefixdrift`).
 *
 * The stamp exists twice, necessarily. [ConformanceFixtures.RUN_ID_STAMP_PREFIX]
 * is the text the test JVM writes as the first line of every evidence file, and
 * `RUN_ID_STAMP_PREFIX` in `scripts/check-conformance-coverage.sh` is the text
 * the guard matches to date that file. Different languages, different files,
 * connected only by bytes on disk — and until this test existed, connected only
 * by a comment saying so. Two definitions of one string held together by a
 * comment is the shape that drifts.
 *
 * A drift fails CLOSED, which is why it was worth writing a test rather than
 * accepting it:
 *
 *  - Change either side's WORD and the guard finds no stamp in a perfectly fresh
 *    file. It refuses every run — as `carries NO run-id stamp`, telling the
 *    reader the file predates the stamp or the recorder is older than the guard,
 *    and pointing them at `./gradlew cleanTest test`. That diagnosis is a
 *    plausible story for a genuine staleness bug, so it costs a real
 *    investigation, and the actual defect is one character in a string literal.
 *  - Move only the TRAILING SPACE and it is worse. The prefix still matches, so
 *    the guard slices a mangled id out of a good file and reports STALE
 *    EVIDENCE — "the test step did not write this file during this invocation"
 *    about a file the test step wrote moments earlier.
 *
 * Both sides are read from their REAL definitions. Restating the literal here
 * would add a third place to drift and prove nothing about the other two.
 *
 * lazily-go is the reference (`TestGuardAndRecorderAgreeOnTheStampPrefix`); this
 * binding flagged the gap itself and is the one closing it.
 */
class ConformanceRunIdStampTest {
    /**
     * The guard script, located the way the evidence paths are: relative to the
     * Gradle test working directory, which is the project root.
     */
    private val guardScript: Path = Path.of("scripts/check-conformance-coverage.sh")

    /**
     * The guard's own declaration, PARSED rather than searched for.
     *
     * A `contains("RUN_ID_STAMP_PREFIX='" + producer + "'")` test would be
     * satisfied by the right bytes appearing anywhere and could not say what the
     * script declares instead when it fails — and it would silently pass a
     * script that declared the prefix twice, where the second assignment is the
     * one in force and the first is dead. Parsing yields the found value, so a
     * failure prints both sides, and the count is assertable.
     *
     * Anchored at line start, so a mention inside a `#` comment is not a
     * declaration.
     */
    private fun guardPrefixDeclarations(source: String): List<String> =
        Regex("""^RUN_ID_STAMP_PREFIX='([^']*)'$""", RegexOption.MULTILINE)
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

    private fun guardSource(): String {
        if (!Files.isRegularFile(guardScript)) {
            fail(
                "the coverage guard is not at ${guardScript.toAbsolutePath()} — this test reads the " +
                    "guard's own stamp constant out of it, so it cannot be run from a working " +
                    "directory that is not the project root",
            )
        }
        return Files.readString(guardScript)
    }

    @Test
    fun `the guard and the recorder agree on the stamp prefix`() {
        val declarations = guardPrefixDeclarations(guardSource())
        assertEquals(
            1,
            declarations.size,
            "expected EXACTLY ONE `RUN_ID_STAMP_PREFIX='...'` declaration in $guardScript, found " +
                "${declarations.size}: $declarations. Zero means the guard was renamed or stopped " +
                "declaring the prefix and this coupling is no longer checking anything. Two means " +
                "the later assignment is the one in force and the earlier is dead, which is the " +
                "same drift one level in.",
        )
        assertEquals(
            ConformanceFixtures.RUN_ID_STAMP_PREFIX,
            declarations.single(),
            "the stamp prefix the recorder WRITES and the one the guard MATCHES have drifted " +
                "(#lzstampprefixdrift).\n" +
                "  recorder (ConformanceFixtures.RUN_ID_STAMP_PREFIX): " +
                "'${ConformanceFixtures.RUN_ID_STAMP_PREFIX}'\n" +
                "  guard ($guardScript RUN_ID_STAMP_PREFIX):           '${declarations.single()}'\n" +
                "Mind the trailing space: it belongs to the prefix on both sides, and dropping it " +
                "on one side leaves the guard slicing a mangled id out of a fresh file and " +
                "reporting STALE EVIDENCE.",
        )
    }

    /**
     * The constant must be the one the BYTES come from.
     *
     * Without this, the coupling above is satisfiable by a decorative constant:
     * `runIdStamp()` could go back to spelling the prefix inline, the test would
     * still compare two agreeing strings, and the evidence files would carry a
     * third one.
     */
    @Test
    fun `the stamp the recorder writes is built from the constant`() {
        val stamp = ConformanceFixtures.runIdStamp()
        assertTrue(
            stamp.startsWith(ConformanceFixtures.RUN_ID_STAMP_PREFIX),
            "runIdStamp() must begin with RUN_ID_STAMP_PREFIX, or the constant the guard is " +
                "coupled to is not the text being written: ${stamp.trimEnd()}",
        )
        assertTrue(
            stamp.endsWith("\n"),
            "the stamp is a whole LINE — the guard reads it with `head -n 1` and the payload " +
                "follows it, so without the newline the first evidence record is fused onto the " +
                "stamp and lost: ${stamp.trimEnd()}",
        )
        assertEquals(
            1,
            stamp.count { it == '\n' },
            "the stamp is exactly one line: a second newline would put real evidence above the " +
                "guard's first-line read",
        )
    }

    /**
     * The stamp must be a COMMENT to the content rule, not a record.
     *
     * `evidence_lines()` in the guard is `grep -v '^#'`, and every population the
     * guard counts is drawn from it. A prefix that did not start with `#` would
     * put the stamp into the fixture-id population, where it resolves against no
     * file in the corpus and is reported as a recorder dropping or interleaving
     * writes — and into the site population, where it inflates rung 0's
     * magnitudes by one.
     */
    @Test
    fun `the stamp prefix is a comment line`() {
        assertTrue(
            ConformanceFixtures.RUN_ID_STAMP_PREFIX.startsWith("#"),
            "the stamp must be a comment or the guard counts it as a record: " +
                "'${ConformanceFixtures.RUN_ID_STAMP_PREFIX}'",
        )
        assertTrue(
            Regex("""^evidence_lines\(\) \{$""", RegexOption.MULTILINE).containsMatchIn(guardSource()),
            "the guard must still define evidence_lines() — the function that strips the stamp " +
                "out of every counted population. If it is gone, this test is asserting a comment " +
                "rule nothing applies.",
        )
    }
}
