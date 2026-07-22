plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `maven-publish`
}

group = "io.github.lazily"
version = "0.35.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
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
                        "a full Harel/SCXML StateChart, and the lazily-spec IPC wire types."
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
