plugins {
    kotlin("jvm") version "2.0.21"
    application
}

val artifactRepository = "__REPOSITORY__"

repositories {
    maven {
        url = uri(artifactRepository)
        if (artifactRepository.startsWith("https://")) {
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
    mavenCentral()
}

dependencies {
    implementation("io.github.lazily:lazily:__VERSION__")
}

application {
    mainClass.set("MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
