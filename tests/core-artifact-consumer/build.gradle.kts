plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    maven { url = uri("__REPOSITORY__") }
    mavenCentral()
}

dependencies {
    implementation("io.github.lazily:lazily:0.46.0")
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
