package com.jakober.energie.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Dienste der Plattform, die die gemeinsame Oberflaeche nicht selbst hat.
 * Android reicht Geocoder und Karten-Intent herein, iOS seine Gegenstuecke;
 * ohne Anbieter bleiben Koordinaten stehen und der Kartenknopf tut nichts.
 */
interface PlatformHooks {
    /** Strasse und Ort zu einer Koordinate, null wenn nicht aufloesbar. */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = null
    /** Oeffnet die Stelle in der Karten-App. */
    fun openMap(lat: Double, lon: Double) {}
    /** Ob der Kartenknopf angezeigt werden soll. */
    val canOpenMap: Boolean get() = false
}

val LocalPlatformHooks = staticCompositionLocalOf<PlatformHooks> { object : PlatformHooks {} }
