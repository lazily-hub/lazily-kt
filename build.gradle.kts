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
