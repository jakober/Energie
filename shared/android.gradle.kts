// Android-Anteil des geteilten Moduls. Als eigenes Skript, damit build.gradle.kts
// auch ohne Android-Gradle-Plugin auf dem Klassenpfad uebersetzt (-PcoreOnly).
apply(plugin = "com.android.library")

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets.getByName("androidMain").dependencies {
        implementation("io.ktor:ktor-client-okhttp:3.1.3")
    }
}

extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "com.jakober.energie.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
