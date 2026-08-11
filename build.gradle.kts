import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    // The formatting gate (#lazilyformattinggate). Version pinned exactly, and
    // the ktlint version it drives is pinned separately below — see the spotless
    // block for why both pins are needed rather than either alone.
    id("com.diffplug.spotless") version "6.25.0"
    id("com.google.protobuf") version "0.9.5"
    `maven-publish`
}

// #lazilyformattinggate. This binding had no formatting floor: nothing in
// `check`, nothing in CI, so drift was invisible until someone read a diff.
//
// TWO pins, deliberately. `spotless` is the plugin; `ktlint("...")` is the
// formatter it shells to, and they version independently. Pinning only the
// plugin would leave the actual style free to move when spotless bumps its
// default ktlint, which is precisely how three sibling gates in this series
// broke: clang-format defaults moving between majors, zig `master` resolving to
// a different nightly in CI than locally, and `dart format` choosing its style
// from build state. Pin the style AND the implementation, or neither is pinned.
//
// `spotlessCheck` is the gate and runs in `make check`; `spotlessApply` writes
// and deliberately does not.
// The four rules below are ktlint's NON-formatting opinions. Each is disabled
// because it fires on something deliberate in this codebase and ktlint cannot
// auto-fix any of them — left on, `spotlessApply` aborts before formatting a
// single file, which is how this gate first failed to land.
//
//   function-naming   the JNA bindings in LazilyFFI.kt must match the native C
//                     symbols exactly (agent_doc_state_projection and friends);
//                     renaming them breaks the FFI.
//   class-naming      private SCREAMING_SNAKE sentinel objects (RETRY_SYNC_COMPUTE,
//                     NO_QUEUE_HEAD) read as the constants they are, and two
//                     independent authors reached for the same idiom.
//   filename          CrdtPlane.kt would have to become CrdtPlaneRuntime.kt; the
//                     file name is part of the JVM facade class name, so that is
//                     a Java-interop change, not a formatting one.
//   no-consecutive-comments
//                     24 sites where a KDoc follows a KDoc. That is an authoring
//                     convention in this repo, not layout.
//
// max-line-length is deliberately NOT in this list. Its single violation is a
// raw-string JSON fixture that cannot be wrapped without changing the bytes
// under test, so it carries a targeted @Suppress at that one site and the rule
// stays enforced everywhere else.
val ktlintNonFormattingRules =
    mapOf(
        "ktlint_standard_function-naming" to "disabled",
        "ktlint_standard_class-naming" to "disabled",
        "ktlint_standard_filename" to "disabled",
        "ktlint_standard_no-consecutive-comments" to "disabled",
    )

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(ktlintNonFormattingRules)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.3.1").editorConfigOverride(ktlintNonFormattingRules)
    }
}

group = "io.github.lazily"
version = "0.40.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.protobuf:protobuf-kotlin:4.31.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

