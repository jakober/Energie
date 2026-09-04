package com.jakober.energie.core.history

import com.jakober.energie.core.model.EnergySample
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Ein Extremwert mit dem Zeitpunkt, an dem er auftrat. */
data class Peak(val at: Instant, val value: Double)

/** Energiemengen einer Stunde (0..23) in Wh. */
data class HourBucket(
    val hour: Int,
    val productionWh: Double,
    val consumptionWh: Double,
    val gridImportWh: Double,
    val gridExportWh: Double,
    val batteryChargeWh: Double,
    val batteryDischargeWh: Double,
    val carChargeWh: Double,
    /** Mittlerer Ladezustand in der Stunde in Prozent. */
    val batterySocPercent: Double?,
)

/**
 * Alles, was die App ueber einen Tag zu sagen hat: Summen, Spitzen mit
 * Uhrzeit, Stundenprofil, Zaehlerstaende. Rechnet ausschliesslich auf den
 * lokal gesammelten Messpunkten.
 */
data class DayStatistics(
    val date: LocalDate,
    val sampleCount: Int,
    val totals: EnergyTotals,
    val peakConsumption: Peak?,
    val peakProduction: Peak?,
    val peakGridImport: Peak?,
    val peakGridExport: Peak?,
    val peakBatteryCharge: Peak?,
    val peakBatteryDischarge: Peak?,
    val socMin: Peak?,
    val socMax: Peak?,
    /** Erster und letzter Ladezustand des Tages. */
    val socStart: Double?,
    val socEnd: Double?,
    /** Zaehlerstaende zu Tagesbeginn und -ende in Wh. */
    val meterImportStartWh: Long?,
    val meterImportEndWh: Long?,
    val meterExportStartWh: Long?,
    val meterExportEndWh: Long?,
    val hours: List<HourBucket>,
    /** Erster Messpunkt mit PV-Leistung ueber 10 W und letzter - grob Sonnenauf-/untergang. */
    val firstProduction: Instant?,
    val lastProduction: Instant?,
) {
    /** Stunde mit dem hoechsten Verbrauch. */
    val heaviestHour: HourBucket? get() = hours.maxByOrNull { it.consumptionWh }?.takeIf { it.consumptionWh > 0 }

    /** Grundlast: der kleinste 15-Minuten-Mittelwert des Verbrauchs. */
    val baseLoadW: Double? get() = baseLoad

    // wird im Builder gesetzt
    internal var baseLoad: Double? = null

    companion object {
        fun of(date: LocalDate, samples: List<EnergySample>, zone: TimeZone = TimeZone.currentSystemDefault()): DayStatistics {
            val sorted = samples.sortedBy { it.at }
            fun peakMax(sel: (EnergySample) -> Double?): Peak? =
                sorted.mapNotNull { s -> sel(s)?.let { Peak(s.at, it) } }.maxByOrNull { it.value }?.takeIf { it.value > 0 }
            fun peakMin(sel: (EnergySample) -> Double?): Peak? =
                sorted.mapNotNull { s -> sel(s)?.let { Peak(s.at, it) } }.minByOrNull { it.value }

            val grid: (EnergySample) -> Double? = { it.gridPowerW }
            val socs = sorted.mapNotNull { s -> s.batterySocPercent?.let { Peak(s.at, it) } }
            val producing = sorted.filter { (it.productionW ?: 0.0) > 10.0 }

            val stats = DayStatistics(
                date = date,
                sampleCount = sorted.size,
                totals = EnergyTotals.of(sorted),
                peakConsumption = peakMax { it.consumptionW },
                peakProduction = peakMax { it.productionW },
                peakGridImport = peakMax { grid(it)?.coerceAtLeast(0.0) },
                peakGridExport = peakMax { grid(it)?.let { g -> (-g).coerceAtLeast(0.0) } },
                peakBatteryCharge = peakMax { it.batteryPowerW?.coerceAtLeast(0.0) },
                peakBatteryDischarge = peakMax { it.batteryPowerW?.let { p -> (-p).coerceAtLeast(0.0) } },
                socMin = socs.minByOrNull { it.value },
                socMax = socs.maxByOrNull { it.value },
                socStart = socs.firstOrNull()?.value,
                socEnd = socs.lastOrNull()?.value,
                meterImportStartWh = sorted.firstOrNull { it.meterImportWh != null }?.meterImportWh,
                meterImportEndWh = sorted.lastOrNull { it.meterImportWh != null }?.meterImportWh,
                meterExportStartWh = sorted.firstOrNull { it.meterExportWh != null }?.meterExportWh,
                meterExportEndWh = sorted.lastOrNull { it.meterExportWh != null }?.meterExportWh,
                hours = hourBuckets(sorted, zone),
                firstProduction = producing.firstOrNull()?.at,
                lastProduction = producing.lastOrNull()?.at,
            )
            stats.baseLoad = baseLoad(sorted)
            return stats
        }

        private fun hourBuckets(sorted: List<EnergySample>, zone: TimeZone): List<HourBucket> {
            val byHour = sorted.groupBy { it.at.toLocalDateTime(zone).hour }
            return (0..23).map { h ->
                val inHour = byHour[h].orEmpty()
                // Jedes Intervall gehoert zu der Stunde, in der es beginnt. Deshalb den
                // ersten Punkt der Folgestunde mitnehmen, sonst fehlt das letzte Intervall.
                val next = sorted.firstOrNull { it.at.toLocalDateTime(zone).hour > h }
                val t = EnergyTotals.of(inHour + listOfNotNull(next))
                val soc = inHour.mapNotNull { it.batterySocPercent }
                HourBucket(
                    hour = h,
                    productionWh = t.productionWh,
                    consumptionWh = t.consumptionWh,
                    gridImportWh = t.gridImportWh,
                    gridExportWh = t.gridExportWh,
                    batteryChargeWh = t.batteryChargeWh,
                    batteryDischargeWh = t.batteryDischargeWh,
                    carChargeWh = t.carChargeWh,
                    batterySocPercent = if (soc.isEmpty()) null else soc.average(),
                )
            }
        }

        /** Kleinster Mittelwert des Verbrauchs ueber ein 15-Minuten-Fenster. */
        private fun baseLoad(sorted: List<EnergySample>): Double? {
            val pts = sorted.mapNotNull { s -> s.consumptionW?.let { s.at to it } }
            if (pts.size < 3) return pts.minOfOrNull { it.second }
            var best: Double? = null
            var i = 0
            for (j in pts.indices) {
                while ((pts[j].first - pts[i].first).inWholeSeconds > 15 * 60) i++
                if (j - i >= 2) {
                    val avg = pts.subList(i, j + 1).map { it.second }.average()
                    if (best == null || avg < best) best = avg
                }
            }
            return best ?: pts.minOfOrNull { it.second }
        }
    }
}
