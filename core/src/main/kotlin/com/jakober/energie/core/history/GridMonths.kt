package com.jakober.energie.core.history

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * Ein Monat auf der Stromrechnung: wie viel wirklich zugekauft und wie viel
 * eingespeist wurde. Bevorzugt aus den Zaehlerstaenden, damit Messluecken der
 * App die Summe nicht verfaelschen.
 */
data class GridMonth(
    val first: LocalDate,
    val importWh: Double,
    val exportWh: Double,
    /** Tage mit Messpunkten. */
    val days: Int,
    /** Tage im Monat, die schon vergangen sind (heute mitgezaehlt). */
    val daysElapsed: Int,
    /** True, wenn jeder Tag des Monats aus dem Zaehlerstand kam. */
    val fromMeter: Boolean,
    /** Erster und letzter Zaehlerstand im Monat, nur zur Anzeige. */
    val meterStartWh: Long?,
    val meterEndWh: Long?,
) {
    val year: Int get() = first.year
    val month: Int get() = first.month.number
    /** Anteil der Tage mit Daten; unter 1 ist die Summe unvollstaendig. */
    val coverage: Double get() = if (daysElapsed > 0) days.toDouble() / daysElapsed else 0.0
    val complete: Boolean get() = days >= daysElapsed

    fun costEur(pricePerKwh: Double): Double = importWh / 1000.0 * pricePerKwh
    fun feedInEur(feedInPerKwh: Double): Double = exportWh / 1000.0 * feedInPerKwh
    /** Was unter dem Strich zu zahlen ist: Bezug minus Verguetung. */
    fun balanceEur(pricePerKwh: Double, feedInPerKwh: Double): Double = costEur(pricePerKwh) - feedInEur(feedInPerKwh)

    /** Hochrechnung auf den vollen Monat, solange er laeuft oder Tage fehlen. */
    fun projectedImportWh(daysInMonth: Int): Double? =
        if (days <= 0) null else importWh / days * daysInMonth
}

/** Ein Kalenderjahr auf der Stromrechnung. */
data class GridYear(
    val year: Int,
    val importWh: Double,
    val exportWh: Double,
    val months: Int,
    val days: Int,
) {
    fun costEur(pricePerKwh: Double): Double = importWh / 1000.0 * pricePerKwh
    fun feedInEur(feedInPerKwh: Double): Double = exportWh / 1000.0 * feedInPerKwh
    fun balanceEur(pricePerKwh: Double, feedInPerKwh: Double): Double = costEur(pricePerKwh) - feedInEur(feedInPerKwh)
    /** True, wenn alle zwoelf Monate Daten haben. */
    val complete: Boolean get() = months >= 12
}

object GridMonths {
    /** Ab so vielen fehlenden Tagen gilt ein Monat als unvollstaendig. */
    const val FULL_DAY_MINUTES = 5

    /**
     * Fasst Tagesstatistiken zu Monaten zusammen. `today` begrenzt den laufenden
     * Monat, damit die Abdeckung nicht an kuenftigen Tagen gemessen wird.
     */
    fun of(days: List<DayStatistics>, today: LocalDate): List<GridMonth> {
        val withData = days.filter { it.sampleCount > 0 }
        if (withData.isEmpty()) return emptyList()
        return withData.groupBy { LocalDate(it.date.year, it.date.month, 1) }
            .map { (first, list) ->
                val sorted = list.sortedBy { it.date }
                val last = lastOfMonth(first)
                val elapsed = if (last <= today) last.dayOfMonth else if (first > today) 0 else today.dayOfMonth
                GridMonth(
                    first = first,
                    importWh = sorted.sumOf { it.totals.gridImportWh },
                    exportWh = sorted.sumOf { it.totals.gridExportWh },
                    days = sorted.size,
                    daysElapsed = elapsed,
                    fromMeter = sorted.all { it.totals.gridFromMeter },
                    meterStartWh = sorted.firstNotNullOfOrNull { it.meterImportStartWh },
                    meterEndWh = sorted.lastOrNull { it.meterImportEndWh != null }?.meterImportEndWh,
                )
            }
            .sortedBy { it.first }
    }

    /** Fasst Monate zu Jahren zusammen, aeltestes zuerst. */
    fun years(months: List<GridMonth>): List<GridYear> =
        months.groupBy { it.year }
            .map { (year, list) ->
                GridYear(
                    year = year,
                    importWh = list.sumOf { it.importWh },
                    exportWh = list.sumOf { it.exportWh },
                    months = list.size,
                    days = list.sumOf { it.days },
                )
            }
            .sortedBy { it.year }

    fun lastOfMonth(first: LocalDate): LocalDate {
        val nextMonth = if (first.month.number == 12) LocalDate(first.year + 1, 1, 1) else LocalDate(first.year, first.month.number + 1, 1)
        return LocalDate.fromEpochDays(nextMonth.toEpochDays() - 1)
    }

    fun daysInMonth(first: LocalDate): Int = lastOfMonth(first).dayOfMonth
}
