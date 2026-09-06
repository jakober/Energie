import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jakober.energie"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jakober.energie"
        // Cloud-Vorgaben: URL und anon-Schluessel sind oeffentlich (Zugriff regeln die
        // Datenbank-Policies). E-Mail und Passwort kommen nur aus GitHub-Secrets, nie aus dem Repo.
        // Leere Umgebungsvariablen (fehlendes GitHub-Secret) zaehlen als nicht gesetzt.
        fun env(name: String, fallback: String = "") = (System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback).replace("\"", "\\\"")
        buildConfigField("String", "CLOUD_URL", "\"${env("CLOUD_URL", "https://mnqcosmyewntcdsfujdm.supabase.co")}\"")
        buildConfigField("String", "CLOUD_ANON_KEY", "\"${env("CLOUD_ANON_KEY", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1ucWNvc215ZXdudGNkc2Z1amRtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODg3MTA1MzksImV4cCI6MjEwNDI4NjUzOX0.rdMBBfPdenCYtgH-8bJM_LJ3YA9lQb-vA87KsaFYnhQ")}\"")
        buildConfigField("String", "CLOUD_EMAIL", "\"${env("CLOUD_EMAIL")}\"")
        buildConfigField("String", "CLOUD_PASSWORD", "\"${env("CLOUD_PASSWORD")}\"")
        // Firebase-Push: Kenndaten des Projekts sind unkritisch; der API-Schluessel der Android-App
        // laesst sich ueber das Secret FIREBASE_API_KEY ueberschreiben.
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${env("FIREBASE_PROJECT_ID", "energie-7e6a7")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${env("FIREBASE_SENDER_ID", "26704680694")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${env("FIREBASE_APP_ID", "1:26704680694:android:1cd1c28205708bb60b3ed7")}\"")
        // Der Android-API-Schluessel von Firebase ist fuer die Auslieferung in Apps gedacht (Google-Doku);
        // er gibt nur Zugriff auf Firebase-Dienste dieser App, nicht auf die Datenbank.
        buildConfigField("String", "FIREBASE_API_KEY", "\"${env("FIREBASE_API_KEY", "AIzaSyA3LG_2PdKNIvt8AcOkVHiO9zjFRvryPrc")}\"")
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }

    // Signierschluessel fuer Release-Builds, liegt ausserhalb des Repos in
    // ~/.energie/keystore.properties (storeFile, storePassword, keyAlias,
    // keyPassword). Ohne die Datei bleibt der Release-Build unsigniert.
    val keystoreProps = file(System.getProperty("user.home") + "/.energie/keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    // Fester Debug-Schluessel im Repository. Ohne ihn erzeugt jeder Build auf
    // GitHub einen neuen Schluessel, und Android verweigert dann das Update
    // ("App nicht installiert"). Der Schluessel taugt nur zum Aufspielen von
    // Hand; fuer den Play Store gilt der Release-Schluessel oben.
    signingConfigs.getByName("debug") {
        storeFile = file("debug.keystore")
        storePassword = "energie-debug"
        keyAlias = "energie"
        keyPassword = "energie-debug"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
}
