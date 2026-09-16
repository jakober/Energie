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
// Der Android-Anteil wird nur ohne -PcoreOnly angewendet und ueber withGroovyBuilder
// konfiguriert, damit dieses Skript auch ohne Android-Plugin auf dem Klassenpfad uebersetzt.
val coreOnly = providers.gradleProperty("coreOnly").isPresent
val withIos = providers.gradleProperty("withIos").isPresent

if (!coreOnly) apply(plugin = "com.android.library")

kotlin {
    if (!coreOnly) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
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
        if (!coreOnly) {
            androidMain.dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }
        val desktopMain by getting {
            dependencies { implementation(libs.ktor.client.cio) }
        }
    }
}

if (!coreOnly) {
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "com.jakober.energie.shared")
        setProperty("compileSdk", 36)
        "defaultConfig" { setProperty("minSdk", 26) }
        "compileOptions" {
            setProperty("sourceCompatibility", JavaVersion.VERSION_17)
            setProperty("targetCompatibility", JavaVersion.VERSION_17)
        }
    }
}

// Es gibt keine Compose-Ressourcen (Bilder, Strings) in diesem Modul. Der Abgleich der
// Ressourcen in das iOS-Bundle braucht Xcode-Umgebungswerte, die nicht in jedem Aufruf
// gesetzt sind, und bricht dann ab - also aus.
tasks.matching { it.name == "syncComposeResourcesForIos" }.configureEach { enabled = false }
