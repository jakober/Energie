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
    /** Rohantworten je Signalcode. */
    val raw: Map<String, String> = emptyMap(),
)

/** Ergebnis eines Befehls: HTTP-Status und Rohantwort. */
data class CommandResult(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}
