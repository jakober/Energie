package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.LocalDate

/** Was ein zusaetzliches Speichermodul an einem Tag gebracht haette. */
data class UpgradeDay(
    val date: LocalDate,
    /** Ins Zusatzmodul geladen (Wh), waere sonst eingespeist worden. */
    val chargedWh: Double,
    /** Aus dem Zusatzmodul entnommen (Wh), waere sonst bezogen worden. */
    val dischargedWh: Double,
    /** Eingespeist, waehrend der echte Speicher voll war (Wh): das Potenzial. */
    val exportWhileFullWh: Double,
    /** Bezogen, waehrend der echte Speicher leer war (Wh). */
    val importWhileEmptyWh: Double,
    /** Ob der echte Speicher an dem Tag voll wurde. */
    val wasFull: Boolean,
    val wasEmpty: Boolean,
    /** Minuten mit Messung, fuer die Gewichtung. */
    val measuredMinutes: Double,
)

data class UpgradeResult(
    val days: List<UpgradeDay>,
    val extraWh: Double,
) {
    val fullDays: List<UpgradeDay> get() = days.filter { it.measuredMinutes >= 20 * 60 }
    val dischargedWh: Double get() = days.sumOf { it.dischargedWh }
    val chargedWh: Double get() = days.sumOf { it.chargedWh }
    val exportWhileFullWh: Double get() = days.sumOf { it.exportWhileFullWh }
    val importWhileEmptyWh: Double get() = days.sumOf { it.importWhileEmptyWh }
    val daysFull: Int get() = days.count { it.wasFull }
    val daysEmpty: Int get() = days.count { it.wasEmpty }
    val daysBothFullAndEmpty: Int get() = days.count { it.wasFull && it.wasEmpty }
    /** Volle Zyklen des Zusatzmoduls im Zeitraum. */
    val cycles: Double get() = if (extraWh > 0) dischargedWh / extraWh else 0.0

    /** Ersparnis im Zeitraum: verschobene Energie zum Strompreis statt zur Einspeiseverguetung. */
    fun savedEur(pricePerKwh: Double, feedInPerKwh: Double): Double = dischargedWh / 1000 * (pricePerKwh - feedInPerKwh)

    /** Ersparnis je Tag mit Daten, hochgerechnet auf 365 Tage. Nur so gut wie die Jahreszeit der Daten. */
    fun savedPerYearEur(pricePerKwh: Double, feedInPerKwh: Double): Double? {
        val n = days.size
        if (n == 0) return null
        return savedEur(pricePerKwh, feedInPerKwh) / n * 365
    }

    fun paybackYears(costEur: Double, pricePerKwh: Double, feedInPerKwh: Double): Double? =
        savedPerYearEur(pricePerKwh, feedInPerKwh)?.takeIf { it > 0 }?.let { costEur / it }
}

/**
 * Spielt die Messpunkte mit einem gedachten Zusatzmodul nach. Das Modul darf nur laden,
 * wenn der echte Speicher voll ist und trotzdem eingespeist wird, und nur entladen,
 * wenn der echte Speicher leer ist und bezogen wird. Alles andere macht der echte
 * Speicher heute schon, ein weiteres Modul aendert daran nichts.
 *
 * "Voll": SoC ab [FULL_SOC], oder es wird eingespeist, obwohl der Speicher kaum noch laedt.
 * "Leer": SoC bis [EMPTY_SOC], oder es wird bezogen, obwohl der Speicher nicht liefert.
 */
object StorageUpgrade {
    const val FULL_SOC = 95.0
    const val EMPTY_SOC = 8.0
    /** Wirkungsgrad je Richtung (Hin- und Rueckweg zusammen ~ 90 %). */
    const val EFFICIENCY_ONE_WAY = 0.95
    /** Was ein Modul hoechstens an Leistung aufnimmt oder abgibt. */
    const val MAX_POWER_W = 2500.0
    /** Unter dieser Netzleistung zaehlt es nicht (Messrauschen). */
    const val MIN_GRID_W = 50.0
    private const val MAX_GAP_SECONDS = 30 * 60

    fun simulate(days: List<Pair<LocalDate, List<EnergySample>>>, extraWh: Double, usableShare: Double = 0.9): UpgradeResult {
        var stored = 0.0
        val cap = extraWh * usableShare
        val out = ArrayList<UpgradeDay>()
        for ((date, samples) in days.sortedBy { it.first }) {
            val sorted = samples.filter { it.gridPowerW != null && it.batterySocPercent != null }.sortedBy { it.at }
            var charged = 0.0; var discharged = 0.0; var exportFull = 0.0; var importEmpty = 0.0
            var full = false; var empty = false; var measured = 0.0
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]; val b = sorted[i]
                val dt = (b.at - a.at).inWholeSeconds
                if (dt <= 0 || dt > MAX_GAP_SECONDS) continue
                val hours = dt / 3600.0
                measured += dt / 60.0
                val grid = a.gridPowerW!!
                val soc = a.batterySocPercent!!
                val batt = a.batteryPowerW ?: 0.0
                val isFull = soc >= FULL_SOC || (grid < -MIN_GRID_W && batt < 100 && soc >= 80)
                val isEmpty = soc <= EMPTY_SOC || (grid > MIN_GRID_W && batt > -50 && soc <= 25)
                if (isFull) full = true
                if (isEmpty) empty = true
                if (grid < -MIN_GRID_W && isFull) {
                    val export = -grid * hours
                    exportFull += export
                    val take = minOf(export, MAX_POWER_W * hours, (cap - stored) / EFFICIENCY_ONE_WAY).coerceAtLeast(0.0)
                    stored += take * EFFICIENCY_ONE_WAY
                    charged += take
                } else if (grid > MIN_GRID_W && isEmpty) {
                    val import = grid * hours
                    importEmpty += import
                    val give = minOf(import, MAX_POWER_W * hours, stored * EFFICIENCY_ONE_WAY).coerceAtLeast(0.0)
                    stored -= give / EFFICIENCY_ONE_WAY
                    discharged += give
                }
            }
            out += UpgradeDay(date, charged, discharged, exportFull, importEmpty, full, empty, measured)
        }
        return UpgradeResult(out, extraWh)
    }
}
