package com.jakober.energie.core.forecast

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Prognose fuer einen Tag: Einstrahlung auf die Modulflaeche und daraus der Ertrag. */
@Serializable
data class PvForecastDay(
    val date: LocalDate,
    /** Summe der Einstrahlung auf die geneigte Flaeche in Wh/m². */
    val irradianceWhPerM2: Double,
    /** Sonnenstunden laut Wetterdienst, wenn geliefert. */
    val sunshineHours: Double? = null,
    /** WMO-Wettercode des Tages, wenn geliefert. */
    val weatherCode: Int? = null,
    /** Einstrahlung auf eine zweite Dachseite (Ost-West-Dach), wenn eingerichtet. */
    val irradiance2WhPerM2: Double? = null,
) {
    /**
     * Ertrag in kWh: 1000 W/m² Einstrahlung bringen je kWp rund 1 kW, davon
     * bleiben nach Verlusten (Temperatur, Wechselrichter, Verschmutzung)
     * typisch 80 %. `calibration` korrigiert das aus den echten Erträgen.
     */
    fun energyKwh(peakKw: Double, calibration: Double = 1.0, performanceRatio: Double = PERFORMANCE_RATIO): Double =
        irradianceWhPerM2 / 1000.0 * peakKw * performanceRatio * calibration

    /**
     * Ertrag beider Dachseiten: jede mit ihrer Einstrahlung und Leistung.
     * Eigener Name, damit kein Aufruf versehentlich in `energyKwh(peak, calibration, ratio)` faellt.
     */
    fun energyKwhTwoSides(peakKw1: Double, peakKw2: Double, calibration: Double, performanceRatio: Double = PERFORMANCE_RATIO): Double {
        val first = irradianceWhPerM2 / 1000.0 * peakKw1
        val second = (irradiance2WhPerM2 ?: irradianceWhPerM2) / 1000.0 * peakKw2
        return (first + second) * performanceRatio * calibration
    }

    val weatherLabel: String? get() = weatherCode?.let { weatherLabel(it) }

    companion object {
        const val PERFORMANCE_RATIO = 0.80

        fun weatherLabel(code: Int): String = when (code) {
            0 -> "sonnig"
            1 -> "meist sonnig"
            2 -> "teils bewölkt"
            3 -> "bedeckt"
            45, 48 -> "Nebel"
            51, 53, 55, 56, 57 -> "Nieselregen"
            61, 63, 65, 66, 67 -> "Regen"
            71, 73, 75, 77 -> "Schnee"
            80, 81, 82 -> "Regenschauer"
            85, 86 -> "Schneeschauer"
            95, 96, 99 -> "Gewitter"
            else -> "wechselhaft"
        }
    }
}

/** Gespeicherte Prognose samt Abrufzeit, damit nicht jede Messung den Wetterdienst fragt. */
@Serializable
data class PvForecast(
    val fetchedAtEpochSeconds: Long,
    val days: List<PvForecastDay>,
) {
    fun day(date: LocalDate): PvForecastDay? = days.firstOrNull { it.date == date }
}

/**
 * Selbstkalibrierung: Verhaeltnis von echtem Tagesertrag zur Prognose desselben
 * Tages, als gleitender Mittelwert. Startet bei 1,0 und bleibt in [0,3 .. 3,0],
 * damit ein Ausreisser (Schnee auf den Modulen) nicht alles verstellt.
 */
object PvCalibration {
    const val WEIGHT = 0.3
    const val MIN_ACTUAL_KWH = 1.0
    const val MIN_FORECAST_KWH = 0.5

    fun update(current: Double, actualKwh: Double, forecastKwhUncalibrated: Double): Double {
        if (actualKwh < MIN_ACTUAL_KWH || forecastKwhUncalibrated < MIN_FORECAST_KWH) return current
        val ratio = (actualKwh / forecastKwhUncalibrated).coerceIn(0.3, 3.0)
        val base = if (current <= 0) 1.0 else current
        return (base * (1 - WEIGHT) + ratio * WEIGHT).coerceIn(0.3, 3.0)
    }
}
