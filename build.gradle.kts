// Alle Kotlin-Plugins einmal hier laden (ohne sie anzuwenden), damit die
// Module dieselbe Version aus einem Klassenpfad bekommen. Sonst meldet
// Gradle "plugin is already on the classpath with an unknown version",
// sobald core kotlin-jvm und app kotlin-android anfordern.
//
// Das Android-Gradle-Plugin bleibt bewusst nur im app-Modul: So laesst sich
// der Kern auch ohne Zugang zu Googles Maven-Repository bauen.
plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}
