# kotlinx.serialization: Serializer-Klassen und Felder der Modelle behalten
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jakober.energie.**$$serializer { *; }
-keepclassmembers class com.jakober.energie.** { *** Companion; }
-keepclasseswithmembers class com.jakober.energie.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**
