package com.jakober.energie.ui

import com.jakober.energie.core.history.DayStatistics
import com.jakober.energie.core.history.EnergyTotals
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

enum class Range {
    DAY, WEEK, MONTH, YEAR;

    companion object {
        /** Erster und letzter Tag des Zeitraums, in dem [d] liegt. */
        fun bounds(d: LocalDate, r: Range): Pair<LocalDate, LocalDate> = when (r) {
            DAY -> d to d
            WEEK -> {
                val monday = d.minus(d.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
                monday to monday.plus(6, DateTimeUnit.DAY)
            }
            MONTH -> {
                val first = LocalDate(d.year, d.month, 1)
                first to first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            }
            YEAR -> LocalDate(d.year, 1, 1) to LocalDate(d.year, 12, 31)
        }

        /** Einen Schritt der Ansicht vor oder zurueck. */
        fun shift(d: LocalDate, r: Range, steps: Int): LocalDate = when (r) {
            DAY -> d.plus(steps, DateTimeUnit.DAY)
            WEEK -> d.plus(steps * 7, DateTimeUnit.DAY)
            MONTH -> d.plus(steps, DateTimeUnit.MONTH)
            YEAR -> d.plus(steps, DateTimeUnit.YEAR)
        }
    }
}

/** Zusammenfassung mehrerer Tage (Woche, Monat). */
data class RangeStatistics(
    val from: LocalDate,
    val to: LocalDate,
    val days: List<DayStatistics>,
) {
    val totals: EnergyTotals = EnergyTotals(
        productionWh = days.sumOf { it.totals.productionWh },
        consumptionWh = days.sumOf { it.totals.consumptionWh },
        gridImportWh = days.sumOf { it.totals.gridImportWh },
        gridExportWh = days.sumOf { it.totals.gridExportWh },
        batteryChargeWh = days.sumOf { it.totals.batteryChargeWh },
        batteryDischargeWh = days.sumOf { it.totals.batteryDischargeWh },
        carChargeWh = days.sumOf { it.totals.carChargeWh },
        carFromGridWh = days.sumOf { it.totals.carFromGridWh },
        meterImportWh = days.mapNotNull { it.totals.meterImportWh }.takeIf { it.isNotEmpty() }?.sum(),
        meterExportWh = days.mapNotNull { it.totals.meterExportWh }.takeIf { it.isNotEmpty() }?.sum(),
    )
    val daysWithData: List<DayStatistics> get() = days.filter { it.sampleCount > 0 }
    val bestProductionDay: DayStatistics? get() = daysWithData.maxByOrNull { it.totals.productionWh }?.takeIf { it.totals.productionWh > 0 }
    val heaviestConsumptionDay: DayStatistics? get() = daysWithData.maxByOrNull { it.totals.consumptionWh }?.takeIf { it.totals.consumptionWh > 0 }
    val averageConsumptionWh: Double? get() = daysWithData.takeIf { it.isNotEmpty() }?.let { d -> d.sumOf { it.totals.consumptionWh } / d.size }
    val peakConsumption: com.jakober.energie.core.history.Peak? get() = daysWithData.mapNotNull { it.peakConsumption }.maxByOrNull { it.value }
}
