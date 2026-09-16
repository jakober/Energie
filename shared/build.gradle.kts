import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Gemeinsame Oberflaeche und Anzeige-Logik fuer Android und iOS (Compose Multiplatform).
// Die Android-App nutzt dieses Modul fuer ihre Karten und Diagramme, die iOS-App zeigt
// dieselben Karten als reine Anzeige. Der Desktop-Zielpfad dient nur der Uebersetzung
// ohne Android-SDK:
//
//   ./gradlew -PcoreOnly :shared:compileKotlinDesktop
//
// Android-Teile stecken in android.gradle.kts, damit der Kern ohne Android-Plugin baut.
val coreOnly = providers.gradleProperty("coreOnly").isPresent
val withIos = providers.gradleProperty("withIos").isPresent

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    if (withIos) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "Shared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
        }
        if (withIos) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val desktopMain by getting {
            dependencies { implementation(libs.ktor.client.cio) }
        }
    }
}

if (!coreOnly) apply(from = "android.gradle.kts")
