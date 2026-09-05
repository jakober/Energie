package com.jakober.energie.core.model

/**
 * Weitere Fahrzeugwerte aus der Telemetrie (FordPass), alle optional. Die
 * Karten in der Uebersicht zeigen, was da ist, und lassen den Rest weg.
 */
data class CarExtras(
    val odometerKm: Double? = null,
    /** 12-V-Bordbatterie. */
    val battery12V: Double? = null,
    val battery12SocPercent: Double? = null,
    val outsideTempC: Double? = null,
    val batteryTempC: Double? = null,
    /** Energie im Hochvolt-Akku in kWh. */
    val energyRemainingKwh: Double? = null,
    val timeToFullMinutes: Double? = null,
    /** Reifendruck je Rad in kPa, Schluessel wie FRONT_LEFT. */
    val tirePressuresKpa: Map<String, Double> = emptyMap(),
    /** Reifenstatus je Rad, etwa NORMAL. */
    val tireStatus: Map<String, String> = emptyMap(),
    /** Tuerzustand je Tuer, etwa CLOSED / AJAR. */
    val doors: Map<String, String> = emptyMap(),
    /** Fensterzustand je Fenster. */
    val windows: Map<String, String> = emptyMap(),
    val alarm: String? = null,
    val ignition: String? = null,
    val oilLifePercent: Double? = null,
    val speedKmh: Double? = null,
    /** Alle skalaren Messwerte flach, Schluessel -> Text, fuer die Vollansicht. */
    val allMetrics: Map<String, String> = emptyMap(),
) {
    val openDoors: List<String> get() = doors.filterValues { it.uppercase() != "CLOSED" && it.uppercase() != "UNKNOWN" }.keys.toList()
    val openWindows: List<String> get() = windows.filterValues { v -> v.uppercase().let { it != "CLOSED" && it != "FULLY_CLOSED" && it != "UNKNOWN" && it != "FALSE" && it != "0" } }.keys.toList()
}
