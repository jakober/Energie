package com.jakober.energie.core.smartcar

import kotlinx.datetime.Instant

/** Eine mit der Smartcar-Anwendung verbundene Fahrzeug-Nutzer-Kombination. */
data class SmartcarConnection(
    val vehicleId: String,
    val userId: String?,
    val raw: String,
)

/**
 * Zustand des Autos, aus einzelnen Smartcar-Signalen zusammengesetzt.
 * Fehlt ein Signal oder ist sein Format unbekannt, bleibt das Feld leer und
 * die Rohantwort steht in `raw` - so laesst sich das Parsen nachjustieren.
 */
data class CarState(
    val at: Instant,
    val vehicleId: String,
    val socPercent: Double? = null,
    val rangeKm: Double? = null,
    val isCharging: Boolean? = null,
    val isPluggedIn: Boolean? = null,
    val chargeLimitPercent: Double? = null,
    val chargingStatus: String? = null,
    /** Gemessene Ladeleistung in W, aus Wattage oder Spannung mal Strom. */
    val chargePowerW: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Entfernung zum Ladeort Zuhause in Metern, wenn beides bekannt ist. */
    val distanceHomeM: Double? = null,
    /** LOCKED, PARTLY_LOCKED, UNLOCKED oder null. */
    val lockState: String? = null,
    /** Rohantworten je Signalcode. */
    val raw: Map<String, String> = emptyMap(),
)

/** Verbindungsliste mit allen Rohantworten, auch wenn nichts erkannt wurde. */
data class ConnectionsResult(val connections: List<SmartcarConnection>, val raw: String)

/** Ergebnis eines Befehls: HTTP-Status und Rohantwort. */
/** Luftlinie zwischen zwei Koordinaten in Metern (Haversine). */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

data class CommandResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}
