pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Energie"

include(":core")

// Das Android-Modul braucht das Android-SDK und das Android-Gradle-Plugin.
// Mit -PcoreOnly laesst sich der reine Kotlin-Teil (Schnittstellen, Parser,
// Tests) auch ohne beides bauen - etwa auf einem Server ohne SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
