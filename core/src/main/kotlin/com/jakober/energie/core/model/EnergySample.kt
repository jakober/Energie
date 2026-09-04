package com.jakober.energie.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Ein Messpunkt, wie ihn die App regelmaessig ablegt: die Momentaufnahme des
 * SENEC-Speichers und die Werte des Lesekopfs am Stromzaehler zur selben Zeit.
 * Fehlt eine Quelle (nicht im Heimnetz, SENEC nicht erreichbar), bleiben ihre
 * Felder leer.
 */
@Serializable
data class EnergySample(
    val at: Instant,
    // --- SENEC ---
    /** Ladezustand des Speichers in Prozent. */
    val batterySocPercent: Double? = null,
    /** Speicherleistung in W: positiv laedt, negativ entlaedt. */
    val batteryPowerW: Double? = null,
    val batteryState: String? = null,
    /** PV-Erzeugung in W. */
    val productionW: Double? = null,
    /** Hausverbrauch in W. */
    val consumptionW: Double? = null,
    /** Netzleistung laut SENEC in W: positiv Bezug, negativ Einspeisung. */
    val senecGridPowerW: Double? = null,
    /** Ladeleistung der Wallbox in W, wenn vorhanden. */
    val evseChargingPowerW: Double? = null,
    val evConnected: Boolean? = null,
    // --- FRITZ!Box / Stromzaehler ---
    /** Netzleistung laut Lesekopf in W: positiv Bezug, negativ Einspeisung. */
    val meterGridPowerW: Double? = null,
    /** Zaehlerstand Bezug in Wh. */
    val meterImportWh: Long? = null,
    /** Zaehlerstand Einspeisung in Wh. */
    val meterExportWh: Long? = null,
    // --- Auto (Smartcar) ---
    val carSocPercent: Double? = null,
    val carCharging: Boolean? = null,
    val carPluggedIn: Boolean? = null,
    /** Ladeleistung des Autos in W, gemessen oder angenommen, nur wenn es zu Hause laedt. */
    val carChargePowerW: Double? = null,
) {
    /** Hausverbrauch ohne das Auto. */
    val householdW: Double? get() = consumptionW?.let { c -> (c - (carChargePowerW ?: 0.0)).coerceAtLeast(0.0) }

    val hasSenec: Boolean get() = batterySocPercent != null || productionW != null || consumptionW != null
    val hasMeter: Boolean get() = meterGridPowerW != null || meterImportWh != null

    /** Anteil des Verbrauchs, der nicht aus dem Netz kam (Autarkie im Moment), 0..1. */
    val selfSufficiency: Double? get() {
        val c = consumptionW ?: return null
        if (c <= 0) return null
        val grid = (senecGridPowerW ?: meterGridPowerW ?: return null).coerceAtLeast(0.0)
        return (1.0 - grid / c).coerceIn(0.0, 1.0)
    }
}
