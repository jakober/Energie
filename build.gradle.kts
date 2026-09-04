// Alle Gradle-Plugins landen einmal hier auf dem Klassenpfad des Root-Builds,
// die Module wenden sie nur noch per id an. Grund: Das Kotlin-Android-Plugin
// muss das Android-Gradle-Plugin im selben Klassenlader sehen, und Gradle
// verweigert es, ein Plugin ein zweites Mal mit Version anzufordern, wenn es
// schon ueber den Eltern-Klassenpfad da ist.
//
// Mit -PcoreOnly bleibt das Android-Plugin draussen; dann laesst sich der
// Kern auch ohne Zugang zu Googles Maven-Repository bauen und testen.
//
// Versionen bitte zusammen mit gradle/libs.versions.toml pflegen.
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.1.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.21")
        if (!providers.gradleProperty("coreOnly").isPresent) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
