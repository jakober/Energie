import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Gemeinsamer Kern ohne Android-Abhaengigkeiten: Datenmodelle, Regeln, Statistik,
// Supabase- und Geraete-Schnittstellen. commonMain laeuft auf JVM/Android und iOS;
// jvmMain enthaelt, was Java-Bibliotheken braucht (Dateien, XML, Verschluesselung)
// und nur die Zentrale benutzt.
//
//   ./gradlew -PcoreOnly :core:jvmTest                     Tests auf der JVM
//   ./gradlew -PcoreOnly -PwithLinux :core:compileKotlinLinuxX64
//                                                          prueft den gemeinsamen Teil nativ
//   ./gradlew -PwithIos ...                                iOS-Ziele (nur auf macOS)
val withIos = providers.gradleProperty("withIos").isPresent
val withLinux = providers.gradleProperty("withLinux").isPresent

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (withIos) {
        iosArm64()
        iosSimulatorArm64()
    }
    if (withLinux) linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