sourceSets {
    main {
        proto {
            srcDir("../lazily-spec/proto")
            include("lazily/graph_boundary/v1/graph_boundary.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()

    // Rungs 2 and 3 of the conformance-evidence ladder fail INSIDE the test JVM:
    // AssertionKeys.requireAllSatisfied() throws when a fixture's assertion key
    // went unread (#lzassertunknownkeys) or was read but never compared against
    // the fixture's own value (#lzconsumednotasserted). Those messages name the
    // fixture AND the offending key on purpose — "some assertion went unread" is
    // not actionable — but Gradle's default console prints only the failing test
    // name, so CI was red for an unreadable reason and the diagnostic died in the
    // HTML report nobody downloads (#lzguardsnotinci). The rung-1 and rung-4
    // guards run as a shell step and already print theirs; this puts rungs 2 and 3
    // on equal footing.
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }

    // The conformance corpus is a real INPUT of this task (#lzktcorpusnotgradleinput).
    //
    // Without this, editing a fixture left `test` UP-TO-DATE: Gradle saw no
    // declared input change, skipped the task, and the run reported GREEN having
    // replayed NOTHING. That is the worst possible failure for a corpus guard,
    // because every rung above it reasons about fixtures the suite opened — and a
    // suite that never ran opened none, so nothing else can contradict it. It also
    // silently defeats corpus-perturbation probes, whose entire method is "change a
    // fixture, expect red".
    //
    // A MISSING corpus is a HARD FAILURE, and it happens here at task validation,
    // before any test JVM starts (#lzcorpusabsencehandling).
    //
    // This comment used to carry an `optional(true)` and claim the opposite: that a
    // checkout without the `lazily-spec` sibling is "a legitimate local state that
    // the tests already skip on", so a missing corpus "must not fail configuration".
    // Both halves were false, and the flag bought nothing either way:
    //   - `optional` excuses an absent provider VALUE, not a nonexistent DIRECTORY,
    //     and this provider always has a value (`orElse`), so the flag was inert.
    //     `LAZILY_SPEC_DIR=/nope ./gradlew test` failed with "property
    //     'conformanceCorpus' specifies directory '...' which doesn't exist" —
    //     the exact outcome the comment promised could not happen.
    //   - nothing skips. `ConformanceFixtures.requireRoot()` throws, deliberately:
    //     "Absence is therefore a loud, explicit failure — never a silent skip".
    //     A conformance suite that quietly replays nothing is the vacuous green
    //     this whole evidence ladder exists to refuse.
    //   - and a sibling-less checkout never reached either of them. `main`'s proto
    //     source set is `srcDir("../lazily-spec/proto")`, so without the sibling
    //     `compileKotlin` fails first, on 28 unresolved references. There is no
    //     state in which this repo builds without lazily-spec for the corpus input
    //     to be lenient about.
    // So the behaviour stays and the false promise goes. A sibling-less checkout
    // cannot run `./gradlew test`, by design — and failing at configuration is the
    // cheapest honest way to say so, ahead of a compile and ~450 red replays that
    // all report the same one thing. `make test` guards the same absence one step
    // earlier with an actionable message ("clone lazily-spec as a sibling or set
    // LAZILY_SPEC_DIR"), and CI clones the sibling and guards it again, so the
    // legible failure is already on the path every caller is told to use.
    //
    // The shell coverage guard's local SKIP is not a counterexample: it audits
    // whether replays happened, so with no corpus it has no population and makes no
    // claim. This task IS the replay, so it has nothing to be silent about.
    //
    // `LAZILY_SPEC_CONFORMANCE_DIR` names the CORPUS directly and wins over
    // `LAZILY_SPEC_DIR`, which names the sibling checkout. Both must be declared:
    // the input fingerprint and the test JVM have to agree on which corpus is in
    // play, or Gradle reuses a result computed against a different one
    // (`#lzoverrideallrunnersaudit`).
    val specDir = providers.environmentVariable("LAZILY_SPEC_DIR").orElse("../lazily-spec")
    val corpusDir =
        providers.environmentVariable("LAZILY_SPEC_CONFORMANCE_DIR")
            .orElse(specDir.map { "$it/conformance" })
    inputs.dir(layout.projectDirectory.dir(corpusDir))
        .withPropertyName("conformanceCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The lazily-spec JSON SCHEMAS are a second, independent input
    // (#lzspecschemasoverride).
    //
    // `LAZILY_SPEC_CONFORMANCE_DIR` redirects the corpus and nothing else, so a
    // probe that had to perturb a SCHEMA — AgentDocStateConformanceTest reads the
    // `type_tag` vocabulary straight out of `schemas/agent-doc-state.json` — could
    // only do it by editing the shared `../lazily-spec` checkout, reddening all ten
    // bindings at once and dirtying a repo other sessions read.
    // `LAZILY_SPEC_SCHEMAS_DIR` gives that seam its own knob, and it is deliberately
    // independent of the corpus one: a scratch corpus carries no `schemas/`, and a
    // scratch schemas tree carries no corpus.
    //
    // Declared as an input for the same reason the corpus is: without it, flipping a
    // field in the schema leaves `test` UP-TO-DATE and the perturbation probe reports
    // GREEN having replayed nothing (#lzktcorpusnotgradleinput). Forwarded explicitly
    // for the same reason too — the test JVM inherits the DAEMON's environment, and
    // the daemon outlives the shell that exported the variable, so the fingerprint and
    // the JVM must be fed from one place or Gradle reuses a result computed against a
    // different schemas tree.
    val schemasDir =
        providers.environmentVariable("LAZILY_SPEC_SCHEMAS_DIR")
            .orElse(specDir.map { "$it/schemas" })
    inputs.dir(layout.projectDirectory.dir(schemasDir))
        .withPropertyName("specSchemas")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional(true)

    // The resolved LOCATION is an input too, not just the bytes under it.
    //
    // `inputs.dir` alone fingerprints content at RELATIVE paths, and a Test task's
    // `environment` is NOT tracked, so two cases reuse a stale verdict:
    //   - a byte-identical scratch copy fingerprints the same as the default, so the
    //     run that was supposed to prove the override took effect reports
    //     `test UP-TO-DATE` and never starts a JVM;
    //   - a MISSING directory under an explicit override fingerprints the same as
    //     "no lazily-spec sibling at all" (both absent, because the input is
    //     `optional`), so a probe pointed at a nonexistent tree could be answered
    //     from a cached GREEN instead of the loud failure it must produce.
    // Naming the path itself makes "which schemas tree" part of the verdict.
    inputs.property("specSchemasDir", schemasDir)

    // Pass the corpus location EXPLICITLY rather than letting it be inherited.
    //
    // A Gradle test JVM inherits the DAEMON's environment, and the daemon outlives
    // the shell that started it. So a daemon launched before `LAZILY_SPEC_DIR` was
    // exported keeps serving the OLD path to every later run, and the only defence
    // was remembering `--no-daemon`. Declaring it here also makes the variable part
    // of the task's input fingerprint, so pointing at a different corpus re-runs
    // the tests instead of reusing a result computed against another one.
    environment("LAZILY_SPEC_DIR", specDir.get())
    environment("LAZILY_SPEC_CONFORMANCE_DIR", corpusDir.get())
    environment("LAZILY_SPEC_SCHEMAS_DIR", schemasDir.get())
}

tasks.register<JavaExec>("interopPeer") {
    group = "verification"
    description = "Run the Lazily cross-binding NDJSON test peer."
    mainClass.set("io.github.lazily.InteropPeerKt")
    classpath = sourceSets.main.get().runtimeClasspath
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}

tasks.register<JavaExec>("interopPeerCheck") {
    group = "verification"
    description = "Run the interop peer production-surface self-check."
    mainClass.set("io.github.lazily.InteropPeerKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args("--self-check")
}

// Reactive-core microbenchmark (parity with lazily-rs benches/context.rs).
// Run via `./gradlew benchmark` or `make benchmark`.
tasks.register<JavaExec>("benchmark") {
    group = "benchmark"
    description = "Run the lazily-kt reactive-core microbenchmarks."
    mainClass.set("io.github.lazily.Benchmarks")
    classpath = sourceSets.main.get().runtimeClasspath
    // JVM microbenchmarks need a warm settled heap; no special flags required.
    standardOutput = System.out
    errorOutput = System.err
}

// Spreadsheet-scale benchmark (parity with lazily-rs benches/scale.rs).
// Run via `./gradlew benchmarkScale -Plazily.scaleN=1000000` or `make benchmark-scale`.
tasks.register<JavaExec>("benchmarkScale") {
    group = "benchmark"
    description = "Run the lazily-kt spreadsheet-scale benchmark (default N=1,000,000)."
    mainClass.set("io.github.lazily.ScaleBench")
    classpath = sourceSets.main.get().runtimeClasspath
    standardOutput = System.out
    errorOutput = System.err
    // LAZILY_SCALE_N / LAZILY_SCALE_VIEWPORT env vars are read by ScaleBench.main.
    val scaleN = project.findProperty("lazily.scaleN") as String?
    if (scaleN != null) environment("LAZILY_SCALE_N", scaleN)
}

// Edge-index width ladder (#lzspecedgeindex). Manual / on-demand only — this is
// deliberately NOT part of `make check` or CI: it climbs to millions of nodes and
// wants a large explicit heap.
//
//   ./gradlew edgeIndexLoad -Plazily.loadMaxWidth=1000000 -Plazily.loadHeap=12g
tasks.register<JavaExec>("edgeIndexLoad") {
    group = "benchmark"
    description = "Run the edge-index pub/sub width ladder (manual, not CI)."
    mainClass.set("io.github.lazily.EdgeIndexLoad")
    classpath = sourceSets.main.get().runtimeClasspath
    standardOutput = System.out
    errorOutput = System.err
    maxHeapSize = (project.findProperty("lazily.loadHeap") as String?) ?: "8g"
    (project.findProperty("lazily.loadMaxWidth") as String?)?.let {
        systemProperty("lazily.loadMaxWidth", it)
    }
    (project.findProperty("lazily.edgeIndexThreshold") as String?)?.let {
        systemProperty("lazily.edgeIndexThreshold", it)
    }
}

// Edge removal + effect-flush fan-out audit (#lzspecedgeindex): wide vs narrow
// fan-out at equal node count, so a per-edge quadratic separates from the cache
// and GC growth that an absolute width ladder cannot distinguish it from.
tasks.register<JavaExec>("edgeAudit") {
    group = "benchmark"
    description = "Audit edge removal + effect flush for fan-out quadratics (manual, not CI)."
    mainClass.set("io.github.lazily.EdgeAudit")
    classpath = sourceSets.main.get().runtimeClasspath
    standardOutput = System.out
    errorOutput = System.err
    maxHeapSize = (project.findProperty("lazily.loadHeap") as String?) ?: "8g"
    (project.findProperty("lazily.forceScanRemove") as String?)?.let {
        systemProperty("lazily.forceScanRemove", it)
    }
    (project.findProperty("lazily.auditMaxWidth") as String?)?.let {
        systemProperty("lazily.auditMaxWidth", it)
    }
    (project.findProperty("lazily.edgeIndexThreshold") as String?)?.let {
        systemProperty("lazily.edgeIndexThreshold", it)
    }
}

// Edge-index crossover sweep (#lzspecedgeindex): the same fan-out width measured
// with the index forced off and forced on, so the crossover degree is measured
// rather than copied from another binding.
tasks.register<JavaExec>("edgeIndexCrossover") {
    group = "benchmark"
    description = "Measure the scan-vs-index crossover degree (manual, not CI)."
    mainClass.set("io.github.lazily.EdgeIndexCrossover")
    classpath = sourceSets.main.get().runtimeClasspath
    standardOutput = System.out
    errorOutput = System.err
    maxHeapSize = (project.findProperty("lazily.loadHeap") as String?) ?: "4g"
    (project.findProperty("lazily.edgeIndexThreshold") as String?)?.let {
        systemProperty("lazily.edgeIndexThreshold", it)
    }
    (project.findProperty("lazily.crossoverDegrees") as String?)?.let {
        systemProperty("lazily.crossoverDegrees", it)
    }
    (project.findProperty("lazily.edgeIndexDemoteThreshold") as String?)?.let {
        systemProperty("lazily.edgeIndexDemoteThreshold", it)
    }
}

kotlin {
    jvmToolchain(21)
}

// Sources JAR for published artifact (provided by the Kotlin plugin).
java {
    withSourcesJar()
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/lazily-hub/lazily-kt")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
    publications {
        register<MavenPublication>("gpr") {
            from(components["java"])
            // README coordinate: io.github.lazily:lazily
            artifactId = "lazily"
            pom {
                name.set("lazily-kt")
                description.set(
                    "Native Kotlin port of the lazily reactive core " +
                        "(Context / Slot / Cell / Signal / Effect), a reactive StateMachine, " +
                        "a full Harel/SCXML StateChart, and the lazily-spec IPC wire types.",
                )
                url.set("https://github.com/lazily-hub/lazily-kt")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Brian Takita")
                        email.set("brian.takita@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/lazily-hub/lazily-kt")
                    connection.set("scm:git:git://github.com/lazily-hub/lazily-kt.git")
                    developerConnection.set("scm:git:ssh://github.com/lazily-hub/lazily-kt.git")
                }
            }
        }
    }
}
