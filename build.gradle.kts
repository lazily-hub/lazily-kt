plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `maven-publish`
}

group = "io.github.lazily"
version = "0.10.0"

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
