package com.jakober.energie.core.fritz

import kotlinx.datetime.Instant

/**
 * Ein Geraet aus `getdevicelistinfos`. Der FRITZ!Smart Energy 250 erscheint
 * dreimal: einmal als Lesekopf selbst (ohne Messwerte) und je einmal als
 * Unterzaehler fuer Bezug (Kennung `...-1`) und Einspeisung (`...-2`).
 */
data class FritzDevice(
    val ain: String,
    val id: String,
    val name: String,
    val productName: String,
    val manufacturer: String,
    val firmware: String,
    val functionBitmask: Int,
    val present: Boolean,
    val powerMeter: PowerMeter?,
    val temperatureCelsius: Double?,
    val switchOn: Boolean?,
) {
    /** Kennung ohne Leerzeichen, so wie die Box sie in URLs erwartet. */
    val ainCompact: String get() = ain.replace(" ", "")

    val isPowerMeter: Boolean get() = powerMeter != null

    /** Unterzaehler des Smart Energy 250: Bezug endet auf -1, Einspeisung auf -2. */
    val meterRole: MeterRole? get() = when {
        ainCompact.endsWith("-1") && isPowerMeter -> MeterRole.GRID_IMPORT
        ainCompact.endsWith("-2") && isPowerMeter -> MeterRole.GRID_EXPORT
        else -> null
    }
}

enum class MeterRole { GRID_IMPORT, GRID_EXPORT }

/**
 * Messwerte eines Energiemessers. Die Box liefert Leistung in mW, Energie in
 * Wh und Spannung in mV; hier stehen sie bereits in W, Wh und V.
 */
data class PowerMeter(
    val powerWatt: Double,
    val energyWh: Long,
    val voltage: Double?,
)

/**
 * Was der Lesekopf am Stromzaehler gerade sieht, zusammengefasst aus den
 * beiden Unterzaehlern.
 */
data class SmartMeterReading(
    val at: Instant,
    /** Momentanleistung am Netzanschluss in W: positiv = Bezug, negativ = Einspeisung. */
    val gridPowerWatt: Double,
    /** Zaehlerstand Bezug (1.8.0) in Wh. */
    val importEnergyWh: Long,
    /** Zaehlerstand Einspeisung (2.8.0) in Wh, null wenn der Zaehler kein Einspeisezaehlwerk hat. */
    val exportEnergyWh: Long?,
    val importAin: String,
    val exportAin: String?,
)

/** Verlaufswerte aus `getbasicdevicestats`: neueste zuerst, `null` = kein Wert. */
data class StatSeries(
    /** Abstand zweier Werte in Sekunden. */
    val gridSeconds: Int,
    /** Zeitpunkt des neuesten Werts, falls die Box ihn mitliefert (FRITZ!OS >= 7.50). */
    val newestAt: Instant?,
    val values: List<Double?>,
)

data class DeviceStats(
    val temperature: List<StatSeries>,
    val voltage: List<StatSeries>,
    val power: List<StatSeries>,
    val energy: List<StatSeries>,
) {
    /** Energie pro Tag der letzten 31 Tage in Wh, neuester Tag zuerst. */
    val energyPerDayWh: StatSeries? get() = energy.firstOrNull { it.gridSeconds == 86_400 }

    /** Energie pro Monat der letzten 12 Monate in Wh, neuester Monat zuerst. */
    val energyPerMonthWh: StatSeries? get() = energy.firstOrNull { it.gridSeconds > 86_400 }
}
