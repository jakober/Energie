package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlin.math.roundToInt

/** Geschaetzte Vollkapazitaet des Fahrakkus an einem Tag (Median der Messpunkte). */
data class CapacityPoint(val date: LocalDate, val capacityKwh: Double, val samples: Int)

/** Zustand des Fahrakkus aus Restenergie und Ladestand, wie das Auto sie meldet. */
data class CarBatteryHealth(
    /** Median der letzten [CarBatteryHealth.CURRENT_DAYS] Tageswerte, null ohne Daten. */
    val currentKwh: Double?,
    val currentFrom: LocalDate?,
    val currentTo: LocalDate?,
    val currentDays: Int,
    /** Bezugswert "neu": aus den Einstellungen oder der hoechste Wochenwert. */
    val referenceKwh: Double?,
    val referenceFromSetting: Boolean,
    /** currentKwh / referenceKwh in Prozent. */
    val healthPercent: Int?,
    /** Ein Punkt je Kalenderwoche (Montag als Datum), aelteste zuerst. */
    val weekly: List<CapacityPoint>,
    val daily: List<CapacityPoint>,
) {
    companion object {
        const val CURRENT_DAYS = 14
        /** Unter diesem Ladestand ist die Prozentrundung zu grob fuer die Rechnung. */
        const val MIN_SOC = 20.0
        const val MIN_SAMPLES_PER_DAY = 3

        /**
         * Tageswert: Restenergie geteilt durch Ladestand. Das Auto rechnet die Restenergie
         * selbst aus seiner Kapazitaetsschaetzung, das Ergebnis ist also die Kapazitaet,
         * die das Auto gerade fuer voll haelt.
         */
        fun dayPoint(date: LocalDate, samples: List<EnergySample>): CapacityPoint? {
            val caps = samples.mapNotNull { s ->
                val e = s.carEnergyKwh ?: return@mapNotNull null
                val soc = s.carSocPercent ?: return@mapNotNull null
                if (e <= 0 || soc < MIN_SOC || soc > 100) null else e / soc * 100
            }
            if (caps.size < MIN_SAMPLES_PER_DAY) return null
            return CapacityPoint(date, median(caps)!!, caps.size)
        }

        fun of(daily: List<CapacityPoint>, nominalKwh: Double?): CarBatteryHealth {
            val days = daily.sortedBy { it.date }
            val weekly = days.groupBy { it.date.minus(it.date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY) }
                .map { (monday, pts) -> CapacityPoint(monday, median(pts.map { it.capacityKwh })!!, pts.sumOf { it.samples }) }
                .sortedBy { it.date }
            val recent = days.takeLast(CURRENT_DAYS)
            val current = median(recent.map { it.capacityKwh })
            val fromSetting = nominalKwh != null && nominalKwh > 0
            val reference = if (fromSetting) nominalKwh else weekly.maxOfOrNull { it.capacityKwh }
            val health = if (current != null && reference != null && reference > 0) (current / reference * 100).roundToInt() else null
            return CarBatteryHealth(
                current, recent.firstOrNull()?.date, recent.lastOrNull()?.date, recent.size,
                reference, fromSetting, health, weekly, days,
            )
        }

        private fun median(xs: List<Double>): Double? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
