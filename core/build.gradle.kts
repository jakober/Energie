import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

// Reiner Kotlin/JVM-Code ohne Android-Abhaengigkeiten: die Schnittstellen zu
// FRITZ!Box und SENEC, die Datenmodelle, Statistik und deren Tests. Laeuft damit
// auch auf einem Rechner ohne Android-SDK (./gradlew -PcoreOnly :core:test).
//
// Bytecode fuer Java 17 (Android-Vorgabe), gebaut mit dem JDK, das gerade da
// ist - keine Toolchain-Pflicht, damit auch JDK 21 ohne Download reicht.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
